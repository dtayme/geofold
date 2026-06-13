// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Noran binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/noran/
 *
 * Supports UDP handshake frames and old/new position, alarm, and control
 * response uploads.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

def MSG_UPLOAD_POSITION = 0x0008
def MSG_UPLOAD_POSITION_NEW = 0x0032
def MSG_CONTROL_RESPONSE = 0x8009
def MSG_ALARM = 0x0003
def MSG_SHAKE_HAND = 0x0000
def MSG_SHAKE_HAND_RESPONSE = 0x8000
def DATE_FORMAT = DateTimeFormatter.ofPattern('yy-MM-dd HH:mm:ss')

def bit = { long value, int index -> (value & (1L << index)) != 0 }
def bits = { long value, int from, int to -> (value >> from) & ((1L << (to - from)) - 1) }
def floatLE = { buf -> ByteBuffer.wrap(buf.readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).getFloat() as double }
def parseDate = { String value -> Date.from(LocalDateTime.parse(value, DATE_FORMAT).atZone(ZoneId.systemDefault()).toInstant()) }
def commandFrame = { String content ->
    byte[] text = content.getBytes('US-ASCII')
    bytes {
        writeByte 0x0d
        writeByte 0x0a
        writeString '*KW'
        writeByte 0
        writeShortLE 68
        writeShortLE 0x0002
        writeInt 0
        writeShortLE 0
        writeBytes text
        writeZero 50 - text.length
        writeByte 0x0d
        writeByte 0x0a
    }
}

protocol("noran") {

    port 5053
    transport 'udp'
    commands TYPE_POSITION_SINGLE,
             TYPE_POSITION_PERIODIC,
             TYPE_POSITION_STOP,
             TYPE_ENGINE_STOP,
             TYPE_ENGINE_RESUME

    variant("main") {

        decode { buf, ctx ->
            buf.readUShortLE()
            int type = buf.readUShortLE()

            if (type == MSG_SHAKE_HAND) {
                ctx.ack(bytes {
                    writeByte 0x0d
                    writeByte 0x0a
                    writeString '*KW'
                    writeByte 0
                    writeShortLE 13
                    writeShortLE MSG_SHAKE_HAND_RESPONSE
                    writeByte 1
                    writeByte 0x0d
                    writeByte 0x0a
                })
                return null
            }

            if (!(type in [MSG_UPLOAD_POSITION, MSG_UPLOAD_POSITION_NEW, MSG_CONTROL_RESPONSE, MSG_ALARM])) return null

            boolean newFormat = (type == MSG_UPLOAD_POSITION && buf.remaining() == 48) ||
                    (type == MSG_ALARM && buf.remaining() == 48) ||
                    (type == MSG_CONTROL_RESPONSE && buf.remaining() == 57)

            def pos = ctx.newPosition()
            if (type == MSG_CONTROL_RESPONSE) {
                buf.readUIntLE()
                buf.readUIntLE()
            }

            pos.valid = bit(buf.readUByte(), 0)
            int alarm = buf.readUByte()
            switch (alarm) {
                case 1: pos.addAlarm(ALARM_SOS); break
                case 2: pos.addAlarm(ALARM_OVERSPEED); break
                case 3: pos.addAlarm(ALARM_GEOFENCE_EXIT); break
                case 9: pos.addAlarm(ALARM_POWER_OFF); break
            }

            if (newFormat) {
                pos.speed = UnitsConverter.knotsFromKph(buf.readUIntLE())
                pos.course = floatLE(buf)
            } else {
                pos.speed = UnitsConverter.knotsFromKph(buf.readUByte())
                pos.course = buf.readUShortLE()
            }
            pos.longitude = floatLE(buf)
            pos.latitude = floatLE(buf)

            if (!newFormat) {
                long timeValue = buf.readUIntLE()
                pos.time = new DateBuilder()
                        .setYear((int) (timeValue >> 26))
                        .setMonth((int) bits(timeValue, 22, 26))
                        .setDay((int) bits(timeValue, 17, 22))
                        .setHour((int) bits(timeValue, 12, 17))
                        .setMinute((int) bits(timeValue, 6, 12))
                        .setSecond((int) bits(timeValue, 0, 6))
                        .getDate()
            }

            String id = buf.readString(newFormat ? 12 : 11).replaceAll('[^\\p{Print}]', '')
            def session = ctx.session(id)
            if (!session) return null
            pos.deviceId = session.deviceId

            if (newFormat) {
                pos.time = parseDate(buf.readString(17))
                buf.readByte()
            }

            if (!newFormat) {
                pos.set(Position.PREFIX_IO + 1, buf.readUByte())
                pos.set(Position.KEY_FUEL, buf.readUByte())
            } else if (type == MSG_UPLOAD_POSITION_NEW) {
                pos.set(Position.PREFIX_TEMP + 1, buf.readShortLE())
                pos.set(Position.KEY_ODOMETER, floatLE(buf))
            }

            return pos
        }

        encode { command, ctx ->
            switch (command.type) {
                case TYPE_POSITION_SINGLE:
                    return commandFrame('*KW,000,000,000000#')
                case TYPE_POSITION_PERIODIC:
                    return commandFrame("*KW,000,002,000000,${ctx.freq()}#")
                case TYPE_POSITION_STOP:
                    return commandFrame('*KW,000,002,000000,0#')
                case TYPE_ENGINE_STOP:
                    return commandFrame('*KW,000,007,000000,0#')
                case TYPE_ENGINE_RESUME:
                    return commandFrame('*KW,000,007,000000,1#')
                default:
                    return null
            }
        }
    }
}
