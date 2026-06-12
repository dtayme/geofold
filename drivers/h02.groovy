// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * H02 GPS tracker driver — text messages only.
 *
 * Supported wire format (text, starts with *, ends with #):
 *   *<mfr>,<imei>,<type>,...#
 *
 * Message types handled:
 *   V0 / HTBT   — heartbeat (battery level, ACK required)
 *   V1..V9      — standard position (GPS fix)
 *   NBR         — LBS cell tower positioning
 *   LINK        — activity/fitness tracker data
 *   V3          — cell tower positioning
 *   VP1         — cell or GPS positioning
 *   SMS         — command result
 *
 * NOT supported: binary $-prefix frames (32/45-byte BCD format).
 * Those require raw ByteBuf access which is not available in the driver system.
 * Devices that only send binary frames must continue using H02ProtocolDecoder.
 *
 * Command encoding mirrors H02ProtocolEncoder exactly.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// Patterns (compiled once)
// ---------------------------------------------------------------------------

// Standard position: *XX,IMEI,Vn,HHMMSS,validity,lat,NS,lon,EW,speed,course,DDMMYY[,statusHex[,...]]
def P_POS = Pattern.compile(
    /^\*..,([\d]+),([^,]+),(?:(\d{2})(\d{2})(\d{2}))?,([ABV])?,(\d*)([\d]{2}\.[\d]+),([NS]),(\d*)([\d]{2}\.[\d]+),([EW]), *([\d.]+),([\d.]*),(?:\d+,)?(?:(\d{2})(\d{2})(\d{2}))?(?:,[^,]*,[^,]*,[^,]*)?(?:,([0-9A-Fa-f]{8})(?:,(\d+),(-?\d+),([\d.]+),(-?\d+),([0-9A-Fa-f]+),([0-9A-Fa-f]+))?(?:,(.*))?)?$/)

// V4/HTBT heartbeat: *HQ,IMEI,V4,response,YYYYMMDDHHmmss  or  *HQ,IMEI,HTBT,battery
def P_HTBT = Pattern.compile(
    /^\*HQ,([\d]{15}),HTBT,(\d+)/)

def P_V4 = Pattern.compile(
    /^\*HQ,([\d]+),V4,(.*),(\d{14})/)

// NBR (LBS): *XX,IMEI,NBR,HHMMSS,mcc,mnc,delay,count,lac,cid,rssi,...,DDMMYY,statusHex
def P_NBR = Pattern.compile(
    /^\*..,([\d]+),NBR,(\d{2})(\d{2})(\d{2}),(\d+),(\d+),\d+,\d+,((?:\d+,\d+,-?\d+,)+)(\d{2})(\d{2})(\d{2}),([0-9A-Fa-f]{8})/)

// LINK (activity): *HQ,IMEI,LINK,HHMMSS,rssi,sats,battery,steps,turnovers,DDMMYY,statusHex
def P_LINK = Pattern.compile(
    /^\*..,([\d]+),LINK,(\d{2})(\d{2})(\d{2}),(\d+),(\d+),(\d+),(\d+),(\d+),(\d{2})(\d{2})(\d{2}),([0-9A-Fa-f]{8})/)

// V3 (cell): *XX,IMEI,V3,HHMMSS,mccmnc,count,cells,battHex,reboot,X,DDMMYY,statusHex
def P_V3 = Pattern.compile(
    /^\*..,([\d]+),V3,(\d{2})(\d{2})(\d{2}),(\d{3})(\d+),(\d+),(.*?),([0-9A-Fa-f]{4}),\d+,X,(\d{2})(\d{2})(\d{2}),([0-9A-Fa-f]{8})/)

// VP1: two variants — cell towers (V,mcc,mnc,cells) or GPS (lat,NS,lon,EW,speed,course,date)
def P_VP1_CELL = Pattern.compile(
    /^\*hq,([\d]{15}),VP1,V,(\d+),(\d+),([^#]+)/)

def P_VP1_GPS = Pattern.compile(
    /^\*hq,([\d]{15}),VP1,[AB],(\d+)(\d{2}\.[\d]+),([NS]),(\d+)(\d{2}\.[\d]+),([EW]),([\d.]+),([\d.]+),(\d{2})(\d{2})(\d{2})/)

// SMS (command result): *HQ,ID,SMS,text#
def P_SMS = Pattern.compile(
    /^\*HQ,([\d]+),SMS,(.+)$/)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Convert ddmm.mmmm + hemisphere to decimal degrees
def nmea = { deg, min, hemi ->
    double v = (deg ?: '0').toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

// Decode H02 status bitmask (active-low) → alarms + ignition
def processStatus = { pos, long status ->
    if (!(status & 0x01)) pos.addAlarm(ALARM_VIBRATION)
    if (!(status & 0x02) || !(status & (1 << 18))) pos.addAlarm(ALARM_SOS)
    if (!(status & 0x04)) pos.addAlarm(ALARM_OVERSPEED)
    if (!(status & (1 << 19))) pos.addAlarm(ALARM_POWER_CUT)
    pos.set(Position.KEY_IGNITION, (status & (1 << 10)) != 0)
    pos.set(Position.KEY_STATUS, status)
}

def utcNow = {
    new Date().format('HHmmss', TimeZone.getTimeZone('UTC'))
}

// ---------------------------------------------------------------------------
// Protocol definition
// ---------------------------------------------------------------------------

protocol("h02") {

    port 5013

    // -----------------------------------------------------------------------
    // Text variant — handles all *...,#  messages
    // -----------------------------------------------------------------------
    variant("text") {

        // Worst case: NBR with many cell towers or SMS with long text, ~600 bytes.
        maxFrameLength 2048
        frame '*' as char, readUntil('#')

        matches { msg -> msg.startsWith('*') }

        decode { msg, ctx ->

            // Extract type field: *XX,IMEI,<TYPE>,...
            def parts = msg.split(',', 4)
            if (parts.length < 3) return null
            def type = parts[2].trim()

            // ----------------------------------------------------------------
            // V0 / HTBT heartbeat
            // ----------------------------------------------------------------
            if (type == 'V0' || type == 'HTBT') {
                def m = P_HTBT.matcher(msg)
                if (!m.find()) {
                    // V0 format: just echo back and return no position
                    def imei = parts[1]
                    if (!ctx.session(imei)) return null
                    // V0 ACK: echo the message back with # appended
                    def ackEnd = msg.indexOf(',', msg.indexOf(',', msg.indexOf(',') + 1) + 1)
                    ctx.ack(msg.substring(0, ackEnd) + '#')
                    return null
                }
                def imei = m.group(1)
                def session = ctx.session(imei)
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_BATTERY_LEVEL, m.group(2).toInteger())
                return pos
            }

            // ----------------------------------------------------------------
            // V4 — response acknowledgement heartbeat
            // ----------------------------------------------------------------
            if (type == 'V4') {
                def m = P_V4.matcher(msg)
                if (!m.find()) return null
                def imei = m.group(1)
                def session = ctx.session(imei)
                if (!session) return null
                ctx.ack("*HQ,${imei},V4,${m.group(2)},${utcNow()}#")
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_RESULT, m.group(2))
                return pos
            }

            // ----------------------------------------------------------------
            // NBR — LBS cell tower positioning
            // ----------------------------------------------------------------
            if (type == 'NBR') {
                def m = P_NBR.matcher(msg)
                if (!m.find()) return null
                def imei = m.group(1)
                def session = ctx.session(imei)
                if (!session) return null

                ctx.ack("*HQ,${imei},NBR,${utcNow()}#")

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                def db = new DateBuilder()
                        .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setDateReverse(m.group(9).toInteger(), m.group(10).toInteger(), m.group(11).toInteger())
                ctx.lastLocation(pos)
                pos.fixTime = db.getDate()

                int mcc = m.group(5).toInteger()
                int mnc = m.group(6).toInteger()
                def net = new Network()
                def cells = m.group(7).split(',')
                for (int i = 0; i + 2 < cells.length; i += 3) {
                    net.addCellTower(CellTower.from(mcc, mnc,
                        cells[i].toInteger(), cells[i + 1].toInteger(), cells[i + 2].toInteger()))
                }
                pos.network = net

                processStatus(pos, Long.parseLong(m.group(12), 16))
                return pos
            }

            // ----------------------------------------------------------------
            // LINK — activity tracker
            // ----------------------------------------------------------------
            if (type == 'LINK') {
                def m = P_LINK.matcher(msg)
                if (!m.find()) return null
                def session = ctx.session(m.group(1))
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                def db = new DateBuilder()
                        .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setDateReverse(m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
                ctx.lastLocation(pos)
                pos.fixTime = db.getDate()

                pos.set(Position.KEY_RSSI,          m.group(5).toInteger())
                pos.set(Position.KEY_SATELLITES,    m.group(6).toInteger())
                pos.set(Position.KEY_BATTERY_LEVEL, m.group(7).toInteger())
                pos.set(Position.KEY_STEPS,         m.group(8).toInteger())
                pos.set('turnovers',                m.group(9).toInteger())

                processStatus(pos, Long.parseLong(m.group(13), 16))
                return pos
            }

            // ----------------------------------------------------------------
            // V3 — cell tower positioning
            // ----------------------------------------------------------------
            if (type == 'V3') {
                def m = P_V3.matcher(msg)
                if (!m.find()) return null
                def session = ctx.session(m.group(1))
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                def db = new DateBuilder()
                        .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setDateReverse(m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
                ctx.lastLocation(pos)
                pos.fixTime = db.getDate()

                int mcc = m.group(5).toInteger()
                int mnc = m.group(6).toInteger()
                int count = m.group(7).toInteger()
                def net = new Network()
                def cells = m.group(8).split(',')
                for (int i = 0; i < count && (i * 4 + 1) < cells.length; i++) {
                    net.addCellTower(CellTower.from(mcc, mnc,
                        cells[i * 4].toInteger(), cells[i * 4 + 1].toInteger()))
                }
                pos.network = net

                pos.set(Position.KEY_BATTERY, Integer.parseInt(m.group(9), 16))
                processStatus(pos, Long.parseLong(m.group(13), 16))
                return pos
            }

            // ----------------------------------------------------------------
            // VP1 — cell or GPS positioning
            // ----------------------------------------------------------------
            if (type == 'VP1') {
                def mc = P_VP1_CELL.matcher(msg)
                if (mc.find()) {
                    def session = ctx.session(mc.group(1))
                    if (!session) return null
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos)
                    int mcc = mc.group(2).toInteger()
                    int mnc = mc.group(3).toInteger()
                    def net = new Network()
                    mc.group(4).split('Y').each { cell ->
                        def cv = cell.split(',')
                        if (cv.length >= 3) {
                            net.addCellTower(CellTower.from(mcc, mnc,
                                cv[0].toInteger(), cv[1].toInteger(), cv[2].toInteger()))
                        }
                    }
                    pos.network = net
                    return pos
                }
                def mg = P_VP1_GPS.matcher(msg)
                if (mg.find()) {
                    def session = ctx.session(mg.group(1))
                    if (!session) return null
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    pos.valid     = true
                    pos.latitude  = nmea(mg.group(2), mg.group(3), mg.group(4))
                    pos.longitude = nmea(mg.group(5), mg.group(6), mg.group(7))
                    pos.speed     = mg.group(8).toDouble()
                    pos.course    = mg.group(9).toDouble()
                    pos.time = new DateBuilder()
                            .setDateReverse(mg.group(10).toInteger(), mg.group(11).toInteger(), mg.group(12).toInteger())
                            .getDate()
                    return pos
                }
                return null
            }

            // ----------------------------------------------------------------
            // SMS — command result
            // ----------------------------------------------------------------
            if (type == 'SMS') {
                def m = P_SMS.matcher(msg)
                if (!m.find()) return null
                def session = ctx.session(m.group(1))
                if (!session) return null
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_RESULT, m.group(2))
                return pos
            }

            // ----------------------------------------------------------------
            // Standard position (V1, V5, V6, and anything else with GPS data)
            // ----------------------------------------------------------------
            def m = P_POS.matcher(msg)
            if (!m.find()) return null

            def imei = m.group(1)
            def session = ctx.session(imei)
            if (!session) return null

            // V1 needs an R12 ACK
            if (type == 'V1') {
                ctx.ack("*HQ,${imei},R12,${utcNow()}#")
            }

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
            if (m.group(3)) db.setTime(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())

            pos.valid     = m.group(6) == 'A'
            pos.latitude  = nmea(m.group(7), m.group(8), m.group(9))
            pos.longitude = nmea(m.group(10), m.group(11), m.group(12))
            pos.speed     = m.group(13).toDouble()
            pos.course    = m.group(14) ? m.group(14).toDouble() : 0

            if (m.group(15)) {
                db.setDateReverse(m.group(15).toInteger(), m.group(16).toInteger(), m.group(17).toInteger())
                pos.time = db.getDate()
            } else {
                pos.time = new Date()
            }

            if (m.group(18)) {
                processStatus(pos, Long.parseLong(m.group(18), 16))
            }

            // Extended fields: odometer, temperature, fuel, altitude, cell tower
            if (m.group(19)) {
                pos.set(Position.KEY_ODOMETER, m.group(19).toInteger())
                pos.set(Position.PREFIX_TEMP + '1', m.group(20).toInteger())
                pos.set(Position.KEY_FUEL, m.group(21).toDouble())
                pos.altitude = m.group(22).toInteger()
                pos.network = new Network(CellTower.fromLacCid(null,
                    Integer.parseInt(m.group(23), 16), Integer.parseInt(m.group(24), 16)))
            }

            // IO data fields
            if (m.group(25)) {
                def values = m.group(25).split(',')
                values.eachWithIndex { v, idx ->
                    pos.set(Position.PREFIX_IO + (idx + 1), v.trim())
                }
            }

            return pos
        }

        // ----------------------------------------------------------------
        // Encode — mirrors H02ProtocolEncoder exactly
        // ----------------------------------------------------------------
        encode { cmd, ctx ->
            def id = ctx.deviceId()
            def t  = ctx.utcTime()

            switch (cmd.type) {
                case TYPE_ALARM_ARM:         return "*HQ,${id},SCF,${t},0,0#"
                case TYPE_ALARM_DISARM:      return "*HQ,${id},SCF,${t},1,1#"
                case TYPE_ENGINE_STOP:       return "*HQ,${id},S20,${t},1,1#"
                case TYPE_ENGINE_RESUME:     return "*HQ,${id},S20,${t},1,0#"
                case TYPE_POSITION_PERIODIC: return "*HQ,${id},S71,${t},22,${ctx.freq()}#"
                default:                     return null
            }
        }
    }
}
