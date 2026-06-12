// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Raveon GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   $PRAVE,<id>,<seq>,<±lat>,<±lon>,<hhmmss>,<valid>,<sats>,<alt>,<temp>,<power>,
 *   <inputs>,<gsm>,<speed_kph>,<course>,<alarm>?,...
 *
 * Coordinates use HEM_DEG_MIN format: optional '-' sign + degrees + 2-int-digit minutes.
 * Time only (no date); date is current wall clock. Speed in km/h.
 * Alarm is a single letter from [PMACIVSX] or absent.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$PRAVE,(\d+),\d+,(-?)(\d+)(\d{2}\.\d+),(-?)(\d+)(\d{2}\.\d+),(\d{2})(\d{2})(\d{2}),(\d),(\d+),(-?\d+),(-?\d+),([\d.]+),(\d+),(-?\d+),(\d+),(\d+),([PMACIVSX])?,/)

protocol("raveon") {

    port 5117

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            double lat = m.group(3).toInteger() + m.group(4).toDouble() / 60
            pos.latitude = m.group(2) == '-' ? -lat : lat

            double lon = m.group(6).toInteger() + m.group(7).toDouble() / 60
            pos.longitude = m.group(5) == '-' ? -lon : lon

            pos.time = new DateBuilder()
                    .setTime(m.group(8).toInteger(), m.group(9).toInteger(), m.group(10).toInteger())
                    .getDate()

            pos.valid = m.group(11).toInteger() != 0

            pos.set(Position.KEY_SATELLITES, m.group(12).toInteger())
            pos.altitude = m.group(13).toInteger()
            pos.set(Position.PREFIX_TEMP + 1, m.group(14).toInteger())
            pos.set(Position.KEY_POWER,       m.group(15).toDouble())
            pos.set(Position.KEY_INPUT,       m.group(16).toInteger())
            pos.set(Position.KEY_RSSI,        m.group(17).toInteger())

            pos.speed  = UnitsConverter.knotsFromKph(m.group(18).toInteger())
            pos.course = m.group(19).toInteger()

            pos.addAlarm(m.group(20))

            return pos
        }
    }
}
