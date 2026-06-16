// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * BCE GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/bce/ (Java reference)
 *
 * Binary batch protocol on port 5080.
 *
 * Connection opens with a "#BCE#\r\n" handshake (7 bytes), discarded.
 *
 * Data frame: IMEI(8, LE) + [chunk]... + checksum(1)
 *   chunk:  length(2, LE) + type(1) + confirmKey(1) + [struct]...
 *   struct: length(1) + rawTime(4, LE) + [mask chain] + [field data]
 *
 * Chunk types:
 *   0xA5 MSG_ASYNC_STACK     — requires an ACK
 *   0xA0 MSG_TIME_TRIGGERED  — no ACK
 *
 * ACK (13 bytes): IMEI(8, LE) + 0x02 0x00 + 0x19 + confirmKey + checksum(1)
 *
 * Frame checksum: sum of all preceding bytes, mod 256.
 *
 * A struct only carries position data when (rawTime & 0x0F) == 7. Timestamp
 * is then `(rawTime >> 4 << 1) + 0x47798280` (seconds since 2008-01-01 UTC),
 * advanced by 2^29 seconds while still in the past (rollover handling).
 *
 * Mask chain: read 16-bit little-endian words while bit 15 is set; each word
 * is a field-presence bitmask for one of up to four field groups.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

def decodeMask1 = { pos, struct, int mask ->
    if (checkBit(mask, 0)) {
        pos.valid = true
        pos.longitude = Float.intBitsToFloat(struct.readIntLE())
        pos.latitude  = Float.intBitsToFloat(struct.readIntLE())
        pos.speed     = UnitsConverter.knotsFromKph(struct.readUByte())
        int status = struct.readUByte()
        pos.set(Position.KEY_SATELLITES, status & 0x0F)
        pos.set(Position.KEY_HDOP,       (status >> 4) & 0x0F)
        pos.course   = struct.readUByte() * 2
        pos.altitude = struct.readUShortLE()
        pos.set(Position.KEY_ODOMETER, struct.readUIntLE())
    }
    if (checkBit(mask, 1)) pos.set(Position.KEY_INPUT, struct.readUShortLE())
    for (int i = 1; i <= 8; i++) {
        if (checkBit(mask, i + 1)) pos.set(Position.PREFIX_ADC + i, struct.readUShortLE())
    }
    if (checkBit(mask, 10)) struct.skip(4)
    if (checkBit(mask, 11)) struct.skip(4)
    if (checkBit(mask, 12)) pos.set('fuel1', struct.readUShort())
    if (checkBit(mask, 13)) pos.set('fuel2', struct.readUShort())
    if (checkBit(mask, 14)) {
        int mcc = struct.readUShortLE()
        int mnc = struct.readUByte()
        int lac = struct.readUShortLE()
        int cid = struct.readUShortLE()
        struct.skip(1) // time advance
        int rssi = -struct.readUByte()
        pos.network = new Network(CellTower.from(mcc, mnc, lac, cid, rssi))
    }
}

def decodeMask2 = { pos, struct, int mask ->
    if (checkBit(mask, 0))  struct.skip(2) // wheel speed
    if (checkBit(mask, 1))  struct.skip(1) // acceleration pedal
    if (checkBit(mask, 2))  pos.set(Position.KEY_FUEL_USED, struct.readUIntLE() * 0.5)
    if (checkBit(mask, 3))  pos.set(Position.KEY_FUEL, struct.readUByte())
    if (checkBit(mask, 4))  pos.set(Position.KEY_RPM, struct.readUShortLE() * 0.125)
    if (checkBit(mask, 5))  pos.set(Position.KEY_HOURS, struct.readUIntLE())
    if (checkBit(mask, 6))  pos.set(Position.KEY_ODOMETER, struct.readUIntLE())
    if (checkBit(mask, 7))  pos.set(Position.KEY_COOLANT_TEMP, struct.readByte() - 40)
    if (checkBit(mask, 8))  pos.set('fuel2', struct.readUByte())
    if (checkBit(mask, 9))  pos.set(Position.KEY_ENGINE_LOAD, struct.readUByte())
    if (checkBit(mask, 10)) pos.set(Position.KEY_ODOMETER_SERVICE, struct.readUShortLE())
    if (checkBit(mask, 11)) struct.skip(8) // sensors
    if (checkBit(mask, 12)) struct.skip(2) // ambient air temperature
    if (checkBit(mask, 13)) struct.skip(8) // trailer id
    if (checkBit(mask, 14)) pos.set(Position.KEY_FUEL_CONSUMPTION, struct.readUShortLE())
}

def decodeMask3 = { pos, struct, int mask ->
    if (checkBit(mask, 0))  struct.skip(2) // fuel economy
    if (checkBit(mask, 1))  pos.set(Position.KEY_FUEL_CONSUMPTION, struct.readUIntLE())
    if (checkBit(mask, 2)) {
        int b0 = struct.readUByte()
        int b1 = struct.readUByte()
        int b2 = struct.readUByte()
        pos.set(Position.KEY_AXLE_WEIGHT, (b2 << 16) | (b1 << 8) | b0)
    }
    if (checkBit(mask, 3))  struct.skip(1)  // mil status
    if (checkBit(mask, 4))  struct.skip(20) // dtc
    if (checkBit(mask, 5))  struct.skip(2)
    if (checkBit(mask, 6))  pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(struct.readLongLE()))
    if (checkBit(mask, 7))  pos.set(Position.PREFIX_TEMP + 1, struct.readUShortLE() / 10.0 - 273)
    if (checkBit(mask, 8))  struct.skip(2) // dallas humidity
    if (checkBit(mask, 9)) {
        pos.set('fuel1',     struct.readUShortLE())
        pos.set('fuelTemp1', struct.readByte())
        pos.set('fuel2',     struct.readUShortLE())
        pos.set('fuelTemp2', struct.readByte())
    }
    if (checkBit(mask, 10)) {
        pos.set('fuel3',     struct.readUShortLE())
        pos.set('fuelTemp3', struct.readByte())
        pos.set('fuel4',     struct.readUShortLE())
        pos.set('fuelTemp4', struct.readByte())
    }
    if (checkBit(mask, 11)) struct.skip(21) // j1979 group 1
    if (checkBit(mask, 12)) struct.skip(20) // j1979 dtc
    if (checkBit(mask, 13)) struct.skip(9)  // j1708 group 1
    if (checkBit(mask, 14)) struct.skip(21) // driving quality
}

def decodeMask4 = { pos, struct, int mask ->
    if (checkBit(mask, 0))  struct.skip(4)
    if (checkBit(mask, 1))  struct.skip(30) // lls group 3
    if (checkBit(mask, 2))  struct.skip(4)  // instant fuel consumption
    if (checkBit(mask, 3))  struct.skip(10) // axle weight group
    if (checkBit(mask, 4))  struct.skip(1)
    if (checkBit(mask, 5))  struct.skip(2)
    if (checkBit(mask, 6)) {
        pos.set('maxAcceleration', struct.readUByte() / 50.0)
        pos.set('maxBraking',      struct.readUByte() / 50.0)
        pos.set('maxCornering',    struct.readUByte() / 50.0)
    }
    if (checkBit(mask, 7))  struct.skip(16)
    if (checkBit(mask, 8)) {
        for (int i = 1; i <= 4; i++) {
            int temperature = struct.readUShortLE()
            if (temperature > 0) pos.set(Position.PREFIX_TEMP + i, temperature / 10.0 - 273)
            struct.skip(8)
        }
    }
    if (checkBit(mask, 9)) {
        pos.set('driver1', struct.readString(16).trim())
        pos.set('driver2', struct.readString(16).trim())
    }
    if (checkBit(mask, 10)) pos.set(Position.KEY_ODOMETER, struct.readUIntLE())
}

protocol("bce") {

    port 5080
    commands TYPE_OUTPUT_CONTROL

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 2) return null

            if (fb.readableBytes() >= 7 && fb.ascii(0, 5) == '#BCE#') return 7

            int end = 8 // IMEI
            while (fb.readableBytes() >= end + 5) {
                int chunkLen = fb.getUShortLE(end)
                end += chunkLen + 2
                if (fb.readableBytes() > end) {
                    int sum = 0
                    for (int i = 0; i < end; i++) sum += fb.getUByte(i)
                    if ((sum & 0xFF) == fb.getUByte(end)) return end + 1
                }
            }
            return null
        }

        decode { msg, ctx ->
            def buf = msg as org.traccar.driver.BufReader

            if (buf.getUByte(0) == 0x23) return null // "#BCE#\r\n" handshake

            String imei = String.format("%015d", buf.readLongLE())
            def session = ctx.session(imei)
            if (!session) return null

            while (buf.readableBytes() > 1) {
                int chunkLen = buf.readUShortLE()
                def chunk = buf.slice(chunkLen)
                int type = chunk.readUByte()
                if (type != 0xA5 && type != 0xA0) return null
                int confirmKey = chunk.readUByte() & 0x7F

                while (chunk.readableBytes() > 0) {
                    int structLen = chunk.readUByte()
                    def struct = chunk.slice(structLen)

                    long rawTime = struct.readUIntLE()
                    if ((rawTime & 0x0F) == 7) {
                        long t = (rawTime >> 4 << 1) + 0x47798280L
                        long threshold = System.currentTimeMillis() / 1000L - 3650L * 86400L
                        while (t < threshold) t += 0x0FFFFFFF * 2L

                        def masks = []
                        int mask
                        do {
                            mask = struct.readUShortLE()
                            masks.add(mask)
                        } while (checkBit(mask, 15))

                        def pos = ctx.newPosition()
                        pos.deviceId = session.deviceId
                        pos.time = new Date(t * 1000L)

                        decodeMask1(pos, struct, masks[0])
                        if (masks.size() >= 2) decodeMask2(pos, struct, masks[1])
                        if (masks.size() >= 3) decodeMask3(pos, struct, masks[2])
                        if (masks.size() >= 4) decodeMask4(pos, struct, masks[3])

                        if (pos.valid) {
                            ctx.emit(pos)
                        } else if (!pos.attributes.isEmpty()) {
                            ctx.lastLocation(pos)
                            ctx.emit(pos)
                        }
                    }
                }

                if (type == 0xA5) {
                    byte[] ack = new byte[13]
                    long imeiLong = Long.parseLong(imei)
                    for (int i = 0; i < 8; i++) ack[i] = (byte) ((imeiLong >> (8 * i)) & 0xFF)
                    ack[8]  = (byte) 2
                    ack[9]  = (byte) 0
                    ack[10] = (byte) 0x19
                    ack[11] = (byte) confirmKey
                    int chk = 0
                    for (int i = 0; i < 12; i++) chk += ack[i] & 0xFF
                    ack[12] = (byte) (chk & 0xFF)
                    ctx.ack(ack)
                }
            }

            return null
        }
    }
}
