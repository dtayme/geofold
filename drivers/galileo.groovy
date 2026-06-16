// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Galileo GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/galileo/ (Java reference)
 *
 * Binary, TLV-tag-based protocol on port 5034:
 *   header(1) ...
 *
 * Frame headers:
 *   0x01  position packet (or, when followed by the fixed 3-byte
 *         0x01001c marker, an Iridium satellite position packet)
 *   0x07  photo chunk — unsupported, see below
 *   0x08  compressed (minimal data set) batch positions
 *
 * Position packets (0x01, non-Iridium) contain a little-endian length field
 * followed by a sequence of single-byte tags, each consuming a fixed or
 * tag-specific number of following bytes. A repeated tag within the same
 * frame starts a new position record (the device packs multiple fixes into
 * one TCP segment). The frame ends with a 2-byte checksum that is echoed
 * back in a 3-byte ACK (0x02 + checksum, little-endian).
 *
 * Iridium packets (0x01 + 0x01001c marker) carry a single fixed-layout
 * position followed by the same tag scheme inside a length-prefixed data
 * block, optionally using a compact "minimal data set" bit-packed encoding
 * (controlled by the protocol's `extended` config flag) instead of the
 * normal tag loop.
 *
 * Photo packets (0x07) require saving binary JPEG chunks to a media file;
 * the driver DSL has no equivalent of Java's writeMediaFile(), so —
 * consistent with the gps103/fifotrack migrations — these packets are
 * simply not decoded (return null). Photo capture isn't in the supported
 * command list, so devices won't normally send these.
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitBuffer
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.Command
import org.traccar.model.Position

import io.netty.buffer.Unpooled

import java.util.Calendar
import java.util.TimeZone

def TAG_LENGTH = [:]

def addTagLength = { int length, List tags -> tags.each { TAG_LENGTH[it] = length } }

addTagLength(1, [
        0x01, 0x02, 0x35, 0x43, 0xc4, 0xc5, 0xc6, 0xc7,
        0xc8, 0xc9, 0xca, 0xcb, 0xcc, 0xcd, 0xce, 0xcf,
        0xd0, 0xd1, 0xd2, 0xd5, 0x88, 0x89, 0x8a, 0x8b, 0x8c,
        0xa0, 0xaf, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6,
        0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae])
addTagLength(2, [
        0x04, 0x10, 0x34, 0x40, 0x41, 0x42, 0x45, 0x46,
        0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x60, 0x61,
        0x62, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76,
        0x77, 0xb0, 0xb1, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
        0xb7, 0xb8, 0xb9, 0xd6, 0xd7, 0xd8, 0xd9, 0xda])
addTagLength(3, [
        0x63, 0x64, 0x6f, 0x5d, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0xfa,
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87])
addTagLength(4, [
        0x20, 0x33, 0x44, 0x90, 0xc0, 0xc2, 0xc3, 0xd3,
        0xd4, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xf0, 0xf9,
        0x5a, 0x47, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
        0xf7, 0xf8, 0xe2, 0xe9])
TAG_LENGTH[0x5b] = 7 // variable length
TAG_LENGTH[0x5c] = 68
TAG_LENGTH[0xfd] = 8

def getTagLength = { int tag ->
    Integer length = TAG_LENGTH[tag]
    if (length == null) {
        throw new IllegalArgumentException(String.format("Unknown tag: 0x%02x", tag))
    }
    length
}

def decodeTagOther = { Position pos, BufReader buf, int tag ->
    switch (tag) {
        case 0x01: pos.set(Position.KEY_VERSION_HW, buf.readUByte()); break
        case 0x02: pos.set(Position.KEY_VERSION_FW, buf.readUByte()); break
        case 0x04: pos.set("deviceId", buf.readUShortLE()); break
        case 0x10: pos.set(Position.KEY_INDEX, buf.readUShortLE()); break
        case 0x20: pos.time = new Date(buf.readUIntLE() * 1000); break
        case 0x33:
            pos.speed = UnitsConverter.knotsFromKph(buf.readUShortLE() / 10.0)
            pos.course = buf.readUShortLE() / 10.0
            break
        case 0x34: pos.altitude = buf.readShortLE(); break
        case 0x35: pos.set(Position.KEY_HDOP, buf.readUByte() / 10.0); break
        case 0x40: pos.set(Position.KEY_STATUS, buf.readUShortLE()); break
        case 0x41: pos.set(Position.KEY_POWER, buf.readUShortLE() / 1000.0); break
        case 0x42: pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 1000.0); break
        case 0x43: pos.set(Position.KEY_DEVICE_TEMP, buf.readByte()); break
        case 0x44: pos.set(Position.KEY_ACCELERATION, buf.readUIntLE()); break
        case 0x45: pos.set(Position.KEY_OUTPUT, buf.readUShortLE()); break
        case 0x46: pos.set(Position.KEY_INPUT, buf.readUShortLE()); break
        case 0x48: pos.set("statusExtended", buf.readUShortLE()); break
        case 0x58: pos.set("rs2320", buf.readUShortLE()); break
        case 0x59: pos.set("rs2321", buf.readUShortLE()); break
        case 0x90: pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(buf.readUIntLE())); break
        case 0xc0: pos.set("fuelTotal", buf.readUIntLE() * 0.5); break
        case 0xc1:
            pos.set(Position.KEY_FUEL, buf.readUByte() * 0.4)
            pos.set(Position.PREFIX_TEMP + 1, buf.readUByte() - 40)
            pos.set(Position.KEY_RPM, buf.readUShortLE() * 0.125)
            break
        case 0xc2: pos.set("canB0", buf.readUIntLE()); break
        case 0xc3: pos.set("canB1", buf.readUIntLE()); break
        case 0xd4: pos.set(Position.KEY_ODOMETER, buf.readUIntLE()); break
        case 0xe0: pos.set(Position.KEY_INDEX, buf.readUIntLE()); break
        case 0xe1: pos.set(Position.KEY_RESULT, buf.readString(buf.readUByte())); break
        case 0xea: pos.set("userDataArray", buf.readHex(buf.readUByte())); break
        case 0xfe: buf.skip(buf.readUShortLE()); break
        default: buf.skip(getTagLength(tag))
    }
}

def decodeTag = { Position pos, BufReader buf, int tag ->
    if (tag >= 0x50 && tag <= 0x57) {
        pos.set(Position.PREFIX_ADC + (tag - 0x50), buf.readUShortLE())
    } else if (tag >= 0x60 && tag <= 0x62) {
        pos.set("fuel" + (tag - 0x60), buf.readUShortLE())
    } else if (tag >= 0xa0 && tag <= 0xaf) {
        pos.set("can8BitR" + (tag - 0xa0 + 15), buf.readUByte())
    } else if (tag >= 0xb0 && tag <= 0xb9) {
        pos.set("can16BitR" + (tag - 0xb0 + 5), buf.readUShortLE())
    } else if (tag >= 0xc4 && tag <= 0xd2) {
        pos.set("can8BitR" + (tag - 0xc4), buf.readUByte())
    } else if (tag >= 0xd6 && tag <= 0xda) {
        pos.set("can16BitR" + (tag - 0xd6), buf.readUShortLE())
    } else if (tag >= 0xdb && tag <= 0xdf) {
        pos.set("can32BitR" + (tag - 0xdb), buf.readUIntLE())
    } else if (tag >= 0xe2 && tag <= 0xe9) {
        pos.set("userData" + (tag - 0xe2), buf.readUIntLE())
    } else if (tag >= 0xf0 && tag <= 0xf9) {
        pos.set("can32BitR" + (tag - 0xf0 + 5), buf.readUIntLE())
    } else {
        decodeTagOther(pos, buf, tag)
    }
}

def decodeMinimalDataSet = { Position pos, BufReader buf ->
    def bits = new BitBuffer(Unpooled.wrappedBuffer(buf.readBytes(10)))
    bits.readUnsigned(1)

    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.set(Calendar.DAY_OF_YEAR, 1)
    calendar.set(Calendar.HOUR_OF_DAY, calendar.getActualMinimum(Calendar.HOUR_OF_DAY))
    calendar.set(Calendar.MINUTE, calendar.getActualMinimum(Calendar.MINUTE))
    calendar.set(Calendar.SECOND, calendar.getActualMinimum(Calendar.SECOND))
    calendar.set(Calendar.MILLISECOND, calendar.getActualMinimum(Calendar.MILLISECOND))
    calendar.add(Calendar.SECOND, bits.readUnsigned(25))
    pos.time = calendar.getTime()

    pos.valid = bits.readUnsigned(1) == 0
    pos.longitude = 360 * bits.readUnsigned(22) / 4194304.0 - 180
    pos.latitude = 180 * bits.readUnsigned(21) / 2097152.0 - 90
    if (bits.readUnsigned(1) > 0) {
        pos.addAlarm(ALARM_GENERAL)
    }
}

def decodeCompressedTags = { Position pos, BufReader buf ->
    int[] tags = new int[BitUtil.to(buf.readUByte(), 8)]
    for (int i = 0; i < tags.length; i++) {
        tags[i] = buf.readUByte()
    }
    tags.each { tag -> decodeTag(pos, buf, tag) }
}

def sendResponse = { ctx, int header, int checksum ->
    byte[] reply = new byte[3]
    reply[0] = (byte) header
    reply[1] = (byte) (checksum & 0xff)
    reply[2] = (byte) ((checksum >> 8) & 0xff)
    ctx.ack(reply)
}

def decodeIridiumPosition = { BufReader buf, ctx ->
    buf.readUShort() // length

    buf.skip(3) // identification header
    buf.readUInt() // index

    def session = ctx.session(buf.readString(15))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    buf.readUByte() // session status
    buf.skip(4) // reserved
    pos.time = new Date(buf.readUInt() * 1000)

    buf.skip(3) // coordinates header
    int flags = buf.readUByte()
    double latitude = buf.readUByte() + buf.readUShort() / 60000.0
    double longitude = buf.readUByte() + buf.readUShort() / 60000.0
    pos.latitude = BitUtil.check(flags, 1) ? -latitude : latitude
    pos.longitude = BitUtil.check(flags, 0) ? -longitude : longitude
    buf.readUInt() // accuracy

    buf.readUByte() // data tag header
    def data = buf.slice(buf.readUShort())

    if (ctx.configBoolean("extended", false)) {
        decodeMinimalDataSet(pos, data)
        decodeCompressedTags(pos, data)
    } else {
        while (data.isReadable()) {
            int tag = data.readUByte()
            if (tag == 0x30) {
                pos.valid = (data.readUByte() & 0xf0) == 0x00
                pos.latitude = data.readIntLE() / 1000000.0
                pos.longitude = data.readIntLE() / 1000000.0
            } else {
                decodeTag(pos, data, tag)
            }
        }
    }

    return pos
}

def decodePositions = { BufReader buf, ctx ->
    int contentLength = buf.readUShortLE() & 0x7fff
    int targetRemaining = buf.readableBytes() - contentLength

    def positions = []
    def tags = [] as Set
    boolean hasLocation = false

    def session = null
    def pos = ctx.newPosition()

    while (buf.readableBytes() > targetRemaining) {

        int tag = buf.readUByte()
        if (tags.contains(tag)) {
            if (hasLocation && pos.fixTime != null) {
                positions << pos
            }
            tags.clear()
            hasLocation = false
            pos = ctx.newPosition()
        }
        tags << tag

        if (tag == 0x03) {
            session = ctx.session(buf.readString(15))
        } else if (tag == 0x30) {
            hasLocation = true
            pos.valid = (buf.readUByte() & 0xf0) == 0x00
            pos.latitude = buf.readIntLE() / 1000000.0
            pos.longitude = buf.readIntLE() / 1000000.0
        } else {
            decodeTag(pos, buf, tag)
        }
    }

    if (!session) {
        session = ctx.session()
        if (!session) return
    }

    if (hasLocation && pos.fixTime != null) {
        positions << pos
    } else if (pos.hasAttribute(Position.KEY_RESULT)) {
        pos.deviceId = session.deviceId
        ctx.lastLocation(pos)
        positions << pos
    }

    sendResponse(ctx, 0x02, buf.readUShortLE())

    positions.each { p ->
        p.deviceId = session.deviceId
        ctx.emit(p)
    }
}

def decodeCompressedPositions = { BufReader buf, ctx ->
    buf.readUShortLE() // length

    def session = ctx.session()
    if (!session) return

    while (buf.readableBytes() > 2) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId

        decodeMinimalDataSet(pos, buf)
        decodeCompressedTags(pos, buf)

        ctx.emit(pos)
    }

    sendResponse(ctx, 0x02, buf.readUShortLE())
}

def encodeText = { String uniqueId, String text ->
    byte[] body = bytes {
        writeByte 0x01
        writeShortLE uniqueId.length() + text.length() + 11

        writeByte 0x03 // imei tag
        writeBytes uniqueId.getBytes("US-ASCII")

        writeByte 0x04 // device id tag
        writeShortLE 0 // not needed if imei provided

        writeByte 0xE0 // index tag
        writeIntLE 0 // index

        writeByte 0xE1 // command text tag
        writeByte text.length()
        writeBytes text.getBytes("US-ASCII")
    }
    int checksum = crc16Modbus(body)
    byte[] result = new byte[body.length + 2]
    System.arraycopy(body, 0, result, 0, body.length)
    result[body.length] = (byte) (checksum & 0xff)
    result[body.length + 1] = (byte) ((checksum >> 8) & 0xff)
    return result
}

protocol("galileo") {

    port 5034
    commands TYPE_CUSTOM, TYPE_OUTPUT_CONTROL

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 6) return null

            int length
            if (fb.getUByte(0) == 0x01 && (fb.getUInt(3) >>> 8) == 0x01001cL) {
                length = 3 + fb.getUShort(1)
            } else {
                length = 5 + (fb.getUShortLE(1) & 0x7fff)
            }

            if (fb.readableBytes() >= length) {
                return length
            }
            return null
        }

        decode { msg, ctx ->
            def buf = msg as BufReader

            int header = buf.readUByte()
            if (header == 0x01) {
                if ((buf.getUInt(2) >>> 8) == 0x01001cL) {
                    return decodeIridiumPosition(buf, ctx)
                } else {
                    decodePositions(buf, ctx)
                }
            } else if (header == 0x07) {
                return null
            } else if (header == 0x08) {
                decodeCompressedPositions(buf, ctx)
            }

            return null
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return encodeText(ctx.deviceId(), ctx.data())
                case TYPE_OUTPUT_CONTROL:
                    return encodeText(ctx.deviceId(),
                            "Out " + cmd.getInteger(Command.KEY_INDEX) + "," + ctx.data())
            }
            return null
        }
    }
}
