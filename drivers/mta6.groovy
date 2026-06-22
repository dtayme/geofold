// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * MTA6 driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/mta6/
 *   archived-protocols/mta6/
 *
 * HTTP POST protocol. Body is ASCII-prefixed binary: "id=<uniqueId>&bin=<payload>".
 * The binary payload uses a delta-encoded float scheme for lat/lon/time.
 *
 * Two decode formats are selected by packet ID (0x31/0x32/0x36):
 *   - Format A  (batch): stateful delta-compressed floats, multiple positions.
 *   - Format A1 (simple): stateless floats, single position, richer fields.
 *
 * The active format is controlled by the mta6.can config key:
 *   mta6.can = false (default) → format A1 (simple)
 *   mta6.can = true            → format A  (batch, delta-compressed)
 *
 * The server sends HTTP 100 Continue followed by an "#ACK#" response.
 */

import io.netty.buffer.Unpooled
import org.traccar.helper.BitUtil
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Arrays

// Returns a closure that delta-decodes IEEE-754 floats from a ByteBuf.
def makeFloatReader = {
    def prev = [0]  // int wrapped in list for mutable capture
    return { buf ->
        int tag = (buf.getUnsignedByte(buf.readerIndex()) >> 6) & 3
        switch (tag) {
            case 0: prev[0] = buf.readInt() << 2; break
            case 1: prev[0] = (prev[0] & 0xffffff00) + ((buf.readUnsignedByte() & 0x3f) << 2); break
            case 2: prev[0] = (prev[0] & 0xffff0000) + ((buf.readUnsignedShort() & 0x3fff) << 2); break
            case 3: prev[0] = (prev[0] & 0xff000000) + ((buf.readUnsignedMedium() & 0x3fffff) << 2); break
        }
        Float.intBitsToFloat(prev[0])
    }
}

// Returns a closure that reads GPS week time + week number to produce a Date.
def makeTimeReader = {
    def fr = makeFloatReader()
    def weekNum = [0L]
    return { buf ->
        long weekTime = (long)(fr(buf) * 1000)
        if (weekNum[0] == 0) weekNum[0] = buf.readUnsignedShort()
        new DateBuilder().setDate(1980, 1, 6)
            .addMillis(weekNum[0] * 7L * 24 * 60 * 60 * 1000 + weekTime)
            .getDate()
    }
}

def skipEvents = { buf ->
    short event = buf.readUnsignedByte()
    if (BitUtil.check(event, 7)) {
        if (BitUtil.check(event, 6)) {
            buf.skipBytes(8)
        } else {
            while (BitUtil.check(event, 7)) {
                event = buf.readUnsignedByte()
            }
        }
    }
}

// Batch format: stateful delta-compressed floats, multiple positions per message.
def parseFormatA = { session, buf, ctx ->
    def latReader  = makeFloatReader()
    def lonReader  = makeFloatReader()
    def timeReader = makeTimeReader()

    try {
        while (buf.isReadable()) {
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            short flags = buf.readUnsignedByte()
            skipEvents(buf)

            pos.latitude  = latReader(buf)  / Math.PI * 180
            pos.longitude = lonReader(buf)  / Math.PI * 180
            pos.time      = timeReader(buf)

            if (BitUtil.check(flags, 0)) buf.readUnsignedByte()  // status

            if (BitUtil.check(flags, 1)) pos.altitude = buf.readUnsignedShort()

            if (BitUtil.check(flags, 2)) {
                pos.speed  = buf.readUnsignedShort() & 0x03ff
                pos.course = buf.readUnsignedByte()
            }

            if (BitUtil.check(flags, 3)) pos.set(Position.KEY_ODOMETER, buf.readUnsignedShort() * 1000)

            if (BitUtil.check(flags, 4)) {
                pos.set(Position.KEY_FUEL_CONSUMPTION + 'Accumulator1', buf.readUnsignedInt())
                pos.set(Position.KEY_FUEL_CONSUMPTION + 'Accumulator2', buf.readUnsignedInt())
                pos.set('hours1', buf.readUnsignedShort())
                pos.set('hours2', buf.readUnsignedShort())
            }

            if (BitUtil.check(flags, 5)) {
                pos.set(Position.PREFIX_ADC + '1', buf.readUnsignedShort() & 0x03ff)
                pos.set(Position.PREFIX_ADC + '2', buf.readUnsignedShort() & 0x03ff)
                pos.set(Position.PREFIX_ADC + '3', buf.readUnsignedShort() & 0x03ff)
                pos.set(Position.PREFIX_ADC + '4', buf.readUnsignedShort() & 0x03ff)
            }

            if (BitUtil.check(flags, 6)) {
                pos.set(Position.PREFIX_TEMP + '1', buf.readByte())
                buf.getUnsignedByte(buf.readerIndex())  // control
                pos.set(Position.KEY_INPUT, buf.readUnsignedShort() & 0x0fff)
                buf.readUnsignedShort()  // old sensor state
            }

            if (BitUtil.check(flags, 7)) {
                pos.set(Position.KEY_BATTERY,     buf.getUnsignedByte(buf.readerIndex()) >> 2)
                pos.set(Position.KEY_POWER,       buf.readUnsignedShort() & 0x03ff)
                pos.set(Position.KEY_DEVICE_TEMP, buf.readByte())
                pos.set(Position.KEY_RSSI,        (buf.getUnsignedByte(buf.readerIndex()) >> 4) & 0x07)
                int sats = buf.readUnsignedByte() & 0x0f
                pos.valid = sats >= 3
                pos.set(Position.KEY_SATELLITES, sats)
            }

            ctx.emit(pos)
        }
    } catch (IndexOutOfBoundsException ignored) {
    }
}

// Simple format: stateless floats, single position, richer optional fields.
def parseFormatA1 = { session, buf, ctx ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    short flags = buf.readUnsignedByte()
    skipEvents(buf)

    pos.latitude  = makeFloatReader()(buf) / Math.PI * 180
    pos.longitude = makeFloatReader()(buf) / Math.PI * 180
    pos.time      = makeTimeReader()(buf)

    pos.set(Position.KEY_STATUS, buf.readUnsignedByte())

    if (BitUtil.check(flags, 0)) {
        pos.altitude = buf.readUnsignedShort()
        pos.speed    = buf.readUnsignedByte()
        pos.course   = buf.readByte()
        pos.set(Position.KEY_ODOMETER, makeFloatReader()(buf))
    }

    if (BitUtil.check(flags, 1)) {
        pos.set(Position.KEY_FUEL_CONSUMPTION, makeFloatReader()(buf))
        pos.set(Position.KEY_HOURS,            UnitsConverter.msFromHours(makeFloatReader()(buf)))
        pos.set('tank',                        buf.readUnsignedByte() * 0.4)
    }

    if (BitUtil.check(flags, 2)) {
        pos.set('engine',                  buf.readUnsignedShort() * 0.125)
        pos.set('pedals',                  buf.readUnsignedByte())
        pos.set(Position.PREFIX_TEMP + '1', buf.readUnsignedByte() - 40)
        pos.set(Position.KEY_ODOMETER_SERVICE, buf.readUnsignedShort())
    }

    if (BitUtil.check(flags, 3)) {
        pos.set(Position.KEY_FUEL,         buf.readUnsignedShort())
        pos.set(Position.PREFIX_ADC + '2', buf.readUnsignedShort())
        pos.set(Position.PREFIX_ADC + '3', buf.readUnsignedShort())
        pos.set(Position.PREFIX_ADC + '4', buf.readUnsignedShort())
    }

    if (BitUtil.check(flags, 4)) {
        pos.set(Position.PREFIX_TEMP + '1', buf.readByte())
        buf.getUnsignedByte(buf.readerIndex())  // control
        pos.set(Position.KEY_INPUT, buf.readUnsignedShort() & 0x0fff)
        buf.readUnsignedShort()  // old sensor state
    }

    if (BitUtil.check(flags, 5)) {
        pos.set(Position.KEY_BATTERY,     buf.getUnsignedByte(buf.readerIndex()) >> 2)
        pos.set(Position.KEY_POWER,       buf.readUnsignedShort() & 0x03ff)
        pos.set(Position.KEY_DEVICE_TEMP, buf.readByte())
        pos.set(Position.KEY_RSSI,        buf.getUnsignedByte(buf.readerIndex()) >> 5)
        int sats = buf.readUnsignedByte() & 0x1f
        pos.valid = sats >= 3
        pos.set(Position.KEY_SATELLITES, sats)
    }

    return pos
}

protocol("mta6") {

    port 5028
    transport 'http'

    variant("binary") {

        decode { req, ctx ->
            byte[] bodyBytes = req.bytes()

            // Parse ASCII prefix: "id=<uniqueId>&bin=<binary>"
            int idStart = 3
            int ampIdx = idStart
            while (ampIdx < bodyBytes.length && bodyBytes[ampIdx] != (byte) '&') ampIdx++
            def uniqueId = new String(bodyBytes, idStart, ampIdx - idStart, 'US-ASCII')

            def session = ctx.session(uniqueId)
            if (!session) { ctx.badRequest(); return null }

            int binStart = ampIdx + 5  // skip "&bin="
            def buf = Unpooled.wrappedBuffer(Arrays.copyOfRange(bodyBytes, binStart, bodyBytes.length))

            try {
                short packetId    = buf.readUnsignedByte()
                short offset      = buf.readUnsignedByte()
                short packetCount = buf.readUnsignedByte()
                buf.readUnsignedByte()  // reserved
                buf.readUnsignedByte()  // timezone
                buf.skipBytes(offset - 5)

                ctx.sendContinue()
                byte[] ack = ('#ACK#'.bytes as byte[]) + ([packetId as byte, packetCount as byte, 0 as byte] as byte[])
                ctx.binary(200, ack, null)

                if (packetId == 0x31 || packetId == 0x32 || packetId == 0x36) {
                    boolean simple = !ctx.configBoolean('can', false)
                    if (simple) {
                        return parseFormatA1(session, buf, ctx)
                    } else {
                        parseFormatA(session, buf, ctx)
                    }
                }
                return null
            } finally {
                buf.release()
            }
        }
    }
}
