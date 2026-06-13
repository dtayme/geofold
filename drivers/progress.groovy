// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Progress binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/progress/
 *
 * Supports identification, current point, alarm point, and archive log records
 * with archive sync requests.
 */

import org.traccar.model.Position

import java.util.Date

def MSG_IDENT = 1
def MSG_IDENT_FULL = 2
def MSG_POINT = 10
def MSG_LOG_SYNC = 100
def MSG_LOGMSG = 101
def MSG_ALARM = 200

long lastIndex = 0
long newIndex = 0

def check = { long value, int bit -> (value & (1L << bit)) != 0 }

def requestArchive = { ctx ->
    if (lastIndex == 0) {
        lastIndex = newIndex
    } else if (newIndex > lastIndex) {
        ctx.ack(bytes {
            writeShortLE MSG_LOG_SYNC
            writeShortLE 4
            writeIntLE((int) lastIndex)
            writeIntLE 0
        })
    }
}

def decodeProgress = { buf, ctx ->
    int type = buf.readUShortLE()
    buf.readUShortLE()

    if (type == MSG_IDENT || type == MSG_IDENT_FULL) {
        buf.readUIntLE()
        int length = buf.readUShortLE()
        buf.skip(length)
        length = buf.readUShortLE()
        buf.skip(length)
        length = buf.readUShortLE()
        ctx.session(buf.readString(length))
        return null
    }

    if (!(type == MSG_POINT || type == MSG_ALARM || type == MSG_LOGMSG)) return null

    def session = ctx.session()
    if (!session) return null

    int recordCount = type == MSG_LOGMSG ? buf.readUShortLE() : 1
    for (int j = 0; j < recordCount; j++) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId

        if (type == MSG_LOGMSG) {
            pos.set(Position.KEY_ARCHIVE, true)
            int subtype = buf.readUShortLE()
            if (subtype == MSG_ALARM) pos.addAlarm(ALARM_GENERAL)
            if (buf.readUShortLE() > buf.remaining()) {
                lastIndex += 1
                break
            }
            lastIndex = buf.readUIntLE()
            pos.set(Position.KEY_INDEX, lastIndex)
        } else {
            newIndex = buf.readUIntLE()
        }

        pos.time = new Date(buf.readUIntLE() * 1000L)
        pos.latitude = buf.readIntLE() * 180.0 / 0x7fffffff
        pos.longitude = buf.readIntLE() * 180.0 / 0x7fffffff
        pos.speed = buf.readUIntLE() / 100.0
        pos.course = buf.readUShortLE() / 100.0
        pos.altitude = buf.readUShortLE() / 100.0

        int satellites = buf.readUByte()
        pos.valid = satellites >= 3
        pos.set(Position.KEY_SATELLITES, satellites)
        pos.set(Position.KEY_RSSI, buf.readUByte())
        pos.set(Position.KEY_ODOMETER, buf.readUIntLE())

        long extraFlags = buf.readLongLE()
        if (check(extraFlags, 0)) {
            int count = buf.readUShortLE()
            for (int i = 1; i <= count; i++) pos.set(Position.PREFIX_ADC + i, buf.readUShortLE())
        }
        if (check(extraFlags, 1)) {
            int size = buf.readUShortLE()
            pos.set("can", buf.readString(size))
        }
        if (check(extraFlags, 2)) {
            pos.set("passenger", buf.readHex(buf.readUShortLE()))
        }

        if (type == MSG_ALARM) {
            pos.addAlarm(ALARM_GENERAL)
            ctx.ack([0xC9, 0, 0, 0, 0, 0, 0, 0] as byte[])
        }

        buf.readUIntLE()
        ctx.emit(pos)
    }

    requestArchive(ctx)
    null
}

protocol("progress") {
    port 5012
    variant("main") {
        frame readLengthFieldLE(2, 2, 4)
        decode decodeProgress
    }
}
