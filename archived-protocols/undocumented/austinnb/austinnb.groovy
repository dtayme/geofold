// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * AustinNb GPS tracker driver.
 *
 * UDP datagrams, newline frame for TCP. Single message format:
 *   <imei>;<yyyy>-<mm>-<dd> <hh>:<mm>:<ss>;<lat>;<lon>;<azimuth>;<angle>;<range>;<outOfRange>;<carrier>
 *
 * Coordinates use comma as decimal separator (European locale).
 * Position is always valid; no GNSS fix quality field.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d+);(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2});(-?\d+,\d+);(-?\d+,\d+);(\d+);(\d+);(\d+);(\d+);(.*)/)

protocol("austinnb") {

    port 5158

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid = true

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.latitude  = m.group(8).replace(',', '.').toDouble()
            pos.longitude = m.group(9).replace(',', '.').toDouble()
            pos.course    = m.group(10).toInteger()

            pos.set("angle",      m.group(11).toInteger())
            pos.set("range",      m.group(12).toInteger())
            pos.set("outOfRange", m.group(13).toInteger())
            pos.set("carrier",    m.group(14))

            return pos
        }
    }
}
