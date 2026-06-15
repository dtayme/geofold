// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Topin binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/topin/
 *
 * Binary framing: 0x7878 header, 1-byte length, 1-byte type, data, 0x0D 0x0A.
 * Frames are delimited by the trailing CRLF; the length byte has dual meaning:
 * for WIFI/LBS message types it equals the WiFi AP count, not the data length.
 *
 * Login: 8-byte BCD IMEI extracted as hexDump(8).substring(1) = readHex(8).substring(1).
 * GPS/GPS_OFFLINE: inline Gt06 GPS block — 6-byte date+time, 1 byte sats (lower 4 bits),
 *   4-byte unsigned lat (/ 60 / 30000), 4-byte unsigned lon, 1-byte speed kph,
 *   2-byte flags (bits 0–9 course, 10 N/S, 11 E/W, 12 valid, 14 ign-present, 15 ign).
 * GPS_2/GPS_OFFLINE_2: 6-byte raw date+time, then 4-byte coordinates using
 *   readCoordinate (1-byte degree + 3-byte decimal, sign in high nibble of first decimal byte).
 * STATUS: battery/fw/tz/interval then optional rssi/temp(skip)/charge/heartrate guarded
 *   by readableBytes() >= N+2 (the +2 accounts for the trailing CRLF still in the frame).
 * WIFI/LBS types: 6-byte BCD time, length WiFi APs (7 bytes each), cell count + mcc + mnc +
 *   cells (5 bytes each), optional 1-byte alarm when readableBytes() > 2.
 * VIBRATION/SOS_ALARM: last-known position with alarm.
 * TIME_UPDATE: reply only, no position.
 *
 * Command: TYPE_SOS_NUMBER encoded as MSG_SOS_NUMBER (0x41) frame.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.Calendar
import java.util.TimeZone

def MSG_LOGIN            = 0x01
def MSG_GPS_2            = 0x08
def MSG_GPS_OFFLINE_2    = 0x09
def MSG_GPS              = 0x10
def MSG_GPS_OFFLINE      = 0x11
def MSG_STATUS           = 0x13
def MSG_WIFI_OFFLINE     = 0x17
def MSG_LBS_WIFI         = 0x18
def MSG_LBS_WIFI_OFFLINE = 0x19
def MSG_LBS_WIFI_2       = 0x1A
def MSG_TIME_UPDATE      = 0x30
def MSG_SOS_NUMBER       = 0x41
def MSG_WIFI             = 0x69
def MSG_VIBRATION        = 0x94
def MSG_SOS_ALARM        = 0x99

// Build a Topin response frame: 0x7878 + length + type + content bytes + CRLF
def buildResponse = { int len, int type, byte[] content ->
    bytes {
        writeByte 0x78
        writeByte 0x78
        writeByte len
        writeByte type
        writeBytes content
        writeByte 0x0D
        writeByte 0x0A
    }
}

// Build a time-update response with current UTC time
def buildTimeUpdate = { int type ->
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    byte[] timeContent = bytes {
        writeShort cal.get(Calendar.YEAR)
        writeByte cal.get(Calendar.MONTH) + 1
        writeByte cal.get(Calendar.DAY_OF_MONTH)
        writeByte cal.get(Calendar.HOUR_OF_DAY)
        writeByte cal.get(Calendar.MINUTE)
        writeByte cal.get(Calendar.SECOND)
    }
    buildResponse(timeContent.length, type, timeContent)
}

// Inline Gt06 GPS decode: 18 bytes total (6 date+time, 1 sats, 4 lat, 4 lon, 1 speed, 2 flags)
def decodeGpsBlock = { buf, pos ->
    pos.time = new DateBuilder()
            .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .getDate()
    pos.set(Position.KEY_SATELLITES, buf.readUByte() & 0x0f)
    long rawLat = buf.readUInt()
    long rawLon = buf.readUInt()
    pos.speed = UnitsConverter.knotsFromKph(buf.readUByte())
    int flags = buf.readUShort()
    pos.course = flags & 0x3FF
    boolean north = checkBit(flags, 10)
    boolean east  = checkBit(flags, 11)
    pos.valid = checkBit(flags, 12)
    pos.latitude  = rawLat / 60.0 / 30000.0 * (north ? 1 : -1)
    pos.longitude = rawLon / 60.0 / 30000.0 * (east  ? 1 : -1)
    if (checkBit(flags, 14)) {
        pos.set(Position.KEY_IGNITION, checkBit(flags, 15))
    }
}

// GPS_2 custom coordinate: 1-byte degree + 3-byte decimal with sign in high nibble
def readCoord = { buf ->
    int deg = buf.readUByte()
    boolean negative = (buf.getUByte(0) & 0xf0) != 0
    int b0 = buf.readUByte()
    int b1 = buf.readUByte()
    int b2 = buf.readUByte()
    int decimal = ((b0 & 0x0f) << 16) | (b1 << 8) | b2
    double result = deg + decimal / 1_000_000.0
    negative ? -result : result
}

// STATUS alarm byte: bit 0 = vibration, bit 1 = overspeed, bit 4 = low power
def decodeStatusAlarm = { int alarms ->
    if (checkBit(alarms, 0)) return ALARM_VIBRATION
    if (checkBit(alarms, 1)) return ALARM_OVERSPEED
    if (checkBit(alarms, 4)) return ALARM_LOW_POWER
    null
}

// BCD byte: each byte encodes 2 decimal digits (high nibble = tens, low nibble = units)
def bcdByte = { int b -> ((b >> 4) & 0xf) * 10 + (b & 0xf) }

protocol("topin") {

    port 5199
    commands TYPE_SOS_NUMBER

    variant("main") {

        frame scriptedFrame { fb ->
            for (int i = 0; i < fb.readableBytes() - 1; i++) {
                if (fb.getByte(fb.readerIndex() + i) == (byte) 0x0D
                        && fb.getByte(fb.readerIndex() + i + 1) == (byte) 0x0A) {
                    return i + 2
                }
            }
            return null
        }

        decode { buf, ctx ->

            buf.skip(2) // 0x7878 header
            int length = buf.readUByte()
            int type = buf.readUByte()

            if (type == MSG_LOGIN) {
                // 8-byte BCD IMEI: hexDump(8).substring(1) strips the leading nibble padding
                String imei = buf.readHex(8).substring(1)
                def session = ctx.session(imei)
                byte[] loginAck = bytes {
                    writeByte session != null ? 0x01 : 0x44
                }
                ctx.ack(buildResponse(length, type, loginAck))
                ctx.ack(buildTimeUpdate(MSG_TIME_UPDATE))
                return null
            }

            def session = ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (type == MSG_GPS_2 || type == MSG_GPS_OFFLINE_2) {

                if (buf.readableBytes() <= 2) return null

                pos.time = new DateBuilder()
                        .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
                        .getDate()
                pos.valid = (type == MSG_GPS_2)
                pos.latitude  = readCoord(buf)
                pos.longitude = readCoord(buf)
                buf.skip(8) // second (redundant) coordinates
                pos.speed  = UnitsConverter.knotsFromKph(buf.readUByte())
                pos.course = buf.readUByte() * 2
                pos.set(Position.KEY_SATELLITES, buf.readUByte())
                return pos

            } else if (type == MSG_GPS || type == MSG_GPS_OFFLINE) {

                byte[] timeBytes = buf.getBytes(0, 6) // save for ACK before decoding
                decodeGpsBlock(buf, pos)
                if (buf.readableBytes() >= 5) {
                    pos.altitude = buf.readShort()
                    String alarm = decodeStatusAlarm(buf.readUByte())
                    if (alarm) pos.addAlarm(alarm)
                }
                ctx.ack(buildResponse(length, type, timeBytes))
                return pos

            } else if (type == MSG_TIME_UPDATE) {

                ctx.ack(buildTimeUpdate(type))
                return null

            } else if (type == MSG_STATUS) {

                ctx.lastLocation(pos, null)
                byte[] statusContent = buf.getBytes(0, buf.readableBytes() - 2)
                pos.set(Position.KEY_BATTERY_LEVEL, buf.readUByte())
                pos.set(Position.KEY_VERSION_FW, buf.readUByte())
                buf.readUByte() // timezone
                buf.readUByte() // interval
                if (buf.readableBytes() >= 1 + 2) {
                    pos.set(Position.KEY_RSSI, buf.readUByte())
                }
                if (buf.readableBytes() >= 3 + 2) {
                    buf.skip(3) // temperature
                }
                if (buf.readableBytes() >= 1 + 2) {
                    pos.set(Position.KEY_CHARGE, buf.readUByte() > 0)
                }
                if (buf.readableBytes() >= 1 + 2) {
                    pos.set(Position.KEY_HEART_RATE, buf.readUByte())
                }
                ctx.ack(buildResponse(length, type, statusContent))
                return pos

            } else if (type == MSG_WIFI || type == MSG_WIFI_OFFLINE
                    || type == MSG_LBS_WIFI || type == MSG_LBS_WIFI_2
                    || type == MSG_LBS_WIFI_OFFLINE) {

                // 6-byte BCD time; save raw bytes for ACK response
                byte[] timeBytes = buf.getBytes(0, 6)
                int yr  = bcdByte(buf.readUByte())
                int mon = bcdByte(buf.readUByte())
                int day = bcdByte(buf.readUByte())
                int hr  = bcdByte(buf.readUByte())
                int min = bcdByte(buf.readUByte())
                int sec = bcdByte(buf.readUByte())
                def date = new DateBuilder()
                        .setYear(yr).setMonth(mon).setDay(day)
                        .setHour(hr).setMinute(min).setSecond(sec)
                        .getDate()
                ctx.lastLocation(pos, date)

                def network = new Network()

                // length byte = WiFi AP count for WIFI/LBS types
                for (int i = 0; i < length; i++) {
                    String mac = String.format('%02x:%02x:%02x:%02x:%02x:%02x',
                            buf.readUByte(), buf.readUByte(), buf.readUByte(),
                            buf.readUByte(), buf.readUByte(), buf.readUByte())
                    int rssi = buf.readUByte()
                    network.addWifiAccessPoint(WifiAccessPoint.from(mac, rssi))
                }

                int cellCount = buf.readUByte()
                int mcc = buf.readUShort()
                int mnc = buf.readUByte()
                for (int i = 0; i < cellCount; i++) {
                    network.addCellTower(CellTower.from(
                            mcc, mnc, buf.readUShort(), buf.readUShort(), buf.readUByte()))
                }

                if (buf.readableBytes() > 2) {
                    String alarm = decodeStatusAlarm(buf.readUByte())
                    if (alarm) pos.addAlarm(alarm)
                }

                pos.setNetwork(network)
                ctx.ack(buildResponse(length, type, timeBytes))
                return pos

            } else if (type == MSG_VIBRATION) {

                ctx.lastLocation(pos, null)
                pos.addAlarm(ALARM_VIBRATION)
                return pos

            } else if (type == MSG_SOS_ALARM) {

                ctx.lastLocation(pos, null)
                pos.addAlarm(ALARM_SOS)
                return pos

            }

            return null
        }

        encode { cmd, ctx ->
            if (cmd.type == TYPE_SOS_NUMBER) {
                String phone = cmd.getString('phone')
                byte[] content = bytes {
                    writeString phone
                }
                // length = 1 (type byte) + phone string length
                return buildResponse(1 + content.length, MSG_SOS_NUMBER, content)
            }
            return null
        }
    }
}
