// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * TK102 GPS tracker driver.
 *
 * Binary framing: [<type(1)><seq(10)><len(1)><data(len)>]
 *   First byte: 0x5B ('['), last byte: 0x5D (']').
 *   Length field is at offset 12, width 1, +1 adjustment for the trailing ']'.
 *   Max data = 255 bytes; max frame = 269 bytes.
 *
 * Message types:
 *   0x80 — login request (data = ASCII device ID)
 *   0x21 — login request 2 (data = '(' + IMEI[15] + ',..)')
 *   0xF0 — heartbeat request (echo data back with type 0xFF)
 *   0x00 — login response (sent by server, not decoded)
 *   0xFF — heartbeat response (sent by server, not decoded)
 *   0x90, 0x93, etc. — position reports
 *
 * Position data format (inside parentheses):
 *   (<type_str>hhmmss<AV>ddmm.mmmmNdddmm.mmmmEddd.ddddmmyy...)
 *   Speed is in knots. Coordinates in deg+min format.
 */

import java.nio.charset.StandardCharsets

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\([A-Z]+(\d{2})(\d{2})(\d{2})([AV])(\d{2})(\d{2}\.\d+)([NS])(\d{3})(\d{2}\.\d+)([EW])(\d{3}\.\d{3})(\d{2})(\d{2})(\d{2})/)

// Builds a response frame: [ <type> <seq(10)> <contentLen> <content> ]
def buildResponse = { int type, byte[] seq, byte[] content ->
    def resp = new byte[14 + content.length]
    resp[0]  = (byte) 0x5B
    resp[1]  = (byte) type
    System.arraycopy(seq, 0, resp, 2, 10)
    resp[12] = (byte) content.length
    System.arraycopy(content, 0, resp, 13, content.length)
    resp[13 + content.length] = (byte) 0x5D
    resp
}

protocol("tk102") {

    port 5036

    variant("main") {

        maxFrameLength 512
        frame 0x5B as byte, readLengthField(12, 1, 1)

        decode { buf, ctx ->

            buf.skip(1)                     // '['
            int type    = buf.readUByte()
            byte[] seq  = buf.readBytes(10)
            int len     = buf.readUByte()
            byte[] data = buf.readBytes(len)
            // trailing ']' remains unread — no need to consume it

            if (type == 0x80) {
                // Login request: data is ASCII device ID
                String id = new String(data, StandardCharsets.US_ASCII)
                if (ctx.session(id)) {
                    byte[] content = new byte[1 + data.length]
                    content[0] = (byte) 0x30  // MODE_GPRS
                    System.arraycopy(data, 0, content, 1, data.length)
                    ctx.ack(buildResponse(0x00, seq, content))
                }

            } else if (type == 0x21) {
                // Login request 2: data = '(' + IMEI[15] + ','  + ...
                String id = new String(data, 1, 15, StandardCharsets.US_ASCII)
                if (ctx.session(id)) {
                    byte[] content = new byte[1 + data.length]
                    content[0] = (byte) 0x30
                    System.arraycopy(data, 0, content, 1, data.length)
                    ctx.ack(buildResponse(0x00, seq, content))
                }

            } else if (type == 0xF0) {
                // Heartbeat: echo data back with type 0xFF
                ctx.ack(buildResponse(0xFF, seq, data))

            } else {
                // Position or other report
                def session = ctx.session()
                if (!session) return null

                def text = new String(data, StandardCharsets.US_ASCII)
                def m = PATTERN.matcher(text)
                if (!m.find()) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                def db = new DateBuilder()
                        .setTime(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())

                pos.valid = m.group(4) == 'A'

                double lat = m.group(5).toInteger() + m.group(6).toDouble() / 60
                if (m.group(7) == 'S') lat = -lat
                pos.latitude = lat

                double lon = m.group(8).toInteger() + m.group(9).toDouble() / 60
                if (m.group(10) == 'W') lon = -lon
                pos.longitude = lon

                pos.speed = m.group(11).toDouble()

                db.setDateReverse(m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
                pos.time = db.getDate()

                return pos
            }

            return null
        }
    }
}
