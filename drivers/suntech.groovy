// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Suntech ST-series driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/suntech/
 *   archived-protocols/suntech/
 *
 * Three wire formats multiplexed on port 5011:
 *   - Binary ZIP (0x02 first byte): compact BCD-encoded position
 *   - Binary Universal (second byte 0x00): bitmask-driven field set
 *   - Text (semicolon-delimited): multiple sub-formats keyed by prefix
 *
 * Text sub-formats:
 *   prefix length < 5           → Universal text (STT, ALT, BLE, RES, UEX)
 *   prefix starts with "ST9"    → ST9 format (kids tracker)
 *   prefix starts with "ST4"    → ST4 format (LTE asset tracker)
 *   prefix ends with "HTE"      → Travel report
 *   prefix starts with "CRR"    → Crash report (multi-frame binary)
 *   else (5+ chars)             → ST2/3/5/6/500/600 legacy formats
 *
 * Channel-store keys written by decode, read by encode:
 *   suntech.universal  (boolean) — true when universal text or binary universal
 *   suntech.prefix     (String)  — legacy command prefix, e.g. "SA200"
 *
 * Config (prefix: suntech.):
 *   hbm          — HBM extension level: 0 (default), 1, or 2
 *   protocolType — 0 (default) or 1 (ST9 adds cell/odometer; ST2356 adds cell)
 *   includeAdc   — include ADC fields in HBM level ≥ 1 (default false)
 *   includeRpm   — include RPM field in HBM level ≥ 1 (default false)
 *   includeTemp  — include temperature fields in HBM level ≥ 1 (default false)
 *   alternative  — use universal command format instead of legacy prefix (default false)
 */

import io.netty.buffer.Unpooled
import org.traccar.driver.FrameResult
import org.traccar.helper.BitUtil
import org.traccar.helper.DateUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Arrays
import java.util.stream.Collectors

def DATE_FORMAT = DateTimeFormatter.ofPattern('yyyyMMddHH:mm:ss').withZone(ZoneOffset.UTC)

// ---- alarm maps ---------------------------------------------------------

def decodeEmergency = { int v ->
    switch (v) {
        case 1: return Position.ALARM_SOS
        case 2: return Position.ALARM_PARKING
        case 3: return Position.ALARM_POWER_CUT
        case 5: case 6: return Position.ALARM_DOOR
        case 7: return Position.ALARM_MOVEMENT
        case 8: return Position.ALARM_VIBRATION
        default: return null
    }
}

def decodeAlert = { int v ->
    switch (v) {
        case 1:  return Position.ALARM_OVERSPEED
        case 5:  return Position.ALARM_GEOFENCE_EXIT
        case 6:  return Position.ALARM_GEOFENCE_ENTER
        case 14: return Position.ALARM_LOW_BATTERY
        case 15: return Position.ALARM_VIBRATION
        case 16: return Position.ALARM_ACCIDENT
        case 40: return Position.ALARM_POWER_RESTORED
        case 41: return Position.ALARM_POWER_CUT
        case 42: return Position.ALARM_SOS
        case 46: return Position.ALARM_ACCELERATION
        case 47: return Position.ALARM_BRAKING
        case 50: return Position.ALARM_JAMMING
        case 132: return Position.ALARM_DOOR
        default: return null
    }
}

// ---- serial data (UEX) ---------------------------------------------------

def decodeSerialData = { pos, String[] values, int index ->
    int remaining = Integer.parseInt(values[index++])
    double totalFuel = 0
    while (remaining > 0) {
        String attr = values[index++]
        if (attr.startsWith('CabAVL')) {
            def parts = attr.split(',')
            double f1 = Double.parseDouble(parts[2])
            double f2 = Double.parseDouble(parts[3])
            if (f1 > 0) { totalFuel += f1; pos.set('fuel1', f1) }
            if (f2 > 0) { totalFuel += f2; pos.set('fuel2', f2) }
        } else if (attr.startsWith('GTSL')) {
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, attr.split('\\|')[4])
        } else if (attr.contains('=')) {
            def pair = attr.split('=')
            if (pair.length >= 2) {
                String val = pair[1].trim()
                if (val.contains('.')) val = val.substring(0, val.indexOf('.'))
                switch (pair[0].charAt(0)) {
                    case 't': pos.set(Position.PREFIX_TEMP + pair[0].charAt(2), Integer.parseInt(val, 16)); break
                    case 'N':
                        int fuel = Integer.parseInt(val, 16)
                        totalFuel += fuel
                        pos.set('fuel' + pair[0].charAt(2), fuel)
                        break
                    case 'Q': pos.set('drivingQuality', Integer.parseInt(val, 16)); break
                }
            }
        } else {
            pos.set('serial', attr.trim())
        }
        remaining -= attr.length() + 1
    }
    if (totalFuel > 0) pos.set(Position.KEY_FUEL, totalFuel)
    return index + 1  // skip checksum
}

// ---- text sub-decoders ---------------------------------------------------

def decode9 = { String[] values, ctx ->
    int idx = 1
    String type = values[idx++]
    if (type != 'Location' && type != 'Emergency' && type != 'Alert') return null

    def session = ctx.session(values[idx++])
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    if (type == 'Emergency' || type == 'Alert') pos.addAlarm(Position.ALARM_GENERAL)

    int protocolType = ctx.configInt('protocolType', 0)
    if (type != 'Alert' || protocolType == 0) pos.set(Position.KEY_VERSION_FW, values[idx++])

    pos.time = DateUtil.parse(DATE_FORMAT, values[idx++] + values[idx++])

    if (protocolType == 1) idx++  // cell

    pos.latitude  = Double.parseDouble(values[idx++])
    pos.longitude = Double.parseDouble(values[idx++])
    pos.speed     = UnitsConverter.knotsFromKph(Double.parseDouble(values[idx++]))
    pos.course    = Double.parseDouble(values[idx++])
    pos.valid     = values[idx++] == '1'

    if (protocolType == 1) pos.set(Position.KEY_ODOMETER, Integer.parseInt(values[idx++]))

    return pos
}

def decode4 = { String[] values, ctx ->
    int idx = 0
    String type = values[idx++].substring(5)
    if (type != 'STT' && type != 'ALT') return null

    def session = ctx.session(values[idx++])
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_TYPE, type)
    pos.set(Position.KEY_VERSION_FW, values[idx++])

    int model = Integer.parseInt(values[idx++])
    if (model == 41) idx++  // variant

    def network = new Network()
    for (int i = 0; i < 7; i++) {
        int cid  = Integer.parseInt(values[idx++])
        int mcc  = Integer.parseInt(values[idx++])
        int mnc  = Integer.parseInt(values[idx++])
        int rssi, lac
        if (i == 0) { rssi = Integer.parseInt(values[idx++]); lac = Integer.parseInt(values[idx++]) }
        else         { lac  = Integer.parseInt(values[idx++]); rssi = Integer.parseInt(values[idx++]) }
        idx++  // timing advance
        if (cid > 0) network.addCellTower(CellTower.from(mcc, mnc, lac, cid, rssi))
    }
    pos.network = network

    pos.set(Position.KEY_BATTERY, Double.parseDouble(values[idx++]))
    pos.set(Position.KEY_ARCHIVE, values[idx++] == '0' ? true : null)
    pos.set(Position.KEY_INDEX,   Integer.parseInt(values[idx++]))
    pos.set(Position.KEY_STATUS,  Integer.parseInt(values[idx++]))

    if (idx < values.length && values[idx].length() == 3) idx++  // collaborative network

    if (model == 41) {
        idx++  // collaborative network
        idx++  // temperature
        pos.set(Position.KEY_MOTION, Integer.parseInt(values[idx++]) == 2)
    }

    if (values[idx].isEmpty()) {
        ctx.lastLocation(pos, null)
    } else {
        pos.time      = DateUtil.parse(DATE_FORMAT, values[idx++] + values[idx++])
        pos.latitude  = Double.parseDouble(values[idx++])
        pos.longitude = Double.parseDouble(values[idx++])
        pos.speed     = UnitsConverter.knotsFromKph(Double.parseDouble(values[idx++]))
        pos.course    = Double.parseDouble(values[idx++])
        pos.set(Position.KEY_SATELLITES, Integer.parseInt(values[idx++]))
        pos.valid     = values[idx++] == '1'
    }

    return pos
}

def decode2356 = { String[] values, String protocol, ctx ->
    int idx = 0
    String type = values[idx++].substring(5)

    boolean result = values[idx] == 'Res'
    if (result) idx++

    def session = ctx.session(values[idx++])
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_TYPE, type)

    if (result) {
        ctx.lastLocation(pos, null)
        pos.set(Position.KEY_RESULT, Arrays.copyOfRange(values, idx, values.length).join(';'))
        return pos
    }

    if (!['STT','EMG','EVT','ALT','UEX'].contains(type)) return null

    if (protocol.startsWith('ST3') || protocol == 'ST500' || protocol == 'ST600') idx++  // model

    pos.set(Position.KEY_VERSION_FW, values[idx++])
    pos.time = DateUtil.parse(DATE_FORMAT, values[idx++] + values[idx++])

    if (protocol != 'ST500') {
        long cid = Long.parseLong(values[idx++], 16)
        if (protocol == 'ST600') {
            pos.network = new Network(CellTower.from(
                Integer.parseInt(values[idx++]), Integer.parseInt(values[idx++]),
                Integer.parseInt(values[idx++], 16), cid, Integer.parseInt(values[idx++])))
        }
    }

    pos.latitude  = Double.parseDouble(values[idx++])
    pos.longitude = Double.parseDouble(values[idx++])
    pos.speed     = UnitsConverter.knotsFromKph(Double.parseDouble(values[idx++]))
    pos.course    = Double.parseDouble(values[idx++])
    pos.set(Position.KEY_SATELLITES, Integer.parseInt(values[idx++]))
    pos.valid     = values[idx++] == '1'
    pos.set(Position.KEY_ODOMETER, Integer.parseInt(values[idx++]))
    pos.set(Position.KEY_POWER,    Double.parseDouble(values[idx++]))

    String io = values[idx++]
    if (io.length() >= 6) {
        pos.set(Position.KEY_IGNITION, io.charAt(0) == '1')
        for (int i = 1; i <= 3; i++) pos.set(Position.PREFIX_IN  + i, io.charAt(i)     == '1')
        for (int i = 1; i <= 2; i++) pos.set(Position.PREFIX_OUT + i, io.charAt(i + 3) == '1')
    }

    switch (type) {
        case 'STT': pos.set(Position.KEY_STATUS, Integer.parseInt(values[idx++])); pos.set(Position.KEY_INDEX, Integer.parseInt(values[idx++])); break
        case 'EMG': pos.addAlarm(decodeEmergency(Integer.parseInt(values[idx++]))); break
        case 'EVT': pos.set(Position.KEY_EVENT, Integer.parseInt(values[idx++])); break
        case 'ALT': pos.addAlarm(decodeAlert(Integer.parseInt(values[idx++]))); break
        case 'UEX': idx = decodeSerialData(pos, values, idx); break
    }

    int hbm = ctx.configInt('hbm', 0)
    if (hbm >= 1) {
        if (idx < values.length) pos.set(Position.KEY_HOURS,   UnitsConverter.msFromMinutes(Integer.parseInt(values[idx++])))
        if (idx < values.length) pos.set(Position.KEY_BATTERY, Double.parseDouble(values[idx++]))
        if (idx < values.length && values[idx++] == '0') pos.set(Position.KEY_ARCHIVE, true)

        if (ctx.configBoolean('includeAdc', false)) {
            for (int i = 1; i <= 3; i++) {
                if (idx < values.length && !values[idx].isEmpty()) {
                    pos.set(Position.PREFIX_ADC + i, Double.parseDouble(values[idx]))
                }
                if (idx < values.length) idx++
            }
        }

        if (ctx.configBoolean('includeRpm', false) && idx < values.length) {
            pos.set(Position.KEY_RPM, Integer.parseInt(values[idx++]))
        }

        if (values.length - idx >= (hbm == 1 ? 2 : 7)) {
            String duid = values[idx++]
            if (!duid.isEmpty()) pos.set(Position.KEY_DRIVER_UNIQUE_ID, duid)
            idx++  // registered
        }

        if (ctx.configBoolean('includeTemp', false)) {
            for (int i = 1; i <= 3; i++) {
                String temperature = values[idx++]
                String val = temperature.substring(temperature.indexOf(':') + 1)
                if (!val.isEmpty()) pos.set(Position.PREFIX_TEMP + i, Double.parseDouble(val))
            }
        }

        if (hbm >= 2 && values.length - idx >= 5) {
            int cid  = Integer.parseInt(values[idx++])
            int mcc  = Integer.parseInt(values[idx++])
            int mnc  = Integer.parseInt(values[idx++])
            int rssi = Integer.parseInt(values[idx++])
            int lac  = Integer.parseInt(values[idx++])
            pos.network = new Network(CellTower.from(mcc, mnc, lac, cid, rssi))
        }
    }

    return pos
}

def decodeUniversal = { String[] values, ctx ->
    int idx = 0
    String type = values[idx++]
    if (!['STT','ALT','BLE','RES','UEX'].contains(type)) return null

    def session = ctx.session(values[idx++])
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_TYPE, type)

    if (type == 'RES') {
        ctx.lastLocation(pos, null)
        pos.set(Position.KEY_RESULT, Arrays.stream(values, idx, values.length).collect(Collectors.joining(';')))
        return pos
    }

    int mask
    if (type == 'BLE') {
        mask = 0b1100000110110
    } else {
        mask = Integer.parseInt(values[idx++], 16)
    }

    if (BitUtil.check(mask, 1)) idx++   // model
    if (BitUtil.check(mask, 2)) pos.set(Position.KEY_VERSION_FW, values[idx++])
    if (BitUtil.check(mask, 3) && values[idx++] == '0') pos.set(Position.KEY_ARCHIVE, true)
    if (BitUtil.check(mask, 4) && BitUtil.check(mask, 5)) {
        pos.time = DateUtil.parse(DATE_FORMAT, values[idx++] + values[idx++])
    }

    def cellTower = new CellTower()
    if (BitUtil.check(mask, 6))  cellTower.cellId               = Long.parseLong(values[idx++], 16)
    if (BitUtil.check(mask, 7))  cellTower.mobileCountryCode    = Integer.parseInt(values[idx++])
    if (BitUtil.check(mask, 8))  cellTower.mobileNetworkCode    = Integer.parseInt(values[idx++])
    if (BitUtil.check(mask, 9))  cellTower.locationAreaCode     = Integer.parseInt(values[idx++], 16)
    if (cellTower.cellId != null) pos.network = new Network(cellTower)

    if (BitUtil.check(mask, 10)) pos.set(Position.KEY_RSSI, Integer.parseInt(values[idx++]))
    if (BitUtil.check(mask, 11)) pos.latitude  = Double.parseDouble(values[idx++])
    if (BitUtil.check(mask, 12)) pos.longitude = Double.parseDouble(values[idx++])

    if (type == 'BLE') {
        pos.valid = true
        int count = Integer.parseInt(values[idx++])
        for (int i = 1; i <= count; i++) {
            pos.set("tag${i}Rssi",    Integer.parseInt(values[idx++]))
            idx++; idx++  // rssi min/max
            pos.set("tag${i}Id",      values[idx++])
            pos.set("tag${i}Samples", Integer.parseInt(values[idx++]))
            pos.set("tag${i}Major",   Integer.parseInt(values[idx++]))
            pos.set("tag${i}Minor",   Integer.parseInt(values[idx++]))
        }
    } else {
        if (BitUtil.check(mask, 13)) pos.speed  = UnitsConverter.knotsFromKph(Double.parseDouble(values[idx++]))
        if (BitUtil.check(mask, 14)) pos.course = Double.parseDouble(values[idx++])
        if (BitUtil.check(mask, 15)) pos.set(Position.KEY_SATELLITES, Integer.parseInt(values[idx++]))
        if (BitUtil.check(mask, 16)) pos.valid  = values[idx++] == '1'
        if (BitUtil.check(mask, 17)) {
            int input = Integer.parseInt(values[idx++])
            pos.set(Position.KEY_IGNITION, BitUtil.check(input, 0))
            pos.set(Position.KEY_INPUT, input)
        }
        if (BitUtil.check(mask, 18)) pos.set(Position.KEY_OUTPUT, Integer.parseInt(values[idx++]))

        switch (type) {
            case 'ALT':
                if (BitUtil.check(mask, 19)) { int alertId = Integer.parseInt(values[idx++]); pos.addAlarm(decodeAlert(alertId)) }
                if (BitUtil.check(mask, 20)) pos.set('alertModifier', values[idx++])
                if (BitUtil.check(mask, 21)) pos.set('alertData', values[idx++])
                break
            case 'UEX':
                idx = decodeSerialData(pos, values, idx)
                break
            default:
                if (BitUtil.check(mask, 19)) pos.set('mode',   Integer.parseInt(values[idx++]))
                if (BitUtil.check(mask, 20)) pos.set('reason', Integer.parseInt(values[idx++]))
                if (BitUtil.check(mask, 21)) pos.set(Position.KEY_INDEX, Integer.parseInt(values[idx++]))
                break
        }

        if (BitUtil.check(mask, 22)) idx++  // reserved

        if (BitUtil.check(mask, 23) && type != 'UEX') {
            int assignMask = Integer.parseInt(values[idx++], 16)
            for (int i = 0; i <= 30; i++) {
                if (BitUtil.check(assignMask, i)) pos.set(Position.PREFIX_IO + (i + 1), values[idx++])
            }
        }

        int assignIdx = 1
        while (idx < values.length) {
            String val = values[idx++]
            if (!val.isEmpty()) pos.set('assign' + assignIdx, val)
            assignIdx++
        }
    }

    return pos
}

def decodeTravelReport = { String[] values, ctx ->
    def session = ctx.session(values[1])
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    pos.set(Position.KEY_DRIVER_UNIQUE_ID, values[values.length - 1])
    return pos
}

// Crash reports arrive in numbered chunks; reassemble via channel store.
def decodeCrashReport = { BufReaderOrBytes buf, ctx ->
    // buf is the raw binary of the full text+binary frame (first 23 bytes are ASCII)
    byte[] frameBytes = buf instanceof byte[] ? buf : buf.readBytes(buf.readableBytes())

    // Validate: byte at offset 3 must be ';'
    if (frameBytes.length < 23 || frameBytes[3] != (byte) ';') return null

    String header = new String(frameBytes, 0, 23, 'US-ASCII')
    String[] hdr  = header.split(';')
    def session   = ctx.session(hdr[1])
    if (!session) return null

    int currentIndex = Integer.parseInt(hdr[2])
    int totalIndex   = Integer.parseInt(hdr[3])

    def store     = ctx.store()
    def crashList = (store['suntech.crash'] ?: []) as List<byte[]>
    // payload = everything after the 23-byte header except the last 3 bytes
    int payloadEnd = frameBytes.length - 3
    if (payloadEnd > 23) {
        crashList << Arrays.copyOfRange(frameBytes, 23, payloadEnd)
    }
    store['suntech.crash'] = crashList

    if (currentIndex < totalIndex) return null

    store.remove('suntech.crash')

    // Reassemble
    int total = crashList.sum { it.length } as int
    byte[] crash = new byte[total]
    int off = 0
    for (byte[] chunk : crashList) {
        System.arraycopy(chunk, 0, crash, off, chunk.length)
        off += chunk.length
    }

    def crashBuf = Unpooled.wrappedBuffer(crash)
    try {
        Date crashTime = new org.traccar.helper.DateBuilder()
            .setDate(crashBuf.readUnsignedByte(), crashBuf.readUnsignedByte(), crashBuf.readUnsignedByte())
            .setTime(crashBuf.readUnsignedByte(), crashBuf.readUnsignedByte(), crashBuf.readUnsignedByte())
            .getDate()

        long baseMs = crashTime.time
        [baseMs - 3000, baseMs - 2000, baseMs - 1000, baseMs + 1000].eachWithIndex { timeMs, i ->
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid    = true
            pos.time     = new Date(timeMs)
            pos.latitude  = crashBuf.readIntLE() / 10_000_000.0
            pos.longitude = crashBuf.readIntLE() / 10_000_000.0
            pos.speed     = UnitsConverter.knotsFromKph(crashBuf.readUnsignedShort() / 100.0)
            pos.course    = crashBuf.readUnsignedShort() / 100.0

            def sb = new StringBuilder('[')
            for (int s = 0; s < 100; s++) {
                if (s > 0) sb.append(',')
                sb.append('[').append(crashBuf.readShortLE()).append(',')
                              .append(crashBuf.readShortLE()).append(',')
                              .append(crashBuf.readShortLE()).append(']')
            }
            sb.append(']')
            pos.set(Position.KEY_G_SENSOR, sb.toString())
            ctx.emit(pos)
        }
    } finally {
        crashBuf.release()
    }
    return null
}

// ---- binary decoders -----------------------------------------------------

def decodeZip = { buf, ctx ->
    buf.skip(1)  // 0x02 header
    buf.skip(2)  // length

    String hexId  = buf.readHex(5)
    def session   = ctx.session(hexId.substring(0, 9))
    if (!session) return null

    buf.skip(1)  // model
    buf.skip(2)  // software version

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    pos.time = new org.traccar.helper.DateBuilder()
        .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
        .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
        .getDate()

    buf.skip(2)  // lac
    buf.skip(1)  // cid

    // BCD-encoded lat/lon: integer degrees + 6-digit BCD fractional degrees
    int latDeg = buf.readUByte()
    double lat = latDeg + Integer.parseInt(buf.readBcd(6)) / 1_000_000.0
    int lonDeg = buf.readUByte()
    double lon = lonDeg + Integer.parseInt(buf.readBcd(6)) / 1_000_000.0

    int speedInt = buf.readUShort()
    double speed = speedInt + Integer.parseInt(buf.readBcd(2)) / 100.0
    int courseInt = buf.readUShort()
    double course = courseInt + Integer.parseInt(buf.readBcd(2)) / 100.0
    pos.speed  = speed
    pos.course = course

    int flags = buf.readUByte()
    pos.valid     = (flags & 0x80) != 0
    pos.latitude  = (flags & 0x40) != 0 ? -lat : lat
    pos.longitude = (flags & 0x20) != 0 ? -lon : lon

    pos.set(Position.KEY_ODOMETER, buf.readUInt())

    int powerInt = buf.readUByte()
    double power = powerInt + Integer.parseInt(buf.readBcd(2)) / 100.0
    pos.set(Position.KEY_POWER, power)

    int io = buf.readUByte()
    pos.set(Position.KEY_IGNITION, (io & 0x01) != 0)
    for (int i = 1; i <= 3; i++) pos.set(Position.PREFIX_IN  + i, (io & (1 << i)) != 0)
    for (int i = 1; i <= 2; i++) pos.set(Position.PREFIX_OUT + i, (io & (1 << (i + 3))) != 0)

    pos.set(Position.KEY_EVENT, buf.readUByte())

    int hbm = ctx.configInt('hbm', 0)
    if (hbm == 1 && buf.readableBytes() >= 7) {
        pos.set(Position.KEY_HOURS,   buf.readUInt())
        pos.set(Position.KEY_BATTERY, buf.readUShort())
        pos.set(Position.KEY_ARCHIVE, buf.readUByte() == 0 ? true : null)
    } else if (hbm == 2 && buf.readableBytes() >= 9) {
        int cid  = buf.readUShort()
        int mcc  = buf.readUShort()
        int mnc  = buf.readUByte()
        int rssi = buf.readUShort()
        int lac  = buf.readUShort()
        buf.skip(1)  // timing advance
        pos.network = new Network(CellTower.from(mcc, mnc, lac, cid, rssi))
    }

    return pos
}

def decodeBinary = { buf, ctx ->
    int type = buf.readUByte()
    buf.skip(2)  // length

    def session = ctx.session(buf.readHex(5))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    int mask = buf.readUByte() | (buf.readUByte() << 8) | (buf.readUByte() << 16)

    if (BitUtil.check(mask, 1)) buf.skip(1)  // model
    if (BitUtil.check(mask, 2)) {
        pos.set(Position.KEY_VERSION_FW, "${buf.readUByte()}.${buf.readUByte()}.${buf.readUByte()}")
    }
    if (BitUtil.check(mask, 3) && buf.readUByte() == 0) pos.set(Position.KEY_ARCHIVE, true)
    if (BitUtil.check(mask, 4) && BitUtil.check(mask, 5)) {
        pos.time = new org.traccar.helper.DateBuilder()
            .setDate(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .setTime(buf.readUByte(), buf.readUByte(), buf.readUByte())
            .getDate()
    }
    if (BitUtil.check(mask, 6))  buf.skip(4)   // cell
    if (BitUtil.check(mask, 7))  buf.skip(2)   // mcc
    if (BitUtil.check(mask, 8))  buf.skip(2)   // mnc
    if (BitUtil.check(mask, 9))  buf.skip(2)   // lac
    if (BitUtil.check(mask, 10)) pos.set(Position.KEY_RSSI, buf.readUByte())

    if (BitUtil.check(mask, 11)) {
        // signed-magnitude 32-bit int
        int raw = buf.readInt()
        pos.latitude = (raw < 0 ? -(raw & 0x7fffffff) : raw) / 1_000_000.0
    }
    if (BitUtil.check(mask, 12)) {
        int raw = buf.readInt()
        pos.longitude = (raw < 0 ? -(raw & 0x7fffffff) : raw) / 1_000_000.0
    }
    if (BitUtil.check(mask, 13)) pos.speed  = UnitsConverter.knotsFromKph(buf.readUShort() / 100.0)
    if (BitUtil.check(mask, 14)) pos.course = buf.readUShort() / 100.0
    if (BitUtil.check(mask, 15)) pos.set(Position.KEY_SATELLITES, buf.readUByte())
    if (BitUtil.check(mask, 16)) pos.valid  = buf.readUByte() > 0
    if (BitUtil.check(mask, 17)) {
        int input = buf.readUByte()
        pos.set(Position.KEY_IGNITION, BitUtil.check(input, 0))
        pos.set(Position.KEY_INPUT, input)
    }
    if (BitUtil.check(mask, 18)) pos.set(Position.KEY_OUTPUT, buf.readUByte())

    int alertId = 0
    if (BitUtil.check(mask, 19)) {
        alertId = buf.readUByte()
        if (type == 0x82) pos.addAlarm(decodeAlert(alertId))
    }
    if (BitUtil.check(mask, 20)) buf.skip(2)  // alert modifier
    if (BitUtil.check(mask, 21) && alertId == 59) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, buf.readHex(8))
    }

    return pos
}

// ---- encoder helpers -----------------------------------------------------

def encodeUniversalCommand = { cmd, ctx ->
    switch (cmd.type) {
        case Command.TYPE_REBOOT_DEVICE:    return "CMD;${ctx.deviceId()};03;03\r"
        case Command.TYPE_POSITION_SINGLE:  return "CMD;${ctx.deviceId()};03;01\r"
        case Command.TYPE_ENGINE_STOP:      return "CMD;${ctx.deviceId()};04;01\r"
        case Command.TYPE_ENGINE_RESUME:    return "CMD;${ctx.deviceId()};04;02\r"
        case Command.TYPE_ALARM_ARM:        return "CMD;${ctx.deviceId()};04;03\r"
        case Command.TYPE_ALARM_DISARM:     return "CMD;${ctx.deviceId()};04;04\r"
        case Command.TYPE_OUTPUT_CONTROL:
            int out = cmd.getInteger(Command.KEY_INDEX)
            boolean on = '1' == cmd.getString(Command.KEY_DATA)
            switch (out) {
                case 1: return "CMD;${ctx.deviceId()};04;${on ? '01' : '02'}\r"
                case 2: return "CMD;${ctx.deviceId()};04;${on ? '03' : '04'}\r"
                case 3: return "CMD;${ctx.deviceId()};04;${on ? '09' : '10'}\r"
            }
    }
    return null
}

def encodeLegacyCommand = { cmd, String prefix, ctx ->
    switch (cmd.type) {
        case Command.TYPE_REBOOT_DEVICE:    return "${prefix}CMD;${ctx.deviceId()};02;Reboot\r"
        case Command.TYPE_POSITION_SINGLE:  return "${prefix}CMD;${ctx.deviceId()};02;StatusReq\r"
        case Command.TYPE_ENGINE_STOP:      return "${prefix}CMD;${ctx.deviceId()};02;Enable1\r"
        case Command.TYPE_ENGINE_RESUME:    return "${prefix}CMD;${ctx.deviceId()};02;Disable1\r"
        case Command.TYPE_ALARM_ARM:        return "${prefix}CMD;${ctx.deviceId()};02;Enable2\r"
        case Command.TYPE_ALARM_DISARM:     return "${prefix}CMD;${ctx.deviceId()};02;Disable2\r"
        case Command.TYPE_OUTPUT_CONTROL:
            boolean on  = '1' == cmd.getString(Command.KEY_DATA)
            String index = cmd.getString(Command.KEY_INDEX)
            return "${prefix}CMD;${ctx.deviceId()};02;${on ? 'Enable' : 'Disable'}${index}\r"
    }
    return null
}

// =========================================================================

protocol("suntech") {

    port 5011

    variant("mixed") {

        // Scripted frame: handles ZIP binary, universal binary, and all text formats.
        frame { buf ->
            if (buf.readableBytes() < 2) return null
            int b0 = buf.getUByte(0)
            if (b0 == 0x02) {
                // ZIP binary: 0x02 + 2-byte length + payload + footer(1)
                if (buf.readableBytes() < 4) return null
                return 1 + 2 + buf.getUShort(1) + 1
            } else if (buf.getUByte(1) == 0) {
                // Universal binary: type(1) + 2-byte length + payload
                if (buf.readableBytes() < 4) return null
                return 1 + 2 + buf.getUShort(1)
            } else {
                // Text: find standalone \r (not followed by \n), skipping \r\n pairs
                int i = 0
                while (true) {
                    int pos = buf.indexOf((int) '\r', i)
                    if (pos < 0) return null
                    if (pos + 1 >= buf.readableBytes()) return null  // wait for byte after \r
                    if (buf.getUByte(pos + 1) == (int) '\n') {
                        i = pos + 1  // \r\n pair inside binary data — keep scanning
                        continue
                    }
                    // Standalone \r: consume text + \r, emit text only
                    return FrameResult.transformed(pos + 1, buf.bytes(0, pos))
                }
            }
        }

        decode { buf, ctx ->
            int b0 = buf.getUByte(0)

            if (b0 == 0x02) {
                ctx.store()['suntech.universal'] = false
                return decodeZip(buf, ctx)
            }

            if (b0 < 0x20 || b0 > 0x7E) {
                // Universal binary: non-printable first byte, second byte was 0x00 in raw stream
                ctx.store()['suntech.universal'] = true
                return decodeBinary(buf, ctx)
            }

            // CRR frames contain binary data after the 23-byte ASCII header — handle before
            // reading everything as text.
            if (buf.readableBytes() >= 4
                    && buf.getUByte(0) == (int)'C'
                    && buf.getUByte(1) == (int)'R'
                    && buf.getUByte(2) == (int)'R'
                    && buf.getUByte(3) == (int)';') {
                ctx.store()['suntech.universal'] = false
                return decodeCrashReport(buf.readBytes(buf.readableBytes()), ctx)
            }

            // Pure text frame
            String text        = buf.readString(buf.readableBytes())
            String[] values    = text.split(';', -1)
            String msgPrefix   = values[0]
            boolean isUniversal = msgPrefix.length() < 5

            ctx.store()['suntech.universal'] = isUniversal
            if (!isUniversal && msgPrefix.length() > 5) {
                ctx.store()['suntech.prefix'] = msgPrefix.substring(0, msgPrefix.length() - 3)
            }

            if (isUniversal) {
                return decodeUniversal(values, ctx)
            } else if (msgPrefix.endsWith('HTE')) {
                return decodeTravelReport(values, ctx)
            } else if (msgPrefix.startsWith('ST9')) {
                return decode9(values, ctx)
            } else if (msgPrefix.startsWith('ST4')) {
                return decode4(values, ctx)
            } else {
                return decode2356(values, msgPrefix.substring(0, 5), ctx)
            }
        }

        encode { cmd, ctx ->
            boolean universal = ctx.store().getOrDefault('suntech.universal', ctx.alternative()) as boolean
            if (universal) {
                return encodeUniversalCommand(cmd, ctx)
            } else {
                String prefix = ctx.store().getOrDefault('suntech.prefix', 'SA200') as String
                return encodeLegacyCommand(cmd, prefix, ctx)
            }
        }
    }
}
