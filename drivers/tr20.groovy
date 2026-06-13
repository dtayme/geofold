// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Tr20 GPS tracker driver.
 *
 * Newline-terminated text frames starting with '%'. Two message types:
 *  - PING: %%<anything>,<id> → ack &&<id>\r\n, return null
 *  - DATA: %%<id>,<AL>,<yymmdd><hhmmss>,<NS><latDeg><latMin><EW><lonDeg><lonMin>,<spd>,<crs>,[NA|<temp>],<status8hex>,<event>
 *
 * Coordinates: HEM_DEG_MIN. Temperature optional (null when NA).
 * Status is a 32-bit hex value stored as long.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN_PING = Pattern.compile(/^%%[^,]+,(\d+)/)
def PATTERN_DATA = Pattern.compile(
    /^%%([^,]+),([AL]),(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([NS])(\d{2})(\d{2}\.\d+)([EW])(\d{3})(\d{2}\.\d+),(\d+),(\d+),(?:NA|[BFC]?(-?\d+)[^,]*),([0-9a-fA-F]{8}),(\d+)/)

protocol("tr20") {

    port 5018

    variant("main") {

        frame '%' as char, readLine()
        matches { msg -> msg.startsWith("%%") }

        decode { msg, ctx ->
            def mp = PATTERN_PING.matcher(msg)
            if (mp.find()) {
                ctx.ack('&&' + mp.group(1) + '\r\n')
                return null
            }

            def m = PATTERN_DATA.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2) == 'A'

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lat = m.group(10).toInteger() + m.group(11).toDouble() / 60.0
            pos.latitude = m.group(9) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + m.group(14).toDouble() / 60.0
            pos.longitude = m.group(12) == 'W' ? -lon : lon

            pos.speed  = UnitsConverter.knotsFromKph(m.group(15).toDouble())
            pos.course = m.group(16).toDouble()

            if (m.group(17)) pos.set(Position.PREFIX_TEMP + 1, m.group(17).toInteger())

            pos.set(Position.KEY_STATUS, Long.parseLong(m.group(18), 16))
            pos.set(Position.KEY_EVENT,  m.group(19).toInteger())

            return pos
        }
    }
}
