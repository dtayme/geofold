// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * ADM binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/adm/
 *
 * Supports IMEI/session frames, ADM5 data records with optional sensor blocks,
 * and command response result frames.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Date

def CMD_RESPONSE_SIZE = 0x84
def MSG_IMEI = 0x03

def bit = { int value, int index -> (value & (1 << index)) != 0 }
def fromBit = { long value, int index -> value >> index }
def toBit = { int value, int index -> value & ((1 << index) - 1) }
def readFloatLE = { buf -> Float.intBitsToFloat(buf.readIntLE()) }

def decodeData
decodeData = { buf, int type, ctx ->
    def session = ctx.session()
    if (!session) return null

    if (toBit(type, 2) != 0) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    pos.set(Position.KEY_VERSION_FW, buf.readUByte())
    pos.set(Position.KEY_INDEX, buf.readUShortLE())

    int status = buf.readUShortLE()
    pos.set(Position.KEY_STATUS, status)
    pos.valid = !bit(status, 5)
    pos.latitude = readFloatLE(buf)
    pos.longitude = readFloatLE(buf)
    pos.course = buf.readUShortLE() / 10.0
    pos.speed = UnitsConverter.knotsFromKph(buf.readUShortLE() / 10.0)
    pos.set(Position.KEY_ACCELERATION, buf.readUByte() / 10.0)
    pos.altitude = buf.readShortLE()
    pos.set(Position.KEY_HDOP, buf.readUByte() / 10.0)
    pos.set(Position.KEY_SATELLITES, buf.readUByte() & 0x0f)
    pos.time = new Date(buf.readUIntLE() * 1000)
    pos.set(Position.KEY_POWER, buf.readUShortLE() / 1000.0)
    pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 1000.0)

    if (bit(type, 2)) {
        buf.readUByte()
        buf.readUByte()
        int out = buf.readUByte()
        for (int i = 0; i <= 3; i++) pos.set(Position.PREFIX_OUT + (i + 1), bit(out, i) ? 1 : 0)
        buf.readUByte()
    }

    if (bit(type, 3)) {
        for (int i = 1; i <= 6; i++) pos.set(Position.PREFIX_ADC + i, buf.readUShortLE() / 1000.0)
    }

    if (bit(type, 4)) {
        for (int i = 1; i <= 2; i++) pos.set(Position.PREFIX_COUNT + i, buf.readUIntLE())
    }

    if (bit(type, 5)) {
        for (int i = 1; i <= 3; i++) pos.set("fuel" + i, buf.readUShortLE())
        for (int i = 1; i <= 3; i++) pos.set(Position.PREFIX_TEMP + i, buf.readUByte())
    }

    if (bit(type, 6)) {
        int length = buf.readUByte()
        int target = buf.remaining() - Math.max(0, length - 1)
        while (buf.remaining() > target) {
            int mask = buf.readUByte()
            if (buf.remaining() <= target) break
            int valueSize = switch ((int) fromBit(mask, 6)) {
                case 3 -> 8
                case 2 -> 4
                case 1 -> 2
                default -> 1
            }
            if (buf.remaining() - target < valueSize) break
            long value = switch ((int) fromBit(mask, 6)) {
                case 3 -> buf.readLongLE()
                case 2 -> buf.readUIntLE()
                case 1 -> buf.readUShortLE()
                default -> buf.readUByte()
            }
            int index = toBit(mask, 6)
            switch (index) {
                case 1 -> pos.set(Position.PREFIX_TEMP + 1, value)
                case 2 -> pos.set(Position.KEY_HUMIDITY, value)
                case 3 -> pos.set("illumination", value)
                case 4 -> pos.set(Position.KEY_BATTERY, value)
                default -> pos.set("can" + index, value)
            }
        }
    }

    if (bit(type, 7)) {
        pos.set(Position.KEY_ODOMETER, buf.readUIntLE())
    }

    pos
}

protocol("adm") {
    port 5092
    commands TYPE_CUSTOM, TYPE_GET_DEVICE_STATUS

    variant("main") {
        frame { fb ->
            if (fb.readableBytes() < 3) return null
            int length
            if (Character.isDigit((char) fb.getUByte(0))) {
                if (fb.readableBytes() < 18) return null
                length = 15 + fb.getUByte(17)
            } else {
                length = fb.getUByte(2)
            }
            fb.readableBytes() >= length ? frameRaw(length) : null
        }

        decode { buf, ctx ->
            if (Character.isDigit((char) buf.getUByte(0))) {
                ctx.session(buf.readString(15, "UTF-8"))
            }

            buf.readUShortLE()
            int size = buf.readUByte()
            if (size != CMD_RESPONSE_SIZE) {
                int type = buf.readUByte()
                if (type == MSG_IMEI) {
                    ctx.session(buf.readString(15, "UTF-8"))
                    return null
                }
                return decodeData(buf, type, ctx)
            }

            def session = ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            ctx.lastLocation(pos)

            byte[] bytes = buf.readBytes(Math.min(CMD_RESPONSE_SIZE - 3, buf.remaining()))
            int end = 0
            while (end < bytes.length && bytes[end] != 0) end++
            pos.set(Position.KEY_RESULT, new String(bytes, 0, end, "UTF-8"))
            pos
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_CUSTOM: return "${ctx.data()}\r\n"
                case TYPE_GET_DEVICE_STATUS: return "STATUS\r\n"
                default: return null
            }
        }
    }
}
