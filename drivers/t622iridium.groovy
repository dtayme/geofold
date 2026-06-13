// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * MEITRACK T622 Iridium binary driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/t622iridium/
 *
 * Supports the configurable payload parameter layout used by T622G-F9 Iridium
 * messages. The parameter list is read from `t622iridium.format`.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Date

def decodeT622 = { buf, ctx ->
    buf.readUByte()
    buf.readUShort()
    buf.readUByte()
    buf.readUShort()
    buf.readUInt()

    String imei = buf.readString(15)
    def session = ctx.session(imei)
    if (!session) return null

    buf.readUByte()
    buf.readUShort()
    buf.readUShort()
    buf.readUInt()
    buf.readUByte()
    buf.readUShort()

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    String format = ctx.configString('format', '')
    if (!format) return pos

    format.split(',').collect { Integer.parseInt(it.trim(), 16) }.each { parameter ->
        switch (parameter) {
            case 0x01: pos.set(Position.KEY_EVENT, buf.readUByte()); break
            case 0x02: pos.latitude = buf.readIntLE() / 1000000.0; break
            case 0x03: pos.longitude = buf.readIntLE() / 1000000.0; break
            case 0x04: pos.time = new Date((buf.readUIntLE() + 946684800L) * 1000L); break
            case 0x05: pos.valid = buf.readUByte() > 0; break
            case 0x06: pos.set(Position.KEY_SATELLITES, buf.readUByte()); break
            case 0x07: pos.set(Position.KEY_RSSI, buf.readUByte()); break
            case 0x08: pos.speed = UnitsConverter.knotsFromKph(buf.readUShortLE()); break
            case 0x09: pos.course = buf.readUShortLE(); break
            case 0x0A: pos.set(Position.KEY_HDOP, buf.readUByte() / 10.0); break
            case 0x0B: pos.altitude = buf.readShortLE(); break
            case 0x0C: pos.set(Position.KEY_ODOMETER, buf.readUIntLE()); break
            case 0x0D: pos.set(Position.KEY_HOURS, buf.readUIntLE() * 1000); break
            case 0x14: pos.set(Position.KEY_OUTPUT, buf.readUByte()); break
            case 0x15: pos.set(Position.KEY_INPUT, buf.readUByte()); break
            case 0x19: pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 100.0); break
            case 0x1A: pos.set(Position.KEY_POWER, buf.readUShortLE() / 100.0); break
            case 0x1B: buf.readUByte(); break
            default: break
        }
    }

    pos
}

protocol("t622iridium") {
    port 5248
    variant("main") {
        frame 0x01 as byte, readLengthField(1, 2)
        decode decodeT622
    }
}
