// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GpsMarker GPS tracker driver.
 *
 * CR-terminated text frames. Single message format:
 *   $GM<type>[<xx>]<imei15>T<ddmmyy><hhmmss[ss]><NS><ddmm><mmmm><EW><dddmm><mmmm><sss><ccc><x><bb><i><o><ttt>
 *
 * Coordinates in HEM_DEG_MIN_MIN format: hemisphere + degrees + integer minutes + minute fraction (4 digits).
 * Date ddmmyy (setDateReverse). Seconds optional. Satellites as single hex digit.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$GM\d(?:[0-9a-fA-F]{2})?(\d{15})T(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?([NS])(\d{2})(\d{2})(\d{4})([EW])(\d{3})(\d{2})(\d{4})(\d{3})(\d{3})([0-9a-fA-F])(\d{2})(\d)(\d)(\d{3})/)

protocol("gpsmarker") {

    port 5057

    variant("main") {

        frame readUntil("\r")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            int ss = m.group(7) != null ? m.group(7).toInteger() : 0
            pos.time = new DateBuilder()
                    .setDateReverse(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), ss)
                    .getDate()

            pos.valid = true

            double lat = m.group(9).toInteger() + (m.group(10).toInteger() + m.group(11).toInteger() / 10000.0) / 60.0
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + (m.group(14).toInteger() + m.group(15).toInteger() / 10000.0) / 60.0
            pos.longitude = m.group(12) == 'W' ? -lon : lon

            pos.speed  = m.group(16).toDouble()
            pos.course = m.group(17).toDouble()

            pos.set(Position.KEY_SATELLITES,    Integer.parseInt(m.group(18), 16))
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(19).toInteger())
            pos.set(Position.KEY_INPUT,         m.group(20))
            pos.set(Position.KEY_OUTPUT,        m.group(21))
            pos.set(Position.PREFIX_TEMP + 1,   m.group(22))

            return pos
        }
    }
}
