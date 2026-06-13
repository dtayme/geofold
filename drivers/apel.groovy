// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Apel binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/apel/
 *
 * Supports extended tracker identification, current GPS/state records, alarm
 * acknowledgements, and archive/log record uploads.
 */

import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

def MSG_ACK_ALARM = 160
def MSG_TRACKER_ID = 11
def MSG_TRACKER_ID_EXT = 12
def MSG_REQUEST_LAST_LOG_INDEX = 120
def MSG_LAST_LOG_INDEX = 121
def MSG_REQUEST_LOG_RECORDS = 130
def MSG_LOG_RECORDS = 131
def MSG_STATE_FULL_INFO_T104 = 92
def MSG_CURRENT_GPS_DATA = 101

long lastIndex = 0
long newIndex = 0

def le = { int value ->
    [(byte) (value & 0xff), (byte) ((value >> 8) & 0xff)] as byte[]
}

def leInt = { long value ->
    [(byte) (value & 0xff), (byte) ((value >> 8) & 0xff), (byte) ((value >> 16) & 0xff), (byte) ((value >> 24) & 0xff)] as byte[]
}

def crc32 = { byte[] data ->
    Checksum.crc32(Checksum.CRC32_STANDARD, ByteBuffer.wrap(data))
}

def simpleMessage = { int type ->
    byte[] head = le(type) + le(0)
    head + leInt(crc32(head))
}

def requestArchive = {
    if (lastIndex == 0) {
        lastIndex = newIndex
        return null
    }
    if (newIndex > lastIndex) {
        byte[] head = le(MSG_REQUEST_LOG_RECORDS) + le(6) + leInt(lastIndex) + le(512)
        return head + leInt(crc32(head))
    }
    null
}

protocol("apel") {
    port 5041

    variant("main") {
        frame readLengthFieldLE(2, 2, 4)

        decode { buf, ctx ->
            int type = buf.readUShortLE()
            boolean alarm = (type & 0x8000) != 0
            type &= 0x7fff
            buf.readUShortLE()

            if (alarm) {
                ctx.ack(simpleMessage(MSG_ACK_ALARM))
            }

            if (type == MSG_TRACKER_ID) {
                return null
            }

            if (type == MSG_TRACKER_ID_EXT) {
                buf.readUIntLE()
                int length = buf.readUShortLE()
                buf.skip(length)
                length = buf.readUShortLE()
                ctx.session(buf.readString(length))
                return null
            }

            if (type == MSG_LAST_LOG_INDEX) {
                long index = buf.readUIntLE()
                if (index > 0) {
                    newIndex = index
                    def request = requestArchive()
                    if (request != null) ctx.ack(request)
                }
                return null
            }

            if (!(type in [MSG_CURRENT_GPS_DATA, MSG_STATE_FULL_INFO_T104, MSG_LOG_RECORDS])) {
                return null
            }

            def session = ctx.session()
            if (!session) return null

            List positions = []
            int recordCount = type == MSG_LOG_RECORDS ? buf.readUShortLE() : 1

            for (int j = 0; j < recordCount; j++) {
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                int subtype = type
                if (type == MSG_LOG_RECORDS) {
                    pos.set(Position.KEY_ARCHIVE, true)
                    lastIndex = buf.readUIntLE() + 1
                    pos.set(Position.KEY_INDEX, lastIndex)

                    subtype = buf.readUShortLE()
                    if (!(subtype in [MSG_CURRENT_GPS_DATA, MSG_STATE_FULL_INFO_T104])) {
                        buf.skip(buf.readUShortLE())
                        continue
                    }
                    buf.readUShortLE()
                }

                pos.time = new Date(buf.readUIntLE() * 1000)
                pos.latitude = buf.readIntLE() * 180.0 / 0x7fffffff
                pos.longitude = buf.readIntLE() * 180.0 / 0x7fffffff

                if (subtype == MSG_STATE_FULL_INFO_T104) {
                    int speed = buf.readUByte()
                    pos.valid = speed != 255
                    pos.speed = UnitsConverter.knotsFromKph(speed)
                    pos.set(Position.KEY_HDOP, buf.readByte())
                } else {
                    int speed = buf.readShortLE()
                    pos.valid = speed != -1
                    pos.speed = UnitsConverter.knotsFromKph(speed / 100.0)
                }

                pos.course = buf.readShortLE() / 100.0
                pos.altitude = buf.readShortLE()

                if (subtype == MSG_STATE_FULL_INFO_T104) {
                    pos.set(Position.KEY_SATELLITES, buf.readUByte())
                    pos.set(Position.KEY_RSSI, buf.readUByte())
                    pos.set(Position.KEY_EVENT, buf.readUShortLE())
                    pos.set(Position.KEY_ODOMETER, buf.readUIntLE())
                    pos.set(Position.KEY_INPUT, buf.readUByte())
                    pos.set(Position.KEY_OUTPUT, buf.readUByte())
                    for (int i = 1; i <= 8; i++) pos.set(Position.PREFIX_ADC + i, buf.readUShortLE())
                    for (int i = 1; i <= 3; i++) pos.set(Position.PREFIX_COUNT + i, buf.readUIntLE())
                }

                positions.add(pos)
            }

            if (buf.remaining() >= 4) buf.readUIntLE()
            if (type == MSG_LOG_RECORDS) {
                def request = requestArchive()
                if (request != null) ctx.ack(request)
            } else {
                ctx.ack(simpleMessage(MSG_REQUEST_LAST_LOG_INDEX))
            }

            positions
        }
    }
}
