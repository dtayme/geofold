// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * SkyPatrol binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/skypatrol/
 *
 * Supports documented API 5 binary position reports with embedded reporting
 * masks, plus the protocol-level skypatrol.mask fallback used by devices that
 * omit the mask from each packet.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

def bit = { long value, int index -> (value & (1L << index)) != 0 }
def from4 = { int value -> value >> 4 }

def coordinate = { long raw ->
    int sign = 1
    if (raw > 0x7fffffffL) {
        sign = -1
        raw = 0xffffffffL - raw
    }
    long degrees = (long) (raw / 1000000L)
    double minutes = (raw % 1000000L) / 10000.0
    sign * (degrees + minutes / 60.0)
}

def medium = { buf ->
    int value = (buf.readUByte() << 16) | (buf.readUByte() << 8) | buf.readUByte()
    (value & 0x800000) != 0 ? value - 0x1000000 : value
}

protocol("skypatrol") {

    port 5021
    transport 'udp'

    variant("main") {

        decode { buf, ctx ->
            int apiNumber = buf.readUShort()
            int commandType = buf.readUByte()
            int messageType = from4(buf.readUByte())
            long mask = ctx.configInt('mask', 0)
            if (buf.readUByte() == 4) {
                mask = buf.readUInt()
            }

            if (!(apiNumber == 5 && commandType == 2 && messageType == 1 && bit(mask, 0))) return null

            def pos = ctx.newPosition()
            if (bit(mask, 1)) pos.set(Position.KEY_STATUS, buf.readUInt())

            String id
            if (bit(mask, 23)) {
                id = buf.readString(8).trim()
            } else if (bit(mask, 2)) {
                id = buf.readString(22).trim()
            } else {
                return null
            }
            def session = ctx.session(id)
            if (!session) return null
            pos.deviceId = session.deviceId

            if (bit(mask, 3)) pos.set(Position.PREFIX_IO + 1, buf.readUShort())
            if (bit(mask, 4)) pos.set(Position.PREFIX_ADC + 1, buf.readUShort())
            if (bit(mask, 5)) pos.set(Position.PREFIX_ADC + 2, buf.readUShort())
            if (bit(mask, 7)) buf.readUByte()

            def date = new DateBuilder()
            if (bit(mask, 8)) date.setDateReverse(buf.readUByte(), buf.readUByte(), buf.readUByte())
            if (bit(mask, 9)) pos.valid = buf.readUByte() == 1
            if (bit(mask, 10)) pos.latitude = coordinate(buf.readUInt())
            if (bit(mask, 11)) pos.longitude = coordinate(buf.readUInt())
            if (bit(mask, 12)) pos.speed = buf.readUShort() / 10.0
            if (bit(mask, 13)) pos.course = buf.readUShort() / 10.0
            if (bit(mask, 14)) date.setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
            pos.time = date.getDate()

            if (bit(mask, 15)) pos.altitude = medium(buf)
            if (bit(mask, 16)) pos.set(Position.KEY_SATELLITES, buf.readUByte())
            if (bit(mask, 17)) pos.set(Position.KEY_BATTERY, buf.readUShort())
            if (bit(mask, 20)) pos.set(Position.KEY_ODOMETER_TRIP, buf.readUInt())
            if (bit(mask, 21)) pos.set(Position.KEY_ODOMETER, buf.readUInt())
            if (bit(mask, 22)) buf.skip(6)
            if (bit(mask, 24)) pos.set(Position.KEY_POWER, buf.readUShort() / 1000.0)
            if (bit(mask, 25)) buf.skip(18)
            if (bit(mask, 26)) buf.skip(54)
            if (bit(mask, 28)) pos.set(Position.KEY_INDEX, buf.readUShort())

            return pos
        }
    }
}
