/*
 * Copyright 2013 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by FOGNETX <Drew.Taylor@fognetx.com>, 2026. Modifications licensed under
// AGPL-3.0-or-later (SPDX-License-Identifier: AGPL-3.0-or-later).

import org.traccar.model.Position

protocol("atrack") {

    port 5044

    variant("main") {

        // Frame detection mirrors AtrackFrameDecoder logic:
        //   0xFE02 keep-alive → 12 bytes
        //   0x40 + byte[2]!=',' → binary: UShort@4 + 6 bytes
        //   text with comma after pos 3 → lengthEnd + parseInt(lengthField) [+ 1 for '\n']
        //   text without comma → up to \r\n
        scriptedFrame { fb ->
            int n = fb.readableBytes()
            if (n < 2) return null

            int b0 = fb.getUByte(0) & 0xFF
            int b1 = fb.getUByte(1) & 0xFF

            if (b0 == 0xFE && b1 == 0x02) {
                return n >= 12 ? 12 : null
            }

            if (b0 == 0x40 && n > 2 && (fb.getUByte(2) & 0xFF) != 0x2C) {
                if (n > 6) {
                    int length = ((fb.getUByte(4) & 0xFF) << 8) | (fb.getUByte(5) & 0xFF)
                    int total = length + 6
                    return n >= total ? total : null
                }
                return null
            }

            // Text frame: find comma at index >= 3 to locate the length field
            int lengthStart = -1
            for (int i = 3; i < n; i++) {
                if ((fb.getUByte(i) & 0xFF) == 0x2C) {
                    lengthStart = i + 1
                    break
                }
            }

            if (lengthStart <= 0) {
                for (int i = 0; i < n - 1; i++) {
                    if ((fb.getUByte(i) & 0xFF) == 0x0D && (fb.getUByte(i + 1) & 0xFF) == 0x0A) {
                        return i + 2
                    }
                }
                return null
            }

            int lengthEnd = -1
            for (int i = lengthStart; i < n; i++) {
                if ((fb.getUByte(i) & 0xFF) == 0x2C) {
                    lengthEnd = i
                    break
                }
            }

            if (lengthEnd <= 0) {
                for (int i = 0; i < n - 1; i++) {
                    if ((fb.getUByte(i) & 0xFF) == 0x0D && (fb.getUByte(i + 1) & 0xFF) == 0x0A) {
                        return i + 2
                    }
                }
                return null
            }

            StringBuilder sb = new StringBuilder()
            for (int i = lengthStart; i < lengthEnd; i++) {
                sb.append((char)(fb.getUByte(i) & 0xFF))
            }
            try {
                int dataLen = Integer.parseInt(sb.toString().trim())
                int total = lengthEnd + dataLen
                if (n > total && (fb.getUByte(total) & 0xFF) == 0x0A) {
                    total += 1
                }
                return n >= total ? total : null
            } catch (Exception ignored) {
                return null
            }
        }

        decode { msg, ctx ->

            // Read a null-terminated string from binary buffer (mirrors readString helper in Java)
            def readNullString = { buf ->
                if (!buf.isReadable()) return null
                int rem = buf.readableBytes()
                int len = 0
                while (len < rem && (buf.getUByte(len) & 0xFF) != 0) {
                    len++
                }
                String s = len > 0 ? buf.readString(len) : null
                if (buf.isReadable()) buf.skip(1)
                return s
            }

            // Send 12-byte ACK: 0xFE02 + rawId(8 bytes BE) + index(2 bytes BE)
            def sendAck = { long rawId, int idx ->
                ctx.ack([
                    (byte)0xFE, (byte)0x02,
                    (byte)((rawId >> 56) & 0xFF), (byte)((rawId >> 48) & 0xFF),
                    (byte)((rawId >> 40) & 0xFF), (byte)((rawId >> 32) & 0xFF),
                    (byte)((rawId >> 24) & 0xFF), (byte)((rawId >> 16) & 0xFF),
                    (byte)((rawId >> 8) & 0xFF),  (byte)(rawId & 0xFF),
                    (byte)((idx >> 8) & 0xFF),    (byte)(idx & 0xFF)
                ] as byte[])
            }

            int b0 = msg.getUByte(0) & 0xFF
            int b1 = msg.getUByte(1) & 0xFF

            // Keep-alive: echo the 12-byte frame back
            if (b0 == 0xFE && b1 == 0x02) {
                ctx.ack(msg.readBytes(12))
                return null
            }

            // $ info/result messages
            if (b0 == (int)'$') {
                String sentence = msg.readString(msg.readableBytes()).trim()

                if (sentence.startsWith("\$INFO=")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        '\\$INFO=(\\d+),([^,]+),([^,]+),\\d+,\\d+,\\d+,(\\d+),(\\d+),(\\d+),\\d+,(\\d+),.*')
                        .matcher(sentence)
                    if (!m.matches()) return null
                    def session = ctx.session(m.group(1))
                    if (!session) return null
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos, null)
                    pos.set("model", m.group(2))
                    pos.set(Position.KEY_VERSION_FW, m.group(3))
                    pos.set(Position.KEY_POWER, Integer.parseInt(m.group(4)) / 10.0)
                    pos.set(Position.KEY_BATTERY, Integer.parseInt(m.group(5)) / 10.0)
                    pos.set(Position.KEY_SATELLITES, Integer.parseInt(m.group(6)))
                    pos.set(Position.KEY_RSSI, Integer.parseInt(m.group(7)))
                    return pos
                }

                // Other $ responses ($OK, $ERROR, etc.) have no parseable device ID
                return null
            }

            int b2 = msg.getUByte(2) & 0xFF

            // Text @P/@R frame: byte[2] == ','
            if (b2 == 0x2C) {
                String sentence = msg.readString(msg.readableBytes()).trim()

                // Locate the 5th comma to split header from position data
                int positionIndex = -1
                for (int i = 0; i < 5; i++) {
                    positionIndex = sentence.indexOf(',', positionIndex + 1)
                    if (positionIndex < 0) return null
                }

                String[] headers = sentence.substring(0, positionIndex).split(",")
                if (headers.length < 5) return null

                long rawId = Long.parseLong(headers[2])
                int index = Integer.parseInt(headers[3])
                def session = ctx.session(headers[4])
                if (!session) return null
                sendAck(rawId, index)

                java.util.regex.Pattern linePat = java.util.regex.Pattern.compile(
                    '(\\d+),\\d+,\\d+,(-?\\d+),(-?\\d+),(\\d+),(\\d+),(\\d+\\.?\\d*),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),([^,]*),(\\d+),(\\d+)(?:,[^,]*(?:,(.*))?)?$')

                String[] lines = sentence.substring(positionIndex + 1).split("\r\n")
                for (String line : lines) {
                    if (line.isEmpty()) continue
                    java.util.regex.Matcher m = linePat.matcher(line)
                    if (!m.find()) continue

                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    pos.valid = true

                    String timeStr = m.group(1)
                    if (timeStr.length() >= 14) {
                        def sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        pos.setTime(sdf.parse(timeStr))
                    } else {
                        pos.setTime(new java.util.Date(Long.parseLong(timeStr) * 1000L))
                    }

                    pos.longitude = Integer.parseInt(m.group(2)) / 1000000.0
                    pos.latitude  = Integer.parseInt(m.group(3)) / 1000000.0
                    pos.course    = Integer.parseInt(m.group(4))
                    pos.set(Position.KEY_EVENT, Integer.parseInt(m.group(5)))
                    pos.set(Position.KEY_ODOMETER, Double.parseDouble(m.group(6)) * 100.0)
                    pos.set(Position.KEY_HDOP, Integer.parseInt(m.group(7)) / 10.0)
                    pos.set(Position.KEY_INPUT, Integer.parseInt(m.group(8)))
                    pos.speed = Integer.parseInt(m.group(9)) * 0.539957
                    pos.set(Position.KEY_OUTPUT, Integer.parseInt(m.group(10)))
                    pos.set(Position.PREFIX_ADC + "1", Integer.parseInt(m.group(11)))
                    String driver = m.group(12)
                    if (driver && !driver.isEmpty()) {
                        pos.set(Position.KEY_DRIVER_UNIQUE_ID, driver)
                    }
                    pos.set(Position.PREFIX_TEMP + "1", Integer.parseInt(m.group(13)))
                    pos.set(Position.PREFIX_TEMP + "2", Integer.parseInt(m.group(14)))

                    ctx.emit(pos)
                }
                return null
            }

            // Binary @P/@R frame: prefix(2)+checksum(2)+length(2)+index(2)+id(8) = 16 bytes
            String prefix = msg.readString(2)
            msg.readUShort()  // checksum
            msg.readUShort()  // length
            int index = (int)(msg.readUShort() & 0xFFFF)
            long rawId = msg.readLong()

            def session = ctx.session(String.valueOf(rawId))
            if (!session) return null
            sendAck(rawId, index)

            if (prefix == "@R") {
                return null  // Photo frames not supported
            }

            // Decode binary position records (non-longDate, non-custom format)
            java.util.regex.Pattern fulsPat = java.util.regex.Pattern.compile(
                "FULS:F=(\\p{XDigit}+) t=(\\p{XDigit}+) N=(\\p{XDigit}+)")

            while (msg.readableBytes() >= 40) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                pos.valid = true

                pos.setFixTime(new java.util.Date(msg.readUInt() * 1000L))
                pos.setDeviceTime(new java.util.Date(msg.readUInt() * 1000L))
                msg.readUInt()  // send time

                pos.longitude = msg.readInt() / 1000000.0
                pos.latitude  = msg.readInt() / 1000000.0
                pos.course    = msg.readUShort()

                int type = msg.readUByte()
                pos.set(Position.KEY_TYPE, type)

                pos.set(Position.KEY_ODOMETER, msg.readUInt() * 100L)
                pos.set(Position.KEY_HDOP, msg.readUShort() / 10.0)
                pos.set(Position.KEY_INPUT, msg.readUByte())
                pos.speed = msg.readUShort() * 0.539957
                pos.set(Position.KEY_OUTPUT, msg.readUByte())
                pos.set(Position.PREFIX_ADC + "1", msg.readUShort() / 1000.0)

                String driverId = readNullString(msg)
                if (driverId) {
                    pos.set(Position.KEY_DRIVER_UNIQUE_ID, driverId)
                }

                pos.set(Position.PREFIX_TEMP + "1", msg.readShort() / 10.0)
                pos.set(Position.PREFIX_TEMP + "2", msg.readShort() / 10.0)

                String message = readNullString(msg)
                if (message && !message.isEmpty()) {
                    java.util.regex.Matcher fm = fulsPat.matcher(message)
                    if (fm.find()) {
                        pos.set(Position.KEY_FUEL, Integer.parseInt(fm.group(3), 16) / 10.0)
                    } else {
                        pos.set("message", message)
                    }
                }

                ctx.emit(pos)
            }

            return null
        }
    }
}
