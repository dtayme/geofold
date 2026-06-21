// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Meiligao GPS tracker driver.
 *
 * Frame: $$ (0x24 0x24) header + length(2, total) + id(7 BCD) + type(2) + data + crc16(2) + \r\n
 * 7-byte BCD id: 0xF nibble = terminator; 14 digits → append Luhn for 15-digit IMEI.
 * Response: @@ total_len(2) id(7) type(2) msg crc16_CCITT_FALSE(2) \r\n
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.model.Command
import org.traccar.model.Position

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

def MSG_HEARTBEAT         = 0x0001
def MSG_SERVER            = 0x0002
def MSG_LOGIN             = 0x5000
def MSG_LOGIN_RESPONSE    = 0x4000
def MSG_POSITION          = 0x9955
def MSG_POSITION_LOGGED   = 0x9016
def MSG_ALARM             = 0x9999
def MSG_RFID              = 0x9966
def MSG_RETRANSMISSION    = 0x6688
def MSG_OBD_RT            = 0x9901
def MSG_OBD_RTA           = 0x9902
def MSG_DTC               = 0x9903
def MSG_TRACK_ON_DEMAND   = 0x4101
def MSG_TRACK_BY_INTERVAL = 0x4102
def MSG_MOVEMENT_ALARM    = 0x4106
def MSG_OUTPUT_CONTROL_2  = 0x4115
def MSG_TIME_ZONE         = 0x4132
def MSG_TAKE_PHOTO        = 0x4151
def MSG_UPLOAD_PHOTO      = 0x0800
def MSG_UPLOAD_PHOTO_RSP  = 0x8801
def MSG_DATA_PHOTO        = 0x9988
def MSG_POSITION_IMAGE    = 0x9977
def MSG_UPLOAD_COMPLETE   = 0x0f80
def MSG_REBOOT_GPS        = 0x4902

def PATTERN = Pattern.compile(
    "(\\d+)(\\d{2})(\\d{2})\\.?\\d*," +
    "([AV])," +
    "(\\d+)(\\d{2}\\.\\d+),([NS])," +
    "(\\d+)(\\d{2}\\.\\d+),([EW])," +
    "(\\d+\\.?\\d*)?," +
    "(\\d+\\.?\\d*)?," +
    "(\\d{2})(\\d{2})(\\d{2})" +
    "[^|]*" +
    "(?:\\|(\\d+\\.\\d+)?" +
    "\\|(-?\\d+\\.?\\d*)?" +
    "\\|(\\p{XDigit}{4})?" +
    "(?:\\|(\\p{XDigit}{4}),(\\p{XDigit}{4})" +
    "(?:,(\\p{XDigit}{4}))?(?:,(\\p{XDigit}{4}))?(?:,(\\p{XDigit}{4}))?" +
    "(?:,(\\p{XDigit}{4}))?(?:,(\\p{XDigit}{4}))?(?:,(\\p{XDigit}{4}))?" +
    "(?:\\|\\p{XDigit}{16,20}\\|(\\p{XDigit}{2})\\|(\\p{XDigit}{8})" +
    "(?:\\|(\\p{XDigit}{2})(?:\\|(.*))?)?)?|\\|(\\d{1,9})(?:\\|(\\p{XDigit}{5,}))?)?)?.*"
)

def PATTERN_RFID = Pattern.compile(
    "\\|(\\d{2})(\\d{2})(\\d{2}),(\\d{2})(\\d{2})(\\d{2})," +
    "(\\d+)(\\d{2}\\.\\d+),([NS])," +
    "(\\d+)(\\d{2}\\.\\d+),([EW])"
)

def PATTERN_OBD = Pattern.compile(
    "(\\d+\\.\\d+),(\\d+),(\\d+),(\\d+\\.\\d+),(\\d+\\.\\d+),(-?\\d+)," +
    "(\\d+\\.\\d+),(\\d+\\.\\d+),(\\d+\\.\\d+),(\\d+\\.?\\d*)," +
    "(\\d+\\.\\d+),(\\d+\\.\\d+),(\\d+),(\\d+),(\\d+)"
)

def PATTERN_OBDA = Pattern.compile(
    "(\\d+),(\\d+\\.\\d+),(\\d+\\.\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)"
)

// Build response: @@ total_len(2) id_bytes(7) type(2) msg crc16(2) \r\n
def buildResponse = { byte[] idBytes, int type, byte[] msg ->
    int total = 2 + 2 + 7 + 2 + msg.length + 2 + 2
    byte[] frame = new byte[total]
    frame[0] = (byte) 0x40; frame[1] = (byte) 0x40
    frame[2] = (byte) ((total >> 8) & 0xFF); frame[3] = (byte) (total & 0xFF)
    System.arraycopy(idBytes, 0, frame, 4, 7)
    frame[11] = (byte) ((type >> 8) & 0xFF); frame[12] = (byte) (type & 0xFF)
    System.arraycopy(msg, 0, frame, 13, msg.length)
    int crcOff = 13 + msg.length
    int crc = Checksum.crc16(Checksum.CRC16_CCITT_FALSE, ByteBuffer.wrap(frame, 0, crcOff))
    frame[crcOff]     = (byte) ((crc >> 8) & 0xFF)
    frame[crcOff + 1] = (byte) (crc & 0xFF)
    frame[crcOff + 2] = (byte) 0x0D; frame[crcOff + 3] = (byte) 0x0A
    frame
}

// Extract device ID from 7 BCD bytes and register session; returns session or null
def identifyAndRegister = { byte[] idBytes, ctx ->
    def sb = new StringBuilder()
    for (int i = 0; i < 7; i++) {
        int b = idBytes[i] & 0xFF
        int d1 = (b >> 4) & 0x0F
        if (d1 == 0xF) break
        sb.append((char)(0x30 + d1))
        int d2 = b & 0x0F
        if (d2 == 0xF) break
        sb.append((char)(0x30 + d2))
    }
    String id = sb.toString()
    def session
    if (id.length() == 14) {
        String luhnId = id + Checksum.luhn(Long.parseLong(id))
        session = ctx.session(luhnId)
        if (!session) session = ctx.session(id)
    } else {
        session = ctx.session(id)
    }
    return session
}

def decodeAlarm = { String model, int value ->
    if ("TK218" == model) {
        switch (value) {
            case 0x01: return Position.ALARM_SOS
            case 0x10: return Position.ALARM_LOW_BATTERY
            case 0x11: return Position.ALARM_OVERSPEED
            case 0x12: return Position.ALARM_MOVEMENT
            case 0x13: return Position.ALARM_GEOFENCE
            case 0x60: return Position.ALARM_FATIGUE_DRIVING
            case 0x71: return Position.ALARM_BRAKING
            case 0x72: return Position.ALARM_ACCELERATION
            case 0x73: return Position.ALARM_ACCIDENT
            case 0x74: return Position.ALARM_IDLE
            default:   return null
        }
    } else {
        switch (value) {
            case 0x01: return Position.ALARM_SOS
            case 0x10: return Position.ALARM_LOW_BATTERY
            case 0x11: return Position.ALARM_OVERSPEED
            case 0x12: return Position.ALARM_MOVEMENT
            case 0x13: return Position.ALARM_GEOFENCE_ENTER
            case 0x14: return Position.ALARM_ACCIDENT
            case 0x50: return Position.ALARM_POWER_OFF
            case 0x53: return Position.ALARM_GPS_ANTENNA_CUT
            case 0x72: return Position.ALARM_BRAKING
            case 0x73: return Position.ALARM_ACCELERATION
            default:   return null
        }
    }
}

def decodeRegular = { Position pos, String sentence ->
    def m = PATTERN.matcher(sentence)
    if (!m.matches()) return null

    int hh = m.group(1).toInteger(), mmT = m.group(2).toInteger(), ss = m.group(3).toInteger()
    pos.setValid("A" == m.group(4))

    double lat = m.group(5).toDouble() + m.group(6).toDouble() / 60.0
    if ("S" == m.group(7)) lat = -lat
    pos.setLatitude(lat)

    double lon = m.group(8).toDouble() + m.group(9).toDouble() / 60.0
    if ("W" == m.group(10)) lon = -lon
    pos.setLongitude(lon)

    if (m.group(11) != null) pos.setSpeed(m.group(11).toDouble())
    if (m.group(12) != null) pos.setCourse(m.group(12).toDouble())

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(15).toInteger() + 2000, m.group(14).toInteger() - 1,
            m.group(13).toInteger(), hh, mmT, ss)
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())

    if (m.group(16) != null) pos.set(Position.KEY_HDOP, m.group(16).toDouble())
    if (m.group(17) != null) pos.setAltitude(m.group(17).toDouble())

    if (m.group(18) != null) {
        int status = Integer.parseInt(m.group(18), 16)
        for (int i = 1; i <= 5; i++) {
            pos.set(Position.PREFIX_OUT + i, BitUtil.check(status, i - 1))
            pos.set(Position.PREFIX_IN  + i, BitUtil.check(status, i - 1 + 8))
        }
    }

    for (int i = 0; i < 8; i++) {
        String g = m.group(19 + i)
        if (g != null) pos.set(Position.PREFIX_ADC + (i + 1), Integer.parseInt(g, 16))
    }

    if (m.group(27) != null) pos.set(Position.KEY_RSSI,       Integer.parseInt(m.group(27), 16))
    if (m.group(28) != null) pos.set(Position.KEY_ODOMETER,   Long.parseLong(m.group(28), 16))
    if (m.group(29) != null) pos.set(Position.KEY_SATELLITES,  Integer.parseInt(m.group(29), 16))
    if (m.group(30) != null) pos.set(Position.KEY_CARD,        m.group(30))
    if (m.group(31) != null) pos.set(Position.KEY_ODOMETER,    m.group(31).toLong())
    if (m.group(32) != null) pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(32))

    pos
}

def decodeRfid = { Position pos, String sentence ->
    def m = PATTERN_RFID.matcher(sentence)
    if (!m.matches()) return null

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(6).toInteger() + 2000, m.group(5).toInteger() - 1,
            m.group(4).toInteger(), m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())
    pos.setValid(true)

    double lat = m.group(7).toDouble() + m.group(8).toDouble() / 60.0
    if ("S" == m.group(9)) lat = -lat
    pos.setLatitude(lat)

    double lon = m.group(10).toDouble() + m.group(11).toDouble() / 60.0
    if ("W" == m.group(12)) lon = -lon
    pos.setLongitude(lon)

    pos
}

def decodeObd = { Position pos, String sentence, ctx ->
    def m = PATTERN_OBD.matcher(sentence)
    if (!m.matches()) return null
    ctx.lastLocation(pos)
    pos.set(Position.KEY_BATTERY,          m.group(1).toDouble())
    pos.set(Position.KEY_RPM,              m.group(2).toInteger())
    pos.set(Position.KEY_OBD_SPEED,        m.group(3).toInteger())
    pos.set(Position.KEY_THROTTLE,         m.group(4).toDouble())
    pos.set(Position.KEY_ENGINE_LOAD,      m.group(5).toDouble())
    pos.set(Position.KEY_COOLANT_TEMP,     m.group(6).toInteger())
    pos.set(Position.KEY_FUEL_CONSUMPTION, m.group(7).toDouble())
    pos.set("averageFuelConsumption",      m.group(8).toDouble())
    pos.set("drivingRange",                m.group(9).toDouble())
    pos.set(Position.KEY_ODOMETER,         m.group(10).toDouble())
    pos.set("singleFuelConsumption",       m.group(11).toDouble())
    pos.set(Position.KEY_FUEL_USED,        m.group(12).toDouble())
    pos.set(Position.KEY_DTCS,             m.group(13).toInteger())
    pos.set("hardAccelerationCount",       m.group(14).toInteger())
    pos.set("hardBrakingCount",            m.group(15).toInteger())
    pos
}

def decodeObdA = { Position pos, String sentence, ctx ->
    def m = PATTERN_OBDA.matcher(sentence)
    if (!m.matches()) return null
    ctx.lastLocation(pos)
    pos.set("totalIgnitionNo",         m.group(1).toInteger())
    pos.set("totalDrivingTime",        m.group(2).toDouble())
    pos.set("totalIdlingTime",         m.group(3).toDouble())
    pos.set("averageHotStartTime",     m.group(4).toInteger())
    pos.set("averageSpeed",            m.group(5).toInteger())
    pos.set("historyHighestSpeed",     m.group(6).toInteger())
    pos.set("historyHighestRpm",       m.group(7).toInteger())
    pos.set("totalHarshAccerleration", m.group(8).toInteger())
    pos.set("totalHarshBrake",         m.group(9).toInteger())
    pos
}

def decodeDtc = { Position pos, String sentence, ctx ->
    ctx.lastLocation(pos)
    pos.set(Position.KEY_DTCS, sentence.replace(',', ' '))
    pos
}

// Build encode frame for commands (server → device)
def encodeContent = { String uniqueId, int type, byte[] content ->
    String padded = (uniqueId + "FFFFFFFFFFFFFF").substring(0, 14)
    byte[] idBytes = new byte[7]
    for (int i = 0; i < 7; i++) {
        int hi = Character.digit(padded.charAt(i * 2), 16)
        int lo = Character.digit(padded.charAt(i * 2 + 1), 16)
        idBytes[i] = (byte) ((hi << 4) | lo)
    }
    buildResponse(idBytes, type, content)
}

protocol("meiligao") {

    port 5009

    commands(
        Command.TYPE_POSITION_SINGLE,
        Command.TYPE_POSITION_PERIODIC,
        Command.TYPE_OUTPUT_CONTROL,
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME,
        Command.TYPE_ALARM_GEOFENCE,
        Command.TYPE_SET_TIMEZONE,
        Command.TYPE_REQUEST_PHOTO,
        Command.TYPE_REBOOT_DEVICE
    )

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 4) return null
            int b0 = fb.getUByte(0), b1 = fb.getUByte(1)
            if ((b0 == 0x24 && b1 == 0x24) || (b0 == 0x40 && b1 == 0x40)) {
                int length = fb.getUShort(2)
                if (length < 4) return null
                return (fb.readableBytes() >= length) ? length : null
            }
            return null
        }

        decode { msg, ctx ->
            BufReader buf = msg as BufReader

            buf.skip(2) // header
            buf.skip(2) // length
            byte[] idBytes = buf.readBytes(7)
            int command = buf.readUShort()

            // Messages handled before device session
            if (command == MSG_LOGIN) {
                ctx.ack(buildResponse(idBytes, MSG_LOGIN_RESPONSE, [0x01] as byte[]))
                return null
            }
            if (command == MSG_HEARTBEAT) {
                ctx.ack(buildResponse(idBytes, MSG_HEARTBEAT, [0x01] as byte[]))
                return null
            }
            if (command == MSG_SERVER) {
                ctx.ack(buildResponse(idBytes, MSG_SERVER, new byte[0]))
                return null
            }
            if (command == MSG_UPLOAD_PHOTO) {
                byte idx = (byte) buf.readUByte()
                ctx.ack(buildResponse(idBytes, MSG_UPLOAD_PHOTO_RSP, [idx] as byte[]))
                return null
            }
            if (command == MSG_UPLOAD_COMPLETE) {
                byte idx = (byte) buf.readUByte()
                ctx.ack(buildResponse(idBytes, MSG_RETRANSMISSION, [idx, 0, 0] as byte[]))
                return null
            }

            // Identify device
            def session = identifyAndRegister(idBytes, ctx)
            if (!session) return null

            if (command == MSG_DATA_PHOTO) return null

            if (command == MSG_RETRANSMISSION) {
                int count = buf.readUByte()
                for (int i = 0; i < count; i++) {
                    if (buf.remaining() <= 4) break
                    buf.readUByte() // alarm byte
                    // Read sentence bytes until '\' (0x5C) or 4 bytes from end
                    def sb = new StringBuilder()
                    while (buf.remaining() > 4) {
                        int b = buf.readUByte()
                        if (b == 0x5C) break // '\' delimiter
                        sb.append((char) b)
                    }
                    Position pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    pos = decodeRegular(pos, sb.toString())
                    if (pos != null) ctx.emit(pos)
                }
                return null
            }

            Position position = ctx.newPosition()
            position.deviceId = session.deviceId

            if (command == MSG_ALARM) {
                int alarmCode = buf.readUByte()
                position.addAlarm(decodeAlarm(null, alarmCode))
                if (alarmCode >= 0x02 && alarmCode <= 0x05) {
                    position.set(Position.PREFIX_IN + alarmCode, 1)
                } else if (alarmCode >= 0x32 && alarmCode <= 0x35) {
                    position.set(Position.PREFIX_IN + (alarmCode - 0x30), 0)
                }
            } else if (command == MSG_POSITION_LOGGED) {
                buf.skip(6)
            } else if (command == MSG_RFID) {
                for (int i = 0; i < 15; i++) {
                    long rfid = buf.readUInt()
                    if (rfid != 0) {
                        String card = String.format("%010d", rfid)
                        position.set("card" + (i + 1), card)
                        position.set(Position.KEY_DRIVER_UNIQUE_ID, card)
                    }
                }
            } else if (command == MSG_POSITION_IMAGE) {
                buf.readByte()  // imageIndex
                buf.readUByte() // upload type
            }

            // Remaining payload as ASCII sentence, excluding 4 trailing bytes (crc + \r\n)
            int sentLen = buf.remaining() > 4 ? buf.remaining() - 4 : 0
            String sentence = sentLen > 0
                ? new String(buf.readBytes(sentLen), StandardCharsets.US_ASCII)
                : ""

            switch (command) {
                case MSG_POSITION:
                case MSG_POSITION_LOGGED:
                case MSG_ALARM:
                case MSG_POSITION_IMAGE:
                    return decodeRegular(position, sentence)
                case MSG_RFID:
                    return decodeRfid(position, sentence)
                case MSG_OBD_RT:
                    return decodeObd(position, sentence, ctx)
                case MSG_OBD_RTA:
                    return decodeObdA(position, sentence, ctx)
                case MSG_DTC:
                    return decodeDtc(position, sentence, ctx)
                default:
                    return null
            }
        }

        encode { cmd, ctx ->
            String uid = ctx.uniqueId()
            switch (cmd.type) {
                case Command.TYPE_POSITION_SINGLE:
                    return encodeContent(uid, MSG_TRACK_ON_DEMAND, new byte[0])
                case Command.TYPE_POSITION_PERIODIC:
                    int freq = cmd.getInteger(Command.KEY_FREQUENCY) / 10
                    return encodeContent(uid, MSG_TRACK_BY_INTERVAL,
                        [(byte)((freq >> 8) & 0xFF), (byte)(freq & 0xFF)] as byte[])
                case Command.TYPE_OUTPUT_CONTROL:
                    int v = cmd.getInteger(Command.KEY_DATA)
                    return encodeContent(uid, MSG_OUTPUT_CONTROL_2, [(byte) v] as byte[])
                case Command.TYPE_ENGINE_STOP:
                    return encodeContent(uid, MSG_OUTPUT_CONTROL_2, [(byte) 1] as byte[])
                case Command.TYPE_ENGINE_RESUME:
                    return encodeContent(uid, MSG_OUTPUT_CONTROL_2, [(byte) 0] as byte[])
                case Command.TYPE_ALARM_GEOFENCE:
                    int r = cmd.getInteger(Command.KEY_RADIUS)
                    return encodeContent(uid, MSG_MOVEMENT_ALARM,
                        [(byte)((r >> 8) & 0xFF), (byte)(r & 0xFF)] as byte[])
                case Command.TYPE_SET_TIMEZONE:
                    int off = TimeZone.getTimeZone(cmd.getString(Command.KEY_TIMEZONE)).getRawOffset() / 60000
                    return encodeContent(uid, MSG_TIME_ZONE,
                        String.valueOf(off).getBytes(StandardCharsets.US_ASCII))
                case Command.TYPE_REQUEST_PHOTO:
                    return encodeContent(uid, MSG_TAKE_PHOTO, new byte[0])
                case Command.TYPE_REBOOT_DEVICE:
                    return encodeContent(uid, MSG_REBOOT_GPS, new byte[0])
                default:
                    return null
            }
        }
    }
}
