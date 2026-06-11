/**
 * GT02 GPS tracker driver (Sinotrack ST-901 and compatible).
 *
 * Binary protocol with a 2-byte ASCII header (0x54 0x68 = "Th").
 *
 * Frame layout:
 *   0x54 0x68  — magic header (2 bytes)
 *   size       — frame data count (1 byte); total frame = size + 5
 *   power      — battery/power indicator, 0 on location frames (1 byte)
 *   gsm        — GSM signal level, 0 on location frames (1 byte)
 *   imei[8]    — IMEI packed as 8-byte big-endian hex, leading nibble always 0
 *   index[2]   — sequence counter (2 bytes)
 *   type       — message type byte (see below)
 *   <payload>  — type-specific fields
 *
 * Message types:
 *   0x10  MSG_DATA       — GPS position report
 *   0x1A  MSG_HEARTBEAT  — keep-alive with power/GSM info; server must ACK
 *   0x1C  MSG_RESPONSE   — text response to a command
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

protocol("gt02") {

    port 5022

    variant("main") {

        // Header byte 0x54 ('T'); size field at offset 2, 1 byte wide;
        // adjustment +2 because the size value does not count the 2 bytes after it.
        // Total frame = 2 (header) + 1 (size byte) + size_value + 2 = size_value + 5.
        // size_value is 1-byte unsigned (0–255), so the maximum possible frame is 260 bytes.
        maxFrameLength 260
        frame 0x54 as byte, readLengthField(2, 1, 2)

        // No matches closure — binary variants are identified by frameByteHint only.

        decode { buf, ctx ->

            buf.skip(3)                             // 0x54 0x68 header + size byte

            int power = buf.readUByte()             // 0 = location frame; >0 = heartbeat info
            int gsm   = buf.readUByte()

            // IMEI is packed as 8 bytes with the leading nibble always 0.
            // Reading as hex gives 16 chars; drop the first to get the 15-digit IMEI.
            String imei = buf.readHex(8).substring(1)

            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.set(Position.KEY_INDEX, buf.readUShort())

            int type = buf.readUByte()

            if (type == 0x1A) {                     // MSG_HEARTBEAT

                ctx.lastLocation(pos)
                pos.set(Position.KEY_POWER, power)
                pos.set(Position.KEY_RSSI,  gsm)
                ctx.ack([0x54, 0x68, 0x1A, 0x0D, 0x0A] as byte[])

            } else if (type == 0x10) {              // MSG_DATA — GPS position

                pos.time = new DateBuilder()
                        .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .getDate()

                // Coordinates stored as fixed-point: actual = raw / (60 * 30000)
                double lat = buf.readUInt() / (60.0 * 30000.0)
                double lon = buf.readUInt() / (60.0 * 30000.0)

                pos.speed  = UnitsConverter.knotsFromKph(buf.readUByte())
                pos.course = buf.readUShort()

                buf.skip(3)                         // reserved

                // Flags: bit 0 = valid fix, bit 1 = latitude positive, bit 2 = longitude positive
                int flags = (int) buf.readUInt()
                pos.valid = checkBit(flags, 0)
                if (!checkBit(flags, 1)) lat = -lat
                if (!checkBit(flags, 2)) lon = -lon
                pos.latitude  = lat
                pos.longitude = lon

            } else if (type == 0x1C) {             // MSG_RESPONSE — command result text

                ctx.lastLocation(pos)
                int len = buf.readUByte()
                pos.set(Position.KEY_RESULT, buf.readString(len))

            } else {
                return null
            }

            return pos
        }
    }
}
