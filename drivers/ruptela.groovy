// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Ruptela GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/ruptela/ (Java reference)
 *
 * Binary frames, length-field framed (offset 0, length 2, adjustment 2):
 *   <length 2b><imei 8b><type 1b>[payload][crc16-kermit 2b]
 *
 * Main types: MSG_RECORDS (1), MSG_EXTENDED_RECORDS (68), MSG_DTCS (9),
 * MSG_FILES (37, photo — not supported), MSG_IDENTIFICATION (15),
 * MSG_HEARTBEAT (16), plus command response types 2/3/4/7/17.
 *
 * IO parameter IDs map to named Position attributes; unknown IDs fall
 * through to generic "ioN" keys.
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.Command
import org.traccar.model.Position

import java.nio.ByteBuffer

def MSG_RECORDS               = 1
def MSG_DEVICE_CONFIGURATION  = 2
def MSG_DEVICE_VERSION        = 3
def MSG_FIRMWARE_UPDATE       = 4
def MSG_SET_CONNECTION        = 5
def MSG_SET_ODOMETER          = 6
def MSG_SMS_VIA_GPRS_RESPONSE = 7
def MSG_SMS_VIA_GPRS          = 8
def MSG_DTCS                  = 9
def MSG_IDENTIFICATION        = 15
def MSG_HEARTBEAT             = 16
def MSG_SET_IO                = 17
def MSG_FILES                 = 37
def MSG_EXTENDED_RECORDS      = 68

def encodeContent = { int type, byte[] content ->
    byte[] pre = bytes {
        writeShort 1 + content.length
        writeByte 100 + type
        writeBytes content
    }
    int crc = Checksum.crc16(Checksum.CRC16_KERMIT, ByteBuffer.wrap(pre, 2, pre.length - 2))
    bytes {
        writeBytes pre
        writeShort crc
    }
}

def readValue = { buf, int length, boolean signed ->
    switch (length) {
        case 1: return signed ? buf.readByte() : (long) buf.readUByte()
        case 2: return signed ? buf.readShort() : (long) buf.readUShort()
        case 4: return signed ? buf.readInt() : buf.readUInt()
        default: return buf.readLong()
    }
}

def decodeDriver = { pos, String part1, String part2 ->
    Long v1 = pos.removeLong(part1)
    Long v2 = pos.removeLong(part2)
    if (v1 != null && v2 != null) {
        ByteBuffer bb = ByteBuffer.allocate(16)
        bb.putLong(v1)
        bb.putLong(v2)
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, new String(bb.array(), "US-ASCII"))
    }
}

def decodeParameter = { pos, int id, buf, int length ->
    switch (id) {
        case [2, 3, 4, 5]:
            pos.set(Position.PREFIX_IN + (id - 1), readValue(buf, length, false)); break
        case [13, 173]:
            pos.set(Position.KEY_MOTION, readValue(buf, length, false) > 0); break
        case 20:
            pos.set(Position.PREFIX_ADC + 3, readValue(buf, length, false)); break
        case 21:
            pos.set(Position.PREFIX_ADC + 4, readValue(buf, length, false)); break
        case 22:
            pos.set(Position.PREFIX_ADC + 1, readValue(buf, length, false)); break
        case 23:
            pos.set(Position.PREFIX_ADC + 2, readValue(buf, length, false)); break
        case 29:
            pos.set(Position.KEY_POWER, readValue(buf, length, false) / 1000.0); break
        case 30:
            pos.set(Position.KEY_BATTERY, readValue(buf, length, false) / 1000.0); break
        case 32:
            pos.set(Position.KEY_DEVICE_TEMP, readValue(buf, length, true)); break
        case 34:
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, buf.readHex(length)); break
        case 39:
            pos.set(Position.KEY_ENGINE_LOAD, readValue(buf, length, false)); break
        case 65:
            pos.set(Position.KEY_ODOMETER, readValue(buf, length, false)); break
        case 74:
            pos.set(Position.PREFIX_TEMP + 3, readValue(buf, length, true) / 10.0); break
        case [78, 79, 80]:
            pos.set(Position.PREFIX_TEMP + (id - 78), readValue(buf, length, true) / 10.0); break
        case 88:
            if (readValue(buf, length, false) > 0) pos.addAlarm(ALARM_JAMMING); break
        case 94:
            pos.set(Position.KEY_RPM, readValue(buf, length, false) * 0.25); break
        case 95:
            pos.set(Position.KEY_OBD_SPEED, readValue(buf, length, false)); break
        case 98:
            pos.set(Position.KEY_FUEL, readValue(buf, length, false) * 100 / 255.0); break
        case 100:
            pos.set(Position.KEY_FUEL_CONSUMPTION, readValue(buf, length, false) / 20.0); break
        case 134:
            if (readValue(buf, length, false) > 0) pos.addAlarm(ALARM_BRAKING); break
        case 136:
            if (readValue(buf, length, false) > 0) pos.addAlarm(ALARM_ACCELERATION); break
        case 150:
            pos.set(Position.KEY_OPERATOR, readValue(buf, length, false)); break
        case 163:
            pos.set(Position.KEY_ODOMETER, readValue(buf, length, false) * 5); break
        case 164:
            pos.set(Position.KEY_ODOMETER_TRIP, readValue(buf, length, false) * 5); break
        case 165:
            pos.set(Position.KEY_OBD_SPEED, readValue(buf, length, false) / 256.0); break
        case [166, 197]:
            pos.set(Position.KEY_RPM, readValue(buf, length, false) * 0.125); break
        case 170:
            pos.set(Position.KEY_CHARGE, readValue(buf, length, false) > 0); break
        case 205:
            pos.set(Position.KEY_FUEL, readValue(buf, length, false)); break
        case 207:
            pos.set(Position.KEY_FUEL_LEVEL, readValue(buf, length, false) * 0.4); break
        case 208:
            pos.set(Position.KEY_FUEL_USED, readValue(buf, length, false) * 0.5); break
        case [251, 409]:
            pos.set(Position.KEY_IGNITION, readValue(buf, length, false) > 0); break
        case 410:
            if (readValue(buf, length, false) > 0) pos.addAlarm(ALARM_TOW); break
        case 411:
            if (readValue(buf, length, false) > 0) pos.addAlarm(ALARM_ACCIDENT); break
        case 415:
            if (readValue(buf, length, false) == 0) pos.addAlarm(ALARM_GPS_ANTENNA_CUT); break
        case 645:
            pos.set(Position.KEY_OBD_ODOMETER, readValue(buf, length, false) * 1000); break
        case 758:
            if (readValue(buf, length, false) == 1) pos.addAlarm(ALARM_TAMPERING); break
        default:
            pos.set(Position.PREFIX_IO + id, readValue(buf, length, false)); break
    }
}

protocol("ruptela") {

    port 5046

    commands(
            TYPE_CUSTOM,
            TYPE_ENGINE_STOP,
            TYPE_ENGINE_RESUME,
            TYPE_REQUEST_PHOTO,
            TYPE_CONFIGURATION,
            TYPE_GET_VERSION,
            TYPE_FIRMWARE_UPDATE,
            TYPE_OUTPUT_CONTROL,
            TYPE_SET_CONNECTION,
            TYPE_SET_ODOMETER)

    variant("main") {

        frame readLengthField(0, 2, 2)

        decode { buf, ctx ->

            buf.readUShort() // data length
            String imei = String.format("%015d", buf.readLong())
            def session = ctx.session(imei)
            if (!session) return null

            int type = buf.readUByte()

            if (type == MSG_RECORDS || type == MSG_EXTENDED_RECORDS) {

                def positions = []

                buf.readUByte() // records left
                int count = buf.readUByte()

                count.times {
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId

                    pos.time = new Date(buf.readUInt() * 1000)
                    buf.readUByte() // timestamp extension

                    if (type == MSG_EXTENDED_RECORDS) {
                        int recordExtension = buf.readUByte()
                        int mergeRecordCount = BitUtil.from(recordExtension, 4)
                        int currentRecord = BitUtil.to(recordExtension, 4)

                        if (currentRecord > 0 && currentRecord <= mergeRecordCount) {
                            if (positions.empty) {
                                ctx.lastLocation(pos, null)
                            } else {
                                pos = positions.remove(positions.size() - 1)
                            }
                        }
                    }

                    buf.readUByte() // priority (reserved)

                    int longitude = buf.readInt()
                    int latitude = buf.readInt()
                    if (longitude > Integer.MIN_VALUE && latitude > Integer.MIN_VALUE) {
                        pos.valid = true
                        pos.longitude = longitude / 10000000.0
                        pos.latitude = latitude / 10000000.0
                        pos.altitude = buf.readUShort() / 10.0
                        pos.course = buf.readUShort() / 100.0
                        pos.set(Position.KEY_SATELLITES, buf.readUByte())
                        pos.speed = UnitsConverter.knotsFromKph(buf.readUShort())
                        pos.set(Position.KEY_HDOP, buf.readUByte() / 10.0)
                    } else {
                        buf.skip(8)
                        ctx.lastLocation(pos, null)
                    }

                    if (type == MSG_EXTENDED_RECORDS) {
                        pos.set(Position.KEY_EVENT, buf.readUShort())
                    } else {
                        pos.set(Position.KEY_EVENT, buf.readUByte())
                    }

                    int valueCount = buf.readUByte()
                    valueCount.times {
                        int id = (type == MSG_EXTENDED_RECORDS) ? buf.readUShort() : buf.readUByte()
                        decodeParameter(pos, id, buf, 1)
                    }

                    valueCount = buf.readUByte()
                    valueCount.times {
                        int id = (type == MSG_EXTENDED_RECORDS) ? buf.readUShort() : buf.readUByte()
                        decodeParameter(pos, id, buf, 2)
                    }

                    valueCount = buf.readUByte()
                    valueCount.times {
                        int id = (type == MSG_EXTENDED_RECORDS) ? buf.readUShort() : buf.readUByte()
                        decodeParameter(pos, id, buf, 4)
                    }

                    valueCount = buf.readUByte()
                    valueCount.times {
                        int id = (type == MSG_EXTENDED_RECORDS) ? buf.readUShort() : buf.readUByte()
                        decodeParameter(pos, id, buf, 8)
                    }

                    decodeDriver(pos, Position.PREFIX_IO + 126, Position.PREFIX_IO + 127)
                    decodeDriver(pos, Position.PREFIX_IO + 155, Position.PREFIX_IO + 156)

                    Long tagIdPart1 = pos.removeLong(Position.PREFIX_IO + 760)
                    Long tagIdPart2 = pos.removeLong(Position.PREFIX_IO + 761)
                    if (tagIdPart1 != null && tagIdPart2 != null) {
                        pos.set("tagId", Long.toHexString(tagIdPart1) + Long.toHexString(tagIdPart2))
                    }

                    positions.add(pos)
                }

                ctx.ack(bytes { writeHex "0002640113bc" })

                positions.each { ctx.emit(it) }
                return null

            } else if (type == MSG_DTCS) {

                int count = buf.readUByte()

                count.times {
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId

                    buf.readUByte() // reserved

                    pos.time = new Date(buf.readUInt() * 1000)

                    pos.valid = true
                    pos.longitude = buf.readInt() / 10000000.0
                    pos.latitude = buf.readInt() / 10000000.0

                    if (buf.readUByte() == 2) {
                        pos.set(Position.KEY_ARCHIVE, true)
                    }

                    pos.set(Position.KEY_DTCS, buf.readString(5, "US-ASCII"))

                    ctx.emit(pos)
                }

                ctx.ack(bytes { writeHex "00026d01c4a4" })
                return null

            } else if (type == MSG_FILES) {

                return null

            } else if (type == MSG_IDENTIFICATION || type == MSG_HEARTBEAT) {

                ctx.ack(encodeContent(type, bytes { writeByte 1 }))
                return null

            } else {

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos, null)
                pos.set(Position.KEY_TYPE, type)

                if (type == MSG_DEVICE_CONFIGURATION || type == MSG_DEVICE_VERSION
                        || type == MSG_FIRMWARE_UPDATE || type == MSG_SMS_VIA_GPRS_RESPONSE) {
                    pos.set(Position.KEY_RESULT, buf.readString(buf.remaining() - 2, "US-ASCII").trim())
                    return pos
                } else if (type == MSG_SET_IO) {
                    pos.set(Position.KEY_RESULT, String.valueOf(buf.readUByte()))
                    return pos
                }
                return null
            }
        }

        encode { command, ctx ->
            switch (command.type) {
                case TYPE_CUSTOM:
                    String data = command.getString(Command.KEY_DATA)
                    if (data.matches("(\\p{XDigit}{2})+")) {
                        return bytes { writeHex data }
                    }
                    return encodeContent(MSG_SMS_VIA_GPRS, data.getBytes("US-ASCII"))
                case TYPE_ENGINE_STOP:
                    return encodeContent(MSG_SMS_VIA_GPRS, "pass immobilizer 10".getBytes("US-ASCII"))
                case TYPE_ENGINE_RESUME:
                    return encodeContent(MSG_SMS_VIA_GPRS, "pass resetimmob".getBytes("US-ASCII"))
                case TYPE_REQUEST_PHOTO:
                    return encodeContent(MSG_FILES, bytes {
                        writeByte 1
                        writeByte 0
                        writeInt 0
                        writeInt Integer.MAX_VALUE
                    })
                case TYPE_CONFIGURATION:
                    return encodeContent(MSG_DEVICE_CONFIGURATION,
                            (command.getString(Command.KEY_DATA) + "\r\n").getBytes("US-ASCII"))
                case TYPE_GET_VERSION:
                    return encodeContent(MSG_DEVICE_VERSION, new byte[0])
                case TYPE_FIRMWARE_UPDATE:
                    return encodeContent(MSG_FIRMWARE_UPDATE, "|FU_STRT*\r\n".getBytes("US-ASCII"))
                case TYPE_OUTPUT_CONTROL:
                    return encodeContent(MSG_SET_IO, bytes {
                        writeInt command.getInteger(Command.KEY_INDEX)
                        writeInt command.getInteger(Command.KEY_DATA)
                    })
                case TYPE_SET_CONNECTION:
                    String c = command.getString(Command.KEY_SERVER) + "," +
                            command.getInteger(Command.KEY_PORT) + ",TCP"
                    return encodeContent(MSG_SET_CONNECTION, c.getBytes("US-ASCII"))
                case TYPE_SET_ODOMETER:
                    return encodeContent(MSG_SET_ODOMETER, bytes {
                        writeInt command.getInteger(Command.KEY_DATA)
                    })
                default:
                    return null
            }
        }
    }
}
