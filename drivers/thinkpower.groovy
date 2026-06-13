// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * ThinkPower binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/thinkpower/
 *
 * Supports login, heartbeat, and record reports with binary acknowledgements.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Date

def MSG_LOGIN_REQUEST = 0x01
def MSG_LOGIN_RESPONSE = 0x02
def MSG_HEARTBEAT_REQUEST = 0x03
def MSG_HEARTBEAT_RESPONSE = 0x04
def MSG_RECORD_REPORT = 0x05
def MSG_RECORD_RESPONSE = 0x06

def signedMagnitudeInt = { buf ->
    long value = buf.readUInt()
    int result = (int) (value & 0x7fffffffL)
    (value & 0x80000000L) != 0 ? -result : result
}

def response = { int type, int index, byte[] content ->
    byte[] packet = bytes {
        writeByte type
        writeByte index
        writeShort content.length
        writeBytes content
    }
    int crc = crc16CcittFalse(packet)
    bytes {
        writeBytes packet
        writeShort crc
    }
}

def decodeValue = { pos, int type, buf ->
    switch (type) {
        case 0x01:
            pos.valid = true
            pos.latitude = signedMagnitudeInt(buf) / 10000000.0
            pos.longitude = signedMagnitudeInt(buf) / 10000000.0
            pos.speed = UnitsConverter.knotsFromKph(buf.readUShort() / 10.0)
            pos.course = buf.readUShort() / 100.0
            break
        case 0x02:
            pos.valid = buf.readUByte() > 0
            break
        case 0x03:
            buf.skip(3)
            break
        case 0x06:
        case 0x07:
        case 0x08:
            buf.skip(2)
            break
        case 0x09:
        case 0x0A:
            buf.skip(1)
            break
        case 0x10:
            if (buf.readUByte() > 0) pos.addAlarm(ALARM_SOS)
            break
        case 0x12:
            pos.set(Position.KEY_BATTERY, buf.readUShort() / 10.0)
            break
        case 0x13:
            if (buf.readUByte() > 0) pos.addAlarm(ALARM_LOW_BATTERY)
            break
        case 0x16:
            buf.skip(2)
            break
        case 0x17:
            buf.skip(1)
            break
        case 0x18:
            buf.skip(2)
            break
        case 0x19:
            buf.skip(1)
            break
        case 0x50:
            if (buf.readUByte() > 0) pos.addAlarm(ALARM_REMOVING)
            break
        case 0x51:
            if (buf.readUByte() > 0) pos.addAlarm(ALARM_TAMPERING)
            break
        default:
            buf.skip(Math.max(0, buf.remaining() - 2))
            break
    }
}

def decodeThinkPower
decodeThinkPower = { buf, ctx ->
    int type = buf.readUByte()
    int index = buf.readUByte()
    buf.readUShort()

    if (type == MSG_LOGIN_REQUEST) {
        buf.skip(2)
        String id = buf.readString(buf.readUByte())
        ctx.ack(response(MSG_LOGIN_RESPONSE, index, [ctx.session(id) ? 0 : 4] as byte[]))
        return null
    }

    if (type == MSG_HEARTBEAT_REQUEST) {
        ctx.ack(response(MSG_HEARTBEAT_RESPONSE, index, new byte[0]))
        return null
    }

    if (type == MSG_RECORD_REPORT) {
        def session = ctx.session()
        if (!session) return null

        buf.readUByte()
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        pos.time = new Date(buf.readUInt() * 1000L)
        while (buf.remaining() > 2) {
            decodeValue(pos, buf.readUByte(), buf)
        }
        ctx.ack(response(MSG_RECORD_RESPONSE, index, new byte[0]))
        return pos
    }

    null
}

protocol("thinkpower") {

    port 5228

    ['login': 0x01, 'heartbeat': 0x03, 'record': 0x05].each { name, hint ->
        variant(name) {
            maxFrameLength 1024
            frame hint as byte, readLengthField(2, 2, 2)
            decode decodeThinkPower
        }
    }
}
