// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Megastek GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/megastek/ (Java reference)
 *
 * Two frame formats on the same port (5024):
 *   Length-prefix: 4-digit decimal length followed by content (e.g. "0132$MGV...")
 *   Delimiter-based: content terminated by \r\n, !, or \n
 *
 * Two message families:
 *   New ($MGV): $MGV002,imei,name,R/S,ddmmyy,hhmmss,A/V,lat,N/S,lon,E/W,...
 *   Old (STX/LOGSTX): STX,id,$GPRMC,... or STX<16-char-id><2-bytes>$GPRMC,...
 *
 * No encoder (no server-initiated commands).
 */

import org.traccar.driver.BufReader
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

def decodeAlarm = { String value ->
    if (value == null) return null
    String v = value.toLowerCase(Locale.ROOT)
    if (v.startsWith('geo')) {
        if (v.endsWith('in'))  return Position.ALARM_GEOFENCE_ENTER
        if (v.endsWith('out')) return Position.ALARM_GEOFENCE_EXIT
    }
    switch (v) {
        case 'pw on':
        case 'poweron':     return Position.ALARM_POWER_ON
        case 'poweroff':    return Position.ALARM_POWER_OFF
        case 'sos':
        case 'help':        return Position.ALARM_SOS
        case 'over speed':
        case 'overspeed':   return Position.ALARM_OVERSPEED
        case 'lowspeed':    return Position.ALARM_LOW_SPEED
        case 'low battery':
        case 'lowbattery':  return Position.ALARM_LOW_BATTERY
        case 'low extern voltage': return Position.ALARM_LOW_POWER
        case 'gps cut':     return Position.ALARM_GPS_ANTENNA_CUT
        case 'vib':
        case 'hit':         return Position.ALARM_VIBRATION
        case 'move in':     return Position.ALARM_GEOFENCE_ENTER
        case 'move out':    return Position.ALARM_GEOFENCE_EXIT
        case 'corner':      return Position.ALARM_CORNERING
        case 'fatigue':     return Position.ALARM_FATIGUE_DRIVING
        case 'psd':         return Position.ALARM_POWER_CUT
        case 'psr':         return Position.ALARM_POWER_RESTORED
        case 'belt on':
        case 'belton':      return Position.ALARM_LOCK
        case 'belt off':
        case 'beltoff':     return Position.ALARM_UNLOCK
        case 'error':       return Position.ALARM_FAULT
        default:            return null
    }
}

// Converts ddmm.mmmm → decimal degrees
def ddmm = { String s ->
    if (!s || s.isEmpty()) return null
    double val = s.toDouble()
    int deg = (int)(val / 100)
    return deg + (val - deg * 100.0) / 60.0
}

def setDateTime = { pos, String dateStr, String timeStr ->
    if (dateStr.length() < 6 || timeStr.length() < 6) return
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    int yr = dateStr[4..5].toInteger()
    cal.set(Calendar.YEAR,         yr + (yr >= 70 ? 1900 : 2000))
    cal.set(Calendar.MONTH,        dateStr[2..3].toInteger() - 1)
    cal.set(Calendar.DAY_OF_MONTH, dateStr[0..1].toInteger())
    cal.set(Calendar.HOUR_OF_DAY,  timeStr[0..1].toInteger())
    cal.set(Calendar.MINUTE,       timeStr[2..3].toInteger())
    cal.set(Calendar.SECOND,       timeStr[4..5].toInteger())
    cal.set(Calendar.MILLISECOND,  0)
    pos.time = cal.getTime()
}

// $MGV new format decoder
def decodeNew = { String raw, ctx ->
    // Strip optional 4-digit length prefix
    String s = (raw.length() >= 4 && raw[0] =~ /\d/) ? raw.substring(4) : raw
    // Strip trailing delimiter/checksum
    int semi = s.indexOf(';')
    if (semi >= 0) s = s.substring(0, semi)

    String[] f = s.split(',', -1)
    if (f.length < 7) return null

    String imei = f.length > 1 ? f[1] : ''
    if (imei.isEmpty()) return null

    def session = ctx.session(imei)
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    if (f.length > 3 && f[3] == 'S') pos.set(Position.KEY_ARCHIVE, true)

    if (f.length > 5) setDateTime(pos, f[4], f[5])

    pos.valid = f.length > 6 && f[6] == 'A'

    // Latitude
    if (f.length > 8 && !f[7].isEmpty()) {
        Double lat = ddmm(f[7])
        if (lat == null) return null
        pos.latitude = (f[8] == 'S') ? -lat : lat
    } else {
        return null
    }

    // Longitude
    if (f.length > 11 && !f[9].isEmpty()) {
        Double lon = ddmm(f[9])
        if (lon == null) return null
        pos.longitude = (f[10] == 'W') ? -lon : lon
    } else {
        return null
    }

    // f[11]=skip, f[12]=sats, f[13]=skip, f[14]=hdop, f[15]=speed(kph), f[16]=course, f[17]=alt, f[18]=odo
    if (f.length > 12 && !f[12].isEmpty()) pos.set(Position.KEY_SATELLITES, f[12].toInteger())
    if (f.length > 14 && !f[14].isEmpty()) pos.set(Position.KEY_HDOP, f[14].toDouble())
    if (f.length > 15 && !f[15].isEmpty()) pos.speed  = UnitsConverter.knotsFromKph(f[15].toDouble())
    if (f.length > 16 && !f[16].isEmpty()) pos.course = f[16].toDouble()
    if (f.length > 17 && !f[17].isEmpty()) pos.altitude = f[17].toDouble()
    if (f.length > 18 && !f[18].isEmpty()) pos.set(Position.KEY_ODOMETER, (long)(f[18].toDouble() * 1000))

    // Cell tower: mcc=f[19], mnc=f[20], lac=f[21], cid=f[22], gsm=f[23]
    Network network = new Network()
    if (f.length > 22 && !f[19].isEmpty() && !f[21].isEmpty() && !f[22].isEmpty()) {
        try {
            CellTower tower = CellTower.from(
                f[19].toInteger(), f[20].toInteger(),
                Integer.parseInt(f[21], 16), Integer.parseInt(f[22], 16))
            if (f.length > 23 && !f[23].isEmpty()) tower.setSignalStrength(f[23].toInteger())
            network.addCellTower(tower)
        } catch (ignored) {}
    }

    // IO section at f[24]
    int fi = 24
    if (fi < f.length && !f[fi].isEmpty()) {
        String f24 = f[fi]
        String f25 = (fi + 1 < f.length) ? f[fi + 1] : ''

        if (f24 ==~ /[01]{4}/) {
            // Branch 1 no heart rate: input(binary), output(binary), adc1, adc2, adc3
            pos.set(Position.KEY_INPUT, Integer.parseInt(f24, 2))
            fi++
            if (fi < f.length && !f[fi].isEmpty()) pos.set(Position.KEY_OUTPUT, Integer.parseInt(f[fi], 2)); fi++
            for (int i = 1; i <= 3; i++) {
                if (fi < f.length && !f[fi].isEmpty()) pos.set(Position.PREFIX_ADC + i, f[fi].toInteger())
                fi++
            }
        } else if (f24 ==~ /\d{1,3}/ && f25 ==~ /[01]{4}/) {
            // Branch 1 with heart rate
            pos.set(Position.KEY_HEART_RATE, f24.toInteger()); fi++
            if (fi < f.length && f[fi] ==~ /[01]{4}/) {
                pos.set(Position.KEY_INPUT, Integer.parseInt(f[fi], 2)); fi++
            }
            if (fi < f.length && f[fi] ==~ /[01]{4}/) {
                pos.set(Position.KEY_OUTPUT, Integer.parseInt(f[fi], 2)); fi++
            }
            for (int i = 1; i <= 3; i++) {
                if (fi < f.length && !f[fi].isEmpty()) pos.set(Position.PREFIX_ADC + i, f[fi].toInteger())
                fi++
            }
        } else if (f24 ==~ /\d+/) {
            // Branch 2: heart rate, steps, activityTime, lightSleepTime, deepSleepTime
            pos.set(Position.KEY_HEART_RATE, f[fi++].toInteger())
            if (fi < f.length && !f[fi].isEmpty()) pos.set(Position.KEY_STEPS, f[fi].toInteger()); fi++
            if (fi < f.length && !f[fi].isEmpty()) pos.set('activityTime', f[fi].toInteger()); fi++
            if (fi < f.length && !f[fi].isEmpty()) pos.set('lightSleepTime', f[fi].toInteger()); fi++
            if (fi < f.length && !f[fi].isEmpty()) pos.set('deepSleepTime', f[fi].toInteger()); fi++
        } else {
            fi += 5
        }
    } else {
        fi += 5
    }

    // Temperatures
    if (fi < f.length && !f[fi].isEmpty() && f[fi] != ' ') {
        pos.set(Position.PREFIX_TEMP + 1, f[fi].toDouble())
    }
    fi++
    if (fi < f.length && !f[fi].isEmpty() && f[fi] != ' ') {
        pos.set(Position.PREFIX_TEMP + 2, f[fi].toDouble())
    }
    fi++

    // RFID
    if (fi < f.length && !f[fi].isEmpty()) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, f[fi])
    }
    fi++

    // Charge + belt: Java regex backtracks if battery (next field) is not an integer,
    // so only consume charge/belt when the following battery field is valid digits.
    String chargeField  = fi < f.length ? f[fi] : ''
    String batteryField = (fi + 1) < f.length ? f[fi + 1] : ''
    if (!chargeField.isEmpty() && (chargeField[0] == '0' || chargeField[0] == '1')
            && !batteryField.isEmpty() && batteryField ==~ /\d+/) {
        pos.set(Position.KEY_CHARGE, chargeField[0] == '1')
        if (chargeField.length() >= 2 && chargeField[1] =~ /\d/) {
            pos.set('belt', chargeField[1..1].toInteger())
        }
        fi++
    }

    // Battery level
    if (fi < f.length && !f[fi].isEmpty() && f[fi] ==~ /\d+/) {
        pos.set(Position.KEY_BATTERY_LEVEL, f[fi].toInteger())
    }
    fi++

    // Alert / alarm
    if (fi < f.length && !f[fi].isEmpty()) {
        String alert = f[fi].replaceAll('[;!].*', '')
        pos.addAlarm(decodeAlarm(alert))
    }
    fi++

    // WiFi access points
    if (fi < f.length && !f[fi].isEmpty()) {
        String wifiField = f[fi].replaceAll('[;!].*', '')
        for (String point : wifiField.split('\\|')) {
            String[] parts = point.split(':')
            if (parts.length == 2) {
                try {
                    String mac = parts[0].replaceAll('(..)', '$1:')
                    mac = mac.substring(0, mac.length() - 1)
                    network.addWifiAccessPoint(WifiAccessPoint.from(mac, parts[1].toInteger()))
                } catch (ignored) {}
            }
        }
    }

    if (network.cellTowers != null || network.wifiAccessPoints != null) {
        pos.network = network
    }

    return pos
}

// GPRMC pattern
def PAT_GPRMC = Pattern.compile(
    '\\$GPRMC,(\\d{2})(\\d{2})(\\d{2})\\.(\\d+),([AV]),(\\d+)(\\d{2}\\.\\d+),([NS]),(\\d+)(\\d{2}\\.\\d+),([EW]),([\\d.]+)?,([\\d.]+)?,(\\d{2})(\\d{2})(\\d{2}).*')

def parseLocation = { pos, String location ->
    def m = PAT_GPRMC.matcher(location)
    if (!m.matches()) return false
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    int yr = m.group(16).toInteger()
    cal.set(Calendar.YEAR,         yr + (yr >= 70 ? 1900 : 2000))
    cal.set(Calendar.MONTH,        m.group(15).toInteger() - 1)
    cal.set(Calendar.DAY_OF_MONTH, m.group(14).toInteger())
    cal.set(Calendar.HOUR_OF_DAY,  m.group(1).toInteger())
    cal.set(Calendar.MINUTE,       m.group(2).toInteger())
    cal.set(Calendar.SECOND,       m.group(3).toInteger())
    cal.set(Calendar.MILLISECOND,  0)
    pos.time = cal.getTime()
    pos.valid = m.group(5) == 'A'
    double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60.0
    if (m.group(8) == 'S') lat = -lat
    pos.latitude = lat
    double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
    if (m.group(11) == 'W') lon = -lon
    pos.longitude = lon
    if (m.group(12)) pos.speed  = m.group(12).toDouble()
    if (m.group(13)) pos.course = m.group(13).toDouble()
    return true
}

// PATTERN_SIMPLE status regex:
// [FL],alarm,imei:imei,sats?,alt?,Battery=bat%,,?charger?,mcc?,mnc?,lac,cid;
def PAT_SIMPLE = Pattern.compile(
    /[FL],([^,]*),imei:(\d+),([\d\/]+)?,(\d+\.\d+)?,Battery=(\d+)%,,?(\d)?,(\d+)?,(\d+)?,([\da-fA-F]{4}),([\da-fA-F]{4});.*/)

// PATTERN_ALTERNATIVE status regex:
// mcc,mnc,lac,cid,gsm,battery,flags,inputs,outputs?,adc1,adc2?,adc3?,alarm;
def PAT_ALT = Pattern.compile(
    /(\d+),(\d+),([\da-fA-F]+),([\da-fA-F]+),(\d+),(\d+),(\d+),(\d+),(?:(\d+),)?(\d\.?\d*),(?:(\d\.\d\d),(\d\.\d\d),)?([^;]+);.*/)

def decodeOld = { String text, ctx ->
    boolean simple = text.charAt(3) == ',' || (text.length() > 6 && text.charAt(6) == ',')

    String id, location, status
    if (simple) {
        int p1 = text.indexOf(',') + 1
        int p2 = text.indexOf(',', p1)
        if (p2 < 0) return null
        id = text.substring(p1, p2)

        int locStart = p2 + 1
        int starPos = text.indexOf('*', locStart)
        int locEnd
        if (starPos >= 0) {
            locEnd = starPos + 3
        } else {
            locEnd = text.length()
        }
        location = text.substring(locStart, locEnd)

        int statusStart = locEnd + 1
        if (statusStart > text.length()) statusStart = locEnd
        status = text.substring(statusStart)
    } else {
        if (text.length() < 21) return null
        id = text.substring(3, 19).trim()

        int locStart = 21
        int starPos = text.indexOf('*', locStart)
        if (starPos < 0) return null
        int locEnd = starPos + 3
        if (locEnd > text.length()) locEnd = text.length()
        location = text.substring(locStart, locEnd)

        int statusStart = locEnd + 1
        if (statusStart > text.length()) statusStart = locEnd
        status = text.substring(statusStart)
    }

    def pos = ctx.newPosition()
    if (!parseLocation(pos, location)) return null

    if (simple) {
        def m = PAT_SIMPLE.matcher(status)
        if (m.matches()) {
            String alarm = m.group(1)
            pos.addAlarm(decodeAlarm(alarm))

            String imei = m.group(2)
            def session = ctx.session(imei.isEmpty() ? id : imei)
            if (!session) {
                session = ctx.session(id)
                if (!session) return null
            }
            pos.deviceId = session.deviceId

            String sats = m.group(3)
            if (sats) {
                if (sats.contains('/')) {
                    pos.set(Position.KEY_SATELLITES, sats.split('/')[0].toInteger())
                    pos.set(Position.KEY_SATELLITES_VISIBLE, sats.split('/')[1].toInteger())
                } else {
                    pos.set(Position.KEY_SATELLITES, sats.toInteger())
                }
            }
            if (m.group(4)) pos.altitude = m.group(4).toDouble()
            if (m.group(5)) pos.set(Position.KEY_BATTERY_LEVEL, m.group(5).toDouble())
            if (m.group(6)) pos.set(Position.KEY_CHARGE, m.group(6).toInteger() == 1)
            if (m.group(7) && m.group(9) && m.group(10)) {
                pos.network = new Network(CellTower.from(
                    m.group(7).toInteger(), m.group(8).toInteger(),
                    Integer.parseInt(m.group(9), 16), Integer.parseInt(m.group(10), 16)))
            }
        } else {
            def session = id.isEmpty() ? null : ctx.session(id)
            if (!session) return null
            pos.deviceId = session.deviceId
        }
    } else {
        def m = PAT_ALT.matcher(status)
        if (m.matches()) {
            def session = ctx.session(id)
            if (!session) return null
            pos.deviceId = session.deviceId

            pos.network = new Network(CellTower.from(
                m.group(1).toInteger(), m.group(2).toInteger(),
                Integer.parseInt(m.group(3), 16), Integer.parseInt(m.group(4), 16),
                m.group(5).toInteger()))
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(6).toDouble())
            pos.set(Position.KEY_FLAGS,  m.group(7))
            pos.set(Position.KEY_INPUT,  m.group(8))
            if (m.group(9)) pos.set(Position.KEY_OUTPUT, m.group(9))
            if (m.group(10)) pos.set(Position.PREFIX_ADC + 1, m.group(10))
            if (m.group(11)) pos.set(Position.PREFIX_ADC + 2, m.group(11))
            if (m.group(12)) pos.set(Position.PREFIX_ADC + 3, m.group(12))
            pos.addAlarm(decodeAlarm(m.group(13)))
        } else {
            def session = ctx.session(id)
            if (!session) return null
            pos.deviceId = session.deviceId
        }
    }

    return pos
}

protocol("megastek") {

    port 5024

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 4) return null
            byte first = fb.getByte(fb.readerIndex())
            if (first >= (byte)'0' && first <= (byte)'9') {
                if (fb.readableBytes() < 4) return null
                byte[] b4 = new byte[4]
                for (int k = 0; k < 4; k++) b4[k] = fb.getByte(fb.readerIndex() + k)
                int length
                try { length = Integer.parseInt(new String(b4)) } catch (e) { return null }
                int total = 4 + length
                if (fb.readableBytes() < total) return null
                return total
            } else {
                // Skip leading \r\n
                int start = fb.readerIndex()
                while (start < fb.writerIndex()) {
                    byte b = fb.getByte(start)
                    if (b == (byte)'\r' || b == (byte)'\n') start++
                    else break
                }
                // \r\n delimiter
                for (int i = start; i < fb.writerIndex() - 1; i++) {
                    if (fb.getByte(i) == (byte)'\r' && fb.getByte(i + 1) == (byte)'\n') {
                        return i + 1 - fb.readerIndex()
                    }
                }
                // ! delimiter
                for (int i = start; i < fb.writerIndex(); i++) {
                    if (fb.getByte(i) == (byte)'!') return i + 1 - fb.readerIndex()
                }
                // \n delimiter
                for (int i = start; i < fb.writerIndex(); i++) {
                    if (fb.getByte(i) == (byte)'\n') return i + 1 - fb.readerIndex()
                }
                return null
            }
        }

        matches { msg -> true }

        decode { msg, ctx ->
            String text = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()
            text = text.trim()

            if (text.contains('$MG')) {
                return decodeNew(text, ctx)
            } else {
                return decodeOld(text, ctx)
            }
        }
    }
}
