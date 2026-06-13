// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Cityeasy binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/cityeasy/
 *
 * Supports documented location reports/requests, cell fallback data, and the
 * archived Java command surface for single/periodic/stop position requests and
 * timezone configuration.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.TimeZone
import java.util.regex.Pattern

def MSG_LOCATION_REPORT = 0x0003
def MSG_LOCATION_REQUEST = 0x0004
def MSG_LOCATION_INTERVAL = 0x0005
def MSG_TIMEZONE = 0x0008

def LOCATION = Pattern.compile(
        '^((\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2}),([AV]),(\\d+),'
                + '([NS]),(\\d+\\.\\d+),([EW]),(\\d+\\.\\d+),'
                + '(\\d+\\.\\d),(\\d+\\.\\d),(\\d+\\.\\d))?;'
                + '(\\d+),(\\d+),(\\d+),(\\d+).*')

def signedCoordinate = { String hemi, String value ->
    double result = value.toDouble()
    (hemi == 'S' || hemi == 'W') ? -result : result
}

def luhn = { String value ->
    long checksum = 0
    long remain = value.toLong()
    for (int i = 0; remain != 0; i++) {
        long digit = remain % 10
        remain = (long) (remain / 10)
        if (i % 2 == 0) {
            digit *= 2
            if (digit >= 10) {
                checksum += 1
                digit -= 10
            }
        }
        checksum += digit
    }
    (10 - checksum % 10) % 10
}

def crc16Kermit = { byte[] bytes ->
    int crc = 0
    for (byte raw : bytes) {
        int b = raw & 0xff
        b = Integer.reverse(b) >>> 24
        for (int i = 0; i < 8; i++) {
            boolean bit = ((crc >> 8) & 0xff ^ b) >> 7 != 0
            crc = (crc << 1) & 0xffff
            if (bit) crc ^= 0x1021
            b = (b << 1) & 0xff
        }
    }
    Integer.reverse(crc) >>> 16
}

def commandFrame = { int type, byte[] content ->
    def withoutChecksum = bytes {
        writeByte 0x53
        writeByte 0x53
        writeShort(2 + 2 + 2 + content.length + 4 + 2 + 2)
        writeShort type
        writeBytes content
        writeInt 0x0B
    }
    int crc = crc16Kermit(withoutChecksum)
    bytes {
        writeBytes withoutChecksum
        writeShort crc
        writeByte 0x0d
        writeByte 0x0a
    }
}

protocol("cityeasy") {

    port 5088
    commands TYPE_POSITION_SINGLE,
             TYPE_POSITION_PERIODIC,
             TYPE_POSITION_STOP,
             TYPE_SET_TIMEZONE

    variant("main") {

        maxFrameLength 8192
        frame 0x54 as byte, readLengthField(2, 2, -4)

        decode { buf, ctx ->
            buf.skip(2) // header
            buf.readUShort() // length

            String imei = buf.readHex(7)
            def session = ctx.session(imei)
            if (!session) {
                session = ctx.session(imei + luhn(imei))
            }
            if (!session) return null

            int type = buf.readUShort()
            if (type != MSG_LOCATION_REPORT && type != MSG_LOCATION_REQUEST) {
                return null
            }

            String sentence = buf.readString(buf.remaining() - 8)
            def m = LOCATION.matcher(sentence)
            if (!m.matches()) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(1)) {
                pos.time = new DateBuilder()
                        .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                        .getDate()
                pos.valid = m.group(8) == 'A'
                pos.set(Position.KEY_SATELLITES, m.group(9).toInteger())
                pos.latitude = signedCoordinate(m.group(10), m.group(11))
                pos.longitude = signedCoordinate(m.group(12), m.group(13))
                pos.speed = m.group(14).toDouble()
                pos.set(Position.KEY_HDOP, m.group(15).toDouble())
                pos.altitude = m.group(16).toDouble()
            } else {
                ctx.lastLocation(pos)
            }

            pos.network = new Network(CellTower.from(
                    m.group(17).toInteger(), m.group(18).toInteger(),
                    m.group(19).toInteger(), m.group(20).toInteger()))

            return pos
        }

        encode { command, ctx ->
            switch (command.type) {
                case TYPE_POSITION_SINGLE:
                    return commandFrame(MSG_LOCATION_REQUEST, new byte[0])
                case TYPE_POSITION_PERIODIC:
                    return commandFrame(MSG_LOCATION_INTERVAL, bytes {
                        writeShort ctx.freq()
                    })
                case TYPE_POSITION_STOP:
                    return commandFrame(MSG_LOCATION_INTERVAL, bytes {
                        writeShort 0
                    })
                case TYPE_SET_TIMEZONE:
                    int offset = TimeZone.getTimeZone(command.getString('timezone')).rawOffset / 60000
                    return commandFrame(MSG_TIMEZONE, bytes {
                        writeByte(offset < 0 ? 1 : 0)
                        writeShort Math.abs(offset)
                    })
                default:
                    return null
            }
        }
    }
}
