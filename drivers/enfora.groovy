// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Enfora modem tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/enfora/
 *
 * Frames use the documented Enfora API two-byte big-endian length prefix. The
 * payload can contain optional API headers before the device identifier and
 * NMEA sentence, so decode scans for the 15-digit IMEI and GPRMC sentence as
 * the archived Java decoder did.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

def GPRMC = Pattern.compile(
        'GPRMC,(\\d{2})(\\d{2})(\\d{2})\\.?\\d*,([AV]),'
                + '(\\d{2})(\\d{2}\\.\\d+),([NS]),'
                + '(\\d{3})(\\d{2}\\.\\d+),([EW]),'
                + '(\\d+\\.\\d+)?,(\\d+\\.\\d+)?,'
                + '(\\d{2})(\\d{2})(\\d{2}),.*')

def coordinate = { deg, min, hemi ->
    double value = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -value : value
}

def commandFrame = { String content ->
    byte[] payload = content.getBytes(StandardCharsets.US_ASCII)
    byte[] frame = new byte[payload.length + 6]
    int length = frame.length
    frame[0] = (byte) ((length >> 8) & 0xff)
    frame[1] = (byte) (length & 0xff)
    frame[2] = 0
    frame[3] = 0
    frame[4] = 0x04
    frame[5] = 0
    System.arraycopy(payload, 0, frame, 6, payload.length)
    frame
}

protocol("enfora") {

    port 5008
    commands TYPE_CUSTOM,
             TYPE_ENGINE_STOP,
             TYPE_ENGINE_RESUME

    variant("main") {

        maxFrameLength 8192
        matches { msg -> msg instanceof byte[] && msg.length >= 2 }
        frame readLengthField(0, 2, -2)

        decode { buf, ctx ->
            byte[] bytes = buf.readBytes(buf.remaining())
            String message = new String(bytes, StandardCharsets.US_ASCII)

            int imeiIndex = -1
            for (int i = 0; i < message.length() - 15; i++) {
                String candidate = message.substring(i, i + 15)
                if (candidate.every { it >= '0' && it <= '9' }) {
                    imeiIndex = i
                    break
                }
            }
            if (imeiIndex < 0) return null

            def session = ctx.session(message.substring(imeiIndex, imeiIndex + 15))
            if (!session) return null

            int start = message.indexOf('GPRMC')
            if (start < 0) return null

            def m = GPRMC.matcher(message.substring(start))
            if (!m.find()) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

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

        encode { command, ctx ->
            switch (command.type) {
                case TYPE_CUSTOM:
                    return commandFrame(ctx.data())
                case TYPE_ENGINE_STOP:
                    return commandFrame('AT$IOGP3=1')
                case TYPE_ENGINE_RESUME:
                    return commandFrame('AT$IOGP3=0')
                default:
                    return null
            }
        }
    }
}
