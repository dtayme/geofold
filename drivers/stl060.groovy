// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * STL060 text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/stl060/
 *
 * Frames are '#' delimited and records start at the '$1,' marker. The decoder
 * accepts both the old and new D001 record tails used by the archived Java
 * implementation.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
        /.*\$1,(\d+),D001,[^,]*,(\d{2})\/(\d{2})\/(\d{2}),(\d{2}):(\d{2}):(\d{2}),(\d{2})(\d{2})\.?(\d+)([NS]),(\d{3})(\d{2})\.?(\d+)([EW]),(\d+\.?\d*),(\d+\.?\d*),(.*),([AV]).*/)

def coordinate = { String degrees, String minutes, String fraction, String hemisphere ->
    double value = degrees.toInteger() + (minutes.toInteger() + fraction.toInteger() / Math.pow(10, fraction.length())) / 60.0
    (hemisphere == 'S' || hemisphere == 'W') ? -value : value
}

protocol("stl060") {

    port 5060

    variant("main") {

        maxFrameLength 1024
        frame readUntil('#')

        matches { msg -> msg.contains('$1,') && msg.contains(',D001,') }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.matches()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(4).toInteger(), m.group(3).toInteger(), m.group(2).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.latitude = coordinate(m.group(8), m.group(9), m.group(10), m.group(11))
            pos.longitude = coordinate(m.group(12), m.group(13), m.group(14), m.group(15))
            pos.speed = m.group(16).toDouble()
            pos.course = m.group(17).toDouble()

            def fields = m.group(18).split(',', -1)
            if (fields.length == 5) {
                pos.set(Position.KEY_ODOMETER, fields[0].toInteger())
                pos.set(Position.KEY_IGNITION, fields[1].toInteger() == 1)
                pos.set(Position.KEY_INPUT, (fields[2].toInteger() + fields[3].toInteger()) << 1)
                pos.set(Position.KEY_FUEL, fields[4].toInteger())
            } else if (fields.length >= 12) {
                pos.set(Position.KEY_CHARGE, fields[0].toInteger() == 1)
                pos.set(Position.KEY_IGNITION, fields[1].toInteger() == 1)
                pos.set(Position.KEY_INPUT, fields[4].toInteger())
                pos.set(Position.KEY_DRIVER_UNIQUE_ID, fields[5])
                pos.set(Position.KEY_ODOMETER, fields[6].toInteger())
                pos.set(Position.PREFIX_TEMP + 1, fields[7].toInteger())
                pos.set(Position.KEY_FUEL, fields[8].toInteger())
                pos.set(Position.KEY_ACCELERATION, fields[9].toInteger() == 1)
                pos.set(Position.KEY_OUTPUT, (fields[10].toInteger() + fields[11].toInteger()) << 1)
            }

            pos.valid = m.group(19) == 'A'

            return pos
        }
    }
}
