// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * AutoFon binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/autofon/
 *
 * Supports login, current location, history batches, and 4.5 protocol location
 * frames.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

def MSG_LOGIN = 0x10
def MSG_LOCATION = 0x11
def MSG_HISTORY = 0x12
def MSG_45_LOGIN = 0x41
def MSG_45_LOCATION = 0x02

def bit = { int value, int index -> (value & (1 << index)) != 0 }
def fromBit = { int value, int index -> value >> index }
def toBit = { int value, int index -> value & ((1 << index) - 1) }
def readMedium = { buf -> (buf.readUByte() << 16) | (buf.readUByte() << 8) | buf.readUByte() }

def coordRaw = { int raw ->
    int degrees = raw.intdiv(1000000)
    double minutes = (raw % 1000000) / 10000.0
    degrees + minutes / 60.0
}

def coord45 = { int degrees, int minutes ->
    double value = degrees + fromBit(minutes, 4) / 600000.0
    bit(minutes, 0) ? value : -value
}

def decodePosition
decodePosition = { session, buf, boolean history, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    if (!history) {
        buf.readUByte()
        buf.skip(8)
    }
    pos.set(Position.KEY_STATUS, buf.readUByte())
    if (!history) buf.readUShort()
    pos.set(Position.KEY_BATTERY, buf.readUByte())
    buf.skip(6)

    if (!history) {
        for (int i = 0; i < 2; i++) {
            buf.skip(5)
            buf.readUShort()
            buf.skip(5)
        }
    }

    pos.set(Position.PREFIX_TEMP + 1, buf.readByte())
    int rssi = buf.readUByte()
    pos.network = new Network(CellTower.from(buf.readUShort(), buf.readUShort(), buf.readUShort(), buf.readUShort(), rssi))

    int valid = buf.readUByte()
    pos.valid = (valid & 0xc0) != 0
    pos.set(Position.KEY_SATELLITES, valid & 0x3f)

    pos.time = new DateBuilder()
            .setDateReverse(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .getDate()

    pos.latitude = coordRaw(buf.readInt())
    pos.longitude = coordRaw(buf.readInt())
    pos.altitude = buf.readShort()
    pos.speed = buf.readUByte()
    pos.course = buf.readUByte() * 2.0
    pos.set(Position.KEY_HDOP, buf.readUShort())

    buf.readUShort()
    buf.readUByte()
    pos
}

protocol("autofon") {
    port 5077

    variant("main") {
        frame { fb ->
            if (fb.readableBytes() < 1) return null
            int length = switch (fb.getUByte(0)) {
                case MSG_LOGIN -> 12
                case MSG_LOCATION -> 78
                case MSG_HISTORY -> 257
                case MSG_45_LOGIN -> 19
                case MSG_45_LOCATION -> 34
                default -> 1
            }
            fb.readableBytes() >= length ? frameRaw(length) : null
        }

        decode { buf, ctx ->
            int type = buf.readUByte()

            if (type == MSG_LOGIN || type == MSG_45_LOGIN) {
                if (type == MSG_LOGIN) {
                    buf.readUByte()
                    buf.readUByte()
                }
                String imei = buf.readHex(8).substring(1)
                ctx.session(imei)
                if (buf.remaining() > 0) {
                    ctx.ack(("resp_crc=" + (char) buf.getUByte(buf.remaining() - 1)).bytes)
                }
                return null
            }

            def session = ctx.session()
            if (!session) return null

            if (type == MSG_LOCATION) {
                return decodePosition(session, buf, false, ctx)
            }

            if (type == MSG_HISTORY) {
                int count = buf.readUByte() & 0x0f
                buf.readUShort()
                List positions = []
                for (int i = 0; i < count; i++) {
                    positions.add(decodePosition(session, buf, true, ctx))
                }
                return positions
            }

            if (type == MSG_45_LOCATION) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                int status = buf.readUByte()
                if (bit(status, 7)) pos.addAlarm(ALARM_GENERAL)
                pos.set(Position.KEY_BATTERY, toBit(status, 7))
                buf.skip(2)
                pos.set(Position.PREFIX_TEMP + 1, buf.readByte())
                buf.skip(2)
                buf.readByte()
                buf.readByte()
                buf.skip(6)

                int valid = buf.readUByte()
                pos.valid = fromBit(valid, 6) != 0
                pos.set(Position.KEY_SATELLITES, fromBit(valid, 6))

                int time = readMedium(buf)
                int date = readMedium(buf)
                pos.time = new DateBuilder()
                        .setTime(time.intdiv(10000), time.intdiv(100) % 100, time % 100)
                        .setDateReverse(date.intdiv(10000), date.intdiv(100) % 100, date % 100)
                        .getDate()

                pos.latitude = coord45(buf.readUByte(), readMedium(buf))
                pos.longitude = coord45(buf.readUByte(), readMedium(buf))
                pos.speed = buf.readUByte()
                pos.course = buf.readUShort()
                return pos
            }

            null
        }
    }
}
