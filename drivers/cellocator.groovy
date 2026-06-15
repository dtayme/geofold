// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Cellocator GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/cellocator/ (Java reference)
 *
 * Binary TCP protocol on port 5033.
 * Frame starts with MCGP (0x4D 0x43 0x47 0x50) or alternative variant.
 * Type byte at offset 4 determines fixed frame length:
 *   0  MSG_CLIENT_STATUS       — 70 bytes
 *   3  MSG_CLIENT_PROGRAMMING  — 31 bytes
 *   7  MSG_CLIENT_SERIAL_LOG   — 70 bytes
 *   8  MSG_CLIENT_SERIAL       — 19 + LE uint16 at offset 16
 *   9  MSG_CLIENT_MODULAR      — 15 + uint8 at offset 13
 *   11 MSG_CLIENT_MODULAR_EXT  — 16 + LE uint16 at offset 13
 *
 * Header (after type-based framing):
 *   magic(4) | type(1) | deviceId(4 LE) | commCtl(2 LE, absent for type 8) | packetNum(1)
 *
 * Alternative mode: byte 3 of magic is not 'P' (e.g., 'G').
 * Alternative affects coordinate encoding and ADC sizes in MSG_CLIENT_STATUS.
 *
 * ACK (MSG_SERVER_ACKNOWLEDGE=4):
 *   magic(4) | type(1) | deviceId(4 LE) | packetNum(1) | authCode(4 LE=0) |
 *   [0x00, devicePacketNum, 11×0x00] | checksum(1, byte-sum from offset 4)
 *
 * Supported commands: TYPE_OUTPUT_CONTROL
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone

def MSG_CLIENT_STATUS      = 0
def MSG_CLIENT_SERIAL      = 8
def MSG_CLIENT_MODULAR_EXT = 11
def MSG_SERVER_ACKNOWLEDGE = 4

def decodeAlarm = { int event ->
    switch (event) {
        case 70: return Position.ALARM_SOS
        case 80: return Position.ALARM_POWER_CUT
        case 81: return Position.ALARM_LOW_POWER
        default: return null
    }
}

def encodeFrame = { int type, int uniqueId, int packetNumber, byte[] content ->
    byte[] frame = bytes {
        writeByte 0x4D; writeByte 0x43; writeByte 0x47; writeByte 0x50
        writeByte type
        writeIntLE uniqueId
        writeByte packetNumber
        writeIntLE 0  // authentication code
        if (content != null) writeBytes content
    }
    int cksum = 0
    for (int i = 4; i < frame.length; i++) cksum += (frame[i] & 0xff)
    byte[] result = new byte[frame.length + 1]
    System.arraycopy(frame, 0, result, 0, frame.length)
    result[frame.length] = (byte) cksum
    return result
}

protocol("cellocator") {

    port 5033

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 15) return null
            int type = fb.getUByte(4)
            int length = 0
            switch (type) {
                case 0:  length = 70; break
                case 3:  length = 31; break
                case 7:  length = 70; break
                case 8:
                    if (fb.readableBytes() < 19) return null
                    length = 19 + (fb.getUByte(16) | (fb.getUByte(17) << 8))
                    break
                case 9:
                    length = 15 + fb.getUByte(13)
                    break
                case 11:
                    length = 16 + (fb.getUByte(13) | (fb.getUByte(14) << 8))
                    break
            }
            if (length == 0 || fb.readableBytes() < length) return null
            return length
        }

        commands TYPE_OUTPUT_CONTROL

        encode { cmd, ctx ->
            String uniqueIdStr = ctx.deviceId()
            int uniqueId = Integer.parseInt(uniqueIdStr)
            switch (cmd.type) {
                case TYPE_OUTPUT_CONTROL:
                    int index = cmd.getInteger('index')
                    int data  = Integer.parseInt(cmd.getString('data'))
                    int cmdByte = (data << 4) + index
                    byte[] content = bytes {
                        writeByte 0x03; writeByte 0x03
                        writeByte cmdByte; writeByte cmdByte
                        writeByte 0; writeByte 0
                        writeIntLE 0  // command-specific data
                    }
                    return encodeFrame(0, uniqueId, 0, content)
                default:
                    return null
            }
        }

        decode { msg, ctx ->
            boolean alternative = msg.getUByte(3) != 0x50  // 0x50 = 'P'

            msg.skip(4)  // system code
            int type     = msg.readUByte()
            long deviceId = msg.readUIntLE()

            def session = ctx.session(String.valueOf(deviceId))
            if (!session) return null

            if (type != MSG_CLIENT_SERIAL) {
                msg.skip(2)  // communication control
            }
            int packetNumber = msg.readUByte()

            if (type == MSG_CLIENT_MODULAR_EXT) {
                byte[] modAckContent = bytes {
                    writeByte 0x80
                    writeShortLE 10      // modules length
                    writeIntLE 0         // reserved
                    writeByte 9          // ack module type
                    writeShortLE 3       // module length
                    writeByte 0          // ack
                    writeShortLE 0       // reserved
                }
                ctx.ack(encodeFrame(MSG_CLIENT_MODULAR_EXT, (int) deviceId, packetNumber, modAckContent))
            } else {
                byte[] ackContent = bytes {
                    writeByte 0
                    writeByte packetNumber
                    writeZero 11
                }
                ctx.ack(encodeFrame(MSG_SERVER_ACKNOWLEDGE, (int) deviceId, 0, ackContent))
            }

            if (type == MSG_CLIENT_STATUS) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                pos.set(Position.KEY_VERSION_HW, msg.readUByte())
                pos.set(Position.KEY_VERSION_FW, msg.readUByte())
                msg.skip(1)  // protocol version
                pos.set(Position.KEY_STATUS, msg.readUByte() & 0x0f)
                msg.skip(1)  // operator/config flags
                msg.skip(1)  // reason data
                int event = msg.readUByte()
                String alarm = decodeAlarm(event)
                if (alarm) pos.set(Position.KEY_ALARM, alarm)
                pos.set(Position.KEY_EVENT, event)
                pos.set('mode', msg.readUByte())

                long input = msg.readUInt()  // BE — bit positions defined over the 4-byte value as-read
                pos.set(Position.KEY_IGNITION, BitUtil.check(input, 3 * 8 + 5))
                pos.set(Position.KEY_DOOR,     BitUtil.check(input, 3 * 8))
                pos.set(Position.KEY_CHARGE,   BitUtil.check(input, 7))
                pos.set(Position.KEY_INPUT,    input)

                if (alternative) {
                    msg.skip(1)
                    pos.set(Position.PREFIX_ADC + 1, msg.readUShortLE())
                    pos.set(Position.PREFIX_ADC + 2, msg.readUShortLE())
                } else {
                    msg.skip(1)
                    pos.set(Position.PREFIX_ADC + 1, msg.readUByte())
                    pos.set(Position.PREFIX_ADC + 2, msg.readUByte())
                    pos.set(Position.PREFIX_ADC + 3, msg.readUByte())
                    pos.set(Position.PREFIX_ADC + 4, msg.readUByte())
                }

                // Odometer: 3-byte unsigned LE (no readUMediumLE in DSL)
                byte[] odoBytes = msg.readBytes(3)
                long odo = (odoBytes[0] & 0xff) | ((odoBytes[1] & 0xff) << 8) | ((odoBytes[2] & 0xff) << 16)
                pos.set(Position.KEY_ODOMETER, odo)

                pos.set(Position.KEY_DRIVER_UNIQUE_ID, msg.readHex(6))

                msg.skip(2)  // fix time
                msg.skip(1)  // location status
                msg.skip(1)  // mode 1
                msg.skip(1)  // mode 2
                pos.set(Position.KEY_SATELLITES, msg.readUByte())

                pos.valid = true

                if (alternative) {
                    pos.longitude = msg.readIntLE() / 10000000.0
                    pos.latitude  = msg.readIntLE() / 10000000.0
                } else {
                    pos.longitude = msg.readIntLE() / Math.PI * 180 / 100000000
                    pos.latitude  = msg.readIntLE() / Math.PI * 180 / 100000000
                }

                pos.altitude = msg.readIntLE() / 100.0

                if (alternative) {
                    pos.speed  = UnitsConverter.knotsFromKph((double) msg.readUIntLE())
                    pos.course = msg.readUShortLE() / 1000.0
                } else {
                    pos.speed  = UnitsConverter.knotsFromMps(msg.readUIntLE() / 100.0)
                    pos.course = msg.readUShortLE() / Math.PI * 180.0 / 1000.0
                }

                int sec   = msg.readUByte()
                int min   = msg.readUByte()
                int hour  = msg.readUByte()
                int day   = msg.readUByte()
                int month = msg.readUByte()
                int year  = msg.readUShortLE()
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
                cal.set(Calendar.YEAR,         year < 100 ? 2000 + year : year)
                cal.set(Calendar.MONTH,        month - 1)
                cal.set(Calendar.DAY_OF_MONTH, day)
                cal.set(Calendar.HOUR_OF_DAY,  hour)
                cal.set(Calendar.MINUTE,       min)
                cal.set(Calendar.SECOND,       sec)
                cal.set(Calendar.MILLISECOND,  0)
                pos.time = cal.getTime()

                return pos

            } else if (type == MSG_CLIENT_MODULAR_EXT) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                msg.skip(1)  // packet control
                msg.skip(2)  // modules total length
                msg.skip(2)  // reserved
                msg.skip(2)  // reserved

                while (msg.remaining() > 3) {
                    int moduleType = msg.readUByte()
                    int moduleLen  = msg.readUShortLE()
                    int consumed   = 0

                    switch (moduleType) {
                        case 2:
                            msg.skip(2); consumed += 2  // operator id
                            msg.skip(4); consumed += 4  // pl signature
                            int count = msg.readUByte(); consumed++
                            for (int i = 0; i < count; i++) {
                                int id = msg.readUShortLE(); consumed += 2
                                msg.skip(1); consumed++   // variable length byte
                                pos.set(Position.PREFIX_IO + id, msg.readUIntLE()); consumed += 4
                            }
                            break
                        case 6:
                            msg.skip(1); consumed++  // hdop
                            msg.skip(1); consumed++  // mode 1
                            msg.skip(1); consumed++  // mode 2
                            msg.skip(1); consumed++  // satellites
                            pos.longitude = msg.readIntLE() / Math.PI * 180 / 100000000; consumed += 4
                            pos.latitude  = msg.readIntLE() / Math.PI * 180 / 100000000; consumed += 4
                            pos.altitude  = msg.readIntLE() / 100.0; consumed += 4
                            pos.speed     = UnitsConverter.knotsFromMps(msg.readUByte() / 100.0); consumed++
                            pos.course    = msg.readUShortLE() / Math.PI * 180.0 / 1000.0; consumed += 2
                            break
                        case 7:
                            msg.skip(1); consumed++  // valid flag
                            int sec   = msg.readUByte(); consumed++
                            int min   = msg.readUByte(); consumed++
                            int hour  = msg.readUByte(); consumed++
                            int day   = msg.readUByte(); consumed++
                            int month = msg.readUByte(); consumed++
                            int year  = msg.readUByte(); consumed++
                            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
                            cal.set(Calendar.YEAR,         2000 + year)
                            cal.set(Calendar.MONTH,        month - 1)
                            cal.set(Calendar.DAY_OF_MONTH, day)
                            cal.set(Calendar.HOUR_OF_DAY,  hour)
                            cal.set(Calendar.MINUTE,       min)
                            cal.set(Calendar.SECOND,       sec)
                            cal.set(Calendar.MILLISECOND,  0)
                            pos.time = cal.getTime()
                            break
                        default:
                            break
                    }

                    if (consumed < moduleLen) msg.skip(moduleLen - consumed)
                }

                return pos
            }

            return null
        }
    }
}
