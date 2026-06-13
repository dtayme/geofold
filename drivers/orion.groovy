// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Orion binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/orion/
 *
 * Implements the documented binary userlog frame family, including the custom
 * record-count/record-length frame sizing and optional acknowledgement.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

def MSG_USERLOG = 0
def MSG_SYSLOG = 3

def coordinate = { int raw ->
    int degrees = (int) (raw / 1000000)
    double minutes = (raw % 1000000) / 10000.0
    degrees + minutes / 60.0
}

def ackFrame = { byte[] frame ->
    byte[] response = new byte[4]
    response[0] = (byte) '*'
    response[1] = frame[frame.length - 2]
    response[2] = frame[frame.length - 1]
    response[3] = frame[frame.length - 3]
    response
}

protocol("orion") {

    port 5070

    variant("userlog") {

        maxFrameLength 8192
        frame 0x50 as byte, { fb ->
            int length = 6
            if (fb.readableBytes() < length) return null

            int type = fb.getUByte(2) & 0x0f
            if (type == MSG_USERLOG && fb.readableBytes() >= length + 5) {
                int index = 3
                int count = fb.getUByte(index) & 0x0f
                index += 5
                length += 5

                for (int i = 0; i < count; i++) {
                    if (fb.readableBytes() < length || fb.readableBytes() <= index + 1) return null
                    int logLength = fb.getUByte(index + 1)
                    index += logLength
                    length += logLength
                }

                return fb.readableBytes() >= length ? frameRaw(length) : null
            }

            if (type == MSG_SYSLOG && fb.readableBytes() >= length + 12) {
                length += fb.getUShortLE(8)
                return fb.readableBytes() >= length ? frameRaw(length) : null
            }

            return null
        }

        decode { buf, ctx ->
            byte[] frame = buf.getBytes(0, buf.remaining())

            buf.skip(2) // header
            int type = buf.readUByte() & 0x0f
            if (type != MSG_USERLOG) return null

            int header = buf.readUByte()
            if ((header & 0x40) != 0) {
                ctx.ack(ackFrame(frame))
            }

            def session = ctx.session(String.valueOf(buf.readUInt()))
            if (!session) return null

            int count = header & 0x0f
            for (int i = 0; i < count; i++) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                pos.set(Position.KEY_EVENT, buf.readUByte())
                buf.readUByte() // record length
                pos.set(Position.KEY_FLAGS, buf.readUShortLE())

                pos.latitude = coordinate(buf.readIntLE())
                pos.longitude = coordinate(buf.readIntLE())
                pos.altitude = buf.readShortLE() / 10.0
                pos.course = buf.readUShortLE()
                pos.speed = buf.readUShortLE() * 0.0539957

                pos.time = new DateBuilder()
                        .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .getDate()

                int satellites = buf.readUByte()
                pos.valid = satellites >= 3
                pos.set(Position.KEY_SATELLITES, satellites)

                ctx.emit(pos)
            }

            return null
        }
    }
}
