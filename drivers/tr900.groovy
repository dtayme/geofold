// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * TR-900 GPS tracker driver.
 *
 * Single-line format (newline-terminated), optional checksum suffix:
 *   ><id>,<period>,<fix>,<yyMMdd>,<hhmmss>,<EW><dddmm.d+>,<NS><ddmm.d+>,,<speed>,<course>,<gsm>,<event>,<adc>-<battery>,<impulses>,<input>,<status>[*csum!]
 *
 * Coordinates: HEM_DEG_MIN — hemisphere letter immediately followed by degrees then minutes
 *   e.g. W05830.2978 → West, 058° 30.2978' → -58.50496°
 *        S3137.2783  → South, 31° 37.2783' → -31.62131°
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^>(\d+),\d+,([01]),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([EW])(\d{3})(\d{2}\.\d+),([NS])(\d{2})(\d{2}\.\d+),[^,]*,([\d.]+),([\d.]+),(\d+),(\d+),(\d+)-(\d+),\d+,(\d+),(\d+)/)

def hemDegMin = { hemi, deg, min ->
    double v = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

protocol("tr900") {

    port 5074

    variant("main") {

        maxFrameLength 256
        frame readLine()

        matches { msg -> msg.startsWith('>') }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2) == '1'

            pos.time = new DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.longitude = hemDegMin(m.group(9),  m.group(10), m.group(11))
            pos.latitude  = hemDegMin(m.group(12), m.group(13), m.group(14))

            pos.speed  = m.group(15).toDouble()
            pos.course = m.group(16).toDouble()

            pos.set(Position.KEY_RSSI,       m.group(17).toDouble())
            pos.set(Position.KEY_EVENT,      m.group(18).toInteger())
            pos.set(Position.PREFIX_ADC + 1, m.group(19).toInteger())
            pos.set(Position.KEY_BATTERY,    m.group(20).toInteger())
            pos.set(Position.KEY_INPUT,      m.group(21))
            pos.set(Position.KEY_STATUS,     m.group(22))

            return pos
        }
    }
}
