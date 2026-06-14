// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Xexun GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/xexun/ (Java reference)
 *
 * Framing: scan the TCP stream for G[PN]RMC, locate "imei:", then
 * cut the frame at the comma after the IMEI number.  Full-mode messages
 * that carry extra fields (sats / altitude / power) after the IMEI have a
 * trailing newline, which extends the frame.
 *
 * Two deployment modes (Java `full` config flag):
 *   basic — G[PN]RMC,<nmea>,...,<signal>,<alarm>,<...>imei:<id>,
 *   full  — <serial>,<phone>,G[PN]RMC,...,<signal>,<alarm>,imei:<id>,<sats>,<alt>,<power>
 *
 * Commands: ENGINE_STOP ("powercar<pwd> 11"), ENGINE_RESUME ("powercar<pwd> 00").
 */

import org.traccar.driver.BufReader
import org.traccar.model.Position

import java.util.Locale
import java.util.regex.Pattern

def PAT = Pattern.compile(
    '(?:(\\d+),([^,]*)?,)?' +               // [1,2] serial, phone (full mode prefix)
    'G[PN]RMC,' +
    '(?:(\\d{2})(\\d{2})(\\d{2}))?\\.?\\d*,' + // [3,4,5] time hhmmss
    '([AV]),' +                             // [6] validity
    '(\\d*?)(\\d?\\d\\.\\d+),([NS]),' +    // [7,8,9] lat
    '(\\d*?)(\\d?\\d\\.\\d+),([EW])?,' +   // [10,11,12] lon
    '(\\d+\\.?\\d*),' +                     // [13] speed (knots)
    '(\\d+\\.?\\d*)?,' +                    // [14] course
    '(?:(\\d{2})(\\d{2})(\\d{2}))?,' +     // [15,16,17] date ddmmyy
    '[^*]*\\*[0-9A-Fa-f]{2}' +             // checksum
    '[\\r\\n]*,' +                          // optional crlf + field separator
    '([FL]),' +                             // [18] signal (F=full, L=low)
    '(?:([^,]*),' +                         // [19] alarm text (optional)
    ')?.*?imei:(\\d+),' +                   // [20] imei
    '(?:(\\d+),' +                          // [21] satellites (full mode)
    '(-?\\d+\\.\\d+)?,' +                   // [22] altitude
    '([FL]):(\\d+\\.\\d+)V)?'              // [23] power voltage
)

def decodeAlarm = { pos, String alarm ->
    if (!alarm) return
    switch (alarm.toLowerCase(Locale.ROOT).trim()) {
        case 'acc on':
        case 'accstart':
            pos.set(Position.KEY_IGNITION, true)
            break
        case 'acc off':
        case 'accstop':
            pos.set(Position.KEY_IGNITION, false)
            break
        case 'help me!':
        case 'help me':
            pos.addAlarm(ALARM_SOS)
            break
        case 'low battery':
            pos.addAlarm(ALARM_LOW_BATTERY)
            break
        case 'move!':
        case 'moved!':
            pos.addAlarm(ALARM_MOVEMENT)
            break
    }
}

protocol("xexun") {

    port 5006
    commands TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 80) return null
            int gIdx = fb.indexOf('GPRMC')
            if (gIdx < 0) gIdx = fb.indexOf('GNRMC')
            if (gIdx < 0) return null
            int imeiIdx = fb.indexOf('imei:', gIdx)
            if (imeiIdx < 0) return null
            int endIdx = fb.indexOf((int) ',', imeiIdx + 5)
            if (endIdx < 0) return null
            // In full mode, the line continues past the IMEI with sats/alt/power.
            // Extend the frame to the trailing newline when present.
            int nlIdx = fb.indexOf((int) '\n', endIdx)
            int totalLen = nlIdx >= 0 ? nlIdx + 1 : endIdx + 1
            frameResult(totalLen, fb.bytes(0, totalLen))
        }

        // Allows text() helpers in tests to find this variant.
        matches { msg -> msg.toString().contains('GPRMC') || msg.toString().contains('GNRMC') }

        decode { msg, ctx ->
            def text = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()
            def m = PAT.matcher(text)
            if (!m.find()) return null

            String imei = m.group(20)
            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(15)) {
                def cal = Calendar.getInstance(java.util.TimeZone.getTimeZone('UTC'))
                cal.set(2000 + m.group(17).toInteger(),
                        m.group(16).toInteger() - 1,
                        m.group(15).toInteger(),
                        m.group(3) ? m.group(3).toInteger() : 0,
                        m.group(4) ? m.group(4).toInteger() : 0,
                        m.group(5) ? m.group(5).toInteger() : 0)
                cal.set(Calendar.MILLISECOND, 0)
                pos.time = cal.time
            } else {
                ctx.lastLocation(pos)
            }

            pos.valid = m.group(6) == 'A'

            int latDeg    = m.group(7) ? m.group(7).toInteger() : 0
            double latMin = m.group(8).toDouble()
            pos.latitude  = (latDeg + latMin / 60.0) * (m.group(9) == 'S' ? -1 : 1)

            int lonDeg    = m.group(10) ? m.group(10).toInteger() : 0
            double lonMin = m.group(11).toDouble()
            pos.longitude = (lonDeg + lonMin / 60.0) * (m.group(12) == 'W' ? -1 : 1)

            pos.speed  = m.group(13) ? m.group(13).toDouble() : 0.0
            pos.course = m.group(14) ? m.group(14).toDouble() : 0.0

            pos.set('signal', m.group(18))
            decodeAlarm(pos, m.group(19))

            if (m.group(21)) pos.set(Position.KEY_SATELLITES,  m.group(21).toInteger())
            if (m.group(22)) pos.altitude = m.group(22).toDouble()
            if (m.group(24)) pos.set(Position.KEY_POWER, m.group(24).toDouble())  // group 23 = F/L letter, 24 = voltage

            return pos
        }

        encode { cmd, ctx ->
            def pwd = ctx.devicePassword('123456')
            switch (cmd.type) {
                case TYPE_ENGINE_STOP:   return "powercar${pwd} 11"
                case TYPE_ENGINE_RESUME: return "powercar${pwd} 00"
                default: return null
            }
        }
    }
}
