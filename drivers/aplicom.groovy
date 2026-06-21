// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

import org.traccar.driver.BufReader
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

// ── IMEI derivation from unit ID ──────────────────────────────────────────────

def IMEI_BASE_TC65_V20  = 0x1437207000000L
def IMEI_BASE_TC65_V28  = 358244010000000L
def IMEI_BASE_TC65I_V11 = 0x14143B4000000L

def imeiFromUnitId = { long unitId ->
    if (unitId == 0L) return 0L
    long imei = IMEI_BASE_TC65I_V11 + unitId
    if (Checksum.luhn(imei.intdiv(10)) == imei % 10) return imei
    imei = IMEI_BASE_TC65_V28 + ((unitId + 0xA8180L) & 0xFFFFFFL)
    if (Checksum.luhn(imei.intdiv(10)) == imei % 10) return imei
    imei = IMEI_BASE_TC65_V20 + unitId
    if (Checksum.luhn(imei.intdiv(10)) == imei % 10) return imei
    unitId
}

// ── EB (Electronic Brake System) sub-decode ──────────────────────────────────

def decodeEB = { pos, BufReader b ->
    if (b.readableBytes() < 2) return
    if (b.readByte() != 0x45 || b.readByte() != 0x42) return  // 'E', 'B'
    pos.set(Position.KEY_VERSION_FW, (int) b.readUByte())
    pos.set(Position.KEY_EVENT, (int) b.readUShort())
    pos.set("dataValidity", (int) b.readUByte())
    pos.set("towed", (int) b.readUByte())
    b.skip(2)  // length
    while (b.readableBytes() >= 3) {
        b.skip(1)  // towed position
        int type = b.readUByte()
        int len  = b.readUByte()
        int targetRemaining = b.readableBytes() - len
        switch (type) {
            case 0x01:
                pos.set("brakeFlags", b.readHex(len)); break
            case 0x02:
                pos.set("wheelSpeed",            b.readUShort() / 256.0)
                pos.set("wheelSpeedDifference",  b.readUShort() / 256.0 - 125.0)
                pos.set("lateralAcceleration",   b.readUByte()  / 10.0  - 12.5)
                pos.set("vehicleSpeed",          b.readUShort() / 256.0); break
            case 0x03:
                pos.set(Position.KEY_AXLE_WEIGHT, (int) (b.readUShort() * 2)); break
            case 0x04:
                pos.set("tirePressure",      b.readUByte() * 10)
                pos.set("pneumaticPressure", b.readUByte() * 5); break
            case 0x05:
                pos.set("brakeLining",     b.readUByte() * 0.4)
                pos.set("brakeTemperature", b.readUByte() * 10); break
            case 0x06:
                pos.set(Position.KEY_ODOMETER,         b.readUInt() * 5L)
                pos.set(Position.KEY_ODOMETER_TRIP,    b.readUInt() * 5L)
                pos.set(Position.KEY_ODOMETER_SERVICE, (b.readUInt() - 2105540607L) * 5L); break
            case 0x0A:
                pos.set("absStatusCounter",  (int) b.readUShort())
                pos.set("atvbStatusCounter", (int) b.readUShort())
                pos.set("vdcActiveCounter",  (int) b.readUShort()); break
            case 0x0B:
                pos.set("brakeMinMaxData", b.readHex(len)); break
            case 0x0C:
                pos.set("missingPgn", b.readHex(len)); break
            case 0x0D:
                b.skip(1)
                pos.set("towedDetectionStatus", b.readUInt())
                b.skip(17); break
            default: break
        }
        if (targetRemaining >= 0) b.skip(b.readableBytes() - targetRemaining)
    }
}

// ── event-data dispatch ───────────────────────────────────────────────────────

def decodeEventData = { pos, BufReader b, int event ->
    switch (event) {
        case 2: case 40:  b.skip(1);  break
        case 9:           b.skip(3);  break
        case 31: case 32: b.skip(2);  break
        case 38:          b.skip(36); break
        case 113:         b.skip(5);  break
        case 119:
            pos.set("eventData", b.readHex(Math.min(b.readableBytes(), 1024))); break
        case 121: case 142: b.skip(8); break
        case 130:           b.skip(4); break
        case 188:           decodeEB(pos, b); break
        default: break
    }
}

// ── D protocol (GPS + IO) ────────────────────────────────────────────────────

def decodeD = { pos, BufReader b, int selector, int event, ctx ->
    if ((selector & 0x0008) != 0) {
        pos.setValid((b.readUByte() & 0x40) != 0)
    } else {
        ctx.lastLocation(pos, null)
    }

    if ((selector & 0x0004) != 0) {
        Date dt = new Date(b.readUInt() * 1000L)
        pos.setDeviceTime(dt)
    }

    if ((selector & 0x0008) != 0) {
        Date ft = new Date(b.readUInt() * 1000L)
        pos.setFixTime(ft)
        if (pos.getDeviceTime() == null) pos.setDeviceTime(ft)
        pos.setLatitude(b.readInt() / 1000000.0)
        pos.setLongitude(b.readInt() / 1000000.0)
        pos.set(Position.KEY_SATELLITES_VISIBLE, (int) b.readUByte())
    }

    if ((selector & 0x0010) != 0) {
        pos.setSpeed(UnitsConverter.knotsFromKph(b.readUByte()))
        pos.set("maximumSpeed", (int) b.readUByte())
        pos.setCourse(b.readUByte() * 2.0)
    }

    if ((selector & 0x0040) != 0) {
        pos.set(Position.KEY_INPUT, (int) b.readUByte())
    }

    if ((selector & 0x0020) != 0) {
        pos.set(Position.PREFIX_ADC + 1, (int) b.readUShort())
        pos.set(Position.PREFIX_ADC + 2, (int) b.readUShort())
        pos.set(Position.PREFIX_ADC + 3, (int) b.readUShort())
        pos.set(Position.PREFIX_ADC + 4, (int) b.readUShort())
    }

    if ((selector & 0x8000) != 0) {
        pos.set(Position.KEY_POWER,   b.readUShort() / 1000.0)
        pos.set(Position.KEY_BATTERY, b.readUShort() / 1000.0)
    }

    if ((selector & 0x10000) != 0) { b.skip(2); b.skip(4) }  // pulse rate 1
    if ((selector & 0x20000) != 0) { b.skip(2); b.skip(4) }  // pulse rate 2

    if ((selector & 0x0080) != 0) pos.set("trip1", b.readUInt())
    if ((selector & 0x0100) != 0) pos.set("trip2", b.readUInt())

    if ((selector & 0x0040) != 0) {
        pos.set(Position.KEY_OUTPUT, (int) b.readUByte())
    }

    if ((selector & 0x0200) != 0) {
        long hi = (long) b.readUShort()
        long lo = b.readUInt()
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(hi << 32) + String.valueOf(lo))
    }

    if ((selector & 0x0400) != 0) b.skip(1)   // keypad
    if ((selector & 0x0800) != 0) pos.setAltitude(b.readShort())
    if ((selector & 0x2000) != 0) b.skip(2)   // snapshot counter
    if ((selector & 0x4000) != 0) b.skip(8)   // state flags
    if ((selector & 0x80000) != 0) b.skip(11) // cell info

    if ((selector & 0x1000) != 0) decodeEventData(pos, b, event)
}

// ── E protocol (tachograph) ──────────────────────────────────────────────────

def decodeE = { pos, BufReader b, int selector, ctx ->
    if ((selector & 0x0008) != 0) {
        pos.set("tachographEvent", (int) b.readUShort())
    }

    if ((selector & 0x0004) != 0) {
        ctx.lastLocation(pos, new Date(b.readUInt() * 1000L))
    } else {
        ctx.lastLocation(pos, null)
    }

    if ((selector & 0x0010) != 0) {
        String t = b.readUByte() + "s " + b.readUByte() + "m " + b.readUByte() + "h " +
                   b.readUByte() + "M " + b.readUByte() + "D " + b.readUByte() + "Y " +
                   b.readByte()  + "m " + b.readByte()  + "h"
        pos.set("tachographTime", t)
    }

    pos.set("workState",   (int) b.readUByte())
    pos.set("driver1State", (int) b.readUByte())
    pos.set("driver2State", (int) b.readUByte())

    if ((selector & 0x0020) != 0) pos.set("tachographStatus", (int) b.readUByte())
    if ((selector & 0x0040) != 0) pos.set(Position.KEY_OBD_SPEED, b.readUShort() / 256.0)
    if ((selector & 0x0080) != 0) pos.set(Position.KEY_OBD_ODOMETER, b.readUInt() * 5L)
    if ((selector & 0x0100) != 0) pos.set(Position.KEY_ODOMETER_TRIP, b.readUInt() * 5L)
    if ((selector & 0x8000) != 0) pos.set("kFactor", b.readUShort() / 1000.0 + " pulses/m")
    if ((selector & 0x0200) != 0) pos.set(Position.KEY_RPM, b.readUShort() * 0.125)
    if ((selector & 0x0400) != 0) pos.set("extraInfo", (int) b.readUShort())

    if ((selector & 0x0800) != 0) {
        pos.set(Position.KEY_VIN, b.readString(18).trim())
    }

    if ((selector & 0x2000) != 0) {
        b.skip(2)  // card 1 type + country
        String card = b.readString(20).trim()
        if (card) pos.set("card1", card)
    }

    if ((selector & 0x4000) != 0) {
        b.skip(2)  // card 2 type + country
        String card = b.readString(20).trim()
        if (card) pos.set("card2", card)
    }

    if ((selector & 0x10000) != 0) {
        int count = b.readUByte()
        for (int i = 1; i <= count; i++) {
            pos.set("driver" + i, b.readString(22).trim())
            pos.set("driverTime" + i, b.readUInt())
        }
    }
}

// ── H protocol (histograms) ──────────────────────────────────────────────────

def decodeH = { pos, BufReader b, int selector, ctx ->
    if ((selector & 0x0004) != 0) {
        ctx.lastLocation(pos, new Date(b.readUInt() * 1000L))
    } else {
        ctx.lastLocation(pos, null)
    }

    if ((selector & 0x0040) != 0) b.skip(4)  // reset time
    if ((selector & 0x2000) != 0) b.skip(2)  // snapshot counter

    boolean pct = (selector & 0x0020) != 0
    int idx = 1
    while (b.isReadable()) {
        pos.set("h" + idx + "Index", (int) b.readUByte())
        b.skip(2)  // length
        int n = b.readUByte()
        int m = b.readUByte()
        pos.set("h" + idx + "XLength", n)
        pos.set("h" + idx + "YLength", m)

        if ((selector & 0x0008) != 0) {
            pos.set("h" + idx + "XType",      (int) b.readUByte())
            pos.set("h" + idx + "YType",      (int) b.readUByte())
            pos.set("h" + idx + "Parameters", (int) b.readUByte())
        }

        StringBuilder data = new StringBuilder()
        for (int i = 0; i < n * m; i++) {
            if (pct) data.append(b.readUByte() * 0.5).append("% ")
            else     data.append(b.readUShort()).append(" ")
        }
        pos.set("h" + idx + "Data",  data.toString().trim())
        pos.set("h" + idx + "Total", b.readUInt())

        if ((selector & 0x0010) != 0) {
            int k = b.readUByte()
            StringBuilder xl = new StringBuilder()
            for (int i = 1; i < n; i++) {
                if (k == 1) xl.append(b.readByte()).append(" ")
                else if (k == 2) xl.append(b.readShort()).append(" ")
            }
            pos.set("h" + idx + "XLimits", xl.toString().trim())

            StringBuilder yl = new StringBuilder()
            for (int i = 1; i < m; i++) {
                if (k == 1) yl.append(b.readByte()).append(" ")
                else if (k == 2) yl.append(b.readShort()).append(" ")
            }
            pos.set("h" + idx + "YLimits", yl.toString().trim())
        }
        idx++
    }
}

// ── F protocol (CAN/J1939 engine) ────────────────────────────────────────────

def decodeF = { pos, BufReader b, int selector, ctx ->
    Date deviceTime = null
    if ((selector & 0x0004) != 0) deviceTime = new Date(b.readUInt() * 1000L)
    ctx.lastLocation(pos, deviceTime)
    b.skip(1)  // data validity

    if ((selector & 0x0008) != 0) {
        pos.set(Position.KEY_RPM, (int) b.readUShort())
        pos.set("rpmMax",         (int) b.readUShort())
        pos.set("rpmMin",         (int) b.readUShort())
    }

    if ((selector & 0x0010) != 0) {
        pos.set(Position.KEY_ENGINE_TEMP, (int) b.readShort())
        pos.set("engineTempMax",          (int) b.readShort())
        pos.set("engineTempMin",          (int) b.readShort())
    }

    if ((selector & 0x0020) != 0) {
        pos.set(Position.KEY_HOURS,    UnitsConverter.msFromHours(b.readUInt()))
        pos.set("serviceDistance",     b.readInt())
        pos.set("driverActivity",      (int) b.readUByte())
        pos.set(Position.KEY_THROTTLE, (int) b.readUByte())
        pos.set(Position.KEY_FUEL,     (int) b.readUByte())
    }

    if ((selector & 0x0040) != 0) pos.set(Position.KEY_FUEL_USED, b.readUInt())
    if ((selector & 0x0080) != 0) pos.set(Position.KEY_ODOMETER,  b.readUInt())

    if ((selector & 0x0100) != 0) {
        pos.set(Position.KEY_OBD_SPEED, (int) b.readUByte())
        pos.set("speedMax",             (int) b.readUByte())
        pos.set("speedMin",             (int) b.readUByte())
        pos.set("hardBraking",          (int) b.readUByte())
    }

    if ((selector & 0x0200) != 0) {
        pos.set("tachographSpeed",  (int) b.readUByte())
        pos.set("driver1State",     (int) b.readUByte())
        pos.set("driver2State",     (int) b.readUByte())
        pos.set("tachographStatus", (int) b.readUByte())
        pos.set("overspeedCount",   (int) b.readUByte())
    }

    if ((selector & 0x0800) != 0) {
        pos.set(Position.KEY_HOURS,        b.readUInt() / 20.0)
        pos.set(Position.KEY_RPM,          b.readUShort() * 0.125)
        pos.set(Position.KEY_OBD_SPEED,    b.readUShort() / 256.0)
        pos.set(Position.KEY_FUEL_USED,    b.readUInt() * 0.5)
        pos.set(Position.KEY_FUEL,         b.readUByte() * 0.4)
    }

    if ((selector & 0x1000) != 0) {
        pos.set("ambientTemperature",        b.readUShort() * 0.03125 - 273)
        b.skip(2)  // fuel rate
        pos.set("fuelEconomy",               b.readUShort() / 512.0)
        pos.set(Position.KEY_FUEL_CONSUMPTION, b.readUInt() / 1000.0)
        b.skip(1)  // pto drive engagement
    }

    if ((selector & 0x2000) != 0) b.skip(b.readUByte())  // driver identification

    if ((selector & 0x4000) != 0) {
        pos.set("torque",            (int) b.readUByte())
        pos.set("brakePressure1",    b.readUByte() * 8)
        pos.set("brakePressure2",    b.readUByte() * 8)
        pos.set("grossWeight",       b.readUShort() * 10)
        pos.set("exhaustFluid",      b.readUByte() * 0.4)
        b.skip(1)  // retarder torque mode
        pos.set("retarderTorque",    (int) b.readUByte())
        pos.set("retarderSelection", b.readUByte() * 0.4)
        b.skip(32) // 4 × 8-byte tell tale blocks
    }

    if ((selector & 0x8000) != 0) {
        pos.set("parkingBrakeStatus", (int) b.readUByte())
        pos.set("doorStatus",         (int) b.readUByte())
        b.skip(8)  // status per door
        pos.set("alternatorStatus",   (int) b.readUByte())
        pos.set("selectedGear",       (int) b.readUByte())
        pos.set("currentGear",        (int) b.readUByte())
        b.skip(8)  // air suspension pressure (4 × 2-byte)
    }

    if ((selector & 0x0400) != 0) {
        int count = b.readUByte()
        for (int i = 0; i < count; i++) pos.set("axle" + i, (int) b.readUShort())
    }
}

// ── protocol ──────────────────────────────────────────────────────────────────

protocol("aplicom") {
    port 5049

    variant("main") {

        scriptedFrame { fb ->
            // Skip leading alive (decimal digit) bytes
            int n = fb.readableBytes()
            int start = 0
            while (start < n && fb.getUByte(start) >= 0x30 && fb.getUByte(start) <= 0x39) start++
            if (n - start < 11) return null

            int version = fb.getUByte(start + 1) & 0xFF
            int offset = 5  // protocol(1) + version(1) + unitId(3)
            if ((version & 0x80) != 0) offset += 4  // IMEI needs 7 bytes total

            if (n - start < offset + 2) return null
            int length = ((fb.getUByte(start + offset) & 0xFF) << 8) | (fb.getUByte(start + offset + 1) & 0xFF)
            offset += 2
            if ((version & 0x40) != 0) offset += 3  // selector

            int total = start + offset + length
            fb.readableBytes() >= total ? total : null
        }

        decode { msg, ctx ->
            def buf = msg as BufReader

            // Skip leading alive (digit) bytes
            while (buf.isReadable() && buf.getUByte(0) >= 0x30 && buf.getUByte(0) <= 0x39) buf.skip(1)
            if (!buf.isReadable()) return null

            char protocol = (char) buf.readByte()
            int version = buf.readUByte()

            String imei
            if ((version & 0x80) != 0) {
                long hi  = buf.readUInt()
                long lo3 = ((buf.readUByte() & 0xFFL) << 16L) | ((buf.readUByte() & 0xFFL) << 8L) | (buf.readUByte() & 0xFFL)
                imei = String.valueOf((hi << 24L) | lo3)
            } else {
                long uid = ((buf.readUByte() & 0xFFL) << 16L) | ((buf.readUByte() & 0xFFL) << 8L) | (buf.readUByte() & 0xFFL)
                imei = String.valueOf(imeiFromUnitId(uid))
            }

            buf.skip(2)  // length (already used for framing)

            int selector
            if (protocol == 'E') selector = 0x007FFC
            else if (protocol == 'F') selector = 0x0007FD
            else selector = 0x0002FC  // D and H share DEFAULT_SELECTOR_D

            if ((version & 0x40) != 0) {
                selector = ((buf.readUByte() & 0xFF) << 16) | ((buf.readUByte() & 0xFF) << 8) | (buf.readUByte() & 0xFF)
            }

            def session = ctx.session(imei)
            if (!session) return null
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            int event = buf.readUByte()
            pos.set(Position.KEY_EVENT, event)
            pos.set("eventInfo", (int) buf.readUByte())

            switch (protocol) {
                case 'D': decodeD(pos, buf, selector, event, ctx); break
                case 'E': decodeE(pos, buf, selector, ctx); break
                case 'H': decodeH(pos, buf, selector, ctx); break
                case 'F': decodeF(pos, buf, selector, ctx); break
                default: return null
            }

            pos
        }
    }
}
