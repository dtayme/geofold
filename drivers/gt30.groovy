// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GT30 GPS tracker driver.
 *
 * Single-line format (newline-terminated):
 *   $$<len:4hex><id:14chars><type:4hex>[<alarm:1byte>]<hhmmss.sss>,<AV>,<lat>,<NS>,<lon>,<EW>,<speed>,<course>,<ddmmyy>[^|]*|<hdop>|<alt><csum:4hex>
 *
 * The optional alarm byte is a raw binary value (not a printable character):
 *   0x01-0x03 → SOS, 0x10 → low battery, 0x11 → overspeed, 0x12 → geofence.
 *
 * Speed is in knots.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$\$[0-9a-fA-F]{4}(.{14})[0-9a-fA-F]{4}(.)?(\d{2})(\d{2})(\d{2})\.(\d{3}),([AV]),(\d+)(\d{2}\.[\d]+),([NS]),(\d+)(\d{2}\.[\d]+),([EW]),([\d.]+)?,([\d.]+)?,(\d{2})(\d{2})(\d{2})[^|]*\|([\d.]+)\|(-?\d+)[0-9a-fA-F]{4}/)

def decodeAlarm = { String s ->
    if (!s) return null
    switch (s.charAt(0) as int) {
        case 0x01: case 0x02: case 0x03: return ALARM_SOS
        case 0x10: return ALARM_LOW_BATTERY
        case 0x11: return ALARM_OVERSPEED
        case 0x12: return ALARM_GEOFENCE
        default: return null
    }
}

def nmea = { deg, min, hemi ->
    double v = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

protocol("gt30") {

    port 5002

    variant("main") {

        maxFrameLength 256
        frame readLine()

        matches { msg -> msg.startsWith('$$') }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1).trim())
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.addAlarm(decodeAlarm(m.group(2)))

            pos.time = new DateBuilder()
                    .setTime(m.group(3).toInteger(), m.group(4).toInteger(),
                             m.group(5).toInteger(), m.group(6).toInteger())
                    .setDateReverse(m.group(16).toInteger(), m.group(17).toInteger(), m.group(18).toInteger())
                    .getDate()

            pos.valid     = m.group(7) == 'A'
            pos.latitude  = nmea(m.group(8),  m.group(9),  m.group(10))
            pos.longitude = nmea(m.group(11), m.group(12), m.group(13))
            pos.speed     = m.group(14) ? m.group(14).toDouble() : 0
            pos.course    = m.group(15) ? m.group(15).toDouble() : 0

            pos.set(Position.KEY_HDOP, m.group(19) ? m.group(19).toDouble() : null)
            pos.altitude  = m.group(20) ? m.group(20).toDouble() : 0

            return pos
        }
    }
}
