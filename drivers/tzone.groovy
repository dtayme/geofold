// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Tzone GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/tzone/ (Java reference)
 *
 * Binary frames, length-field framed (offset 2, length 2, adjustment 2):
 *   <header 2b><length 2b><magic 0x2424><hardware 2b><firmware 4b>
 *   <imei bcd 8b><date/time 6b><gps block><lbs block><status block>
 *   [hardware-specific trailing blocks]
 *
 * Decoding branches heavily on the "hardware" model field, which selects
 * the GPS field layout, LBS (cell tower) format, status field layout, and
 * any trailing blocks (RFID cards/passengers, sensor tags, WiFi APs).
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

def DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

def readBcdInt = { buf, int digits ->
    int result = 0
    (digits.intdiv(2)).times {
        int b = buf.readUByte()
        result = result * 100 + (b >>> 4) * 10 + (b & 0x0f)
    }
    result
}

def readUMedium = { buf -> (buf.readUByte() << 16) | buf.readUShort() }

def decodeAlarm = { int value ->
    switch (value) {
        case 0x01: return ALARM_SOS
        case 0x10: return ALARM_LOW_BATTERY
        case 0x11: return ALARM_OVERSPEED
        case 0x14: return ALARM_BRAKING
        case 0x15: return ALARM_ACCELERATION
        case 0x30: return ALARM_PARKING
        case 0x42: return ALARM_GEOFENCE_EXIT
        case 0x43: return ALARM_GEOFENCE_ENTER
        case 0xA0: return ALARM_TEMPERATURE
        case 0xA3: return ALARM_VIBRATION
        case 0xB0: return ALARM_POWER_ON
        case 0xB1: return ALARM_POWER_OFF
        default: return null
    }
}

def sendResponse = { ctx, int index ->
    String ack = String.format("@ACK,%d#", index)
    String time = String.format("@UTC time:%s", DATE_FORMAT.format(Instant.now()))
    ctx.ack(ack + time)
}

def decodeGps = { pos, buf, int hardware ->
    int blockLength = buf.readUShort()
    int targetRemaining = buf.remaining() - blockLength

    if (hardware == 0x40A) {
        if (blockLength < 19) {
            return false
        }

        int status = buf.readUByte()
        pos.valid = true
        pos.fixTime = new DateBuilder()
                .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte()).getDate()

        double latitude = buf.readUInt() / 1000000.0
        double longitude = buf.readUInt() / 1000000.0
        pos.latitude = BitUtil.check(status, 0) ? latitude : -latitude
        pos.longitude = BitUtil.check(status, 1) ? -longitude : longitude

        pos.course = buf.readUShort()
        pos.speed = buf.readUShort() / 10.0

        buf.skip(buf.remaining() - targetRemaining)
        return true
    }

    if (blockLength < 22) {
        return false
    }

    if (hardware == 0x413) {
        buf.readUByte() // status
    } else {
        pos.set(Position.KEY_SATELLITES, buf.readUByte())
    }

    if (hardware == 0x413) {
        pos.fixTime = new DateBuilder()
                .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte()).getDate()
    }

    double lat
    double lon

    if (hardware == 0x10A || hardware == 0x10B) {
        lat = buf.readUInt() / 600000.0
        lon = buf.readUInt() / 600000.0
    } else {
        lat = buf.readUInt() / 100000.0 / 60.0
        lon = buf.readUInt() / 100000.0 / 60.0
    }

    if (hardware == 0x413) {

        pos.set(Position.KEY_HDOP, buf.readUShort() / 10.0)

        pos.altitude = buf.readUShort()
        pos.course = buf.readUShort()
        pos.speed = UnitsConverter.knotsFromKph(buf.readUShort() / 10.0)

        pos.set(Position.KEY_SATELLITES, buf.readUByte())

    } else {

        pos.fixTime = new DateBuilder()
                .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte()).getDate()

        pos.speed = buf.readUShort() / 100.0

        pos.set(Position.KEY_ODOMETER, readUMedium(buf))

        int flags = buf.readUShort()
        pos.course = BitUtil.to(flags, 9)
        if (!BitUtil.check(flags, 10)) {
            lat = -lat
        }
        pos.latitude = lat
        if (BitUtil.check(flags, 9)) {
            lon = -lon
        }
        pos.longitude = lon
        pos.valid = BitUtil.check(flags, 11)

    }

    buf.skip(buf.remaining() - targetRemaining)

    return true
}

def decodeCards = { pos, buf ->
    int index = 1
    4.times {
        int blockLength = buf.readUShort()
        int targetRemaining = buf.remaining() - blockLength

        if (blockLength > 0) {
            int count = buf.readUByte()
            count.times {
                int length = buf.readUByte()

                boolean odd = length % 2 != 0
                if (odd) {
                    length += 1
                }

                String num = buf.readHex(length.intdiv(2))

                if (odd) {
                    num = num.substring(1)
                }

                pos.set("card" + index, num)
            }
        }

        buf.skip(buf.remaining() - targetRemaining)
    }
}

def decodePassengers = { pos, buf ->
    int blockLength = buf.readUShort()
    int targetRemaining = buf.remaining() - blockLength

    if (blockLength > 0) {
        pos.set("passengersOn", readUMedium(buf))
        pos.set("passengersOff", readUMedium(buf))
    }

    buf.skip(buf.remaining() - targetRemaining)
}

def decodeTags = { pos, buf, int hardware ->
    int blockLength = buf.readUShort()
    int targetRemaining = buf.remaining() - blockLength

    if (blockLength > 0) {

        int type = buf.readUByte()

        if (hardware != 0x153 || type >= 2) {

            int count = buf.readUByte()
            int tagLength = buf.readUByte()

            (1..count).each {
                int tagTargetRemaining = buf.remaining() - tagLength

                buf.readUByte() // status
                buf.readUShortLE() // battery voltage

                pos.set(Position.PREFIX_TEMP + it, (buf.readShortLE() & 0x3fff) / 10.0)

                buf.readUByte() // humidity
                buf.readUByte() // rssi

                buf.skip(buf.remaining() - tagTargetRemaining)
            }

        } else if (type == 1) {

            pos.set(Position.KEY_CARD, buf.readString(buf.remaining() - targetRemaining, "UTF-8"))

        }

    }

    buf.skip(buf.remaining() - targetRemaining)
}

protocol("tzone") {

    port 5029

    variant("main") {

        frame readLengthField(2, 2, 2)

        decode { buf, ctx ->

            buf.skip(2) // header
            buf.readUShort() // length
            if (buf.readUShort() != 0x2424) return null
            int hardware = buf.readUShort()
            long firmware = buf.readUInt()

            String imei = buf.readHex(8).substring(1)
            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_VERSION_HW, hardware)
            pos.set(Position.KEY_VERSION_FW, firmware)

            Date deviceTime = new DateBuilder()
                    .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                    .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte()).getDate()
            pos.deviceTime = deviceTime

            // GPS info

            if (hardware == 0x406 || !decodeGps(pos, buf, hardware)) {
                ctx.lastLocation(pos, deviceTime)
            }

            // LBS info

            int blockLength = buf.readUShort()
            int targetRemaining = buf.remaining() - blockLength

            if (blockLength > 0) {
                if (hardware == 0x10A || hardware == 0x10B || hardware == 0x406) {

                    pos.network = new Network(CellTower.from(0, 0, buf.readUShort(), buf.readUShort()))

                } else if (hardware == 0x407) {

                    def network = new Network()
                    int count = buf.readUByte()
                    count.times {
                        buf.readUByte() // signal information

                        int mcc = readBcdInt(buf, 4)
                        int mnc = readBcdInt(buf, 4) % 1000

                        network.addCellTower(CellTower.from(
                                mcc, mnc, buf.readUShort(), buf.readUInt()))
                    }
                    pos.network = network

                } else if (hardware == 0x40A) {

                    def network = new Network()
                    int count = buf.readUByte()
                    count.times {
                        int signalInfo = buf.readUByte()
                        int type = signalInfo >>> 5
                        int cellTargetRemaining = buf.remaining() - (signalInfo & 0x1F)

                        def tower = CellTower.from(
                                readBcdInt(buf, 4),
                                readBcdInt(buf, 4) % 1000,
                                buf.readUShort(),
                                buf.readUInt())

                        if (type == 0b000) {
                            tower.signalStrength = -buf.readUByte()
                        } else if (type == 0b110) {
                            buf.readUShort() // pci
                            buf.skip(3) // earfcn
                            buf.readUByte() // rsrp
                            buf.readUByte() // rsrq
                            tower.signalStrength = -buf.readUByte()
                        }

                        buf.skip(buf.remaining() - cellTargetRemaining)
                        network.addCellTower(tower)
                    }
                    pos.network = network

                }
            }

            buf.skip(buf.remaining() - targetRemaining)

            // Status info

            blockLength = buf.readUShort()
            targetRemaining = buf.remaining() - blockLength

            if (hardware == 0x40A) {

                if (blockLength >= 14) {
                    pos.addAlarm(decodeAlarm(buf.readUByte()))
                    pos.set("terminalInfo", buf.readUByte())
                    pos.set(Position.KEY_RSSI, buf.readUByte())
                    pos.set("gsmStatus", buf.readUByte())
                    pos.set(Position.KEY_BATTERY, buf.readUShort() / 100.0)
                    int temperature = buf.readUShort()
                    if (!BitUtil.check(temperature, 15)) {
                        double value = BitUtil.to(temperature, 14) / 10.0
                        pos.set(Position.PREFIX_TEMP + 1, BitUtil.check(temperature, 14) ? -value : value)
                    }
                    int humidity = buf.readUShort()
                    if (!BitUtil.check(humidity, 15)) {
                        pos.set(Position.KEY_HUMIDITY, BitUtil.to(humidity, 15) / 10.0)
                    }
                }

            } else {

                if (hardware == 0x407 || blockLength >= 13) {
                    pos.addAlarm(decodeAlarm(buf.readUByte()))
                    pos.set("terminalInfo", buf.readUByte())

                    if (hardware != 0x407) {
                        int status = buf.readUByte()
                        pos.set(Position.PREFIX_OUT + 1, BitUtil.check(status, 0))
                        pos.set(Position.PREFIX_OUT + 2, BitUtil.check(status, 1))
                        status = buf.readUByte()
                        pos.set(Position.PREFIX_IN + 1, BitUtil.check(status, 4))
                        if (BitUtil.check(status, 0)) {
                            pos.addAlarm(ALARM_SOS)
                        }
                    }

                    pos.set(Position.KEY_RSSI, buf.readUByte())
                    pos.set("gsmStatus", buf.readUByte())
                    pos.set(Position.KEY_BATTERY, buf.readUShort() / 100.0)

                    if (hardware != 0x407) {
                        pos.set(Position.KEY_POWER, buf.readUShort())
                        pos.set(Position.PREFIX_ADC + 1, buf.readUShort())
                        pos.set(Position.PREFIX_ADC + 2, buf.readUShort())
                    } else {
                        int temperature = buf.readUShort()
                        if (!BitUtil.check(temperature, 15)) {
                            double value = BitUtil.to(temperature, 14) / 10.0
                            pos.set(Position.PREFIX_TEMP + 1, BitUtil.check(temperature, 14) ? -value : value)
                        }
                        int humidity = buf.readUShort()
                        if (!BitUtil.check(humidity, 15)) {
                            pos.set(Position.KEY_HUMIDITY, BitUtil.to(humidity, 15) / 10.0)
                        }
                        pos.set("lightSensor", buf.readUByte() == 0)
                    }
                }

                if (blockLength >= 15) {
                    pos.set(Position.PREFIX_TEMP + 1, buf.readUShort())
                }

            }

            buf.skip(buf.remaining() - targetRemaining)

            if (hardware == 0x10B) {

                decodeCards(pos, buf)

                buf.skip(buf.readUShort()) // temperature
                buf.skip(buf.readUShort()) // lock

                decodePassengers(pos, buf)

            }

            if (hardware == 0x153 || hardware == 0x406) {

                decodeTags(pos, buf, hardware)

            }

            if (hardware == 0x40A) {

                blockLength = buf.readUShort()
                targetRemaining = buf.remaining() - blockLength

                if (blockLength > 0) {
                    def network = pos.network ?: new Network()
                    while (buf.remaining() > targetRemaining) {
                        byte[] mac = buf.readBytes(6)
                        String macAddress = mac.collect { String.format("%02x", it & 0xff) }.join(":")
                        network.addWifiAccessPoint(WifiAccessPoint.from(macAddress, -buf.readUByte()))
                    }
                    pos.network = network
                }

                buf.skip(buf.remaining() - targetRemaining)

            }

            if (ctx.configBoolean("ack", false)) {
                sendResponse(ctx, buf.getUShort(buf.remaining() - 6))
            }

            return pos
        }
    }
}
