// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * T800x / Topflytech binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/t800x/
 *
 * Supports documented binary headers 0x2323, 0x2525, 0x2626, and 0x2727,
 * including login, heartbeat, GPS/alarm reports, network reports, driver
 * behavior reports, BLE reports, command-result reports, binary ACKs, and
 * custom command encoding.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.math.BigInteger

def DEFAULT_HEADER = 0x2323

def MSG_LOGIN = 0x01
def MSG_GPS = 0x02
def MSG_HEARTBEAT = 0x03
def MSG_ALARM = 0x04
def MSG_NETWORK = 0x05
def MSG_DRIVER_BEHAVIOR_1 = 0x05
def MSG_DRIVER_BEHAVIOR_2 = 0x06
def MSG_BLE = 0x10
def MSG_NETWORK_2 = 0x11
def MSG_GPS_2 = 0x13
def MSG_ALARM_2 = 0x14
def MSG_COMMAND = 0x81

def check = { long value, int index -> (value & (1L << index)) != 0 }
def toBits = { long value, int to -> value & ((1L << to) - 1L) }
def between = { long value, int from, int to -> (value >> from) & ((1L << (to - from)) - 1L) }

def hex = { byte[] bytes ->
    bytes.collect { String.format('%02x', it & 0xff) }.join()
}

def bcd = { buf, int digits ->
    int result = 0
    int bytes = (int) (digits / 2)
    for (int i = 0; i < bytes; i++) {
        int value = buf.readUByte()
        result = result * 10 + (value >> 4)
        result = result * 10 + (value & 0x0f)
    }
    result
}

def readFloatLE = { buf ->
    Float.intBitsToFloat(Integer.reverseBytes(buf.readInt()))
}

def readDate = { buf ->
    new DateBuilder()
            .setYear(bcd(buf, 2))
            .setMonth(bcd(buf, 2))
            .setDay(bcd(buf, 2))
            .setHour(bcd(buf, 2))
            .setMinute(bcd(buf, 2))
            .setSecond(bcd(buf, 2))
            .getDate()
}

def sendResponse = { ctx, int header, int type, int index, byte[] imei, int alarm ->
    ctx.ack(bytes {
        writeShort header
        writeByte type
        writeShort alarm > 0 ? 16 : 15
        writeShort index
        writeBytes imei
        if (alarm > 0) {
            writeByte alarm
        }
    })
}

def decodeAlarm1 = { int value ->
    switch (value) {
        case 1: return ALARM_POWER_CUT
        case 2: return ALARM_LOW_BATTERY
        case 3: return ALARM_SOS
        case 4: return ALARM_OVERSPEED
        case 5: return ALARM_GEOFENCE_ENTER
        case 6: return ALARM_GEOFENCE_EXIT
        case 7: return ALARM_TOW
        case 8:
        case 10: return ALARM_VIBRATION
        case 21: return ALARM_JAMMING
        case 23: return ALARM_POWER_RESTORED
        case 24: return ALARM_LOW_POWER
        default: return null
    }
}

def decodeAlarm2 = { int value ->
    switch (value) {
        case 1:
        case 4: return ALARM_REMOVING
        case 2: return ALARM_TAMPERING
        case 3: return ALARM_SOS
        case 5: return ALARM_FALL_DOWN
        case 6: return ALARM_LOW_BATTERY
        case 14: return ALARM_GEOFENCE_ENTER
        case 15: return ALARM_GEOFENCE_EXIT
        default: return null
    }
}

def decodeBleTemp = { buf ->
    int value = buf.readUShort()
    (check(value, 15) ? -toBits(value, 15) : toBits(value, 15)) / 100.0
}

def decodeBle
decodeBle = { ctx, session, buf, int header, int type, int index, byte[] imei ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, readDate(buf))

    pos.set(Position.KEY_IGNITION, buf.readUByte() > 0)

    int i = 1
    while (buf.isReadable()) {
        switch (buf.readUShort()) {
            case 0x01:
                pos.set("tag${i}Id", buf.readHex(6))
                pos.set("tag${i}Battery", buf.readUByte() / 100.0 + 1.22)
                pos.set("tag${i}TirePressure", buf.readUByte() * 1.527 * 2)
                pos.set("tag${i}TireTemp", buf.readUByte() - 55)
                pos.set("tag${i}TireStatus", buf.readUByte())
                break
            case 0x02:
                pos.set("tag${i}Id", buf.readHex(6))
                pos.set("tag${i}Battery", bcd(buf, 2) / 10.0)
                switch (buf.readUByte()) {
                    case 0: pos.addAlarm(ALARM_SOS); break
                    case 1: pos.addAlarm(ALARM_LOW_BATTERY); break
                    default: break
                }
                buf.readUByte()
                buf.skip(16)
                break
            case 0x03:
                pos.set(Position.KEY_DRIVER_UNIQUE_ID, buf.readHex(6))
                pos.set("tag${i}Battery", bcd(buf, 2) / 10.0)
                if (buf.readUByte() == 1) {
                    pos.addAlarm(ALARM_LOW_BATTERY)
                }
                buf.readUByte()
                buf.skip(16)
                break
            case 0x04:
                pos.set("tag${i}Id", buf.readHex(6))
                pos.set("tag${i}Battery", buf.readUByte() / 100.0 + 2)
                buf.readUByte()
                pos.set("tag${i}Temp", decodeBleTemp(buf))
                pos.set("tag${i}Humidity", buf.readUShort() / 100.0)
                pos.set("tag${i}LightSensor", buf.readUShort())
                pos.set("tag${i}Rssi", buf.readUByte() - 128)
                break
            case 0x05:
                pos.set("tag${i}Id", buf.readHex(6))
                pos.set("tag${i}Battery", buf.readUByte() / 100.0 + 2)
                buf.readUByte()
                pos.set("tag${i}Temp", decodeBleTemp(buf))
                pos.set("tag${i}Door", buf.readUByte() > 0)
                pos.set("tag${i}Rssi", buf.readUByte() - 128)
                break
            case 0x06:
                pos.set("tag${i}Id", buf.readHex(6))
                pos.set("tag${i}Battery", buf.readUByte() / 100.0 + 2)
                pos.set("tag${i}Output", buf.readUByte() > 0)
                pos.set("tag${i}Rssi", buf.readUByte() - 128)
                break
            default:
                return pos
        }
        i += 1
    }

    sendResponse(ctx, header, type, index, imei, 0)
    pos
}

def decodePosition
decodePosition = { ctx, session, buf, int header, int type, int index, byte[] imei ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, index)

    if (header != 0x2727) {
        buf.readUShort()
        buf.readUShort()
        buf.readUByte()
        buf.readUShort()
        pos.set(Position.KEY_RSSI, toBits(buf.readUShort(), 7))
    }

    int status = buf.readUByte()
    pos.set(Position.KEY_SATELLITES, toBits(status, 5))

    if (header != 0x2727) {
        buf.readUByte()
        buf.readUByte()
        buf.readUByte()
        buf.readUByte()
        buf.readUShort()

        int io = buf.readUShort()
        pos.set(Position.KEY_IGNITION, check(io, 14))
        pos.set("ac", check(io, 13))
        pos.set(Position.PREFIX_IN + 3, check(io, 12))
        pos.set(Position.PREFIX_IN + 4, check(io, 11))

        if (type == MSG_GPS_2 || type == MSG_ALARM_2) {
            pos.set(Position.KEY_OUTPUT, buf.readUByte())
            buf.readUByte()
        } else {
            pos.set(Position.PREFIX_OUT + 1, check(io, 7))
            pos.set(Position.PREFIX_OUT + 2, check(io, 8))
            pos.set(Position.PREFIX_OUT + 3, check(io, 9))
        }

        if (header != 0x2626) {
            int adcCount = type == MSG_GPS_2 || type == MSG_ALARM_2 ? 5 : 2
            for (int i = 1; i <= adcCount; i++) {
                String value = buf.readHex(2)
                if (value != 'ffff') {
                    pos.set(Position.PREFIX_ADC + i, Integer.parseInt(value, 16) / 100.0)
                }
            }
        }
    }

    int alarm = buf.readUByte()
    pos.addAlarm(header != 0x2727 ? decodeAlarm1(alarm) : decodeAlarm2(alarm))
    pos.set("alarmCode", alarm)

    if (header != 0x2727) {
        buf.readUByte()
        pos.set(Position.KEY_ODOMETER, buf.readUInt())
        int battery = bcd(buf, 2)
        pos.set(Position.KEY_BATTERY_LEVEL, battery > 0 ? battery : 100)
    }

    if (check(status, 6)) {
        pos.valid = true
        pos.time = readDate(buf)
        pos.altitude = readFloatLE(buf)
        pos.longitude = readFloatLE(buf)
        pos.latitude = readFloatLE(buf)
        if (header == 0x2626) {
            buf.readUShort()
        } else {
            pos.speed = UnitsConverter.knotsFromKph(bcd(buf, 4) / 10.0)
        }
        pos.course = buf.readUShort()
    } else {
        ctx.lastLocation(pos, readDate(buf))
        int mcc = buf.readUShortLE()
        int mnc = buf.readUShortLE()
        if (mcc != 0xffff && mnc != 0xffff) {
            def network = new Network()
            for (int i = 0; i < 3; i++) {
                network.addCellTower(CellTower.from(mcc, mnc, buf.readUShortLE(), buf.readUShortLE()))
            }
            pos.network = network
        }
    }

    if (header == 0x2727) {
        long acceleration = new BigInteger(buf.readBytes(5)).longValue()
        double z = between(acceleration, 8, 15) + between(acceleration, 4, 8) / 10.0
        if (!check(acceleration, 15)) z = -z
        double y = between(acceleration, 20, 27) + between(acceleration, 16, 20) / 10.0
        if (!check(acceleration, 27)) y = -y
        double x = between(acceleration, 28, 32) + between(acceleration, 32, 39) / 10.0
        if (!check(acceleration, 39)) x = -x
        pos.set(Position.KEY_G_SENSOR, "[${x},${y},${z}]")

        int battery = bcd(buf, 2)
        pos.set(Position.KEY_BATTERY_LEVEL, battery > 0 ? battery : 100)
        pos.set(Position.KEY_DEVICE_TEMP, buf.readByte())
        pos.set("lightSensor", bcd(buf, 2) / 10.0)
        pos.set(Position.KEY_BATTERY, bcd(buf, 2) / 10.0)
        pos.set("solarPanel", bcd(buf, 2) / 10.0)
        pos.set(Position.KEY_ODOMETER, buf.readUInt())

        int inputStatus = buf.readUShort()
        pos.set(Position.KEY_IGNITION, check(inputStatus, 2))
        pos.set(Position.KEY_RSSI, between(inputStatus, 4, 11))
        pos.set(Position.KEY_INPUT, inputStatus)

        buf.readUShort()
        buf.readUInt()
        buf.readUByte()
        buf.readUShort()
        buf.readUByte()
    } else {
        String model = session.model
        if (model == 'TLW2-2BL') {
            pos.set(Position.KEY_BATTERY, bcd(buf, 4) / 100.0)
        }
        if (buf.remaining() >= 2) {
            pos.set(Position.KEY_POWER, bcd(buf, 4) / 100.0)
        }
        if (buf.remaining() >= 19) {
            pos.speed = UnitsConverter.knotsFromKph(bcd(buf, 4) / 10.0)
            pos.set(Position.KEY_OBD_SPEED, bcd(buf, 4) / 100.0)
            pos.set(Position.KEY_FUEL_USED, buf.readUInt() / 1000.0)
            pos.set(Position.KEY_FUEL_CONSUMPTION, buf.readUInt() / 1000.0)
            pos.set(Position.KEY_RPM, buf.readUShort())
            int value = buf.readUByte()
            if (value != 0xff) pos.set("airInput", value)
            if (value != 0xff) pos.set("airPressure", value)
            if (value != 0xff) pos.set(Position.KEY_COOLANT_TEMP, value - 40)
            if (value != 0xff) pos.set("airTemp", value - 40)
            if (value != 0xff) pos.set(Position.KEY_ENGINE_LOAD, value)
            if (value != 0xff) pos.set(Position.KEY_THROTTLE, value)
            if (value != 0xff) pos.set(Position.KEY_FUEL, value)
        }
    }

    boolean ack = ctx.configBoolean('ack', false)
    if (ack || type == MSG_ALARM || type == MSG_ALARM_2) {
        sendResponse(ctx, header, type, header == 0x2323 ? 1 : index, imei, alarm)
    }

    pos
}

def decodeT800x
decodeT800x = { buf, ctx ->
    int header = buf.readUShort()
    int type = buf.readUByte()
    buf.readUShort()
    int index = buf.readUShort()
    byte[] imeiBytes = buf.readBytes(8)

    def session = ctx.session(hex(imeiBytes).substring(1))
    if (!session) return null

    boolean positionType = type == MSG_GPS || type == MSG_GPS_2 || type == MSG_ALARM || type == MSG_ALARM_2
    if (!positionType) {
        sendResponse(ctx, header, type, header == 0x2323 ? 1 : index, imeiBytes, 0)
    }

    if (positionType) {
        return decodePosition(ctx, session, buf, header, type, index, imeiBytes)
    } else if ((type == MSG_NETWORK && header == 0x2727) || type == MSG_NETWORK_2) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        ctx.lastLocation(pos, readDate(buf))
        pos.set(Position.KEY_OPERATOR, buf.readString(buf.readUByte(), 'UTF-16LE'))
        pos.set("networkTechnology", buf.readString(buf.readUByte()))
        pos.set("networkBand", buf.readString(buf.readUByte()))
        buf.readString(buf.readUByte())
        pos.set(Position.KEY_ICCID, buf.readString(buf.readUByte()))
        return pos
    } else if ((type == MSG_DRIVER_BEHAVIOR_1 || type == MSG_DRIVER_BEHAVIOR_2) && header == 0x2626) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        switch (buf.readUByte()) {
            case 0:
            case 4: pos.addAlarm(ALARM_BRAKING); break
            case 1:
            case 3:
            case 5: pos.addAlarm(ALARM_ACCELERATION); break
            case 2:
                pos.addAlarm(type == MSG_DRIVER_BEHAVIOR_1 ? ALARM_BRAKING : ALARM_CORNERING)
                break
            default: break
        }
        pos.time = readDate(buf)
        if (type == MSG_DRIVER_BEHAVIOR_2) {
            int status = buf.readUByte()
            pos.valid = !check(status, 7)
            buf.skip(5)
        } else {
            pos.valid = true
        }
        pos.altitude = readFloatLE(buf)
        pos.longitude = readFloatLE(buf)
        pos.latitude = readFloatLE(buf)
        pos.speed = UnitsConverter.knotsFromKph(bcd(buf, 4) / 10.0)
        pos.course = buf.readUShort()
        pos.set(Position.KEY_RPM, buf.readUShort())
        return pos
    } else if (type == MSG_BLE) {
        return decodeBle(ctx, session, buf, header, type, index, imeiBytes)
    } else if (type == MSG_COMMAND) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        ctx.lastLocation(pos)
        buf.readUByte()
        pos.set(Position.KEY_RESULT, buf.readString(buf.remaining(), 'UTF-16LE'))
        return pos
    }

    null
}

def encodeCommand = { int header, cmd, ctx ->
    if (cmd.type != TYPE_CUSTOM) return null
    String content = ctx.data()
    bytes {
        writeShort header
        writeByte MSG_COMMAND
        writeShort 7 + 8 + 1 + content.length()
        writeShort 1
        writeHex "0${ctx.deviceId()}"
        writeByte 0x01
        writeString content
    }
}

protocol("t800x") {

    port 5094
    transport 'tcp', 'udp'
    commands TYPE_CUSTOM

    [
            h2323: 0x2323,
            h2525: 0x2525,
            h2626: 0x2626,
            h2727: 0x2727,
    ].each { name, header ->
        variant(name) {
            byte hint = (byte) ((header >> 8) & 0xff)
            frame hint, readLengthField(3, 2, -5)
            decode decodeT800x
            encode { cmd, ctx -> encodeCommand(header, cmd, ctx) }
        }
    }
}
