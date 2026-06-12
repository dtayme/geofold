// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Pt3000 GPS tracker driver.
 *
 * 'd'-terminated text frames. Single message format:
 *   %<imei>,$GPRMC,<hhmmss.[ms]>,<AV>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>,<course>,<ddmmyy>,...d
 *
 * Coordinates in DEG_MIN format. Date in ddmmyy (European) order.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    '^%(\\d+),\\$GPRMC,(\\d{2})(\\d{2})(\\d{2})\\.?\\d*,([AV]),(\\d{2})(\\d{2}\\.\\d+),([NS]),(\\d{3})(\\d{2}\\.\\d+),([EW]),([\\d.]+)?,([\\d.]+)?,(\\d{2})(\\d{2})(\\d{2})')

protocol("pt3000") {

    port 5045

    variant("main") {

        frame '%' as char, readUntil("d")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            pos.valid = m.group(5) == 'A'

            double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60.0
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            pos.speed  = m.group(12) ? m.group(12).toDouble() : 0.0
            pos.course = m.group(13) ? m.group(13).toDouble() : 0.0

            db.setDateReverse(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
            pos.time = db.getDate()

            return pos
        }
    }
}
