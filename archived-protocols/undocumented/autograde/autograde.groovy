// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * AutoGrade GPS tracker driver.
 *
 * ')'-terminated text frames starting with '('. Single message format:
 *   (<12-index><15-imei><ddmmyy><AV><lat><NS><lon><EW><speed5><hhmmss><course6><status>A<adc1>B<adc2>C<adc3>D<adc4>E<adc5>K<can1>L<can2>M<can3>N<can4>O<can5>...)
 *
 * Coordinates in DEG_MIN format. Bit 0 of status char = ignition.
 * ADC and CAN values are 4-digit hex strings.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\(\d{12}(\d{15})(\d{2})(\d{2})(\d{2})([AV])(\d+)(\d{2}\.\d+)([NS])(\d+)(\d{2}\.\d+)([EW])([\d.]{5})(\d{2})(\d{2})(\d{2})([\d.]{6})(.)A([0-9a-fA-F]{4})B([0-9a-fA-F]{4})C([0-9a-fA-F]{4})D([0-9a-fA-F]{4})E([0-9a-fA-F]{4})K([0-9a-fA-F]{4})L([0-9a-fA-F]{4})M([0-9a-fA-F]{4})N([0-9a-fA-F]{4})O([0-9a-fA-F]{4})/)

protocol("autograde") {

    port 5120

    variant("main") {

        frame '(' as char, readUntil(")")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setDateReverse(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            pos.valid = m.group(5) == 'A'

            double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60.0
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            pos.speed = m.group(12).toDouble()

            db.setTime(m.group(13).toInteger(), m.group(14).toInteger(), m.group(15).toInteger())
            pos.time = db.getDate()

            pos.course = m.group(16).toDouble()

            int statusChar = (int) m.group(17).charAt(0)
            pos.set(Position.KEY_STATUS, statusChar)
            pos.set(Position.KEY_IGNITION, checkBit(statusChar, 0))

            (1..5).each { i -> pos.set(Position.PREFIX_ADC + i, m.group(17 + i)) }
            (1..5).each { i -> pos.set("can" + i, m.group(22 + i)) }

            return pos
        }
    }
}
