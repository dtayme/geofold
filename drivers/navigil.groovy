// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Navigil GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/navigil/ (Java reference)
 *
 * Binary TCP protocol on port 5025.  Frames optionally start with a 4-byte
 * preamble 0x2477F5F6 (LE); the 2-byte total length field is always at
 * byte-offset 6 from the start of the full (pre-strip) frame.
 *
 * Header structure (20 bytes, after preamble stripping):
 *   version(1)  vid(1)  seqNum(2LE)  msgId(2LE)  length(2LE)
 *   flags(2LE)  checksum(2LE)  deviceId(4LE)  timestamp(4LE)
 *
 * An ACK (msgId=255) is sent for every frame whose flags bit 0 is clear.
 * The ACK carries a CRC-16/CCITT-FALSE of the 4-byte data section.
 *
 * GPS timestamps are GPS epoch (not UTC); subtract 25 leap-seconds for UTC.
 *
 * Supported message types:
 *   8  MSG_UNIT_REPORT
 *   12 MSG_TG2_REPORT
 *   13 MSG_POSITION_REPORT       (3-byte MediumLE coordinates / 50000)
 *   15 MSG_POSITION_REPORT_2     (4-byte IntLE coordinates / 10000000)
 *   17 MSG_SNAPSHOT4
 *   18 MSG_TRACKING_DATA
 */

import org.traccar.driver.BufReader
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.nio.ByteBuffer

def LEAP_SECONDS_DELTA = 25

def senderSeq = 1

def readMediumLE = { buf ->
    int b0 = buf.readUByte()
    int b1 = buf.readUByte()
    int b2 = buf.readByte()   // signed — provides correct sign extension
    return (b2 << 16) | (b1 << 8) | b0
}

def convertTimestamp = { long ts ->
    return new Date((ts - LEAP_SECONDS_DELTA) * 1000L)
}

def buildAck = { int seqNum ->
    byte[] data = new byte[4]
    data[0] = (byte)(seqNum & 0xFF)
    data[1] = (byte)((seqNum >> 8) & 0xFF)
    data[2] = 0
    data[3] = 0

    int crc = Checksum.crc16(Checksum.CRC16_CCITT_FALSE, ByteBuffer.wrap(data))
    int now  = (int)(System.currentTimeMillis() / 1000) + LEAP_SECONDS_DELTA

    byte[] ack = new byte[24]
    ack[0]  = 1
    ack[1]  = 0
    ack[2]  = (byte)(senderSeq & 0xFF)
    ack[3]  = (byte)((senderSeq >> 8) & 0xFF)
    ack[4]  = (byte) 255            // MSG_ACKNOWLEDGEMENT
    ack[5]  = 0
    ack[6]  = 24                    // total length = 24
    ack[7]  = 0
    ack[8]  = 0                     // flags
    ack[9]  = 0
    ack[10] = (byte)(crc & 0xFF)
    ack[11] = (byte)((crc >> 8) & 0xFF)
    ack[12] = 0                     // deviceId = 0
    ack[13] = 0
    ack[14] = 0
    ack[15] = 0
    ack[16] = (byte)(now & 0xFF)
    ack[17] = (byte)((now >> 8) & 0xFF)
    ack[18] = (byte)((now >> 16) & 0xFF)
    ack[19] = (byte)((now >> 24) & 0xFF)
    ack[20] = data[0]
    ack[21] = data[1]
    ack[22] = data[2]
    ack[23] = data[3]

    senderSeq++
    return ack
}

protocol("navigil") {

    port 5025

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 20) return null
            boolean hasPreamble = (fb.getUIntLE(0) == 0x2477F5F6L)
            int length = fb.getUShortLE(6)
            if (fb.readableBytes() < length) return null
            if (hasPreamble) {
                return frameResult(length, fb.bytes(4, length - 4))
            }
            return length
        }

        decode { msg, ctx ->
            def buf = msg as BufReader

            buf.skip(2)                           // version, vid
            int seqNum  = buf.readUShortLE()
            int msgId   = buf.readUShortLE()
            buf.skip(2)                           // length
            int flags   = buf.readUShortLE()
            buf.skip(2)                           // checksum

            String deviceId = String.valueOf(buf.readUIntLE())
            def session = ctx.session(deviceId)
            if (!session) return null

            long timestamp = buf.readUIntLE()
            Date time = convertTimestamp(timestamp)

            if ((flags & 0x1) == 0) {
                ctx.ack(buildAck(seqNum))
            }

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.set(Position.KEY_INDEX, seqNum)

            switch (msgId) {

                case 8:  // MSG_UNIT_REPORT
                    pos.valid = true
                    buf.skip(2)                   // report trigger
                    pos.set(Position.KEY_FLAGS,              buf.readUShortLE())
                    pos.latitude  = buf.readIntLE() / 10000000.0
                    pos.longitude = buf.readIntLE() / 10000000.0
                    pos.altitude  = buf.readUShortLE()
                    pos.set(Position.KEY_SATELLITES,         buf.readUShortLE())
                    pos.set(Position.KEY_SATELLITES_VISIBLE, buf.readUShortLE())
                    pos.set('gpsAntennaState',               buf.readUShortLE())
                    pos.speed  = buf.readUShortLE() * 0.194384
                    pos.course = buf.readUShortLE()
                    pos.set(Position.KEY_ODOMETER,           buf.readUIntLE())
                    pos.set(Position.KEY_DISTANCE,           buf.readUIntLE())
                    pos.set(Position.KEY_BATTERY,            buf.readUShortLE() / 1000.0)
                    pos.set(Position.KEY_CHARGE,             buf.readUShortLE())
                    pos.time = convertTimestamp(buf.readUIntLE())
                    return pos

                case 12: // MSG_TG2_REPORT
                    pos.valid = true
                    buf.skip(2)                   // report trigger
                    buf.skip(2)                   // reserved + assisted GPS age
                    pos.time = convertTimestamp(buf.readUIntLE())
                    pos.latitude  = buf.readIntLE() / 10000000.0
                    pos.longitude = buf.readIntLE() / 10000000.0
                    pos.altitude  = buf.readUShortLE()
                    pos.set(Position.KEY_SATELLITES,         buf.readUByte())
                    pos.set(Position.KEY_SATELLITES_VISIBLE, buf.readUByte())
                    pos.speed  = buf.readUShortLE() * 0.194384
                    pos.course = buf.readUShortLE()
                    pos.set(Position.KEY_ODOMETER,   buf.readUIntLE())
                    pos.set('maximumSpeed',          buf.readUShortLE())
                    pos.set('minimumSpeed',          buf.readUShortLE())
                    pos.set(Position.PREFIX_IO + 1,  buf.readUShortLE())
                    pos.set(Position.PREFIX_IO + 2,  buf.readUShortLE())
                    pos.set(Position.PREFIX_IO + 3,  buf.readUShortLE())
                    pos.set(Position.KEY_BATTERY,    buf.readUShortLE() / 1000.0)
                    return pos

                case 13: // MSG_POSITION_REPORT — 3-byte MediumLE coordinates
                    pos.time = time
                    pos.latitude  = readMediumLE(buf) / 50000.0
                    pos.longitude = readMediumLE(buf) / 50000.0
                    pos.speed  = UnitsConverter.knotsFromKph(buf.readUByte())
                    pos.course = buf.readUByte() * 2.0
                    int posFlags = buf.readUByte()
                    pos.valid = (posFlags & 0x80) == 0x80 && (posFlags & 0x40) == 0x40
                    return pos

                case 15: // MSG_POSITION_REPORT_2
                    pos.time = time
                    pos.latitude  = buf.readIntLE() / 10000000.0
                    pos.longitude = buf.readIntLE() / 10000000.0
                    buf.skip(1)                   // report trigger
                    pos.speed  = UnitsConverter.knotsFromKph(buf.readUByte())
                    int posFlags2 = buf.readUByte()
                    pos.valid = (posFlags2 & 0x80) == 0x80 && (posFlags2 & 0x40) == 0x40
                    pos.set(Position.KEY_SATELLITES, buf.readUByte())
                    pos.set(Position.KEY_ODOMETER,   buf.readUIntLE())
                    return pos

                case 17: // MSG_SNAPSHOT4
                    buf.skip(4)                   // trigger, fix src, fix quality, GNSS age
                    long s4flags = buf.readUIntLE()
                    pos.valid = (s4flags & 0x0400) == 0x0400
                    pos.time = convertTimestamp(buf.readUIntLE())
                    pos.latitude  = buf.readIntLE() / 10000000.0
                    pos.longitude = buf.readIntLE() / 10000000.0
                    pos.altitude  = buf.readUShortLE()
                    pos.set(Position.KEY_SATELLITES,         buf.readUByte())
                    pos.set(Position.KEY_SATELLITES_VISIBLE, buf.readUByte())
                    pos.speed  = buf.readUShortLE() * 0.194384
                    pos.course = buf.readUShortLE() / 10.0
                    pos.set('maximumSpeed',          buf.readUByte())
                    pos.set('minimumSpeed',          buf.readUByte())
                    pos.set(Position.KEY_ODOMETER,   buf.readUIntLE())
                    pos.set(Position.PREFIX_IO + 1,  buf.readUByte())
                    pos.set(Position.PREFIX_IO + 2,  buf.readUByte())
                    pos.set(Position.KEY_BATTERY,    buf.readUShortLE() / 1000.0)
                    return pos

                case 18: // MSG_TRACKING_DATA
                    pos.time = time
                    buf.skip(1)                   // tracking mode
                    int tdFlags = buf.readUByte()
                    pos.valid = (tdFlags & 0x01) == 0x01
                    buf.skip(2)                   // duration
                    pos.latitude  = buf.readIntLE() / 10000000.0
                    pos.longitude = buf.readIntLE() / 10000000.0
                    pos.speed  = UnitsConverter.knotsFromKph(buf.readUByte())
                    pos.course = buf.readUByte() * 2.0
                    pos.set(Position.KEY_SATELLITES, buf.readUByte())
                    pos.set(Position.KEY_BATTERY,    buf.readUShortLE() / 1000.0)
                    pos.set(Position.KEY_ODOMETER,   buf.readUIntLE())
                    return pos

                default:
                    return null
            }
        }
    }
}
