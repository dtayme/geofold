// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * JT600 GPS tracker driver.
 *
 * Binary frames start with '$' (0x24); length field is at offset 7 (short format)
 * or offset 8 (long format, detected by buf[8]==0). Total frame = length_field + 10.
 * Text frames are '(' ... ')' with escape sequences: 0x3d 0x15 → '(', 0x3d 0x14 → ')',
 * 0x3d 0x11 → ',', 0x3d 0x00 → '='. A WLNET peripheral frame is a mixed
 * ASCII header + binary body; it is detected before unescape by scanning for "WLNET,5,".
 *
 * Binary: id is hexDump(5 bytes) parsed as decimal long.
 * Long format has extra protocolVersion byte before the version/length bytes.
 * Versions: 1 = sat/power/alt/cell, 2 = fuel/status/odometer, 3 = BitBuffer fuel+odometer+status.
 *
 * Text message types:
 *   W01  GPS position with lon/lat in DDDMM.mmmm format (lon first)
 *   U01/U02/U03/U06  position with decimal lat/lon, cell info, odometer
 *   P45  position with decimal lat/lon, RFID
 *   WLNET peripheral sensor (temperature/humidity)
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

def PATTERN_W01 = Pattern.compile(
    "\\((\\d+)," +
    "W01," +
    "(\\d{3})(\\d{2}\\.\\d{4}),([EW])," +
    "(\\d{2})(\\d{2}\\.\\d{4}),([NS])," +
    "([AV])," +
    "(\\d{2})(\\d{2})(\\d{2})," +
    "(\\d{2})(\\d{2})(\\d{2})," +
    "(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)," +
    ".*"
)

def PATTERN_U01 = Pattern.compile(
    "\\((\\d+),(U\\d{2}),(?:\\d+,)?" +
    "(\\d{2})(\\d{2})(\\d{2}),(\\d{2})(\\d{2})(\\d{2})," +
    "([TF])," +
    "(\\d+\\.\\d+),([NS]),(\\d+\\.\\d+),([EW])," +
    "(\\d+\\.?\\d*),(\\d+),(\\d+),(\\d+)%,([01]+)," +
    "(\\d+),(\\d+),(\\d+),(\\d+),(\\d+).*"
)

def PATTERN_P45 = Pattern.compile(
    "\\((\\d+),P45," +
    "(\\d{2})(\\d{2})(\\d{2}),(\\d{2})(\\d{2})(\\d{2})," +
    "(\\d+\\.\\d+),([NS]),(\\d+\\.\\d+),([EW])," +
    "([AV]),(\\d+),(\\d+),(\\d+),\\d+,(.+?),\\d+,\\d+,(\\d+).*"
)

def convertCoordinate = { int raw ->
    int degrees = raw / 1000000
    double minutes = (raw % 1000000) / 10000.0
    degrees + minutes / 60.0
}

def setDateTime = { Calendar cal, int day, int month, int year, int hour, int min, int sec ->
    cal.set(year, month - 1, day, hour, min, sec)
    cal.set(Calendar.MILLISECOND, 0)
}

// Mimics BcdUtil.readInteger(buf, n): for even n reads n/2 bytes; for odd n reads (n-1)/2
// bytes then peeks the high nibble of the next byte without consuming it.
def bcdInt = { BufReader b, int n ->
    int r = 0
    for (int i = 0; i < (n >> 1); i++) {
        int v = b.readUByte()
        r = r * 10 + ((v >> 4) & 0xF)
        r = r * 10 + (v & 0xF)
    }
    if ((n & 1) != 0) {
        r = r * 10 + ((b.getUByte(0) >> 4) & 0xF)
    }
    r
}

def decodeBinaryLocation = { BufReader buf, Position pos ->
    int day   = bcdInt(buf, 2)
    int month = bcdInt(buf, 2)
    int year  = bcdInt(buf, 2) + 2000
    int hour  = bcdInt(buf, 2)
    int min   = bcdInt(buf, 2)
    int sec   = bcdInt(buf, 2)
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    setDateTime(cal, day, month, year, hour, min, sec)
    pos.setTime(cal.getTime())

    int latRaw = bcdInt(buf, 8)
    int lonRaw = bcdInt(buf, 9)   // reads 4 bytes, peeks high nibble of 5th (like BcdUtil)

    int flags = buf.readByte() & 0xFF
    pos.setValid(BitUtil.check(flags, 0))
    double lat = convertCoordinate(latRaw)
    double lon = convertCoordinate(lonRaw)
    pos.setLatitude(BitUtil.check(flags, 1) ? lat : -lat)
    pos.setLongitude(BitUtil.check(flags, 2) ? lon : -lon)

    pos.setSpeed(bcdInt(buf, 2))
    pos.setCourse(buf.readUByte() * 2.0)
}

def decodeStatus = { BufReader buf, Position pos ->
    int v1 = buf.readUByte()
    pos.set(Position.KEY_IGNITION, BitUtil.check(v1, 0))
    pos.set(Position.KEY_DOOR, BitUtil.check(v1, 6))
    int v2 = buf.readUByte()
    pos.set(Position.KEY_CHARGE, BitUtil.check(v2, 0))
    pos.set(Position.KEY_BLOCKED, BitUtil.check(v2, 1))
    if (BitUtil.check(v2, 2)) pos.addAlarm(Position.ALARM_SOS)
    if (BitUtil.check(v2, 3) || BitUtil.check(v2, 4)) pos.addAlarm(Position.ALARM_GPS_ANTENNA_CUT)
    if (BitUtil.check(v2, 4)) pos.addAlarm(Position.ALARM_OVERSPEED)
    int v3 = buf.readUByte()
    if (BitUtil.check(v3, 2)) pos.addAlarm(Position.ALARM_FATIGUE_DRIVING)
    if (BitUtil.check(v3, 3)) pos.addAlarm(Position.ALARM_TOW)
    buf.readUByte() // reserved
}

def decodeBinary = { BufReader buf, ctx ->
    boolean longFormat = buf.getUByte(8) == 0
    buf.readByte() // '$'
    String id = String.valueOf(Long.parseLong(buf.readHex(5)))
    def session = ctx.session(id)
    if (!session) return null

    int protocolVersion = 0
    if (longFormat) protocolVersion = buf.readUByte()
    int version = (buf.readUByte() >> 4) & 0xF
    buf.readUShort() // length

    boolean responseRequired = false
    while (buf.remaining() >= 17) {
        Position pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        decodeBinaryLocation(buf, pos)

        if (longFormat) {
            pos.set(Position.KEY_ODOMETER, buf.readUInt() * 1000L)
            pos.set(Position.KEY_SATELLITES, buf.readUByte())
            buf.readUInt() // vehicle id combined
            int status = buf.readUShort()
            if (BitUtil.check(status, 1)) pos.addAlarm(Position.ALARM_GEOFENCE_ENTER)
            if (BitUtil.check(status, 2)) pos.addAlarm(Position.ALARM_GEOFENCE_EXIT)
            if (BitUtil.check(status, 3)) pos.addAlarm(Position.ALARM_POWER_CUT)
            if (BitUtil.check(status, 4)) pos.addAlarm(Position.ALARM_VIBRATION)
            if (BitUtil.check(status, 5)) responseRequired = true
            pos.set(Position.KEY_BLOCKED, BitUtil.check(status, 7))
            if (BitUtil.check(status, 11)) pos.addAlarm(Position.ALARM_LOW_BATTERY)
            if (BitUtil.check(status, 14)) pos.addAlarm(Position.ALARM_FAULT)
            pos.set(Position.KEY_STATUS, status)
            int battery = buf.readUByte()
            if (battery == 0xff) {
                pos.set(Position.KEY_CHARGE, true)
            } else {
                pos.set(Position.KEY_BATTERY_LEVEL, battery)
            }
            int cid = buf.readUShort()
            int lac = buf.readUShort()
            int rssi = buf.readUByte()
            if (cid != 0 && lac != 0) {
                def ct = CellTower.from(0, 0, lac, cid)
                ct.setSignalStrength(rssi)
                pos.setNetwork(new Network(ct))
            }
            if (protocolVersion == 0x17 || protocolVersion == 0x19) {
                buf.readUByte() // geofence id
                buf.skip(3)    // reserved
                if (buf.remaining() > 1) buf.skip(buf.remaining() - 1)
            }
        } else if (version == 1) {
            pos.set(Position.KEY_SATELLITES, buf.readUByte())
            pos.set(Position.KEY_POWER, buf.readUByte())
            buf.readUByte() // other flags
            pos.setAltitude(buf.readUShort())
            int cid = buf.readUShort()
            int lac = buf.readUShort()
            int rssi = buf.readUByte()
            if (cid != 0 && lac != 0) {
                def ct = CellTower.from(0, 0, lac, cid)
                ct.setSignalStrength(rssi)
                pos.setNetwork(new Network(ct))
            } else {
                pos.set(Position.KEY_RSSI, rssi)
            }
        } else if (version == 2) {
            int fuelHi = buf.readUByte() << 8
            decodeStatus(buf, pos)
            pos.set(Position.KEY_ODOMETER, buf.readUInt() * 1000L)
            pos.set(Position.KEY_FUEL_LEVEL, fuelHi | buf.readUByte())
        } else if (version == 3) {
            // 10 bytes = 80 bits: fuel1(12) fuel2(12) fuel3(12) odometer(20) status(24)
            long v = 0
            for (int i = 0; i < 10; i++) v = (v << 8) | (long)(buf.readUByte())
            pos.set("fuel1",               (int)((v >> 68) & 0xFFF))
            pos.set("fuel2",               (int)((v >> 56) & 0xFFF))
            pos.set("fuel3",               (int)((v >> 44) & 0xFFF))
            pos.set(Position.KEY_ODOMETER, ((v >> 24) & 0xFFFFF) * 1000L)
            int st = (int)(v & 0xFFFFFF)
            pos.set(Position.KEY_IGNITION, BitUtil.check(st, 0))
            pos.set(Position.KEY_STATUS,   st)
        }
        ctx.emit(pos)
    }

    if (buf.isReadable()) {
        int index = buf.readUByte()
        if (responseRequired) {
            ctx.ack(protocolVersion < 0x19 ? "(P35)" : "(P69,0," + index + ")")
        }
    }
    return null
}

def decodeW01 = { String sentence, ctx ->
    def m = PATTERN_W01.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    Position pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    double lon = m.group(2).toDouble() + m.group(3).toDouble() / 60.0
    if ("W" == m.group(4)) lon = -lon
    pos.setLongitude(lon)

    double lat = m.group(5).toDouble() + m.group(6).toDouble() / 60.0
    if ("S" == m.group(7)) lat = -lat
    pos.setLatitude(lat)

    pos.setValid("A" == m.group(8))

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(11).toInteger() + 2000, m.group(10).toInteger() - 1, m.group(9).toInteger(),
            m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())

    pos.setSpeed(UnitsConverter.knotsFromKph(m.group(15).toDouble()))
    pos.setCourse(m.group(16).toDouble())
    pos.set(Position.KEY_POWER,    m.group(17).toDouble())
    pos.set(Position.KEY_GPS,      m.group(18).toInteger())
    pos.set(Position.KEY_RSSI,     m.group(19).toInteger())
    pos.set("alertType",           m.group(20).toInteger())
    pos
}

def decodeU01 = { String sentence, ctx ->
    def m = PATTERN_U01.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    String type = m.group(2)
    Position pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(5).toInteger() + 2000, m.group(4).toInteger() - 1, m.group(3).toInteger(),
            m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())

    pos.setValid("T" == m.group(9))

    double lat = m.group(10).toDouble()
    if ("S" == m.group(11)) lat = -lat
    pos.setLatitude(lat)

    double lon = m.group(12).toDouble()
    if ("W" == m.group(13)) lon = -lon
    pos.setLongitude(lon)

    pos.setSpeed(UnitsConverter.knotsFromMph(m.group(14).toDouble()))
    pos.setCourse(m.group(15).toDouble())
    pos.set(Position.KEY_SATELLITES,    m.group(16).toInteger())
    pos.set(Position.KEY_BATTERY_LEVEL, m.group(17).toInteger())
    pos.set(Position.KEY_STATUS,        Integer.parseInt(m.group(18), 2))

    int cid = m.group(19).toInteger()
    int lac = m.group(20).toInteger()
    int rssi = m.group(21).toInteger()
    if (cid != 0 && lac != 0) {
        def ct = CellTower.from(0, 0, lac, cid)
        ct.setSignalStrength(rssi)
        pos.setNetwork(new Network(ct))
    }

    pos.set(Position.KEY_ODOMETER, m.group(22).toLong() * 1000)
    pos.set(Position.KEY_INDEX,    m.group(23).toInteger())

    if (type == "U01" || type == "U02" || type == "U03") {
        ctx.ack("(S39)")
    } else if (type == "U06") {
        ctx.ack("(S20)")
    }

    pos
}

def decodeP45 = { String sentence, ctx ->
    def m = PATTERN_P45.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    Position pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(4).toInteger() + 2000, m.group(3).toInteger() - 1, m.group(2).toInteger(),
            m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())

    double lat = m.group(8).toDouble()
    if ("S" == m.group(9)) lat = -lat
    pos.setLatitude(lat)

    double lon = m.group(10).toDouble()
    if ("W" == m.group(11)) lon = -lon
    pos.setLongitude(lon)

    pos.setValid("A" == m.group(12))
    pos.setSpeed(UnitsConverter.knotsFromMph(m.group(13).toDouble()))
    pos.setCourse(m.group(14).toDouble())
    pos.set("eventSource", m.group(15).toInteger())

    String rfid = m.group(16)
    if (rfid != "0000000000") pos.set(Position.KEY_DRIVER_UNIQUE_ID, rfid)

    int index = m.group(17).toInteger()
    ctx.ack("(P69,0," + index + ")")

    pos
}

def decodePeripherals = { BufReader buf, ctx ->
    // Device ID: chars at offsets 1-10 (after opening '(')
    byte[] idPeek = buf.getBytes(1, 10)
    String deviceId = new String(idPeek, StandardCharsets.US_ASCII)
    def session = ctx.session(deviceId)
    if (!session) return null

    Position pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    // Advance past 6 comma-delimited fields to reach binary data
    int commaCount = 0
    while (buf.isReadable() && commaCount < 6) {
        if (buf.readUByte() == 0x2C) commaCount++
    }

    decodeBinaryLocation(buf, pos)
    buf.skip(6) // sensor time
    buf.skip(5) // sensor id
    buf.readUByte() // sensor index

    pos.set("sensorBattery",      buf.readUShort() / 100.0)
    pos.set("sensorBatteryLevel", buf.readUByte())
    pos.set("sensorRssi",        -(int) buf.readUByte())

    int type = buf.readUByte()
    if (type == 1 && buf.remaining() >= 5) {
        int temperature = buf.readUShort()
        if (temperature != 0xffff) {
            int value = temperature & 0xfff
            if ((temperature & 0xf000) > 0) value = -value
            pos.set(Position.PREFIX_TEMP + 1, value / 10.0)
        }
        pos.set(Position.KEY_HUMIDITY, buf.readUByte())
    }

    pos
}

def decodeText = { BufReader buf, ctx ->
    // Peek for WLNET peripheral format without consuming
    int peekLen = Math.min(50, buf.remaining())
    byte[] peek = buf.getBytes(0, peekLen)
    String header = new String(peek, StandardCharsets.ISO_8859_1)
    if (header.contains("WLNET,5,")) {
        return decodePeripherals(buf, ctx)
    }

    // Unescape '(' type frame
    byte[] rawBytes = buf.readBytes(buf.remaining())
    def unescaped = []
    for (int i = 0; i < rawBytes.length; i++) {
        int b = rawBytes[i] & 0xFF
        if (b == 0x3D && i + 1 < rawBytes.length) {
            int ext = rawBytes[++i] & 0xFF
            if      (ext == 0x15) unescaped.add((byte) 0x28)
            else if (ext == 0x14) unescaped.add((byte) 0x29)
            else if (ext == 0x11) unescaped.add((byte) 0x2C)
            else if (ext == 0x00) unescaped.add((byte) 0x3D)
            else { unescaped.add((byte) 0x3D); unescaped.add((byte) ext) }
        } else {
            unescaped.add((byte) b)
        }
    }
    String sentence = new String(unescaped as byte[], StandardCharsets.US_ASCII)

    if (sentence.contains("W01")) return decodeW01(sentence, ctx)
    if (sentence.contains("P45")) return decodeP45(sentence, ctx)
    return decodeU01(sentence, ctx)
}

protocol("jt600") {

    port 5014

    commands(
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME,
        Command.TYPE_SET_TIMEZONE,
        Command.TYPE_REBOOT_DEVICE
    )

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 10) return null
            int b0 = fb.getUByte(0)

            if (b0 == 0x24) {  // '$' — binary frame
                boolean longFmt = fb.getUByte(8) == 0
                int lenOff = longFmt ? 8 : 7
                int length = fb.getUShort(lenOff) + 10
                return fb.readableBytes() >= length ? length : null
            }

            if (b0 == 0x28) {  // '(' — text frame, find closing ')'
                for (int i = 1; i < fb.readableBytes(); i++) {
                    if (fb.getUByte(i) == 0x29) return i + 1
                }
                return null
            }

            return null
        }

        decode { msg, ctx ->
            BufReader buf = msg as BufReader
            int b0 = buf.getUByte(0)
            if (b0 == 0x24) return decodeBinary(buf, ctx)
            if (b0 == 0x28) return decodeText(buf, ctx)
            return null
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case Command.TYPE_ENGINE_STOP:    return "(S07,0)"
                case Command.TYPE_ENGINE_RESUME:  return "(S07,1)"
                case Command.TYPE_SET_TIMEZONE:
                    int off = TimeZone.getTimeZone(cmd.getString(Command.KEY_TIMEZONE)).getRawOffset() / 60000
                    return "(S09,1," + off + ")"
                case Command.TYPE_REBOOT_DEVICE:  return "(S17)"
                default:                          return null
            }
        }
    }
}
