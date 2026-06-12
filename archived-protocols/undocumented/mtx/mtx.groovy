// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * MTX GPS tracker driver.
 *
 * Single-line format (newline-terminated):
 *   #MTX,<imei>,<yyyyMMdd>,<hhmmss>,<lat>,<lon>,<speed>,<course>,<odometer_km>,<X|d+>,<X|[01]>,<input>,<output>,<adc1>,<adc2>[,...]
 *
 * Server must respond with #ACK on every message.
 * Coordinates are decimal degrees. Odometer is in km (stored as metres × 1000).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^#MTX,(\d+),(\d{4})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(-?[\d.]+),(-?[\d.]+),([\d.]+),(\d+),([\d.]+),(?:\d+|X),(?:[01]|X),([01]+),([01]+),(\d+),(\d+)/)

protocol("mtx") {

    port 5083

    variant("main") {

        maxFrameLength 256
        frame readLine()

        matches { msg -> msg.startsWith('#MTX,') }

        decode { msg, ctx ->
            ctx.ack('#ACK')

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.valid     = true
            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.speed     = m.group(10).toDouble()
            pos.course    = m.group(11).toDouble()

            pos.set(Position.KEY_ODOMETER, m.group(12).toDouble() * 1000)
            pos.set(Position.KEY_INPUT,    m.group(13))
            pos.set(Position.KEY_OUTPUT,   m.group(14))
            pos.set(Position.PREFIX_ADC + 1, m.group(15))
            pos.set(Position.PREFIX_ADC + 2, m.group(16))

            return pos
        }
    }
}
