// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * EeLink GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/eelink/ (Java reference)
 *
 * Binary protocol on port 5064. Supports both TCP and UDP transports.
 *
 * TCP framing: length-field-based — 2-byte header (0x67 0x67), 1-byte type,
 * 2-byte length (payload size including 2-byte index), payload.
 *
 * UDP framing: outer "EL" header (0x45 0x4C) + 2-byte outer-length +
 * 2-byte checksum + 8-byte device-ID, followed by one or more TCP-style frames.
 *
 * Protocol variants ("old" protocol types 0x01–0x07, "new" types 0x12–0x1F):
 *   0x01 MSG_LOGIN     — device registers IMEI
 *   0x02 MSG_GPS       — old: position + optional status/battery/ADC
 *   0x03 MSG_HEARTBEAT — old: status decode
 *   0x04 MSG_ALARM     — old: position + alarm type
 *   0x05 MSG_STATE     — old: position + event type + optional status
 *   0x07 MSG_OBD       — old: OBD data or status-only (4-byte payload)
 *   0x12 MSG_NORMAL    — new: full position with optional extras
 *   0x14 MSG_WARNING   — new: position + alarm
 *   0x15 MSG_REPORT    — new: position + report type
 *   0x16–0x1F         — new: OBD/camera variants (basic decode)
 *   0x80 MSG_DOWNLINK  — command result with optional GPS sentence
 *
 * ACK (TCP): 0x67 0x67 <type> 00 02 <index_hi> <index_lo>
 *   MSG_LOGIN ACK also appends: <timestamp_4> 00 01 00 (protocol v1, mask 0)
 *
 * Encoder sends TCP-format downlink (MSG_DOWNLINK = 0x80).
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

// ── message type constants ──────────────────────────────────────────────────

def MSG_LOGIN     = 0x01
def MSG_GPS       = 0x02
def MSG_HEARTBEAT = 0x03
def MSG_ALARM     = 0x04
def MSG_STATE     = 0x05
def MSG_OBD       = 0x07
def MSG_DOWNLINK  = 0x80

def MSG_NORMAL    = 0x12
def MSG_WARNING   = 0x14
def MSG_REPORT    = 0x15
def MSG_OBD_CODE  = 0x19

// ── alarm table ────────────────────────────────────────────────────────────

def decodeAlarm = { int v ->
    switch (v) {
        case 0x01: return Position.ALARM_POWER_OFF
        case 0x02: return Position.ALARM_SOS
        case 0x03: return Position.ALARM_LOW_BATTERY
        case 0x04: return Position.ALARM_VIBRATION
        case 0x08: return Position.ALARM_GPS_ANTENNA_CUT
        case 0x09: return Position.ALARM_GPS_ANTENNA_CUT
        case 0x25: return Position.ALARM_REMOVING
        case 0x81: return Position.ALARM_LOW_SPEED
        case 0x82: return Position.ALARM_OVERSPEED
        case 0x83: return Position.ALARM_GEOFENCE_ENTER
        case 0x84: return Position.ALARM_GEOFENCE_EXIT
        case 0x85: return Position.ALARM_ACCIDENT
        case 0x86: return Position.ALARM_FALL_DOWN
        default:   return null
    }
}

// ── old-protocol status field ───────────────────────────────────────────────

def decodeStatus = { pos, int status ->
    if (BitUtil.check(status, 1)) pos.set(Position.KEY_IGNITION, BitUtil.check(status, 2))
    if (BitUtil.check(status, 3)) pos.set(Position.KEY_ARMED,    BitUtil.check(status, 4))
    if (BitUtil.check(status, 5)) pos.set(Position.KEY_BLOCKED,  !BitUtil.check(status, 6))
    if (BitUtil.check(status, 7)) pos.set(Position.KEY_CHARGE,   BitUtil.check(status, 8))
    pos.set(Position.KEY_STATUS, status)
}

// ── old protocol decoder (types 0x02, 0x04, 0x05, 0x06) ────────────────────

def decodeOld = { BufReader buf, int type, int index, session, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, index)

    pos.time      = new Date(buf.readUInt() * 1000)
    pos.latitude  = buf.readInt() / 1800000.0
    pos.longitude = buf.readInt() / 1800000.0
    pos.speed     = UnitsConverter.knotsFromKph(buf.readUByte())
    pos.course    = buf.readUShort()

    int mcc = buf.readUShort()
    int mnc = buf.readUShort()
    int lac = buf.readUShort()
    // 3-byte big-endian CID (readUnsignedMedium equivalent)
    long cid = ((buf.readUByte() << 16) | buf.readUShort()) & 0xFFFFFF

    pos.network = new Network(CellTower.from(mcc, mnc, lac, cid))
    pos.valid   = (buf.readUByte() & 0x01) != 0

    if (type == MSG_GPS) {
        if (buf.readableBytes() >= 2) {
            decodeStatus(pos, buf.readUShort())
        }
        if (buf.readableBytes() >= 8) {
            pos.set(Position.KEY_BATTERY,          buf.readUShort() / 1000.0)
            pos.set(Position.KEY_RSSI,             buf.readUShort())
            pos.set(Position.PREFIX_ADC + 1,       buf.readUShort())
            pos.set(Position.PREFIX_ADC + 2,       buf.readUShort())
        }
    } else if (type == MSG_ALARM) {
        pos.addAlarm(decodeAlarm(buf.readUByte()))
    } else if (type == MSG_STATE) {
        int statusType = buf.readUByte()
        pos.set(Position.KEY_EVENT, statusType)
        if (statusType == 0x01 || statusType == 0x02 || statusType == 0x03) {
            buf.skip(4) // device time
            if (buf.readableBytes() >= 2) {
                decodeStatus(pos, buf.readUShort())
            }
        }
    }

    return pos
}

// ── new protocol decoder (types 0x12–0x1F) ─────────────────────────────────

def decodeNew = { BufReader buf, int type, int index, session, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, index)

    long timestamp = buf.readUInt()
    pos.time = new Date(timestamp * 1000)

    int flags = buf.readUByte()

    if (BitUtil.check(flags, 0)) {
        pos.latitude  = buf.readInt() / 1800000.0
        pos.longitude = buf.readInt() / 1800000.0
        pos.altitude  = buf.readShort()
        pos.speed     = UnitsConverter.knotsFromKph(buf.readUShort())
        pos.course    = buf.readUShort()
        pos.set(Position.KEY_SATELLITES, buf.readUByte())
    } else {
        ctx.lastLocation(pos, pos.fixTime)
    }

    def network = new Network()
    int mcc = 0, mnc = 0

    if (BitUtil.check(flags, 1)) {
        mcc = buf.readUShort()
        mnc = buf.readUShort()
        network.addCellTower(CellTower.from(mcc, mnc, buf.readUShort(), buf.readUInt(), buf.readUByte()))
    }
    if (BitUtil.check(flags, 2)) {
        network.addCellTower(CellTower.from(mcc, mnc, buf.readUShort(), buf.readUInt(), buf.readUByte()))
    }
    if (BitUtil.check(flags, 3)) {
        network.addCellTower(CellTower.from(mcc, mnc, buf.readUShort(), buf.readUInt(), buf.readUByte()))
    }

    if (BitUtil.check(flags, 4)) {
        String raw = buf.readHex(6)
        String mac = raw.replaceAll('(..)', '$1:')
        mac = mac.substring(0, mac.length() - 1)
        network.addWifiAccessPoint(WifiAccessPoint.from(mac, buf.readUByte()))
    }
    if (BitUtil.check(flags, 5)) {
        String raw = buf.readHex(6)
        String mac = raw.replaceAll('(..)', '$1:')
        mac = mac.substring(0, mac.length() - 1)
        network.addWifiAccessPoint(WifiAccessPoint.from(mac, buf.readUByte()))
    }
    if (BitUtil.check(flags, 6)) {
        String raw = buf.readHex(6)
        String mac = raw.replaceAll('(..)', '$1:')
        mac = mac.substring(0, mac.length() - 1)
        network.addWifiAccessPoint(WifiAccessPoint.from(mac, buf.readUByte()))
    }

    if (BitUtil.check(flags, 7)) {
        buf.readUByte() // radio access technology
        int count = buf.readUByte()
        int llteLac = 0
        if (count > 0) {
            mcc = buf.readUShort()
            mnc = buf.readUShort()
            llteLac = buf.readUShort() // lac
            buf.skip(2)               // tac
            buf.skip(4)               // cid
            buf.skip(2)               // ta
        }
        for (int i = 0; i < count; i++) {
            int physCid = buf.readUShort()
            buf.skip(2) // e-arfcn
            int rssi    = buf.readUByte()
            network.addCellTower(CellTower.from(mcc, mnc, llteLac, physCid, rssi))
        }
    }

    if (network.cellTowers || network.wifiAccessPoints) {
        pos.network = network
    }

    if (type == MSG_WARNING) {
        pos.addAlarm(decodeAlarm(buf.readUByte()))
    } else if (type == MSG_REPORT) {
        buf.readUByte() // report type
    }

    if (type == MSG_NORMAL || type == MSG_WARNING || type == MSG_REPORT) {
        int status = buf.readUShort()
        pos.valid = BitUtil.check(status, 0)
        if (BitUtil.check(status, 1))  pos.set(Position.KEY_IGNITION, BitUtil.check(status, 2))
        if (BitUtil.check(status, 3)) {
            pos.set(Position.KEY_ARMED,  BitUtil.check(status, 4))
            pos.set(Position.KEY_MOTION, BitUtil.check(status, 9))
        }
        if (BitUtil.check(status, 5))  pos.set(Position.KEY_BLOCKED, BitUtil.check(status, 6))
        if (BitUtil.check(status, 7))  pos.set(Position.KEY_CHARGE,  BitUtil.check(status, 8))
        pos.set(Position.KEY_GPS,    BitUtil.check(status, 10))
        pos.set(Position.KEY_STATUS, status)
    }

    if (type == MSG_NORMAL) {
        if (buf.readableBytes() >= 2) {
            pos.set(Position.KEY_BATTERY, buf.readUShort() / 1000.0)
        }
        if (buf.readableBytes() >= 4) {
            pos.set(Position.PREFIX_ADC + 0, buf.readUShort())
            pos.set(Position.PREFIX_ADC + 1, buf.readUShort())
        }
        if (buf.readableBytes() >= 4) {
            pos.set(Position.KEY_ODOMETER, buf.readUInt())
        }
        if (buf.readableBytes() >= 4) {
            buf.skip(2) // gsm counter
            buf.skip(2) // gps counter
        }
        if (buf.readableBytes() >= 4) {
            pos.set(Position.KEY_STEPS, buf.readUShort())
            buf.skip(2) // walking time
        }
        if (buf.readableBytes() >= 12) {
            pos.set(Position.PREFIX_TEMP + 1, buf.readShort() / 256.0)
            pos.set(Position.KEY_HUMIDITY,    buf.readUShort() / 10.0)
            pos.set("illuminance",            buf.readUInt() / 256.0)
            pos.set("co2",                    buf.readUInt())
        }
        if (buf.readableBytes() >= 2) {
            pos.set(Position.PREFIX_TEMP + 2, buf.readShort() / 16.0)
        }
        if (buf.readableBytes() >= 2) {
            int count = buf.readUByte()
            buf.readUByte() // id
            for (int i = 1; i <= count; i++) {
                pos.set("tag${i}Id",      buf.readHex(6))
                buf.skip(4)               // signal, reserved, model, version
                pos.set("tag${i}Battery", buf.readUShort() / 1000.0)
                pos.set("tag${i}Temp",    buf.readShort() / 256.0)
                pos.set("tag${i}Data",    buf.readUShort())
            }
        }
    }

    return pos
}

// ── OBD-data decoder ────────────────────────────────────────────────────────

def decodeObd = { BufReader buf, session, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    long timestamp = buf.readUInt()
    ctx.lastLocation(pos, new Date(timestamp * 1000))

    while (buf.readableBytes() > 0) {
        int pid   = buf.readUByte()
        int value = buf.readInt()
        switch (pid) {
            case 0x89: pos.set(Position.KEY_FUEL_CONSUMPTION, value); break
            case 0x8a: pos.set(Position.KEY_ODOMETER, value * 1000L); break
            case 0x8b: pos.set(Position.KEY_FUEL, value / 10); break
        }
    }

    return pos
}

// ── command-result decoder (MSG_DOWNLINK 0x80) ──────────────────────────────

def PATTERN_RESULT = Pattern.compile(
    "(?s)Lat:([NS])(\\d+\\.\\d+).*Lon:([EW])(\\d+\\.\\d+).*Course:(\\d+\\.\\d+).*Speed:(\\d+\\.\\d+).*Date ?Time:(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})")

def decodeResult = { BufReader buf, int index, session, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, index)

    buf.readUByte() // command type
    buf.skip(4)     // uid

    String sentence = buf.readString(buf.readableBytes(), "UTF-8")

    def m = PATTERN_RESULT.matcher(sentence)
    if (m.matches()) {
        pos.valid     = true
        double latDeg = m.group(2).toDouble()
        pos.latitude  = m.group(1) == "S" ? -latDeg : latDeg
        double lonDeg = m.group(4).toDouble()
        pos.longitude = m.group(3) == "W" ? -lonDeg : lonDeg
        pos.course = m.group(5).toDouble()
        pos.speed  = UnitsConverter.knotsFromKph(m.group(6).toDouble())
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(m.group(7).toInteger(), m.group(8).toInteger() - 1, m.group(9).toInteger(),
                m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
        cal.set(Calendar.MILLISECOND, 0)
        pos.time = cal.getTime()
    } else {
        ctx.lastLocation(pos)
        pos.set(Position.KEY_RESULT, sentence)
    }

    return pos
}

// ── frame processor (one 67 67 inner frame) ─────────────────────────────────

def processFrame = { BufReader buf, isUdp, udpSession, ctx ->
    if (buf.readableBytes() < 5) return

    buf.skip(2) // header 67 67
    int type   = buf.readUByte()
    int length = buf.readUShort()
    def payload = buf.slice(length)
    int index   = payload.readUShort()

    // ACK for all types except MSG_GPS (0x02) and MSG_DATA (0x81)
    if (type != 0x02 && type != 0x81) {
        if (type == 0x01) {
            // Login ACK: type + length(9) + index + timestamp(4) + protocol-version(2) + action-mask(1)
            ctx.ack(bytes {
                writeByte 0x67; writeByte 0x67
                writeByte type
                writeShort 9
                writeShort index
                writeInt((int) (System.currentTimeMillis() / 1000))
                writeShort 1
                writeByte 0
            })
        } else {
            ctx.ack(bytes {
                writeByte 0x67; writeByte 0x67
                writeByte type
                writeShort 2
                writeShort index
            })
        }
    }

    if (type == 0x01) {
        // MSG_LOGIN: register device from payload
        if (!isUdp) {
            if (payload.readableBytes() >= 8) {
                String uniqueId = payload.readHex(8).substring(1)
                ctx.session(uniqueId)
            }
        }
        // UDP session already registered from outer header
        return
    }

    def session = isUdp ? udpSession : ctx.session()
    if (!session) return null

    if (type == 0x02 || type == 0x04 || type == 0x05 || type == 0x06) {
        return decodeOld(payload, type, index, session, ctx)

    } else if (type >= MSG_NORMAL && type <= MSG_OBD_CODE) {
        return decodeNew(payload, type, index, session, ctx)

    } else if (type == 0x03 && payload.readableBytes() >= 2
               || type == 0x07 && payload.readableBytes() == 4) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        ctx.lastLocation(pos)
        decodeStatus(pos, payload.readUShort())
        return pos

    } else if (type == 0x07) {
        return decodeObd(payload, session, ctx)

    } else if (type == 0x80) {
        return decodeResult(payload, index, session, ctx)
    }
    return null
}

// ── protocol definition ─────────────────────────────────────────────────────

protocol("eelink") {

    port 5064
    commands TYPE_CUSTOM, TYPE_POSITION_SINGLE, TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME, TYPE_REBOOT_DEVICE

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 5) return null

            int b0 = fb.getUByte(0)
            int b1 = fb.getUByte(1)

            if (b0 == 0x45 && b1 == 0x4C) {
                // UDP datagram: deliver entire buffer as one frame
                return fb.readableBytes()
            }

            if (b0 == 0x67 && b1 == 0x67) {
                int len   = fb.getUShort(3)
                int total = 5 + len
                return (fb.readableBytes() >= total) ? total : null
            }

            return null
        }

        decode { msg, ctx ->
            def buf = msg as BufReader

            if (buf.getUByte(0) == 0x45 && buf.getUByte(1) == 0x4C) {
                // UDP mode: extract device ID from outer header, process sub-frames
                buf.skip(6) // EL(2) + outer_length(2) + checksum(2)
                String uniqueId = buf.readHex(8).substring(1)
                def session = ctx.session(uniqueId)

                def positions = []
                while (buf.isReadable()) {
                    def pos = processFrame(buf, true, session, ctx)
                    if (pos) positions << pos
                }
                if (positions.size() == 1) return positions[0]
                positions.each { ctx.emit(it) }
                return null
            } else {
                // TCP mode: session registered via prior MSG_LOGIN, return single position
                return processFrame(buf, false, null, ctx)
            }
        }

        encode { cmd, ctx ->
            String text
            switch (cmd.type) {
                case TYPE_CUSTOM:          text = ctx.data(); break
                case TYPE_POSITION_SINGLE: text = "WHERE#"; break
                case TYPE_ENGINE_STOP:     text = "RELAY,1#"; break
                case TYPE_ENGINE_RESUME:   text = "RELAY,0#"; break
                case TYPE_REBOOT_DEVICE:   text = "RESET#"; break
                default: return null
            }
            if (!text) return null

            byte[] textBytes = text.getBytes("UTF-8")
            return bytes {
                writeByte 0x67; writeByte 0x67
                writeByte 0x80
                writeShort 7 + textBytes.length
                writeShort 0
                writeByte 0x01
                writeInt 0
                writeBytes textBytes
            }
        }
    }
}
