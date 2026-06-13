// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * MXT binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/mxt/
 *
 * Implements escaped 0x01..0x04 binary frames, position reports, and ACKs.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

def MSG_ACK = 0x02
def MSG_POSITION = 0x31

def bit = { long value, int index -> (value & (1L << index)) != 0 }
def bits = { long value, int from, int to -> (value >> from) & ((1L << (to - from)) - 1) }

def crc16Xmodem = { byte[] data ->
    int crc = 0
    for (byte raw : data) {
        crc ^= (raw & 0xff) << 8
        for (int i = 0; i < 8; i++) {
            crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1)
            crc &= 0xffff
        }
    }
    crc
}

def encodeEscaped = { byte[] packet ->
    def out = []
    out << 0x01
    packet.each { byte raw ->
        int b = raw & 0xff
        if (b in [0x01, 0x04, 0x10, 0x11, 0x13]) {
            out << 0x10
            out << ((b + 0x20) & 0xff)
        } else {
            out << b
        }
    }
    out << 0x04
    out.collect { (byte) it } as byte[]
}

def ackFrame = { int device, long id, int crc ->
    byte[] packet = bytes {
        writeByte device
        writeByte MSG_ACK
        writeIntLE((int) id)
        writeShortLE crc
    }
    int checksum = crc16Xmodem(packet)
    byte[] withChecksum = bytes {
        writeBytes packet
        writeShortLE checksum
    }
    encodeEscaped(withChecksum)
}

protocol("mxt") {

    port 5087

    variant("main") {

        maxFrameLength 2048
        frame 0x01 as byte, readEscaped(0x04 as byte, 0x10 as byte, [
                0x21: 0x01,
                0x24: 0x04,
                0x30: 0x10,
                0x31: 0x11,
                0x33: 0x13,
        ])

        decode { buf, ctx ->
            buf.readUByte()
            int device = buf.readUByte()
            int type = buf.readUByte()
            long id = buf.readUIntLE()
            def session = ctx.session(String.valueOf(id))
            if (!session) return null

            if (type != MSG_POSITION) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            buf.readUByte()
            int infoGroups = buf.readUByte()
            pos.set(Position.KEY_INDEX, buf.readUShortLE())

            long date = buf.readUIntLE()
            long days = date >> 17
            long hours = bits(date, 12, 17)
            long minutes = bits(date, 6, 12)
            long seconds = bits(date, 0, 6)
            pos.time = new DateBuilder().setDate(2000, 1, 1)
                    .addMillis((((days * 24 + hours) * 60 + minutes) * 60 + seconds) * 1000L)
                    .getDate()

            pos.valid = true
            pos.latitude = buf.readIntLE() / 1000000.0
            pos.longitude = buf.readIntLE() / 1000000.0

            long flags = buf.readUIntLE()
            pos.set(Position.KEY_IGNITION, bit(flags, 0))
            if (bit(flags, 1)) pos.addAlarm(ALARM_GENERAL)
            pos.set(Position.KEY_INPUT, bits(flags, 2, 7) as int)
            pos.set(Position.KEY_OUTPUT, bits(flags, 7, 10) as int)
            pos.course = bits(flags, 10, 13) * 45
            pos.set(Position.KEY_CHARGE, bit(flags, 20))
            pos.speed = UnitsConverter.knotsFromKph(buf.readUByte())
            buf.readUByte()

            if (bit(infoGroups, 0)) buf.skip(8)
            if (bit(infoGroups, 1)) buf.skip(8)
            if (bit(infoGroups, 2)) {
                pos.set(Position.KEY_SATELLITES, buf.readUByte())
                pos.set(Position.KEY_HDOP, buf.readUByte())
                pos.accuracy = buf.readUByte()
                pos.set(Position.KEY_RSSI, buf.readUByte())
                buf.readUShortLE()
                pos.set(Position.KEY_POWER, buf.readUByte())
                pos.set(Position.PREFIX_TEMP + 1, buf.readByte())
            }
            if (bit(infoGroups, 3)) pos.set(Position.KEY_ODOMETER, buf.readUIntLE())
            if (bit(infoGroups, 4)) pos.set(Position.KEY_HOURS, UnitsConverter.msFromMinutes(buf.readUIntLE()))
            if (bit(infoGroups, 5)) buf.readUIntLE()
            if (bit(infoGroups, 6)) {
                pos.set(Position.KEY_POWER, buf.readUShortLE() / 1000.0)
                pos.set(Position.KEY_BATTERY, buf.readUShortLE())
            }
            if (bit(infoGroups, 7)) pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(buf.readUIntLE()))

            byte[] frame = buf.getBytes(0, buf.remaining())
            int crc = ((frame[frame.length - 2] & 0xff) | ((frame[frame.length - 1] & 0xff) << 8))
            ctx.ack(ackFrame(device, id, crc))

            return pos
        }
    }
}
