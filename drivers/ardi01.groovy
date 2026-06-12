// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Ardi01 GPS tracker driver.
 *
 * Single-line CSV format (newline-terminated):
 *   <imei>,<yyyymmdd><hhmmss>,<lon>,<lat>,<speed_kph>,<course>,<alt>,<sats>,<event>,<battery_%>,<temp>
 *
 * Note: longitude comes before latitude in this protocol.
 * Speed is in km/h; converted to knots for storage.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d+),(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(-?[\d.]+),(-?[\d.]+),([\d.]*),([\d.]*),(-?[\d.]*),(\d+),(\d+),(\d+),(-?\d+)/)

protocol("ardi01") {

    port 5004

    variant("main") {

        maxFrameLength 256
        frame readLine()

        matches { msg -> msg =~ /^\d{10,15},\d{14},/ }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            // Protocol sends longitude before latitude
            pos.longitude = m.group(8).toDouble()
            pos.latitude  = m.group(9).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(10) ? m.group(10).toDouble() : 0)
            pos.course    = m.group(11) ? m.group(11).toDouble() : 0
            pos.altitude  = m.group(12) ? m.group(12).toDouble() : 0

            int satellites = m.group(13).toInteger()
            pos.valid = satellites >= 3
            pos.set(Position.KEY_SATELLITES, satellites)

            pos.set(Position.KEY_EVENT,         m.group(14))
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(15).toInteger())
            pos.set(Position.PREFIX_TEMP + '1', m.group(16))

            return pos
        }
    }
}
