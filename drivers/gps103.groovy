// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GPS-103 / TK-102 compatible tracker driver.
 *
 * Source documentation:
 *   archived-protocols/gps103/ (Java reference)
 *
 * TCP port 5001 (plus UDP server with no framing).
 * Frames delimited by \r\n, \n, ;, or * (stripDelimiter=false in Java).
 *
 * Handshake: message contains "imei:" AND total length ≤ 30 chars
 *   → reply "LOAD", register device session.
 * Digit-prefix: message starts with digit (e.g. IMEI prefix before "imei:")
 *   → reply "ON", strip to "imei:".
 *
 * Three decode paths selected by content at offset 21
 * (= "imei:" + 15-digit IMEI + ","):
 *   "vr"  → photo packet (return null; not supported in DSL)
 *   "OBD" → decodeObd (OBD2 data only, last-known location)
 *   sentence ends with "*" → decodeAlternative (fixed CSV format with signed coords)
 *   else  → decodeRegular (GPRMC-style, L/F branch)
 *
 * Commands (server → device): **,imei:<IMEI>,<payload>
 */

import org.traccar.driver.BufReader
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.regex.Pattern

// Regular decode:
// Groups: 1=imei, 2=alarm,
//   3=yy, 4=mo, 5=dd, 6=lh, 7=lm (local date/time, inside non-capturing alt),
//   8=rfid,
//   9=lac (L-branch), 10=cid (L-branch),
//   11=utcH, 12=utcM, 13=utcS (F-branch utc time, inside non-capturing alt),
//   14=valid(AV),
//   15=ns1(before lat), 16=latDeg, 17=latMin, 18=ns2(after lat),
//   19=ew1(before lon), 20=lonDeg, 21=lonMin, 22=ew2(after lon),
//   23=speed, 24=course, 25=alt, 26=ignition, 27=door,
//   28=fuel1, 29=fuel2, 30=temp
def PAT_REG = Pattern.compile(
        'imei:(\\d+),' +
        '([^,]*),' +
        '(?:(\\d{2})/?(\\d{2})/?(\\d{2}) ?(\\d{2}):?(\\d{2})(?:\\d{2})?,|\\d*,)' +
        '([^,]+)?,' +
        '(?:L,(?:,,(\\p{XDigit}+),,(\\p{XDigit}+),,,)?' +
        '|F,(?:(\\d{2})(\\d{2})(\\d{2})(?:\\.\\d+)?|(?:\\d{1,5}\\.\\d+)?),' +
        '([AV]),(?:([NS]),)?(\\d+)(\\d{2}\\.\\d+),(?:([NS]),)?(?:([EW]),)?(\\d+)(\\d{2}\\.\\d+),(?:([EW])?,)?' +
        '(\\d+\\.?\\d*)?(?:,(\\d+\\.?\\d*)?)?(?:,(-?\\d+\\.?\\d*)?)?(?:,([01])?)?(?:,([01])?)?' +
        '(?:,(?:(\\d+\\.\\d+)%)?)?(?:,(?:(\\d+\\.\\d+)%|\\d+)?)?(?:,([-+]?\\d+)?)?)' +
        '.*'
)

// OBD decode:
// Groups: 1=imei, 2-4=date(yymmdd), 5-7=time(hhmmss),
//   8=odo, 9=fuelInst, 10=fuelAvg, 11=hours, 12=speed,
//   13=engineLoad%, 14=coolantTemp, 15=throttle%, 16=rpm, 17=battery, 18=dtcs
def PAT_OBD = Pattern.compile(
        'imei:(\\d+),OBD,' +
        '(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2}),' +
        '(\\d+)?,(\\d+\\.\\d+)?,(\\d+\\.\\d+)?,(\\d+)?,' +
        '(\\d+),(\\d+\\.?\\d*%),' +
        '(?:([-+]?\\d+)|[-+]?),' +
        '(\\d+\\.?\\d*%),(\\d+),(\\d+\\.\\d+),' +
        '([^;]*).*'
)

// Alternative decode (ends with *):
// Groups: 1=imei, 2=event, 3=sensorId, 4=sensorVolt,
//   5-7=time(hhmmss), 8-10=date(ddmmyy),
//   11=rssi, 12=gpsStatus(1=valid), 13=lat, 14=lon,
//   15=speed(kph), 16=course, 17=alt, 18=hdop, 19=sats,
//   20=ignition, 21=charge, 22=error
def PAT_ALT = Pattern.compile(
        'imei:(\\d+),[^,]+,' +
        '(?:-+|(.+)),(?:-+|(.+)),(?:-+|(.+)),' +
        '(\\d{2})(\\d{2})(\\d{2}),(\\d{2})(\\d{2})(\\d{2}),' +
        '(\\d+),(\\d),' +
        '(-?\\d+\\.\\d+),(-?\\d+\\.\\d+),' +
        '(\\d+),(\\d+),(-?\\d+),' +
        '(\\d+\\.\\d+),(\\d+),' +
        '([01]),([01]),' +
        '(?:-+|(.+)).*'
)

def parseCoord = { String h1, String degStr, String minStr, String h2 ->
    double coord = degStr.toInteger() + minStr.toDouble() / 60.0
    String h = h1 ?: h2
    return (h == 'S' || h == 'W') ? -coord : coord
}

def decodeAlarm = { String alarm ->
    if (alarm.startsWith('T:')) return Position.ALARM_TEMPERATURE
    if (alarm.startsWith('oil')) return Position.ALARM_FUEL_LEAK
    switch (alarm) {
        case 'help me': return Position.ALARM_SOS
        case 'low battery': return Position.ALARM_LOW_BATTERY
        case 'stockade': return Position.ALARM_GEOFENCE
        case 'move': return Position.ALARM_MOVEMENT
        case 'speed': return Position.ALARM_OVERSPEED
        case 'door alarm': return Position.ALARM_DOOR
        case 'ac alarm': return Position.ALARM_POWER_CUT
        case 'accident alarm': return Position.ALARM_ACCIDENT
        case 'sensor alarm': return Position.ALARM_VIBRATION
        case 'bonnet alarm': return Position.ALARM_BONNET
        case 'footbrake alarm': return Position.ALARM_FOOT_BRAKE
        case 'DTC': return Position.ALARM_FAULT
        default: return null
    }
}

def decodeRegular = { String sentence, ctx ->
    def m = PAT_REG.matcher(sentence)
    if (!m.matches()) return null

    String imei = m.group(1)
    def session = ctx.session(imei)
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    String alarm = m.group(2)
    String alarmKey = decodeAlarm(alarm)
    if (alarmKey) pos.set(Position.KEY_ALARM, alarmKey)

    if (alarm == 'help me') {
        ctx.ack("**,imei:${imei},E;")
    } else if (alarm == 'acc on') {
        pos.set(Position.KEY_IGNITION, true)
    } else if (alarm == 'acc off') {
        pos.set(Position.KEY_IGNITION, false)
    } else if (alarm.startsWith('T:')) {
        pos.set(Position.PREFIX_TEMP + 1, alarm.substring(2).toDouble())
    } else if (alarm.startsWith('oil ')) {
        pos.set(Position.KEY_FUEL, alarm.substring(4).toDouble())
    } else if (!alarmKey && alarm != 'tracker') {
        pos.set(Position.KEY_EVENT, alarm)
    }

    // RFID driver ID
    String rfid = m.group(8)
    if (alarm == 'rfid' && rfid) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, rfid)
    }

    // L branch — cell tower only, no GPS fix
    if (m.group(9) != null) {
        int lac = Integer.parseInt(m.group(9), 16)
        long cid = Long.parseLong(m.group(10), 16)
        pos.network = new Network(CellTower.from(0, 0, lac, cid))
        ctx.lastLocation(pos)
        return pos
    }

    // F branch — GPS fix
    if (m.group(14) != null) {
        int yy   = m.group(3) ? m.group(3).toInteger() : 0
        int mo   = m.group(4) ? m.group(4).toInteger() : 1
        int dd5  = m.group(5) ? m.group(5).toInteger() : 1
        int lh   = m.group(6) ? m.group(6).toInteger() : 0
        int lm   = m.group(7) ? m.group(7).toInteger() : 0
        int ss   = m.group(13) ? m.group(13).toInteger() : 0

        def db = new DateBuilder().setDate(yy, mo, dd5).setTime(lh, lm, ss)

        if (m.group(11) != null && m.group(12) != null) {
            int utcH = m.group(11).toInteger()
            int utcM = m.group(12).toInteger()
            int delta = (lh - utcH) * 60 + (lm - utcM)
            if (delta <= -720) delta += 1440
            else if (delta > 720) delta -= 1440
            db.addMinute(-delta)
        }
        pos.time = db.getDate()

        pos.valid     = m.group(14) == 'A'
        pos.latitude  = parseCoord(m.group(15), m.group(16), m.group(17), m.group(18))
        pos.longitude = parseCoord(m.group(19), m.group(20), m.group(21), m.group(22))
        if (m.group(23)) pos.speed    = m.group(23).toDouble()
        if (m.group(24)) pos.course   = m.group(24).toDouble()
        if (m.group(25)) pos.altitude = m.group(25).toDouble()
        if (m.group(26) != null) pos.set(Position.KEY_IGNITION, m.group(26) == '1')
        if (m.group(27) != null) pos.set(Position.KEY_DOOR,     m.group(27) == '1')
        if (m.group(28)) pos.set('fuel1', m.group(28).toDouble())
        if (m.group(29)) pos.set('fuel2', m.group(29).toDouble())
        if (m.group(30)) pos.set(Position.PREFIX_TEMP + 1, m.group(30).toInteger())
        return pos
    }

    // L branch without cell tower data, or unrecognised tail
    ctx.lastLocation(pos)
    return pos
}

def decodeObd = { String sentence, ctx ->
    def m = PAT_OBD.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    def db = new DateBuilder()
            .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
            .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
    ctx.lastLocation(pos, db.getDate())

    if (m.group(8))  pos.set(Position.KEY_ODOMETER,         m.group(8).toLong())
    // group 9 = fuel instant (skipped)
    if (m.group(10)) pos.set(Position.KEY_FUEL_CONSUMPTION,  m.group(10).toDouble())
    if (m.group(11)) pos.set(Position.KEY_HOURS,             UnitsConverter.msFromHours(m.group(11).toInteger()))
    if (m.group(12)) pos.set(Position.KEY_OBD_SPEED,         m.group(12).toInteger())
    if (m.group(13)) pos.set(Position.KEY_ENGINE_LOAD,       m.group(13))
    if (m.group(14)) pos.set(Position.KEY_COOLANT_TEMP,      m.group(14).toInteger())
    if (m.group(15)) pos.set(Position.KEY_THROTTLE,          m.group(15))
    if (m.group(16)) pos.set(Position.KEY_RPM,               m.group(16).toInteger())
    if (m.group(17)) pos.set(Position.KEY_BATTERY,           m.group(17).toDouble())
    String dtcs = m.group(18)?.replace(',', ' ')?.trim()
    if (dtcs) pos.set(Position.KEY_DTCS, dtcs)

    return pos
}

def decodeAlternative = { String sentence, ctx ->
    def m = PAT_ALT.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    if (m.group(2)) pos.set(Position.KEY_EVENT,    m.group(2))
    if (m.group(3)) pos.set('sensorId',             m.group(3))
    if (m.group(4)) pos.set('sensorVoltage',        m.group(4).toDouble())

    pos.time = new DateBuilder()
            .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
            .setDate(m.group(10).toInteger(), m.group(9).toInteger(), m.group(8).toInteger())
            .getDate()

    if (m.group(11)) pos.set(Position.KEY_RSSI, m.group(11).toInteger())
    pos.valid     = m.group(12).toInteger() > 0
    pos.latitude  = m.group(13).toDouble()
    pos.longitude = m.group(14).toDouble()
    pos.speed     = UnitsConverter.knotsFromKph(m.group(15).toInteger())
    pos.course    = m.group(16).toInteger()
    pos.altitude  = m.group(17).toInteger()
    pos.set(Position.KEY_HDOP,       m.group(18).toDouble())
    pos.set(Position.KEY_SATELLITES, m.group(19).toInteger())
    pos.set(Position.KEY_IGNITION,   m.group(20) == '1')
    pos.set(Position.KEY_CHARGE,     m.group(21) == '1')
    if (m.group(22)) pos.set('error', m.group(22))

    return pos
}

protocol("gps103") {

    port 5001

    commands TYPE_CUSTOM, TYPE_POSITION_SINGLE, TYPE_POSITION_PERIODIC,
             TYPE_POSITION_STOP, TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME,
             TYPE_ALARM_ARM, TYPE_ALARM_DISARM, TYPE_REQUEST_PHOTO

    variant("main") {

        frame scriptedFrame { fb ->
            for (int i = 0; i < fb.readableBytes(); i++) {
                byte b = fb.getByte(fb.readerIndex() + i)
                if (b == (byte) ';' || b == (byte) '*' || b == (byte) '\n') return i + 1
                if (b == (byte) '\r' && i + 1 < fb.readableBytes()
                        && fb.getByte(fb.readerIndex() + i + 1) == (byte) '\n') return i + 2
            }
            return null
        }

        matches { msg -> true }

        encode { cmd, ctx ->
            String imei = ctx.deviceId()
            String base = "**,imei:${imei},"
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return base + cmd.getString('data')
                case TYPE_POSITION_STOP:
                    return base + 'D'
                case TYPE_POSITION_SINGLE:
                    return base + 'B'
                case TYPE_POSITION_PERIODIC:
                    long freq = cmd.getLong('frequency')
                    String f
                    if (freq / 3600 > 0) f = String.format('%02dh', freq / 3600)
                    else if (freq / 60 > 0) f = String.format('%02dm', freq / 60)
                    else f = String.format('%02ds', freq)
                    return base + 'C,' + f
                case TYPE_ENGINE_STOP:
                    return base + 'J'
                case TYPE_ENGINE_RESUME:
                    return base + 'K'
                case TYPE_ALARM_ARM:
                    return base + 'L'
                case TYPE_ALARM_DISARM:
                    return base + 'M'
                case TYPE_REQUEST_PHOTO:
                    return base + '160'
                default:
                    return null
            }
        }

        decode { msg, ctx ->
            String raw = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()

            // Check for * delimiter before stripping
            boolean endsWithStar = raw.length() > 0 && (raw.charAt(raw.length() - 1) == (char) '*'
                    || (raw.length() > 1 && raw.charAt(raw.length() - 2) == (char) '*'))

            // Strip trailing frame delimiters
            String sentence = raw.replaceAll('[;*\\r\\n]+$', '').trim()
            if (sentence.isEmpty()) return null

            // Handshake: short message containing "imei:" (e.g. ##,imei:...,A or imei:...,A)
            if (sentence.contains("imei:") && sentence.length() <= 30) {
                ctx.ack("LOAD")
                def hm = sentence =~ /imei:(\d+)/
                if (hm) ctx.session(hm[0][1] as String)
                return null
            }

            // Digit-prefix: device ID prepended before "imei:"
            if (sentence.length() > 0 && Character.isDigit(sentence.charAt(0))) {
                ctx.ack("ON")
                int start = sentence.indexOf("imei:")
                if (start < 0) return null
                sentence = sentence.substring(start)
            }

            // Photo packet at offset 21 (not supported in DSL)
            if (sentence.length() > 21 && sentence.startsWith("vr", 21)) {
                return null
            }

            // OBD message
            if (sentence.length() >= 24 && sentence.substring(21, 24).contains("OBD")) {
                return decodeObd(sentence, ctx)
            }

            // Alternative format (ends with *)
            if (endsWithStar) {
                return decodeAlternative(sentence, ctx)
            }

            return decodeRegular(sentence, ctx)
        }
    }
}
