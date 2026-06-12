// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Supermate GPS tracker driver.
 *
 * '#'-terminated text frames. Single message format:
 *   <n>:<imei>:<seq>:*,<cmdId>,<cmd>,<AV>,<yymmdd_hex>,<hhmmss_hex>,<latSign><lat_x100000_hex>,<lonSign><lon_x100000_hex>,<speed_x100_hex>,<course_x100_hex>,<status>,<signal>,<power>,<oil_hex>,<odometer_hex>#
 *
 * Date/time fields: each 2-char group is a hex-encoded decimal value
 *   e.g. "10031b" → 0x10=16 (year 2016), 0x03=3 (March), 0x1b=27 (day 27)
 *
 * Coordinates: first hex nibble is sign flag (8=negative), remaining 7 nibbles
 *   divided by 600000.0 give decimal degrees.
 *
 * Server responds with current UTC date/time in the same hex format.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.Calendar
import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\d+:(\d+):\d+:\*,(\d+),([^,]{2}),([AV]),([\da-f]{2})([\da-f]{2})([\da-f]{2}),([\da-f]{2})([\da-f]{2})([\da-f]{2}),([\da-f])([\da-f]{7}),([\da-f])([\da-f]{7}),([\da-f]{4}),([\da-f]{4}),([\da-f]{12}),([0-9a-f]+),(\d+),([\da-f]{4}),([\da-f]+)?/,
    Pattern.CASE_INSENSITIVE)

protocol("supermate") {

    port 5108

    variant("main") {

        maxFrameLength 512
        frame readUntil('#')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            String imei = m.group(1)
            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set("commandId", m.group(2))
            pos.set(Position.KEY_COMMAND, m.group(3))
            pos.valid = m.group(4) == 'A'

            pos.time = new DateBuilder()
                    .setDate(Integer.parseInt(m.group(5), 16),
                             Integer.parseInt(m.group(6), 16),
                             Integer.parseInt(m.group(7), 16))
                    .setTime(Integer.parseInt(m.group(8), 16),
                             Integer.parseInt(m.group(9), 16),
                             Integer.parseInt(m.group(10), 16))
                    .getDate()

            boolean latNeg = Integer.parseInt(m.group(11), 16) == 8
            double lat = Integer.parseInt(m.group(12), 16) / 600000.0
            pos.latitude = latNeg ? -lat : lat

            boolean lonNeg = Integer.parseInt(m.group(13), 16) == 8
            double lon = Integer.parseInt(m.group(14), 16) / 600000.0
            pos.longitude = lonNeg ? -lon : lon

            pos.speed  = Integer.parseInt(m.group(15), 16) / 100.0
            pos.course = Integer.parseInt(m.group(16), 16) / 100.0

            pos.set(Position.KEY_STATUS, m.group(17))
            pos.set("signal",            m.group(18))
            pos.set(Position.KEY_POWER,  m.group(19).toDouble())
            pos.set("oil",               Integer.parseInt(m.group(20), 16))

            if (m.group(21) != null) {
                pos.set(Position.KEY_ODOMETER, Integer.parseInt(m.group(21), 16))
            }

            // Respond with current UTC time in the same hex format
            def cal = Calendar.getInstance()
            ctx.ack(String.format("#1:%s:1:*,00000000,UP,%02x%02x%02x,%02x%02x%02x#",
                    imei,
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)))

            return pos
        }
    }
}
