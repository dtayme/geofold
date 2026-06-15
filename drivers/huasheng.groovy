// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * HuaSheng GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/huasheng/ (Java reference)
 *
 * Binary TCP protocol on port 5111.
 * Frame: 0xC0-delimited with SLIP-style escape sequences:
 *   0xDB 0xDC → 0xC0
 *   0xDB 0xDD → 0xDB
 *
 * After unescaping, frame layout:
 *   0xC0 | flag(1) | reserved(1) | length(2 BE) | type(2 BE) |
 *   checksum(2 BE) | index(4 BE signed) | content | 0xC0
 *
 * Message types:
 *   0xAA00 MSG_POSITION — position report with TLV extensions
 *   0xAA02 MSG_LOGIN    — login with IMEI in TLV subtype 0x0003
 *   0xAA12 MSG_UPFAULT  — fault/DTC codes
 *   0x0002 MSG_HSO_REQ  — heartbeat (ACK only)
 *
 * Commands (server → device): same frame format, types:
 *   0xAA04 MSG_SET_REQ  — periodic report interval / alarm arm / speed limit
 *   0xAA16 MSG_CTRL_REQ — output control
 *
 * Supported commands:
 *   TYPE_POSITION_PERIODIC, TYPE_OUTPUT_CONTROL,
 *   TYPE_ALARM_ARM, TYPE_ALARM_DISARM, TYPE_SET_SPEED_LIMIT
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

def MSG_POSITION     = 0xAA00
def MSG_POSITION_RSP = 0xFF01
def MSG_LOGIN        = 0xAA02
def MSG_LOGIN_RSP    = 0xFF03
def MSG_UPFAULT      = 0xAA12
def MSG_HSO_REQ      = 0x0002
def MSG_HSO_RSP      = 0x0003
def MSG_SET_REQ      = 0xAA04
def MSG_CTRL_REQ     = 0xAA16

def decodeAlarm = { int event ->
    switch (event) {
        case 4:  return Position.ALARM_FATIGUE_DRIVING
        case 6:  return Position.ALARM_SOS
        case 7:  return Position.ALARM_BRAKING
        case 8:  return Position.ALARM_ACCELERATION
        case 9:  return Position.ALARM_CORNERING
        case 10: return Position.ALARM_ACCIDENT
        case 16: return Position.ALARM_REMOVING
        default: return null
    }
}

def buildResponse = { int type, int index, byte[] content ->
    bytes {
        writeByte 0xC0
        writeShort 0x0100
        writeShort 12 + (content != null ? content.length : 0)
        writeShort type
        writeShort 0
        writeInt index
        if (content != null) writeBytes content
        writeByte 0xC0
    }
}

protocol("huasheng") {

    port 5111

    variant("main") {

        frame (0xC0 as byte) { fb ->
            if (fb.readableBytes() < 2) return null
            int endIdx = fb.indexOf(0xC0, 1)
            if (endIdx < 0) return null
            int wireLen = endIdx + 1
            byte[] raw = fb.bytes(0, wireLen)
            def out = new ByteArrayOutputStream()
            int i = 0
            while (i < raw.length) {
                int b = raw[i] & 0xff
                if (b == 0xDB && i + 1 < raw.length) {
                    int ext = raw[i + 1] & 0xff
                    i += 2
                    if (ext == 0xDC) out.write(0xC0)
                    else if (ext == 0xDD) out.write(0xDB)
                } else {
                    out.write(b)
                    i++
                }
            }
            return frameResult(wireLen, out.toByteArray())
        }

        commands TYPE_POSITION_PERIODIC, TYPE_OUTPUT_CONTROL,
                 TYPE_ALARM_ARM, TYPE_ALARM_DISARM, TYPE_SET_SPEED_LIMIT

        encode { cmd, ctx ->
            def buildCmd = { int type, byte[] content ->
                bytes {
                    writeByte 0xC0
                    writeShort 0x0000
                    writeShort 12 + content.length
                    writeShort type
                    writeShort 0
                    writeInt 1
                    writeBytes content
                    writeByte 0xC0
                }
            }
            switch (cmd.type) {
                case TYPE_POSITION_PERIODIC:
                    return buildCmd(MSG_SET_REQ, bytes {
                        writeShort 0x0002
                        writeShort 6
                        writeShort cmd.getInteger('frequency')
                    })
                case TYPE_OUTPUT_CONTROL:
                    return buildCmd(MSG_CTRL_REQ, bytes {
                        writeByte((cmd.getInteger('index') - 1) * 2 + (2 - cmd.getInteger('data')))
                    })
                case TYPE_ALARM_ARM:
                    return buildCmd(MSG_SET_REQ, bytes {
                        writeShort 0x0001
                        writeShort 5
                        writeByte 1
                    })
                case TYPE_ALARM_DISARM:
                    return buildCmd(MSG_SET_REQ, bytes {
                        writeShort 0x0001
                        writeShort 5
                        writeByte 0
                    })
                case TYPE_SET_SPEED_LIMIT:
                    return buildCmd(MSG_SET_REQ, bytes {
                        writeShort 0x0004
                        writeShort 6
                        writeShort cmd.getInteger('data')
                    })
                default:
                    return null
            }
        }

        decode { msg, ctx ->
            msg.skip(1)  // start 0xC0
            msg.skip(1)  // flag
            msg.skip(1)  // reserved
            msg.skip(2)  // length
            int type = msg.readUShort()
            msg.skip(2)  // checksum
            int index = msg.readInt()

            if (type == MSG_LOGIN) {
                String imei = null
                while (msg.remaining() > 4) {
                    int subtype = msg.readUShort()
                    int subLen  = msg.readUShort() - 4
                    if (subtype == 0x0003) {
                        imei = new String(msg.readBytes(subLen), StandardCharsets.US_ASCII)
                    } else {
                        msg.skip(subLen)
                    }
                }
                if (imei) {
                    ctx.session(imei)
                    ctx.ack(buildResponse(MSG_LOGIN_RSP, index, bytes { writeByte 0 }))
                }
                return null
            }

            if (type == MSG_HSO_REQ) {
                ctx.ack(buildResponse(MSG_HSO_RSP, index, null))
                return null
            }

            def session = ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (type == MSG_UPFAULT) {
                ctx.lastLocation(pos)
                msg.skip(4)  // inner TLV type + length
                def codes = new StringBuilder()
                while (msg.remaining() > 2) {
                    String value = msg.readHex(2)
                    int digit = Integer.parseInt(value.substring(0, 1), 16)
                    char prefix = (digit >> 2) == 1 ? 'C' : (digit >> 2) == 2 ? 'B' : (digit >> 2) == 3 ? 'U' : 'P'
                    if (codes.length() > 0) codes.append(' ')
                    codes.append(prefix).append(digit % 4).append(value.substring(1))
                }
                pos.set(Position.KEY_DTCS, codes.toString())
                return pos
            }

            if (type == MSG_POSITION) {
                int status = msg.readUShort()
                pos.valid = BitUtil.check(status, 15)
                pos.set(Position.KEY_STATUS, status)
                pos.set(Position.KEY_IGNITION, BitUtil.check(status, 14))

                int event = msg.readUShort()
                String alarm = decodeAlarm(event)
                if (alarm) pos.set(Position.KEY_ALARM, alarm)
                pos.set(Position.KEY_EVENT, event)

                String time = new String(msg.readBytes(12), StandardCharsets.US_ASCII)
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
                cal.set(Calendar.YEAR,         2000 + time.substring(0, 2).toInteger())
                cal.set(Calendar.MONTH,        time.substring(2, 4).toInteger() - 1)
                cal.set(Calendar.DAY_OF_MONTH, time.substring(4, 6).toInteger())
                cal.set(Calendar.HOUR_OF_DAY,  time.substring(6, 8).toInteger())
                cal.set(Calendar.MINUTE,       time.substring(8, 10).toInteger())
                cal.set(Calendar.SECOND,       time.substring(10, 12).toInteger())
                cal.set(Calendar.MILLISECOND,  0)
                pos.time = cal.getTime()

                pos.longitude = msg.readInt() / 100000.0
                pos.latitude  = msg.readInt() / 100000.0
                pos.speed     = UnitsConverter.knotsFromKph(msg.readUShort())
                pos.course    = msg.readUShort()
                pos.altitude  = msg.readUShort()
                msg.skip(2)  // odometer speed

                def network = new Network()

                while (msg.remaining() > 4) {
                    int subtype = msg.readUShort()
                    int subLen  = msg.readUShort() - 4
                    int consumed = 0

                    switch (subtype) {
                        case 0x0001:
                            int coolant = msg.readUByte() - 40; consumed++
                            if (coolant <= 215) pos.set(Position.KEY_COOLANT_TEMP, coolant)
                            pos.set(Position.KEY_RPM, msg.readUShort()); consumed += 2
                            pos.set('averageSpeed', msg.readUByte()); consumed++
                            msg.skip(2); consumed += 2
                            pos.set(Position.KEY_FUEL_CONSUMPTION, msg.readUShort() / 100.0); consumed += 2
                            pos.set(Position.KEY_ODOMETER_TRIP, msg.readUShort()); consumed += 2
                            pos.set(Position.KEY_POWER, msg.readUShort() / 100.0); consumed += 2
                            pos.set(Position.KEY_FUEL, msg.readUByte() * 0.4); consumed++
                            if (subLen - consumed >= 7) {
                                pos.set('fuelLevel2', msg.readUShort()); consumed += 2
                            }
                            if (consumed + 4 <= subLen) {
                                msg.skip(4); consumed += 4  // trip id
                            }
                            if (consumed < subLen) {
                                pos.set('adBlueLevel', msg.readUByte() * 0.4); consumed++
                            }
                            break
                        case 0x0005:
                            pos.set(Position.KEY_RSSI, msg.readUByte()); consumed++
                            pos.set(Position.KEY_HDOP, msg.readUByte()); consumed++
                            msg.skip(4); consumed += 4
                            break
                        case 0x0009:
                            pos.set(Position.KEY_VIN,
                                    new String(msg.readBytes(subLen), StandardCharsets.US_ASCII))
                            consumed = subLen
                            break
                        case 0x0010:
                            pos.set(Position.KEY_ODOMETER, Double.parseDouble(
                                    new String(msg.readBytes(subLen), StandardCharsets.US_ASCII)) * 1000)
                            consumed = subLen
                            break
                        case 0x0011:
                            pos.set(Position.KEY_HOURS, msg.readUInt() / 20.0); consumed += 4
                            break
                        case 0x0014:
                            msg.skip(1); consumed++
                            pos.set(Position.KEY_ENGINE_LOAD, msg.readUByte() / 255.0d); consumed++
                            pos.set('timingAdvance', msg.readUByte() * 0.5); consumed++
                            pos.set('airTemp', msg.readUByte() - 40); consumed++
                            pos.set('airFlow', msg.readUShort() / 100.0); consumed += 2
                            pos.set(Position.KEY_THROTTLE, msg.readUByte() / 255.0d); consumed++
                            break
                        case 0x0020:
                            String cellData = new String(msg.readBytes(subLen), StandardCharsets.US_ASCII)
                            consumed = subLen
                            for (String cell : cellData.split('\\+')) {
                                String[] v = cell.split('@')
                                if (v.length >= 4) {
                                    network.addCellTower(CellTower.from(
                                            v[0].toInteger(), v[1].toInteger(),
                                            Integer.parseInt(v[2], 16),
                                            Long.parseLong(v[3], 16)))
                                }
                            }
                            break
                        case 0x0021:
                            String wifiData = new String(msg.readBytes(subLen), StandardCharsets.US_ASCII)
                            consumed = subLen
                            for (String point : wifiData.split('\\+')) {
                                String[] v = point.split('@')
                                if (v.length >= 2) {
                                    network.addWifiAccessPoint(
                                            WifiAccessPoint.from(v[0], v[1].toInteger()))
                                }
                            }
                            break
                        default:
                            break
                    }

                    if (consumed < subLen) msg.skip(subLen - consumed)
                }

                if (network.cellTowers != null || network.wifiAccessPoints != null) {
                    pos.network = network
                }

                ctx.ack(buildResponse(MSG_POSITION_RSP, index, null))
                return pos
            }

            return null
        }
    }
}
