// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Swiftech GPS tracker driver.
 *
 * '#'-terminated text frames. Single message format:
 *   @@<imei>,<f1>,<f2>,<hhmmss>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>,<ddmmyy>,<AV>,
 *   <status>,<charge>,<reserved>,<adc1>,<adc2>,...#
 *
 * Coordinates in deg+min format. Date ddmmyy (setDateReverse). ADC values in millivolts.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^@@(\d+),[^,]*,[^,]*,(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2}\.\d+),([NS]),(\d{2,3})(\d{2}\.\d+),([EW]),([\d.]+),(\d{2})(\d{2})(\d{2}),([AV]),(\d{4}),([01]),\d+,(\d+),(\d+),/)

protocol("swiftech") {

    port 5217

    variant("main") {

        frame readUntil('#')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            double lat = m.group(5).toInteger() + m.group(6).toDouble() / 60
            pos.latitude = m.group(7) == 'S' ? -lat : lat

            double lon = m.group(8).toInteger() + m.group(9).toDouble() / 60
            pos.longitude = m.group(10) == 'W' ? -lon : lon

            pos.speed = m.group(11).toDouble()

            db.setDateReverse(m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
            pos.time = db.getDate()

            pos.valid = m.group(15) == 'A'

            pos.set(Position.KEY_STATUS, m.group(16).toInteger())
            pos.set(Position.KEY_CHARGE,  m.group(17).toInteger() > 0)

            pos.set(Position.PREFIX_ADC + 1, m.group(18).toInteger() / 1000.0)
            pos.set(Position.PREFIX_ADC + 2, m.group(19).toInteger() / 1000.0)

            return pos
        }
    }
}
