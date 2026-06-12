// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Mictrack GPS tracker driver.
 *
 * Supported devices and their wire formats:
 *   HQ variant  (*HQ,...#)       MT532 — V1/V5/V6 position, V4 heartbeat
 *   MT700 variant (#IMEI#...##)  MT700, MT700W, MT600, MT530
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// Shared patterns (compiled once at script load time)
// ---------------------------------------------------------------------------

def HQ_POSITION = Pattern.compile(
    /^\*HQ,([^,]+),(V\d+),(\d{2})(\d{2})(\d{2}),([AV]),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]+),([\d.]+),(\d{2})(\d{2})(\d{2}),([0-9A-Fa-f]{8}),(\d+),(\d+),(\d+),(\d+)(?:,([^,#]*))?(?:,(\d+))?/)

def HQ_HEARTBEAT = Pattern.compile(
    /^\*HQ,([^,]+),V4,[^,]*,(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/)

def MT_POSITION = Pattern.compile(
    /^#(?:(\d{2,4})|[\da-fA-F]*)(?:#(?:(\d+),(\d+),([0-9a-fA-F]+),([0-9a-fA-F]+))?)?\$GPRMC,(?:(\d{2})(\d{2})(\d{2})\.\d+)?,([AVL]),(?:(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]*),([\d.]*),(\d{2})(\d{2})(\d{2}))?/)

def MT_WIFI = Pattern.compile(
    /^#(?:(\d{2,4})|[\da-fA-F]+)#?(?:(\d+),(\d+),([0-9a-fA-F]+),([0-9a-fA-F]+))?\$WIFI,(\d{2})(\d{2})(\d{2})\.\d+,[AVL],(.*),(\d{2})(\d{2})(\d{2})\*[\da-fA-F]{2}$/)

// ---------------------------------------------------------------------------
// Helpers shared between variants
// ---------------------------------------------------------------------------

def decodeVehicleStatus = { Position pos, String hex ->
    int b1 = Integer.parseInt(hex[0..1], 16)
    int b2 = Integer.parseInt(hex[2..3], 16)
    int b3 = Integer.parseInt(hex[4..5], 16)
    int b4 = Integer.parseInt(hex[6..7], 16)
    if ((b1 & 0x02) == 0) pos.addAlarm(ALARM_TOW)
    if ((b1 & 0x08) == 0) pos.addAlarm(ALARM_POWER_CUT)
    if ((b1 & 0x10) == 0) pos.addAlarm(ALARM_REMOVING)
    if ((b2 & 0x02) == 0) pos.addAlarm(ALARM_VIBRATION)
    pos.set(Position.KEY_DOOR, (b3 & 0x01) == 0)
    if ((b3 & 0x02) == 0) pos.addAlarm(ALARM_GEOFENCE)
    if      ((b3 & 0x20) == 0) pos.set(Position.KEY_IGNITION, true)
    else if ((b3 & 0x04) == 0) pos.set(Position.KEY_IGNITION, false)
    if ((b4 & 0x02) == 0) pos.addAlarm(ALARM_SOS)
    if ((b4 & 0x04) == 0) pos.addAlarm(ALARM_OVERSPEED)
    if ((b4 & 0x08) == 0) pos.addAlarm(ALARM_POWER_ON)
    if ((b4 & 0x20) == 0) pos.addAlarm(ALARM_LOW_BATTERY)
}

def coordinate = { deg, min, hemi ->
    double v = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

def voltage = { raw ->
    int v = raw.toInteger()
    v > 100 ? v / 1000.0 : v / 10.0
}

def utcNow = { new Date().format('HHmmss', TimeZone.getTimeZone('UTC')) }

// ---------------------------------------------------------------------------
// Protocol definition
// ---------------------------------------------------------------------------

protocol("mictrack") {

    // -----------------------------------------------------------------------
    // HQ variant — MT532 and compatible (*HQ,...# framing)
    // -----------------------------------------------------------------------
    variant("hq") {
        // Longest message: V6 with ICCID, ~200 bytes.
        maxFrameLength 512
        frame '*' as char, readUntil('#')
        matches { msg -> msg.startsWith("*HQ,") }

        decode { msg, ctx ->
            // V4 heartbeat
            def hb = HQ_HEARTBEAT.matcher(msg)
            if (hb.find()) {
                def deviceId = hb.group(1)
                if (!ctx.session(deviceId)) return null
                ctx.ack("HQ,${deviceId},R12,${utcNow()}#")
                return null
            }

            // V1 / V5 / V6 position
            def m = HQ_POSITION.matcher(msg)
            if (!m.find()) return null

            def deviceId = m.group(1)
            def session  = ctx.session(deviceId)
            if (!session) return null

            ctx.ack("HQ,${deviceId},R12,${utcNow()}#")

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def dataType = m.group(2)

            pos.time = new DateBuilder()
                    .setTime(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setDateReverse(m.group(15).toInteger(), m.group(16).toInteger(), m.group(17).toInteger())
                    .getDate()

            pos.valid     = m.group(6) == 'A'
            pos.latitude  = coordinate(m.group(7),  m.group(8),  m.group(9))
            pos.longitude = coordinate(m.group(10), m.group(11), m.group(12))
            pos.speed     = UnitsConverter.knotsFromKph(m.group(13).toDouble())
            pos.course    = m.group(14).toDouble()

            decodeVehicleStatus(pos, m.group(18))

            def network = new Network(CellTower.from(
                    m.group(19).toInteger(), m.group(20).toInteger(),
                    m.group(21).toInteger(), m.group(22).toInteger()))
            pos.network = network

            def extra1 = m.group(23)
            def extra2 = m.group(24)
            if (dataType == 'V5' && extra1) {
                pos.set(Position.KEY_ODOMETER, extra1.toLong() * 100)
                if (extra2) pos.set(Position.KEY_POWER, extra2.toInteger() / 10.0)
            } else if (dataType == 'V6' && extra1) {
                pos.set(Position.KEY_ICCID, extra1)
            }

            return pos
        }

        encode { cmd, ctx ->
            def id = ctx.deviceId()
            def t  = ctx.utcTime()
            switch (cmd.type) {
                case TYPE_ENGINE_STOP:       return "*HQ,${id},S20,${t},1,1#"
                case TYPE_ENGINE_RESUME:     return "*HQ,${id},S20,${t},0,0#"
                case TYPE_ALARM_ARM:         return "*HQ,${id},SF,${t},0,0#"
                case TYPE_ALARM_DISARM:      return "*HQ,${id},CF,${t},1,1#"
                case TYPE_POSITION_PERIODIC: return "*HQ,${id},D1,${t},${ctx.freq()},1#"
                case TYPE_CUSTOM:            return "*HQ,${id},${ctx.data()},${t}#"
                default:                     return null
            }
        }
    }

    // -----------------------------------------------------------------------
    // MT700 variant — MT700/MT700W/MT600/MT530 (#IMEI#MODEL#...## framing)
    // -----------------------------------------------------------------------
    variant("mt700") {
        // Multi-line frame; WIFI variant with many APs can reach ~1 KB.
        maxFrameLength 2048
        frame '#' as char, readUntil('##')
        matches { msg -> msg.startsWith("#") && msg.contains("#MT") }
        model   { msg -> msg.split("#")[2] }   // "MT700", "MT600", etc.

        alarms {
            "TOWED"      >> ALARM_TOW
            "SHAKE"      >> ALARM_VIBRATION
            "BLP"        >> ALARM_LOW_BATTERY
            "CLP"        >> ALARM_LOW_BATTERY
            "SOS"        >> ALARM_SOS
            "OVERSPEED"  >> ALARM_OVERSPEED
            "OS"         >> ALARM_GEOFENCE_EXIT
            "RS"         >> ALARM_GEOFENCE_ENTER
            // DEF and HT differ between MT700 and MT600
            "DEF" >> { m -> m?.startsWith("MT700") ? ALARM_REMOVING  : ALARM_POWER_CUT  }
            "HT"  >> { m -> m?.startsWith("MT700") ? null            : ALARM_TEMPERATURE }
        }

        decode { msg, ctx ->
            def lines = msg.split(/\r?\n/)
            if (lines.length < 2) return null

            def header = lines[0].split("#")
            if (header.length < 5) return null

            def imei    = header[1]
            def model   = header[2]
            def event   = header[4]
            def body    = lines[1]

            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.addAlarm(ctx.alarm(event, model))

            if (body.contains('$GPRMC')) {
                def m = MT_POSITION.matcher(body)
                if (!m.find()) return null

                if (m.group(1)) pos.set(Position.KEY_BATTERY, voltage(m.group(1)))
                if (m.group(2)) {
                    def network = new Network()
                    network.addCellTower(CellTower.from(
                            m.group(2).toInteger(), m.group(3).toInteger(),
                            Integer.parseInt(m.group(4), 16), Integer.parseInt(m.group(5), 16)))
                    pos.network = network
                }

                def db = new DateBuilder()
                if (m.group(6)) db.setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())

                pos.valid = m.group(9) == 'A'
                if (m.group(10)) {
                    pos.latitude  = coordinate(m.group(10), m.group(11), m.group(12))
                    pos.longitude = coordinate(m.group(13), m.group(14), m.group(15))
                    pos.speed     = m.group(16) ? m.group(16).toDouble() : 0
                    pos.course    = m.group(17) ? m.group(17).toDouble() : 0
                    db.setDateReverse(m.group(18).toInteger(), m.group(19).toInteger(), m.group(20).toInteger())
                    pos.time = db.getDate()
                } else {
                    ctx.lastLocation(pos)
                }

            } else if (body.contains('$WIFI')) {
                def m = MT_WIFI.matcher(body)
                if (!m.find()) return null

                if (m.group(1)) pos.set(Position.KEY_BATTERY, voltage(m.group(1)))

                def network = new Network()
                if (m.group(2)) {
                    network.addCellTower(CellTower.from(
                            m.group(2).toInteger(), m.group(3).toInteger(),
                            Integer.parseInt(m.group(4), 16), Integer.parseInt(m.group(5), 16)))
                }

                def db = new DateBuilder()
                        .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())

                m.group(9).split(",(?=-)").each { ap ->
                    def parts = ap.split(",", 2)
                    if (parts.length == 2 && parts[1]) {
                        try {
                            def mac = parts[1].replaceAll(/(..)/, '$1:')[0..16]
                            network.addWifiAccessPoint(WifiAccessPoint.from(mac, parts[0].toInteger()))
                        } catch (ignored) {}
                    }
                }
                pos.network = network

                db.setDateReverse(m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
                ctx.lastLocation(pos)
                pos.fixTime = db.getDate()

            } else {
                return null
            }

            return pos
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_REBOOT_DEVICE:     return "REBOOT"
                case TYPE_POSITION_PERIODIC: return "MODE,1,${ctx.freq()}"
                case TYPE_MODE_DEEP_SLEEP:
                    long hours = ctx.clamp((long)(ctx.freq() / 3600), 1, 24)
                    return "MODE,3,${hours}"
                case TYPE_SET_CONNECTION:    return "804,${ctx.server()},${ctx.port()}"
                case TYPE_GET_DEVICE_STATUS: return "RCONF,1"
                case TYPE_CUSTOM:            return ctx.data()
                default:                     return null
            }
        }
    }
}
