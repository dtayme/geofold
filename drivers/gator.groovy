// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Gator binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/gator/
 *
 * Supports heartbeat responses, Gator/M588 identifiers, position and alarm
 * frames, terminal status/message passthrough, and documented event alarms.
 */

import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Command
import org.traccar.model.Position

import java.nio.ByteBuffer

def MSG_HEARTBEAT = 0x21
def MSG_POSITION_REQUEST = 0x30
def MSG_OVERSPEED_ALARM = 0x3F
def MSG_RESET_MILEAGE = 0x6B
def MSG_RESTORE_OIL_DUCT = 0x38
def MSG_CLOSE_OIL_DUCT = 0x39
def MSG_POSITION_DATA = 0x80
def MSG_ROLLCALL_RESPONSE = 0x81
def MSG_ALARM_DATA = 0x82
def MSG_BLIND_AREA = 0x8E

def bit = { int value, int index -> (value & (1 << index)) != 0 }

def decodeId = { int b1, int b2, int b3, int b4 ->
    int d1 = 30 + ((b1 >> 7) << 3) + ((b2 >> 7) << 2) + ((b3 >> 7) << 1) + (b4 >> 7)
    int d2 = b1 & 0x7f
    int d3 = b2 & 0x7f
    int d4 = b3 & 0x7f
    int d5 = b4 & 0x7f
    String.format("%02d%02d%02d%02d%02d", d1, d2, d3, d4, d5)
}

def readBcdInt = { buf, int digits ->
    int result = 0
    for (int i = 0; i < digits.intdiv(2); i++) {
        int b = buf.readUByte()
        result = result * 10 + (b >> 4)
        result = result * 10 + (b & 0x0f)
    }
    if (digits % 2 != 0) {
        result = result * 10 + (buf.getUByte(0) >> 4)
    }
    result
}

def readCoordinate = { buf ->
    int b1 = buf.readUByte()
    int b2 = buf.readUByte()
    int b3 = buf.readUByte()
    int b4 = buf.readUByte()

    double value = (b2 & 0x0f) * 10 + (b3 >> 4)
    value += (((b3 & 0x0f) * 10 + (b4 >> 4)) * 10 + (b4 & 0x0f)) / 1000.0
    value /= 60.0
    value += (((b1 >> 4) & 0x07) * 10 + (b1 & 0x0f)) * 10 + (b2 >> 4)
    (b1 & 0x80) != 0 ? -value : value
}

def response = { int type, int checksum ->
    byte[] out = new byte[10]
    out[0] = 0x24
    out[1] = 0x24
    out[2] = MSG_HEARTBEAT
    out[3] = 0
    out[4] = 5
    out[5] = checksum as byte
    out[6] = type as byte
    out[7] = 0
    out[8] = Checksum.xor(ByteBuffer.wrap(out, 2, 6)) as byte
    out[9] = 0x0d
    out
}

def encodeId = { String id ->
    int firstDigit = Integer.parseInt(id.substring(1, 3)) - 30
    [
        (byte) (Integer.parseInt(id.substring(3, 5)) | (((firstDigit >> 3) & 1) << 7)),
        (byte) (Integer.parseInt(id.substring(5, 7)) | (((firstDigit >> 2) & 1) << 7)),
        (byte) (Integer.parseInt(id.substring(7, 9)) | (((firstDigit >> 1) & 1) << 7)),
        (byte) (Integer.parseInt(id.substring(9)) | ((firstDigit & 1) << 7)),
    ] as byte[]
}

def encodeContent = { String id, int type, byte[] content ->
    byte[] device = encodeId(id)
    int contentLength = content != null ? content.length : 0
    byte[] out = new byte[2 + 1 + 2 + device.length + contentLength + 1 + 1]
    int p = 0
    out[p++] = 0x24
    out[p++] = 0x24
    out[p++] = type as byte
    out[p++] = 0
    out[p++] = (4 + 1 + contentLength + 1) as byte
    System.arraycopy(device, 0, out, p, device.length)
    p += device.length
    if (content != null) {
        System.arraycopy(content, 0, out, p, content.length)
        p += content.length
    }
    out[p++] = Checksum.xor(ByteBuffer.wrap(out, 0, p)) as byte
    out[p] = 0x0d
    out
}

protocol("gator") {
    port 5052
    transport 'tcp', 'udp'
    commands TYPE_POSITION_SINGLE, TYPE_ENGINE_RESUME, TYPE_ENGINE_STOP, TYPE_SET_SPEED_LIMIT, TYPE_SET_ODOMETER

    variant("main") {
        frame 0x24 as byte, readLengthField(3, 2, 5)

        decode { buf, ctx ->
            buf.skip(2)
            int type = buf.readUByte()
            buf.readUShort()

            boolean modelM588 = false
            String imei = null
            if (buf.remaining() > 8) {
                imei = buf.getBytes(0, 8).collect { String.format("%02x", it & 0xff) }.join("").substring(0, 15)
                if (imei ==~ /\d+/) {
                    long number = Long.parseLong(imei.substring(0, 14))
                    modelM588 = Checksum.luhn(number) == Long.parseLong(imei.substring(14))
                }
            }

            String[] ids
            if (modelM588) {
                ids = [imei] as String[]
                buf.skip(8)
            } else {
                String id = decodeId(buf.readUByte(), buf.readUByte(), buf.readUByte(), buf.readUByte())
                ids = ["1" + id, id] as String[]
            }

            ctx.ack(response(type, buf.getUByte(buf.remaining() - 2)))

            if (!(type in [MSG_POSITION_DATA, MSG_ROLLCALL_RESPONSE, MSG_ALARM_DATA, MSG_BLIND_AREA])) {
                return null
            }

            def session = ctx.session(ids[0])
            if (!session && ids.length > 1) session = ctx.session(ids[1])
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setYear(readBcdInt(buf, 2))
                    .setMonth(readBcdInt(buf, 2))
                    .setDay(readBcdInt(buf, 2))
                    .setHour(readBcdInt(buf, 2))
                    .setMinute(readBcdInt(buf, 2))
                    .setSecond(readBcdInt(buf, 2))
                    .getDate()

            pos.latitude = readCoordinate(buf)
            pos.longitude = readCoordinate(buf)
            pos.speed = UnitsConverter.knotsFromKph(readBcdInt(buf, 4))
            pos.course = readBcdInt(buf, 4)

            int flags = buf.readUByte()
            pos.valid = (flags & 0x80) != 0
            pos.set(Position.KEY_SATELLITES, flags & 0x0f)
            pos.set(Position.KEY_STATUS, buf.readUByte())
            pos.set("key", buf.readUByte())
            pos.set(Position.PREFIX_ADC + 1, buf.readUByte() + buf.readUByte() / 100.0)
            pos.set(Position.PREFIX_ADC + 2, buf.readUByte() + buf.readUByte() / 100.0)
            pos.set(Position.KEY_ODOMETER, buf.readUInt())

            if (modelM588 && buf.remaining() >= 7) {
                buf.readUShort()
                buf.readUShort()
                int alarm = buf.readUByte()
                if (bit(alarm, 0)) pos.addAlarm(ALARM_ACCELERATION)
                if (bit(alarm, 1)) pos.addAlarm(ALARM_BRAKING)
                if (bit(alarm, 2)) pos.addAlarm(ALARM_CORNERING)
            }

            if (type == MSG_ALARM_DATA) {
                int alarm1 = buf.readUByte()
                if (bit(alarm1, 0)) pos.addAlarm(ALARM_BRAKING)
                if (bit(alarm1, 5)) pos.addAlarm(ALARM_ACCELERATION)
                int alarm2 = buf.readUByte()
                if (bit(alarm2, 1)) pos.addAlarm(ALARM_OVERSPEED)
                if (bit(alarm2, 4)) pos.addAlarm(ALARM_CORNERING)
            }

            pos
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_POSITION_SINGLE:
                    return encodeContent(ctx.deviceId(), MSG_POSITION_REQUEST, null)
                case TYPE_ENGINE_STOP:
                    return encodeContent(ctx.deviceId(), MSG_CLOSE_OIL_DUCT, null)
                case TYPE_ENGINE_RESUME:
                    return encodeContent(ctx.deviceId(), MSG_RESTORE_OIL_DUCT, null)
                case TYPE_SET_SPEED_LIMIT:
                    return encodeContent(ctx.deviceId(), MSG_RESET_MILEAGE, [(byte) cmd.getInteger(Command.KEY_DATA)] as byte[])
                case TYPE_SET_ODOMETER:
                    int value = cmd.getInteger(Command.KEY_DATA)
                    return encodeContent(ctx.deviceId(), MSG_OVERSPEED_ALARM, [(byte) (value >> 8), (byte) value] as byte[])
                default:
                    return null
            }
        }
    }
}
