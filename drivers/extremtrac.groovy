// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * ExtremTrac GPS tracker driver.
 *
 * Newline-terminated text frames. GPRMC-variant with embedded device ID:
 *   $GPRMC,<id>,<hhmmss.sss>,<AV>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>,<course>,<ddmmyy>,...
 *
 * Coordinates in deg+min format. Date is ddmmyy (setDateReverse).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$GPRMC,(\d+),(\d{2})(\d{2})(\d{2})\.(\d{3}),([AV]),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]+),([\d.]+),(\d{2})(\d{2})(\d{2}),/)

protocol("extremtrac") {

    port 5126

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(),
                             m.group(4).toInteger(), m.group(5).toInteger())

            pos.valid = m.group(6) == 'A'

            double lat = m.group(7).toInteger() + m.group(8).toDouble() / 60
            pos.latitude = m.group(9) == 'S' ? -lat : lat

            double lon = m.group(10).toInteger() + m.group(11).toDouble() / 60
            pos.longitude = m.group(12) == 'W' ? -lon : lon

            pos.speed  = m.group(13).toDouble()
            pos.course = m.group(14).toDouble()

            db.setDateReverse(m.group(15).toInteger(), m.group(16).toInteger(), m.group(17).toInteger())
            pos.time = db.getDate()

            return pos
        }
    }
}
