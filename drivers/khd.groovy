// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * KHD binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/khd/
 *
 * Binary framing: 0x29 0x29 header, 2-byte big-endian length field at
 * offset 3. Total frame = 5 + length-field-value bytes.
 *
 * Device identifier: 4 raw bytes. Two lookup keys:
 *   id0 = 8-hex-digit string (raw bytes, lowercase)
 *   id1 = "%02d%02d%02d%02d" with b2-=0x80, b3-=0x80 offsets
 *
 * BCD-encoded date, time, coordinates (degrees+minutes), speed (kph),
 * course (tenths-of-degree) and GPS validity flag.
 *
 * Acknowledgement (MSG_CONFIRMATION) is returned for LOGIN,
 * ADMIN_NUMBER, SEND_TEXT, SMS_ALARM_SWITCH, and POSITION_REUPLOAD.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

def MSG_LOGIN              = 0xB1
def MSG_CONFIRMATION       = 0x21
def MSG_ON_DEMAND          = 0x81
def MSG_POSITION_UPLOAD    = 0x80
def MSG_POSITION_REUPLOAD  = 0x8E
def MSG_ALARM              = 0x82
def MSG_ADMIN_NUMBER       = 0x83
def MSG_SEND_TEXT          = 0x84
def MSG_REPLY              = 0x85
def MSG_SMS_ALARM_SWITCH   = 0x86
def MSG_PERIPHERAL         = 0xA3

def bcdInt = { buf, digits ->
    int result = 0
    (digits / 2).times {
        int b = buf.readUByte()
        result = result * 10 + (b >> 4)
        result = result * 10 + (b & 0xf)
    }
    result
}

def bcdCoord = { buf ->
    int b1 = buf.readUByte()
    int b2 = buf.readUByte()
    int b3 = buf.readUByte()
    int b4 = buf.readUByte()
    double degrees = (((b1 >> 4) & 0x7) * 10.0 + (b1 & 0xf)) * 10.0 + (b2 >> 4)
    double minutes  = (b2 & 0xf) * 10.0 + (b3 >> 4) +
            (((b3 & 0xf) * 10 + (b4 >> 4)) * 10 + (b4 & 0xf)) / 1000.0
    double value = degrees + minutes / 60.0
    (b1 & 0x80) != 0 ? -value : value
}

def buildAck = { byte[] rawFrame, int type ->
    int ackByte1 = rawFrame[rawFrame.length - 2] & 0xff
    int ackByte2 = rawFrame.length > 9 ? (rawFrame[9] & 0xff) : 0
    byte[] head = bytes {
        writeByte 0x29
        writeByte 0x29
        writeByte 0x21
        writeShort 5
        writeByte ackByte1
        writeByte type
        writeByte ackByte2
    }
    bytes {
        writeBytes head
        writeByte xor(head)
        writeByte 0x0D
    }
}

def decodeAlarm = { pos, byte[] status ->
    int s0 = status[0] & 0xff
    int s1 = status[1] & 0xff
    int s2 = status[2] & 0xff
    int s6 = status.length > 6 ? (status[6] & 0xff) : 0
    if      (checkBit(s0, 4)) pos.addAlarm(ALARM_LOW_POWER)
    else if (checkBit(s0, 6)) pos.addAlarm(ALARM_GEOFENCE_EXIT)
    else if (checkBit(s0, 7)) pos.addAlarm(ALARM_GEOFENCE_ENTER)
    else if (checkBit(s1, 0)) pos.addAlarm(ALARM_SOS)
    else if (checkBit(s1, 1)) pos.addAlarm(ALARM_OVERSPEED)
    else if (checkBit(s1, 3)) pos.addAlarm(ALARM_POWER_CUT)
    else if (checkBit(s1, 6)) pos.addAlarm(ALARM_TOW)
    else if (checkBit(s1, 7)) pos.addAlarm(ALARM_DOOR)
    else if (checkBit(s2, 2)) pos.addAlarm(ALARM_TEMPERATURE)
    else if (checkBit(s2, 4)) pos.addAlarm(ALARM_TAMPERING)
    else if (checkBit(s2, 6)) pos.addAlarm(ALARM_FATIGUE_DRIVING)
    else if (checkBit(s2, 7)) pos.addAlarm(ALARM_IDLE)
    else if (checkBit(s6, 3)) pos.addAlarm(ALARM_VIBRATION)
    else if (checkBit(s6, 4)) pos.addAlarm(ALARM_BRAKING)
    else if (checkBit(s6, 5)) pos.addAlarm(ALARM_ACCELERATION)
    else if (checkBit(s6, 6)) pos.addAlarm(ALARM_CORNERING)
    else if (checkBit(s6, 7)) pos.addAlarm(ALARM_ACCIDENT)
}

protocol("khd") {

    port 5058
    commands TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME, TYPE_GET_VERSION,
             TYPE_FACTORY_RESET, TYPE_SET_SPEED_LIMIT, TYPE_SET_ODOMETER,
             TYPE_POSITION_SINGLE

    variant("main") {

        frame 0x29 as byte, readLengthField(3, 2)

        decode { buf, ctx ->
            byte[] rawFrame = buf.getBytes(0, buf.remaining())
            buf.skip(2) // header
            int type = buf.readUByte()
            buf.readUShort() // size field

            if (type == MSG_LOGIN || type == MSG_ADMIN_NUMBER
                    || type == MSG_SEND_TEXT || type == MSG_SMS_ALARM_SWITCH
                    || type == MSG_POSITION_REUPLOAD) {
                ctx.ack(buildAck(rawFrame, type))
            }

            if (type != MSG_ON_DEMAND && type != MSG_POSITION_UPLOAD
                    && type != MSG_POSITION_REUPLOAD && type != MSG_ALARM
                    && type != MSG_REPLY && type != MSG_PERIPHERAL) {
                return null
            }

            // Device identifier: 4 bytes, b2 and b3 have +0x80 encoding
            byte[] idBytes = buf.readBytes(4)
            String id0 = String.format('%02x%02x%02x%02x',
                    idBytes[0] & 0xff, idBytes[1] & 0xff,
                    idBytes[2] & 0xff, idBytes[3] & 0xff)
            String id1 = String.format('%02d%02d%02d%02d',
                    idBytes[0] & 0xff,
                    (idBytes[1] & 0xff) - 0x80,
                    (idBytes[2] & 0xff) - 0x80,
                    idBytes[3] & 0xff)

            def session = ctx.session(id0)
            if (!session) session = ctx.session(id1)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setYear(bcdInt(buf, 2)).setMonth(bcdInt(buf, 2)).setDay(bcdInt(buf, 2))
                    .setHour(bcdInt(buf, 2)).setMinute(bcdInt(buf, 2)).setSecond(bcdInt(buf, 2))
                    .getDate()

            pos.latitude  = bcdCoord(buf)
            pos.longitude = bcdCoord(buf)
            pos.speed  = UnitsConverter.knotsFromKph(bcdInt(buf, 4))
            pos.course = bcdInt(buf, 4)
            pos.valid  = (buf.readUByte() & 0x80) != 0

            if (type != MSG_ALARM) {
                int b1 = buf.readUByte()
                int b2 = buf.readUByte()
                int b3 = buf.readUByte()
                int odometer = (b1 << 16) | (b2 << 8) | b3
                if ((odometer & 0xFFFF) > 0) {
                    pos.set(Position.KEY_ODOMETER, odometer)
                } else if (odometer > 0) {
                    pos.set(Position.KEY_FUEL, b1)
                }

                long status = buf.readUInt()
                pos.set(Position.KEY_IGNITION, (status & 0x80000000L) == 0)
                pos.set(Position.KEY_STATUS, status)

                buf.readUShort()
                buf.skip(5)

                pos.set(Position.KEY_RESULT, String.valueOf(buf.readUByte()))

                if (type == MSG_PERIPHERAL && buf.readableBytes() >= 4) {
                    buf.readUShort() // peripheral data length
                    int dataType   = buf.readUByte()
                    int dataLength = buf.readUByte()
                    switch (dataType) {
                        case 0x01:
                            pos.set(Position.KEY_FUEL, buf.readUByte() * 100 + buf.readUByte())
                            break
                        case 0x02:
                            pos.set(Position.PREFIX_TEMP + 1, buf.readUByte() * 100 + buf.readUByte())
                            break
                        case 0x05:
                            int sign = buf.readUByte()
                            if (sign == 1) pos.set('sign', true)
                            else if (sign == 2) pos.set('sign', false)
                            pos.set(Position.KEY_DRIVER_UNIQUE_ID,
                                    buf.readString(dataLength - 1).trim())
                            break
                        case 0x18:
                            for (int i = 1; i <= 4; i++) {
                                int val = buf.readUShort()
                                if (val > 0 && val < 0xFFFF) {
                                    pos.set('fuel' + i, val / 0xFFFE as double)
                                }
                            }
                            break
                        case 0x20:
                            pos.set(Position.KEY_BATTERY_LEVEL, buf.readUByte())
                            break
                        case 0x23:
                            def network = new Network()
                            int count = buf.readUByte()
                            count.times {
                                network.addCellTower(CellTower.from(
                                        buf.readUShort(), buf.readUByte(),
                                        buf.readUShort(), buf.readUShort(),
                                        buf.readUByte()))
                            }
                            if (count > 0) pos.network = network
                            break
                    }
                }
            } else {
                buf.skip(2) // overloaded state + logging status
                decodeAlarm(pos, buf.readBytes(8))
            }

            return pos
        }

        encode { cmd, ctx ->
            String uid = ('00000000' + ctx.deviceId())
            uid = uid.substring(uid.length() - 8)
            int p0 = Integer.parseInt(uid.substring(0, 2))
            int p1 = Integer.parseInt(uid.substring(2, 4)) + 0x80
            int p2 = Integer.parseInt(uid.substring(4, 6)) + 0x80
            int p3 = Integer.parseInt(uid.substring(6, 8))

            def khdCmd = { int msgType, byte[] extra = null ->
                int length = 6 + (extra ? extra.length : 0)
                byte[] head = bytes {
                    writeByte 0x29; writeByte 0x29
                    writeByte msgType
                    writeShort length
                    writeByte p0; writeByte p1; writeByte p2; writeByte p3
                    if (extra) writeBytes extra
                }
                bytes {
                    writeBytes head
                    writeByte xor(head)
                    writeByte 0x0D
                }
            }

            switch (cmd.type) {
                case TYPE_ENGINE_STOP:    return khdCmd(0x39)
                case TYPE_ENGINE_RESUME:  return khdCmd(0x38)
                case TYPE_GET_VERSION:    return khdCmd(0x3D)
                case TYPE_FACTORY_RESET:  return khdCmd(0xC3)
                case TYPE_SET_SPEED_LIMIT:
                    return khdCmd(0x38, [(byte)(cmd.getInteger('data') & 0xff)] as byte[])
                case TYPE_SET_ODOMETER:   return khdCmd(0x66)
                case TYPE_POSITION_SINGLE: return khdCmd(0x30)
                default: return null
            }
        }
    }
}
