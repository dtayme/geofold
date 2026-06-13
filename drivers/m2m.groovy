// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * M2M tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/m2m/
 *
 * The wire format is fixed 23-byte packets with a byte offset applied to most
 * fields. The first packet identifies the channel session; subsequent packets
 * carry position data.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

def firstPacket = true

def removeOffset = { byte[] raw ->
    raw.collect { int value ->
        int b = value & 0xff
        b == 0x0b ? b : ((b - 0x20) & 0xff)
    }
}

def decimalDigits = { List<Integer> bytes ->
    bytes.collect { int b -> "${((int) (b / 10))}${b % 10}" }.join()
}

protocol("m2m") {

    port 5054

    variant("main") {

        maxFrameLength 23
        matches { msg -> msg instanceof byte[] && msg.length == 23 }
        frame readFixed(23)

        decode { buf, ctx ->
            List<Integer> data = removeOffset(buf.readBytes(buf.remaining()))
            int i = 0
            def next = { data[i++] }

            if (firstPacket) {
                firstPacket = false
                ctx.session(decimalDigits(data.take(8)).substring(1))
                return null
            }

            def session = ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDay(next() & 0x3f)
                    .setMonth(next() & 0x3f)
                    .setYear(next())
                    .setHour(next() & 0x3f)
                    .setMinute(next() & 0x7f)
                    .setSecond(next() & 0x7f)
                    .getDate()

            int degrees = next()
            double latitude = next()
            latitude += next() / 100.0
            latitude += next() / 10000.0
            latitude = latitude / 60.0 + degrees

            int flags = next()

            degrees = (flags & 0x7f) * 100 + next()
            double longitude = next()
            longitude += next() / 100.0
            longitude += next() / 10000.0
            longitude = longitude / 60.0 + degrees

            if ((flags & 0x80) != 0) longitude = -longitude
            if ((flags & 0x40) != 0) latitude = -latitude

            pos.valid = true
            pos.latitude = latitude
            pos.longitude = longitude
            pos.speed = next()

            int satellites = next()
            if (satellites == 0) return null
            pos.set(Position.KEY_SATELLITES, satellites)

            return pos
        }
    }
}
