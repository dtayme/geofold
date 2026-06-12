// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Box GPS tracker driver.
 *
 * CR-terminated text frames. Three message types:
 *
 *   H,<type>,<id>,... — handshake: registers session with the device ID at field 3.
 *   E,<data>         — event echo: server responds with "A,<data>\r".
 *   L,<yymmdd><hhmmss>,G,<lat>,<lon>,<speed>,<course>,<dist>,<event>,<status>[;<key>,<val>;...]
 *                   — position report (uses existing channel session).
 *
 * Status bits: bit 0 = ignition, bit 1 = motion, bit 2 = invalid fix.
 * Optional trailing data: semicolon-separated key,value pairs stored as attributes.
 * Speed in km/h. Distance in km (stored as metres × 1000).
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^L,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),G,(-?[\d.]+),(-?[\d.]+),([\d.]+),([\d.]+),([\d.]+),(\d+),(\d+)(?:;(.+))?/)

protocol("box") {

    port 5065

    variant("main") {

        maxFrameLength 1024
        frame readUntil("\r")

        decode { msg, ctx ->

            if (msg.startsWith('H,')) {
                int start = msg.indexOf(',', 2) + 1
                int end   = msg.indexOf(',', start)
                String id = end >= 0 ? msg.substring(start, end) : msg.substring(start)
                ctx.session(id)
                return null
            }

            if (msg.startsWith('E,')) {
                ctx.ack("A," + msg.substring(2) + "\r")
                return null
            }

            if (!msg.startsWith('L,')) return null

            def session = ctx.session()
            if (!session) return null

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
                    .setTime(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                    .getDate()

            pos.latitude  = m.group(7).toDouble()
            pos.longitude = m.group(8).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(9).toDouble())
            pos.course    = m.group(10).toDouble()

            pos.set(Position.KEY_ODOMETER_TRIP, m.group(11).toDouble() * 1000)
            pos.set(Position.KEY_EVENT, m.group(12))

            int status = m.group(13).toInteger()
            pos.set(Position.KEY_IGNITION, (status & 1) != 0)
            pos.set(Position.KEY_MOTION,   (status & 2) != 0)
            pos.valid = (status & 4) == 0
            pos.set(Position.KEY_STATUS, status)

            if (m.group(14) != null) {
                for (String item : m.group(14).split(';')) {
                    int sep = item.indexOf(',')
                    if (sep > 0) {
                        pos.set(item.substring(0, sep).toLowerCase(), item.substring(sep + 1))
                    }
                }
            }

            return pos
        }
    }
}
