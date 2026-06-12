// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Net GPS tracker driver.
 *
 * '!'-terminated text frames. Single fixed-width message:
 *   @L<nnn><imei15><xx><ddmmyy><hhmmss><f><latDeg><latMin><latFrac><lonDeg><lonMin><lonFrac>
 *   <status8hex><speed4hex><odo6hex><course3hex><alarm3hex>
 *
 * Coordinates in DEG_MIN_MIN format (degrees + integer minutes + 4-digit minute fraction).
 * Hemisphere from flags byte: bit 3 = valid, bit 1 = lat-south, bit 0 = lon-west.
 * Speed as hex/100 knots. Odometer as hex * 1852/16 metres.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^@L\d{3}(\d{15})[0-9a-fA-F]{2}(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})([0-9a-fA-F])(\d{2})(\d{2})(\d{4})(\d{3})(\d{2})(\d{4})([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{6})([0-9a-fA-F]{3})[0-9a-fA-F]{3}/)

protocol("net") {

    port 5215

    variant("main") {

        frame readUntil('!')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            int flags = Integer.parseInt(m.group(8), 16)
            pos.valid = (flags & 8) != 0

            double lat = m.group(9).toInteger() + (m.group(10).toInteger() + m.group(11).toInteger() / 10000.0) / 60.0
            pos.latitude = (flags & 2) != 0 ? -lat : lat

            double lon = m.group(12).toInteger() + (m.group(13).toInteger() + m.group(14).toInteger() / 10000.0) / 60.0
            pos.longitude = (flags & 1) != 0 ? -lon : lon

            pos.set(Position.KEY_STATUS,   Long.parseLong(m.group(15), 16))
            pos.speed = Integer.parseInt(m.group(16), 16) / 100.0
            pos.set(Position.KEY_ODOMETER, Integer.parseInt(m.group(17), 16) * 1852.0 / 16)
            pos.course = Integer.parseInt(m.group(18), 16)

            return pos
        }
    }
}
