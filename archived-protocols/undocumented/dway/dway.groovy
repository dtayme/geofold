// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Dway GPS tracker driver.
 *
 * Newline-terminated text frames. Heartbeat: AA55,HB → 55AA,HB,OK.
 * Position format:
 *   AA55,<idx>,<imei>,<type>,<yymmdd>,<hhmmss>,<lat>,<lon>,<alt>,<speed>,<crs>,<in4>,<out4>,<flags>,<bat>,<adc1>,<adc2>,<driver>
 *
 * Input/output are 4-bit binary strings. Battery and ADC in mV/1000 = volts.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^AA55,\d+,(\d+),\d+,(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(-?[\d.]+),(-?[\d.]+),(-?\d+), ?([\d.]+),(\d+),([01]{4}),([01]{4}),[01]+,(\d+),(\d+),(\d+),(\d+)/)

protocol("dway") {

    port 5150

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            if (msg == "AA55,HB") {
                ctx.ack("55AA,HB,OK\r\n")
                return null
            }

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

            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.altitude  = m.group(10).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(11).toDouble())
            pos.course    = m.group(12).toDouble()

            pos.set(Position.KEY_INPUT,  Integer.parseInt(m.group(13), 2))
            pos.set(Position.KEY_OUTPUT, Integer.parseInt(m.group(14), 2))

            pos.set(Position.KEY_BATTERY,     m.group(15).toInteger() / 1000.0)
            pos.set(Position.PREFIX_ADC + 1,  m.group(16).toInteger() / 1000.0)
            pos.set(Position.PREFIX_ADC + 2,  m.group(17).toInteger() / 1000.0)
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(18))

            return pos
        }
    }
}
