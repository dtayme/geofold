// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Riti GPS tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/riti/
 *
 * Binary frame layout matches the archived Java decoder:
 *   2-byte header, 2-byte device id, status/power/odometer fields, embedded
 *   ASCII GPRMC sentence, and a little-endian length field at offset 105.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def GPRMC = Pattern.compile(
        '^\\$GPRMC,(\\d{2})(\\d{2})(\\d{2})\\.?\\d*,([AV]),'
                + '(\\d{2})(\\d{2}\\.\\d+),([NS]),'
                + '(\\d{3})(\\d{2}\\.\\d+),([EW]),'
                + '(\\d+\\.?\\d*)?,(\\d+\\.?\\d*)?,'
                + '(\\d{2})(\\d{2})(\\d{2})')

def coordinate = { deg, min, hemi ->
    double value = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -value : value
}

protocol("riti") {

    port 5071

    variant("main") {

        maxFrameLength 2048
        frame 0x3B as byte, readLengthFieldLE(105, 2, 3)

        decode { buf, ctx ->
            buf.skip(2) // header

            def session = ctx.session(String.valueOf(buf.readUShort()))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set('mode', buf.readUByte())
            pos.set(Position.KEY_COMMAND, buf.readUByte())
            pos.set(Position.KEY_POWER, buf.readUShortLE() / 1000.0)

            buf.skip(5)      // status
            buf.readUShortLE() // idle count
            buf.readUShortLE() // idle time, seconds

            pos.set(Position.KEY_DISTANCE, buf.readUIntLE())
            pos.set(Position.KEY_ODOMETER_TRIP, buf.readUIntLE())

            String payload = buf.readString(buf.remaining())
            int end = payload.indexOf('*')
            if (end < 0) return null

            def m = GPRMC.matcher(payload.substring(0, end))
            if (!m.find()) return null

            pos.time = new DateBuilder()
                    .setTime(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
                    .setDateReverse(m.group(13).toInteger(), m.group(14).toInteger(), m.group(15).toInteger())
                    .getDate()

            pos.valid = m.group(4) == 'A'
            pos.latitude = coordinate(m.group(5), m.group(6), m.group(7))
            pos.longitude = coordinate(m.group(8), m.group(9), m.group(10))
            pos.speed = m.group(11) ? m.group(11).toDouble() : 0
            pos.course = m.group(12) ? m.group(12).toDouble() : 0

            return pos
        }
    }
}
