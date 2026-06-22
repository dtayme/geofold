// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Navis GPS tracker protocol driver.
 *
 * Binary TCP with two sub-protocols on port 5010:
 *   NTCB – 16-byte header frames starting with '@'
 *   FLEX – variable-length frames starting with '~' or 0x7F (keepalive)
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position
import io.netty.buffer.Unpooled
import org.traccar.NetworkMessage

import java.nio.ByteBuffer
import java.util.Date

// ── FLEX field sizes (index → byte count) ─────────────────────────────────────
int[] FLEX_FIELDS_SIZES = [
    4, 2, 4, 1, 1, 1, 1, 1, 4, 4, 4, 4, 4, 2, 4, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 4, 4, 2, 2,
    4, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 2, 4, 2, 1, 4, 2, 2, 2, 2, 2, 1, 1, 1, 2, 4, 2, 1,
    // FLEX 2.0
    8, 2, 1, 16, 4, 2, 4, 37, 1, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 6, 12, 24, 48, 1, 1, 1, 1, 4, 4,
    1, 4, 2, 6, 2, 6, 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 1,
    // FLEX 3.0
    1, 1, 1, 1, 4, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 1, 1, 2, 3, 2, 1, 1, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 2, 4, 4, 4, 2, 4, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 4,
    4, 4, 6, 3, 1, 2, 2, 1, 4, 5, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8
]

// ── NTCB position format constants ────────────────────────────────────────────
def F10 = 0x01; def F20 = 0x02; def F30 = 0x03; def F40 = 0x04
def F50 = 0x05; def F51 = 0x15; def F52 = 0x25; def F60 = 0x06

// ── Per-channel persistent state (script-level, persists across decode calls) ─
def navisPrefix          = "@NTC"
def navisServerId        = 0L
def navisDeviceUniqueId  = 0L
def navisFlexDataSize    = 0
def navisFlexBitFieldSize = 0
byte[] navisFlexBitField = new byte[32]

// ── Helpers ───────────────────────────────────────────────────────────────────

def isFormat = { int fmt, int... fmts ->
    for (int f : fmts) if (fmt == f) return true
    false
}

def checkFlexBit = { int i ->
    BitUtil.check(navisFlexBitField[Math.floorDiv(i, 8)], 7 - Math.floorMod(i, 8))
}

def leInt = { long v ->
    [(byte)(v & 0xFF), (byte)((v >> 8) & 0xFF), (byte)((v >> 16) & 0xFF), (byte)((v >> 24) & 0xFF)] as byte[]
}

def leShort = { int v ->
    [(byte)(v & 0xFF), (byte)((v >> 8) & 0xFF)] as byte[]
}

def asciiBytes = { String s -> s.getBytes("US-ASCII") }

def ntcbReply = { byte[] data ->
    byte dxor = (byte) Checksum.xor(ByteBuffer.wrap(data))
    byte[] pfx = navisPrefix.getBytes("US-ASCII")
    byte[] did = leInt(navisDeviceUniqueId)
    byte[] sid = leInt(navisServerId)
    byte[] len = leShort(data.length)
    byte[] h15 = new byte[15]
    System.arraycopy(pfx, 0, h15, 0,  4)
    System.arraycopy(did, 0, h15, 4,  4)
    System.arraycopy(sid, 0, h15, 8,  4)
    System.arraycopy(len, 0, h15, 12, 2)
    h15[14] = dxor
    byte hxor = (byte) Checksum.xor(ByteBuffer.wrap(h15))
    byte[] full = new byte[16 + data.length]
    System.arraycopy(h15, 0, full, 0, 15)
    full[15] = hxor
    System.arraycopy(data, 0, full, 16, data.length)
    full
}

def flexReply = { byte[] data ->
    int crc = Checksum.crc8(Checksum.CRC8_EGTS, ByteBuffer.wrap(data))
    byte[] full = new byte[data.length + 1]
    System.arraycopy(data, 0, full, 0, data.length)
    full[data.length] = (byte) crc
    full
}

// ── NTCB position parser ──────────────────────────────────────────────────────

def parseNtcbPos = { ctx, session, buf ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    int fmt
    if (buf.getUByte(0) == 0) {
        fmt = buf.readUShortLE()
    } else {
        fmt = buf.readUByte()
    }
    pos.set("format", fmt)

    pos.set(Position.KEY_INDEX, buf.readUIntLE())
    pos.set(Position.KEY_EVENT, buf.readUShortLE())
    buf.skip(6) // event time

    int armed = buf.readUByte()
    if (isFormat(fmt, F10, F20, F30, F40, F50, F51, F52)) {
        pos.set(Position.KEY_ARMED, BitUtil.to(armed, 7))
        if (BitUtil.check(armed, 7)) pos.addAlarm(Position.ALARM_GENERAL)
    } else if (isFormat(fmt, F60)) {
        pos.set(Position.KEY_ARMED, BitUtil.check(armed, 0))
        if (BitUtil.check(armed, 1)) pos.addAlarm(Position.ALARM_GENERAL)
    }

    pos.set(Position.KEY_STATUS, buf.readUByte())
    pos.set(Position.KEY_RSSI,   buf.readUByte())

    if (isFormat(fmt, F10, F20, F30)) {
        int out = buf.readUShortLE()
        pos.set(Position.KEY_OUTPUT, out)
        for (int i = 0; i < 16; i++) pos.set(Position.PREFIX_OUT + (i + 1), BitUtil.check(out, i))
    } else if (isFormat(fmt, F50, F51, F52)) {
        int ext = buf.readUByte()
        pos.set(Position.KEY_OUTPUT, BitUtil.to(ext, 2))
        pos.set(Position.PREFIX_OUT + 1, BitUtil.check(ext, 0))
        pos.set(Position.PREFIX_OUT + 2, BitUtil.check(ext, 1))
        pos.set(Position.KEY_SATELLITES, BitUtil.from(ext, 2))
    } else if (isFormat(fmt, F40, F60)) {
        int out = buf.readUByte()
        pos.set(Position.KEY_OUTPUT, BitUtil.to(out, 4))
        for (int i = 0; i < 4; i++) pos.set(Position.PREFIX_OUT + (i + 1), BitUtil.check(out, i))
    }

    if (isFormat(fmt, F10, F20, F30, F40)) {
        int inp = buf.readUShortLE()
        pos.set(Position.KEY_INPUT, inp)
        if (!isFormat(fmt, F40)) {
            for (int i = 0; i < 16; i++) pos.set(Position.PREFIX_IN + (i + 1), BitUtil.check(inp, i))
        } else {
            pos.set(Position.PREFIX_IN + 1, BitUtil.check(inp, 0))
            pos.set(Position.PREFIX_IN + 2, BitUtil.check(inp, 1))
            pos.set(Position.PREFIX_IN + 3, BitUtil.check(inp, 2))
            pos.set(Position.PREFIX_IN + 4, BitUtil.check(inp, 3))
            pos.set(Position.PREFIX_IN + 5, BitUtil.between(inp, 4, 7))
            pos.set(Position.PREFIX_IN + 6, BitUtil.between(inp, 7, 10))
            pos.set(Position.PREFIX_IN + 7, BitUtil.between(inp, 10, 12))
            pos.set(Position.PREFIX_IN + 8, BitUtil.between(inp, 12, 14))
        }
    } else if (isFormat(fmt, F50, F51, F52, F60)) {
        int inp = buf.readUByte()
        pos.set(Position.KEY_INPUT, inp)
        for (int i = 0; i < 8; i++) pos.set(Position.PREFIX_IN + (i + 1), BitUtil.check(inp, i))
    }

    pos.set(Position.KEY_POWER,   buf.readUShortLE() / 1000.0)
    pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 1000.0)

    if (isFormat(fmt, F10, F20, F30)) {
        pos.set(Position.PREFIX_TEMP + 1, buf.readShortLE())
    }

    if (isFormat(fmt, F10, F20, F50, F51, F52, F60)) {
        pos.set(Position.PREFIX_ADC + 1, buf.readUShortLE())
        pos.set(Position.PREFIX_ADC + 2, buf.readUShortLE())
    }
    if (isFormat(fmt, F60)) {
        pos.set(Position.PREFIX_ADC + 3, buf.readUShortLE())
    }

    if (isFormat(fmt, F20, F50, F51, F52, F60)) {
        buf.readUIntLE(); buf.readUIntLE() // impulse counters
    }

    if (isFormat(fmt, F60)) {
        buf.readUShortLE(); buf.readUShortLE(); buf.readByte()
        buf.readShortLE();  buf.readByte();    buf.readUShortLE(); buf.readByte()
        buf.readUShortLE(); buf.readByte();    buf.readUShortLE(); buf.readByte()
        buf.readUShortLE(); buf.readByte();    buf.readUShortLE(); buf.readByte()
        buf.readUShortLE(); buf.readByte();    buf.readUShortLE()
        pos.set(Position.PREFIX_TEMP + 1, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 2, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 3, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 4, buf.readByte())
        pos.set(Position.KEY_AXLE_WEIGHT, buf.readIntLE())
        pos.set(Position.KEY_RPM,         buf.readUShortLE())
    }

    if (isFormat(fmt, F20, F50, F51, F52, F60)) {
        int nav = buf.readUByte()
        pos.valid = BitUtil.check(nav, 1)
        if (isFormat(fmt, F60)) pos.set(Position.KEY_SATELLITES, BitUtil.from(nav, 2))

        def db = new DateBuilder()
                .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
                .setDateReverse(buf.readUByte(), buf.readUByte() + 1, buf.readUByte())
        pos.time = db.getDate()

        if (isFormat(fmt, F60)) {
            pos.latitude  = buf.readIntLE() / 600000.0
            pos.longitude = buf.readIntLE() / 600000.0
            pos.altitude  = buf.readIntLE() / 10.0
        } else {
            pos.latitude  = Float.intBitsToFloat(buf.readIntLE()) / Math.PI * 180
            pos.longitude = Float.intBitsToFloat(buf.readIntLE()) / Math.PI * 180
        }

        pos.speed  = UnitsConverter.knotsFromKph(Float.intBitsToFloat(buf.readIntLE()))
        pos.course = buf.readUShortLE()
        pos.set(Position.KEY_ODOMETER, Float.intBitsToFloat(buf.readIntLE()) * 1000)
        pos.set(Position.KEY_DISTANCE, Float.intBitsToFloat(buf.readIntLE()) * 1000)
        buf.readUShortLE(); buf.readUShortLE() // segment times
    }

    if (isFormat(fmt, F51, F52)) {
        buf.readUShortLE(); buf.readByte()
        buf.readUShortLE(); buf.readUShortLE(); buf.readByte()
        buf.readUShortLE(); buf.readUShortLE(); buf.readByte()
        buf.readUShortLE()
    }

    if (isFormat(fmt, F40, F52)) {
        pos.set(Position.PREFIX_TEMP + 1, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 2, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 3, buf.readByte())
        pos.set(Position.PREFIX_TEMP + 4, buf.readByte())
    }

    pos
}

// ── FLEX 1.x / 3.x position parser ───────────────────────────────────────────

def parseFlexPos = { ctx, session, buf ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    int status = 0; int inp = 0; int out = 0

    for (int i = 0; i < navisFlexBitFieldSize; i++) {
        if (!checkFlexBit(i)) continue
        int value
        switch (i) {
            case 0:  pos.set(Position.KEY_INDEX, buf.readUIntLE()); break
            case 1:  pos.set(Position.KEY_EVENT, buf.readUShortLE()); break
            case 2:  pos.deviceTime = new Date(buf.readUIntLE() * 1000L); break
            case 3:
                int a = buf.readUByte()
                pos.set(Position.KEY_ARMED, BitUtil.check(a, 0))
                if (BitUtil.check(a, 1)) pos.addAlarm(Position.ALARM_GENERAL)
                break
            case 4:
                status = buf.readUByte(); pos.set(Position.KEY_STATUS, status); break
            case 5:
                int s2 = buf.readUByte()
                pos.set(Position.KEY_STATUS, (short)(BitUtil.to(status, 8) | (s2 << 8)))
                break
            case 6:  pos.set(Position.KEY_RSSI, buf.readUByte()); break
            case 7:
                int nav = buf.readUByte()
                pos.valid = BitUtil.check(nav, 1)
                pos.set(Position.KEY_SATELLITES, BitUtil.from(nav, 2))
                break
            case 8:  pos.time = new DateBuilder(new Date(buf.readUIntLE() * 1000L)).getDate(); break
            case 9:  pos.latitude  = buf.readIntLE() / 600000.0; break
            case 10: pos.longitude = buf.readIntLE() / 600000.0; break
            case 11: pos.altitude  = buf.readIntLE() / 10.0; break
            case 12: pos.speed  = UnitsConverter.knotsFromKph(Float.intBitsToFloat(buf.readIntLE())); break
            case 13: pos.course = buf.readUShortLE(); break
            case 14: pos.set(Position.KEY_ODOMETER, Float.intBitsToFloat(buf.readIntLE()) * 1000); break
            case 15: pos.set(Position.KEY_DISTANCE, Float.intBitsToFloat(buf.readIntLE()) * 1000); break
            case 18: pos.set(Position.KEY_POWER,   buf.readUShortLE() / 1000.0); break
            case 19: pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 1000.0); break
            case 20: case 21: case 22: case 23: case 24: case 25: case 26: case 27:
                pos.set(Position.PREFIX_ADC + (i - 19), buf.readUShortLE()); break
            case 28:
                inp = buf.readUByte(); pos.set(Position.KEY_INPUT, inp)
                for (int k = 0; k < 8; k++) pos.set(Position.PREFIX_IN + (k + 1), BitUtil.check(inp, k))
                break
            case 29:
                int i2 = buf.readUByte()
                pos.set(Position.KEY_INPUT, (short)(BitUtil.to(inp, 8) | (i2 << 8)))
                for (int k = 0; k < 8; k++) pos.set(Position.PREFIX_IN + (k + 9), BitUtil.check(i2, k))
                break
            case 30:
                out = buf.readUByte(); pos.set(Position.KEY_OUTPUT, out)
                for (int k = 0; k < 8; k++) pos.set(Position.PREFIX_OUT + (k + 1), BitUtil.check(out, k))
                break
            case 31:
                int o2 = buf.readUByte()
                pos.set(Position.KEY_OUTPUT, (short)(BitUtil.to(out, 8) | (o2 << 8)))
                for (int k = 0; k < 8; k++) pos.set(Position.PREFIX_OUT + (k + 9), BitUtil.check(o2, k))
                break
            case 32: case 33:
                pos.set(Position.PREFIX_COUNT + (i - 31), buf.readUIntLE()); break
            case 34: case 35:
                pos.set("freq" + (i - 33), buf.readUShortLE()); break
            case 36: pos.set(Position.KEY_HOURS, buf.readUIntLE() * 1000L); break
            case 37: case 38: case 39: case 40: case 41: case 42:
                value = buf.readUShortLE()
                if (value < 65500) pos.set(Position.KEY_FUEL + (i - 36), value); break
            case 43:
                value = buf.readUShortLE()
                if (value < 65500) pos.set(Position.KEY_FUEL, value); break
            case 44: case 45: case 46: case 47: case 48: case 49: case 50: case 51:
                pos.set(Position.PREFIX_TEMP + (i - 43), buf.readByte()); break
            case 52:
                value = buf.readUShortLE()
                if (value != 0x7FFF) {
                    if (BitUtil.check(value, 15)) pos.set("obdFuelLevel", BitUtil.to(value, 14))
                    else pos.set("obdFuel", BitUtil.to(value, 14) / 10.0)
                }
                break
            case 53:
                double fu = Float.intBitsToFloat(buf.readIntLE()) * 0.5
                if (fu >= 0) pos.set(Position.KEY_FUEL_USED, fu); break
            case 54:
                value = buf.readUShortLE()
                if (value != 0xFFFF) pos.set(Position.KEY_RPM, value); break
            case 55:
                value = buf.readByte()
                if (value != (int)(byte)0x80) pos.set(Position.KEY_COOLANT_TEMP, value); break
            case 56: pos.set(Position.KEY_OBD_ODOMETER, Float.intBitsToFloat(buf.readIntLE()) * 1000); break
            case 57: case 58: case 59: case 60: case 61:
                value = buf.readUShortLE()
                if (value != 0xFFFF) pos.set("axleWeight" + (i - 56), value); break
            case 62:
                value = buf.readUByte()
                if (value != 0xFF) pos.set("acceleratorPosition", value); break
            case 63:
                value = buf.readUByte()
                if (value != 0xFF) pos.set("brakePosition", value); break
            case 64:
                value = buf.readUByte()
                if (value != 0xFF) pos.set(Position.KEY_ENGINE_LOAD, value); break
            case 65:
                value = buf.readUShortLE()
                if (value != 0x7FFF) {
                    if (BitUtil.check(value, 15)) pos.set("obdAdBlueLevel", BitUtil.to(value, 14))
                    else pos.set("obdAdBlue", BitUtil.to(value, 14) / 10.0)
                }
                break
            case 66: pos.set("obdHours", buf.readUIntLE() * 1000L); break
            case 67:
                value = buf.readUShortLE()
                if (value != 0xFFFF) pos.set(Position.KEY_ODOMETER_SERVICE, value * 5000); break
            case 68: pos.set(Position.KEY_OBD_SPEED, buf.readUByte()); break
            // FLEX 2.0
            case 69:
                int sv = 0
                for (int k = 0; k < 8; k++) sv += buf.readUByte()
                pos.set(Position.KEY_SATELLITES_VISIBLE, sv); break
            case 70:
                pos.set(Position.KEY_HDOP, buf.readUByte() / 10.0)
                pos.set(Position.KEY_PDOP, buf.readUByte() / 10.0); break
            case 77: case 78: case 79: case 80: case 81: case 82:
                pos.set("fuelTemp" + (i - 76), buf.readByte()); break
            case 162: case 163: case 164: case 165:
                value = buf.readShortLE()
                if (value != (short)0x8000) pos.set(Position.PREFIX_TEMP + (i + 9 - 162), value / 20.0); break
            case 166: case 167: case 168: case 169:
                value = buf.readUByte()
                if (value != 0xFF) pos.set("humidity" + (i - 165), value * 0.5); break
            default:
                if (i >= 206 && i <= 221) {
                    pos.set("user1Byte" + (i - 205), buf.readUByte())
                } else if (i >= 222 && i <= 236) {
                    pos.set("user2Byte" + (i - 221), buf.readUShortLE())
                } else if (i >= 237 && i <= 251) {
                    pos.set("user4Byte" + (i - 236), buf.readUIntLE())
                } else if (i >= 252 && i <= 254) {
                    pos.set("user8Byte" + (i - 251), buf.readLongLE())
                } else if (i < FLEX_FIELDS_SIZES.length) {
                    buf.skip(FLEX_FIELDS_SIZES[i])
                }
        }
    }
    pos
}

// ── FLEX 2.0 extra-package position parser ────────────────────────────────────

def parseFlex20Pos = { ctx, session, buf ->
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    int length = buf.readUShort() // big-endian per Java source
    if (length <= buf.readableBytes() && buf.readUByte() == 0x0A) {
        buf.readUByte() // length of static part
        pos.set(Position.KEY_INDEX, buf.readUIntLE())
        pos.set(Position.KEY_EVENT, buf.readUShortLE())
        buf.readUInt() // event time (big-endian unsigned)
        int nav = buf.readUByte()
        pos.valid = BitUtil.check(nav, 1)
        pos.set(Position.KEY_SATELLITES, BitUtil.from(nav, 2))
        pos.time      = new DateBuilder(new Date(buf.readUIntLE() * 1000L)).getDate()
        pos.latitude  = buf.readIntLE() / 600000.0
        pos.longitude = buf.readIntLE() / 600000.0
        pos.altitude  = buf.readIntLE() / 10.0
        pos.speed     = UnitsConverter.knotsFromKph(Float.intBitsToFloat(buf.readIntLE()))
        pos.course    = buf.readUShortLE()
        pos.set(Position.KEY_ODOMETER, Float.intBitsToFloat(buf.readIntLE()) * 1000)
        // skip variable tail (length - 35 bytes already consumed + 2 for length field itself)
        // static read = 0x0A(1)+size(1)+idx(4)+evt(2)+evtTime(4)+nav(1)+gpsTime(4)+lat(4)+lon(4)+alt(4)+spd(4)+crs(2)+odo(4) = 39
        int consumed = 39 // bytes consumed after length field
        int toSkip = length - consumed
        if (toSkip > 0 && buf.readableBytes() >= toSkip) buf.skip(toSkip)
    }
    pos
}

// ── Frame length closure ──────────────────────────────────────────────────────

def NTCB_HDR = 16

protocol("navis") {

    port 5010

    variant("main") {

        scriptedFrame { fb ->
            if (fb.readableBytes() < 1) return null

            int first = fb.getUByte(0)

            if (first == 0x7F) {
                // FLEX keepalive
                return 1
            }

            if (first == (int) '@') {
                // NTCB: 16-byte header then data
                if (fb.readableBytes() < NTCB_HDR) return null
                int dataLen = fb.getUShortLE(12)
                int total = NTCB_HDR + dataLen
                return fb.readableBytes() >= total ? total : null
            }

            // FLEX: 2-byte type then variable content
            if (fb.readableBytes() < 2) return null
            String t2 = fb.ascii(0, 2)
            int flen = 0
            switch (t2) {
                case "~A":
                    if (fb.readableBytes() < 3) return null
                    flen = navisFlexDataSize * fb.getUByte(2) + 2
                    break
                case "~T":
                    flen = navisFlexDataSize + 5
                    break
                case "~C":
                    flen = navisFlexDataSize + 1
                    break
                case "~E":
                    int cnt = fb.getUByte(2)
                    int off = 3
                    flen = 1  // count byte
                    for (int i = 0; i < cnt; i++) {
                        if (fb.readableBytes() <= off + 1) return null
                        int plen = fb.getUShort(off)  // big-endian per protocol spec
                        off += plen + 2
                        flen += plen + 2
                    }
                    flen++  // CRC
                    break
                case "~X":
                    if (fb.readableBytes() < 4) return null
                    flen = fb.getUShortLE(2) + 5  // event_idx(4) + data(N) + CRC(1)
                    break
                default:
                    return null
            }
            return fb.readableBytes() >= 2 + flen ? 2 + flen : null
        }

        decode { msg, ctx ->

            int first = msg.getUByte(0)

            // FLEX keepalive
            if (first == 0x7F) {
                msg.skip(1)
                return null
            }

            // ── NTCB frame ────────────────────────────────────────────────
            if (first == (int) '@') {
                navisPrefix         = msg.readString(4)
                navisServerId       = msg.readUIntLE()
                navisDeviceUniqueId = msg.readUIntLE()
                int length = msg.readUShortLE()
                msg.skip(2) // checksums

                if (length == 0) return null // NTCB keepalive

                String type = msg.readString(3)

                if (type == "*>S") {
                    // Handshake
                    msg.skip(1) // ':'
                    String uid = msg.readString(msg.readableBytes())
                    def session = ctx.session(uid)
                    if (session) {
                        ctx.ack(ntcbReply(asciiBytes("*<S")))
                    }
                    return null
                }

                def session = ctx.session()
                if (session == null) return null

                if (type == "*>T") {
                    def pos = parseNtcbPos(ctx, session, msg)
                    long idx = pos.getLong(Position.KEY_INDEX)
                    ctx.ack(ntcbReply([
                        (byte)'*', (byte)'<', (byte)'T',
                        (byte)(idx & 0xFF), (byte)((idx >> 8) & 0xFF),
                        (byte)((idx >> 16) & 0xFF), (byte)((idx >> 24) & 0xFF)] as byte[]))
                    return pos.fixTime != null ? pos : null

                } else if (type == "*>A") {
                    int count = msg.readUByte()
                    for (int i = 0; i < count; i++) {
                        def pos = parseNtcbPos(ctx, session, msg)
                        if (pos.fixTime != null) ctx.emit(pos)
                    }
                    ctx.ack(ntcbReply([(byte)'*', (byte)'<', (byte)'A', (byte)(count & 0xFF)] as byte[]))
                    return null

                } else if (type == "*>F") {
                    msg.skip(3) // "LEX"
                    if (msg.readUByte() != 0xB0) return null
                    int pver = msg.readUByte()
                    int sver = msg.readUByte()
                    navisFlexBitFieldSize = msg.readUByte()
                    if (navisFlexBitFieldSize > FLEX_FIELDS_SIZES.length) return null
                    int fbytes = (int) Math.ceil(navisFlexBitFieldSize / 8.0)
                    byte[] bf = msg.readBytes(fbytes)
                    System.arraycopy(bf, 0, navisFlexBitField, 0, fbytes)
                    navisFlexDataSize = 0
                    for (int i = 0; i < navisFlexBitFieldSize; i++) {
                        if (checkFlexBit(i)) navisFlexDataSize += FLEX_FIELDS_SIZES[i]
                    }
                    ctx.ack(ntcbReply([
                        (byte)'*', (byte)'<', (byte)'F', (byte)'L', (byte)'E', (byte)'X',
                        (byte)0xB0, (byte)(pver & 0xFF), (byte)(sver & 0xFF)] as byte[]))
                    return null

                } else if (type == "*@C") {
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos, null)
                    pos.set(Position.KEY_RESULT, "*@C" + msg.readString(msg.readableBytes()))
                    return pos
                }

                return null
            }

            // ── FLEX frame ────────────────────────────────────────────────
            String type = msg.readString(2)

            def session = ctx.session()
            if (session == null) return null

            def sendFlexIdxAck = { String hdr, long idx ->
                ctx.ack(flexReply([
                    *hdr.getBytes("US-ASCII"),
                    (byte)(idx & 0xFF), (byte)((idx >> 8) & 0xFF),
                    (byte)((idx >> 16) & 0xFF), (byte)((idx >> 24) & 0xFF)] as byte[]))
            }

            def sendFlexCntAck = { String hdr, int cnt ->
                ctx.ack(flexReply([*hdr.getBytes("US-ASCII"), (byte)(cnt & 0xFF)] as byte[]))
            }

            switch (type) {
                case "~T":
                    msg.skip(4) // event index
                    def pos = parseFlexPos(ctx, session, msg)
                    sendFlexIdxAck("~T", pos.getLong(Position.KEY_INDEX))
                    return pos.fixTime != null ? pos : null

                case "~C":
                    def pos = parseFlexPos(ctx, session, msg)
                    sendFlexIdxAck("~C", pos.getLong(Position.KEY_INDEX))
                    return pos.fixTime != null ? pos : null

                case "~A":
                    int count = msg.readUByte()
                    for (int i = 0; i < count; i++) {
                        def pos = parseFlexPos(ctx, session, msg)
                        if (pos.fixTime != null) ctx.emit(pos)
                    }
                    sendFlexCntAck("~A", count)
                    return null

                case "~X":
                    msg.skip(4) // event index
                    def pos = parseFlex20Pos(ctx, session, msg)
                    sendFlexIdxAck("~X", pos.getLong(Position.KEY_INDEX))
                    return pos.fixTime != null ? pos : null

                case "~E":
                    int count = msg.readUByte()
                    for (int i = 0; i < count; i++) {
                        def pos = parseFlex20Pos(ctx, session, msg)
                        if (pos.fixTime != null) ctx.emit(pos)
                    }
                    sendFlexCntAck("~E", count)
                    return null

                default:
                    return null
            }
        }
    }
}
