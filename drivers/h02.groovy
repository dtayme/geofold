// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * H02 GPS tracker driver.
 *
 * Supported wire formats:
 *   *<mfr>,<imei>,<type>,...#        text protocol
 *   $<32/45-byte BCD frame>          binary standard frame
 *   X<32-byte BCD frame>             binary X-mode frame
 *
 * Message types handled:
 *   V0 / HTBT / XT — heartbeat
 *   V1 / V2 / V5..V11 / VI1 / ALRM — GPS position-style reports
 *   BC             — blind spot batch upload
 *   NBR            — multi-cell LBS positioning
 *   LINK           — activity/fitness tracker data
 *   V3             — LBS positioning and address-request variants
 *   VP1            — simple GPS or LBS positioning
 *   V4             — command acknowledgements, with or without GPS fields
 *   SMS            — command result
 *
 * The '$' binary format does not carry a length field, so 32-byte and 45-byte
 * records are distinguishable only when the TCP buffer boundary exposes the
 * complete record length. This matches the archived Java H02 decoder behavior.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// Patterns (compiled once)
// ---------------------------------------------------------------------------

def P_LINK = Pattern.compile(
    /^\*..,([\d]+),LINK,(\d{2})(\d{2})(\d{2}),(\d+),(\d+),(\d+),(\d+),(\d+),(\d{2})(\d{2})(\d{2}),([0-9A-Fa-f]{8})/)

def P_SMS = Pattern.compile(
    /^\*..,([\d]+),SMS,(.+)$/)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

def isUInt = { v -> v != null && v ==~ /\d+/ }
def isTime = { v -> v != null && v ==~ /\d{6}/ }
def isDate = { v -> v != null && v ==~ /\d{6}/ }
def isStatus = { v -> v != null && v ==~ /[0-9A-Fa-f]{8}/ }

def safeInt = { v, d = 0 ->
    isUInt(v) ? v.toInteger() : d
}

def utcTimeNow = {
    def format = new SimpleDateFormat('HHmmss')
    format.setTimeZone(TimeZone.getTimeZone('UTC'))
    format.format(new Date())
}

def utcDateTimeNow = {
    def format = new SimpleDateFormat('yyyyMMddHHmmss')
    format.setTimeZone(TimeZone.getTimeZone('UTC'))
    format.format(new Date())
}

def currentDateForDayTime = { day, hh, mm, ss ->
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    cal.set(Calendar.DAY_OF_MONTH, day)
    cal.set(Calendar.HOUR_OF_DAY, hh)
    cal.set(Calendar.MINUTE, mm)
    cal.set(Calendar.SECOND, ss)
    cal.set(Calendar.MILLISECOND, 0)
    cal.getTime()
}

// Convert H02 ddmm.mmmm / dddmm.mmmm / ddmmffff coordinate strings.
def coordinate = { raw, hemi, boolean lon ->
    if (!raw) return 0.0

    int explicitSign = 1
    String value = raw.trim()
    if (value.startsWith('-')) {
        explicitSign = -1
        value = value.substring(1)
    }

    double degrees
    double minutes

    if (value.contains('-')) {
        def parts = value.split('-', 2)
        degrees = parts[0].toDouble()
        minutes = parts[1].toDouble()
    } else if (value.contains('.')) {
        int dot = value.indexOf('.')
        int degLen = dot - 2
        if (degLen <= 0) return 0.0
        degrees = value.substring(0, degLen).toDouble()
        minutes = value.substring(degLen).toDouble()
    } else {
        int degLen = lon ? 3 : 2
        if (value.length() <= degLen + 1) return 0.0
        degrees = value.substring(0, degLen).toDouble()
        minutes = (value.substring(degLen, degLen + 2) + '.' + value.substring(degLen + 2)).toDouble()
    }

    double result = explicitSign * (degrees + minutes / 60.0)
    (hemi == 'S' || hemi == 'W') ? -Math.abs(result) : result
}

def processStatus = { pos, long status ->
    // H02 status uses active-low alarm bits. Keep the raw value for consumers
    // that need device-specific interpretation beyond the common alarms.
    if (!(status & 0x01)) pos.addAlarm(ALARM_VIBRATION)
    if (!(status & 0x02) || !(status & (1 << 18))) pos.addAlarm(ALARM_SOS)
    if (!(status & 0x04)) pos.addAlarm(ALARM_OVERSPEED)
    if (!(status & (1 << 19))) pos.addAlarm(ALARM_POWER_CUT)
    pos.set(Position.KEY_IGNITION, (status & (1 << 10)) != 0)
    pos.set(Position.KEY_STATUS, status)
}

def decodeBattery = { int value ->
    if (value == 0) {
        null
    } else if (value <= 3) {
        (value - 1) * 10
    } else if (value <= 6) {
        (value - 1) * 20
    } else if (value <= 100) {
        value
    } else if (value >= 0xF1 && value <= 0xF6) {
        value - 0xF0
    } else {
        null
    }
}

def addCellNetwork = { pos, String[] values, int start ->
    for (int i = start; i + 3 < values.length; i++) {
        if (!(isUInt(values[i]) && values[i].length() == 3 && isUInt(values[i + 1]))) {
            continue
        }

        int mcc = values[i].toInteger()
        int mnc = values[i + 1].toInteger()
        int cellStart = i + 2
        int count = -1
        if (cellStart < values.length && isUInt(values[cellStart])
                && values[cellStart].toInteger() <= 10 && cellStart + 2 < values.length) {
            count = values[cellStart].toInteger()
            cellStart++
        }

        def network = new Network()
        int added = 0
        for (int j = cellStart; j + 1 < values.length; j += 2) {
            if (!(isUInt(values[j]) && isUInt(values[j + 1]))) break
            network.addCellTower(CellTower.from(mcc, mnc, values[j].toInteger(), values[j + 1].toInteger()))
            added++
            if (count >= 0 && added >= count) break
        }
        if (added > 0) {
            pos.network = network
            return true
        }
    }
    false
}

def parseGpsFields = { String[] values, int start, ctx, String imei, String event ->
    if (values.length <= start + 7) return null

    def session = ctx.session(imei)
    if (!session) return null

    int index = start
    def db = new DateBuilder()
    if (isTime(values[index])) {
        db.setTime(values[index].substring(0, 2).toInteger(),
                values[index].substring(2, 4).toInteger(),
                values[index].substring(4, 6).toInteger())
        index++
    }

    boolean valid = true
    if (index < values.length && values[index] ==~ /[ABV]/) {
        valid = values[index] != 'V'
        index++
    } else if (index < values.length && isUInt(values[index])) {
        // Coding scheme field used by VI1 and some address-request packets.
        index++
    }

    if (values.length <= index + 6) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.valid = valid
    pos.latitude = coordinate(values[index], values[index + 1], false)
    pos.longitude = coordinate(values[index + 2], values[index + 3], true)
    pos.speed = values[index + 4] ? values[index + 4].toDouble() : 0
    pos.course = values[index + 5] ? values[index + 5].toDouble() : 0
    index += 6

    int dateIndex = -1
    for (int i = index; i < values.length; i++) {
        if (isDate(values[i])) {
            dateIndex = i
            break
        }
    }

    if (dateIndex >= 0) {
        db.setDateReverse(values[dateIndex].substring(0, 2).toInteger(),
                values[dateIndex].substring(2, 4).toInteger(),
                values[dateIndex].substring(4, 6).toInteger())
        pos.time = db.getDate()
        index = dateIndex + 1
    } else {
        pos.time = new Date()
    }

    if (event) {
        pos.set(Position.KEY_EVENT, event)
    }

    int statusIndex = -1
    for (int i = index; i < values.length; i++) {
        if (isStatus(values[i])) {
            statusIndex = i
            break
        }
    }

    if (statusIndex >= 0) {
        processStatus(pos, Long.parseLong(values[statusIndex], 16))
        if (statusIndex + 1 < values.length && isUInt(values[statusIndex + 1])
                && values[statusIndex + 1].toInteger() <= 100) {
            pos.set(Position.KEY_BATTERY_LEVEL, Math.min(values[statusIndex + 1].toInteger(), 100))
        }
        addCellNetwork(pos, values, statusIndex + 1)
    }

    return pos
}

def decodeV4 = { String[] values, ctx ->
    def imei = values[1]
    def session = ctx.session(imei)
    if (!session) return null

    int gpsStart = -1
    for (int i = 3; i + 7 < values.length; i++) {
        if (isTime(values[i]) && values[i + 1] ==~ /[ABV]/) {
            gpsStart = i
            break
        }
    }

    def resultEnd = gpsStart >= 0 ? gpsStart : values.length
    def result = values.length > 3 ? values[3..<resultEnd].join(',') : ''

    if (gpsStart >= 0) {
        def pos = parseGpsFields(values, gpsStart, ctx, imei, null)
        if (pos) {
            pos.set(Position.KEY_RESULT, result)
            return pos
        }
    }

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos)
    pos.set(Position.KEY_RESULT, result)
    return pos
}

def decodeNbr = { String[] values, ctx ->
    if (values.length < 11) return null
    def imei = values[1]
    def session = ctx.session(imei)
    if (!session) return null

    int count = safeInt(values[7], 0)
    int cellsStart = 8
    int dateIndex = cellsStart + count * 3
    if (values.length <= dateIndex + 1) return null

    ctx.ack("*HQ,${imei},V4,NBR,${utcDateTimeNow()}#")

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    def db = new DateBuilder()
            .setTime(values[3].substring(0, 2).toInteger(), values[3].substring(2, 4).toInteger(),
                    values[3].substring(4, 6).toInteger())
            .setDateReverse(values[dateIndex].substring(0, 2).toInteger(),
                    values[dateIndex].substring(2, 4).toInteger(),
                    values[dateIndex].substring(4, 6).toInteger())
    ctx.lastLocation(pos)
    pos.fixTime = db.getDate()

    int mcc = safeInt(values[4])
    int mnc = safeInt(values[5])
    def net = new Network()
    for (int i = 0; i < count && cellsStart + i * 3 + 2 < values.length; i++) {
        net.addCellTower(CellTower.from(mcc, mnc,
                safeInt(values[cellsStart + i * 3]),
                safeInt(values[cellsStart + i * 3 + 1]),
                safeInt(values[cellsStart + i * 3 + 2])))
    }
    pos.network = net

    processStatus(pos, Long.parseLong(values[dateIndex + 1], 16))
    if (values.length > dateIndex + 2 && isUInt(values[dateIndex + 2])) {
        pos.set(Position.KEY_BATTERY_LEVEL, decodeBattery(values[dateIndex + 2].toInteger()))
    }
    return pos
}

def decodeV3 = { String[] values, ctx ->
    if (values.length < 10) return null
    def imei = values[1]
    def session = ctx.session(imei)
    if (!session) return null

    // Address-request variants carry normal GPS fields after V3.
    if (values.length > 5 && values[4] ==~ /[ABV]/) {
        return parseGpsFields(values, 3, ctx, imei, null)
    }

    int xIndex = values.findIndexOf { it == 'X' }
    if (xIndex < 0 || xIndex + 2 >= values.length) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    def db = new DateBuilder()
            .setTime(values[3].substring(0, 2).toInteger(), values[3].substring(2, 4).toInteger(),
                    values[3].substring(4, 6).toInteger())
            .setDateReverse(values[xIndex + 1].substring(0, 2).toInteger(),
                    values[xIndex + 1].substring(2, 4).toInteger(),
                    values[xIndex + 1].substring(4, 6).toInteger())
    ctx.lastLocation(pos)
    pos.fixTime = db.getDate()

    int mcc = values[4].substring(0, 3).toInteger()
    int mnc = values[4].substring(3).toInteger()
    int count = safeInt(values[5])
    def net = new Network()
    for (int i = 0; i < count && 6 + i * 4 + 1 < values.length; i++) {
        net.addCellTower(CellTower.from(mcc, mnc, safeInt(values[6 + i * 4]), safeInt(values[6 + i * 4 + 1])))
    }
    pos.network = net

    if (xIndex >= 2 && values[xIndex - 2] ==~ /[0-9A-Fa-f]{4}/) {
        pos.set(Position.KEY_BATTERY, Integer.parseInt(values[xIndex - 2], 16))
    }
    processStatus(pos, Long.parseLong(values[xIndex + 2], 16))
    return pos
}

def decodeVp1 = { String[] values, ctx ->
    if (values.length < 5) return null
    def imei = values[1]
    def session = ctx.session(imei)
    if (!session) return null

    if (values[3] == 'V') {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        ctx.lastLocation(pos)

        int mcc = safeInt(values[4])
        int mnc = safeInt(values[5])
        def net = new Network()
        values[6].split('Y').each { cell ->
            def cv = cell.split(',')
            if (cv.length >= 3 && isUInt(cv[0]) && isUInt(cv[1]) && isUInt(cv[2])) {
                net.addCellTower(CellTower.from(mcc, mnc, cv[0].toInteger(), cv[1].toInteger(), cv[2].toInteger()))
            }
        }
        pos.network = net
        return pos
    }

    return parseGpsFields(values, 3, ctx, imei, null)
}

def decodeBc = { String[] values, ctx ->
    if (values.length < 6) return null
    def imei = values[1]
    def session = ctx.session(imei)
    if (!session) return null

    def segments = values[5..-1].join(',').split(';')
    segments.each { segment ->
        def f = segment.trim().split(',', -1)
        if (f.length < 9) return

        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        pos.valid = f[0] != 'V'
        pos.latitude = coordinate(f[1], f[2], false)
        pos.longitude = coordinate(f[3], f[4], true)
        pos.speed = f[5] ? f[5].toDouble() : 0
        pos.course = f[6] ? f[6].toDouble() : 0

        if (f[7] ==~ /\d{8}/) {
            pos.time = currentDateForDayTime(
                    f[7].substring(0, 2).toInteger(), f[7].substring(2, 4).toInteger(),
                    f[7].substring(4, 6).toInteger(), f[7].substring(6, 8).toInteger())
        } else {
            pos.time = new Date()
        }

        if (isStatus(f[8])) {
            processStatus(pos, Long.parseLong(f[8], 16))
        }
        addCellNetwork(pos, f, 9)
        ctx.emit(pos)
    }
    return null
}

def readBcdInt = { buf, int digits ->
    int result = 0
    for (int i = 0; i < (int) (digits / 2); i++) {
        int b = buf.readUByte()
        result = result * 10 + ((b >> 4) & 0x0f)
        result = result * 10 + (b & 0x0f)
    }
    if ((digits % 2) != 0) {
        int b = buf.getUByte(0)
        result = result * 10 + ((b >> 4) & 0x0f)
    }
    result
}

def readBinaryCoordinate = { buf, boolean lon ->
    int degrees = readBcdInt(buf, 2)
    if (lon) {
        degrees = degrees * 10 + ((buf.getUByte(0) >> 4) & 0x0f)
    }

    double result = 0
    if (lon) {
        result = buf.readUByte() & 0x0f
    }

    int length = lon ? 5 : 6
    result = result * 10 + readBcdInt(buf, length) / 10000.0
    result / 60.0 + degrees
}

def decodeBinaryStandard = { buf, ctx ->
    int size = buf.readableBytes()
    boolean longId = size == 45
    buf.readUByte() // marker

    String imei = longId ? buf.readHex(8).substring(0, 15) : buf.readHex(5)
    def session = ctx.session(imei)
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    def db = new DateBuilder()
            .setTime(readBcdInt(buf, 2), readBcdInt(buf, 2), readBcdInt(buf, 2))
            .setDateReverse(readBcdInt(buf, 2), readBcdInt(buf, 2), readBcdInt(buf, 2))
    pos.time = db.getDate()

    double lat = readBinaryCoordinate(buf, false)
    def battery = decodeBattery(buf.readUByte())
    if (battery != null) pos.set(Position.KEY_BATTERY_LEVEL, battery)
    double lon = readBinaryCoordinate(buf, true)

    int flags = buf.readUByte() & 0x0f
    pos.valid = (flags & 0x02) != 0
    if ((flags & 0x04) == 0) lat = -lat
    if ((flags & 0x08) == 0) lon = -lon

    pos.latitude = lat
    pos.longitude = lon
    pos.speed = readBcdInt(buf, 3)
    pos.course = (buf.readUByte() & 0x0f) * 100.0 + readBcdInt(buf, 2)
    processStatus(pos, buf.readUInt())
    return pos
}

def decodeBinaryX = { buf, ctx ->
    buf.readUByte() // marker

    def session = ctx.session()
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_ARCHIVE, true)

    long mileage = readBcdInt(buf, 10)
    pos.set(Position.KEY_ODOMETER, (long) (mileage * 0.51444))

    def db = new DateBuilder()
            .setTime(readBcdInt(buf, 2), readBcdInt(buf, 2), readBcdInt(buf, 2))
            .setDateReverse(readBcdInt(buf, 2), readBcdInt(buf, 2), readBcdInt(buf, 2))
    pos.time = db.getDate()

    double lat = readBinaryCoordinate(buf, false)
    int temp = buf.readUByte()
    double lon = readBinaryCoordinate(buf, true)

    int flags = buf.readUByte() & 0x0f
    pos.valid = (flags & 0x02) != 0
    if ((flags & 0x04) == 0) lat = -lat
    if ((flags & 0x08) == 0) lon = -lon
    if (temp < 0xFE) {
        pos.set(Position.PREFIX_TEMP + '1', ((flags & 0x01) != 0 ? -temp : temp) / 2.0)
    }

    pos.latitude = lat
    pos.longitude = lon
    pos.speed = readBcdInt(buf, 3)
    pos.course = (buf.readUByte() & 0x0f) * 100.0 + readBcdInt(buf, 2)
    processStatus(pos, buf.readUInt())
    return pos
}

// ---------------------------------------------------------------------------
// Protocol definition
// ---------------------------------------------------------------------------

protocol("h02") {

    port 5013

    commands TYPE_ALARM_ARM,
             TYPE_ALARM_DISARM,
             TYPE_ENGINE_STOP,
             TYPE_ENGINE_RESUME,
             TYPE_POSITION_SINGLE,
             TYPE_POSITION_PERIODIC,
             TYPE_POSITION_STOP,
             TYPE_REBOOT_DEVICE,
             TYPE_GET_DEVICE_STATUS,
             TYPE_SET_CONNECTION

    variant("binary") {
        maxFrameLength 45
        frame 0x24 as byte, readFixedAny(32, 45)

        decode { buf, ctx ->
            decodeBinaryStandard(buf, ctx)
        }
    }

    variant("binaryX") {
        maxFrameLength 32
        frame 0x58 as byte, readFixed(32)

        decode { buf, ctx ->
            decodeBinaryX(buf, ctx)
        }
    }

    variant("text") {

        maxFrameLength 2048
        frame '*' as char, readUntil('#')

        matches { msg -> msg.startsWith('*') }

        decode { msg, ctx ->

            def values = msg.split(',', -1)
            if (values.length < 3) return null

            def imei = values[1]
            def type = values[2].trim().toUpperCase()

            switch (type) {
                case 'V0':
                case 'HTBT':
                case 'XT': {
                    def session = ctx.session(imei)
                    if (!session) return null
                    ctx.ack(values.length > 3 ? "*${values[0].substring(1)},${imei},${type}#" : "${msg}#")

                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos)
                    if (values.length > 3 && isUInt(values[3])) {
                        pos.set(Position.KEY_BATTERY_LEVEL, decodeBattery(values[3].toInteger()))
                    }
                    pos.set(Position.KEY_RESULT, type)
                    return pos
                }
                case 'V4':
                    return decodeV4(values, ctx)
                case 'NBR':
                    return decodeNbr(values, ctx)
                case 'LINK': {
                    def m = P_LINK.matcher(msg)
                    if (!m.find()) return null
                    def session = ctx.session(m.group(1))
                    if (!session) return null

                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId

                    def db = new DateBuilder()
                            .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                            .setDateReverse(m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
                    ctx.lastLocation(pos)
                    pos.fixTime = db.getDate()

                    pos.set(Position.KEY_RSSI,          m.group(5).toInteger())
                    pos.set(Position.KEY_SATELLITES,    m.group(6).toInteger())
                    pos.set(Position.KEY_BATTERY_LEVEL, m.group(7).toInteger())
                    pos.set(Position.KEY_STEPS,         m.group(8).toInteger())
                    pos.set('turnovers',                m.group(9).toInteger())

                    processStatus(pos, Long.parseLong(m.group(13), 16))
                    return pos
                }
                case 'V3':
                    return decodeV3(values, ctx)
                case 'VP1':
                    return decodeVp1(values, ctx)
                case 'BC':
                    return decodeBc(values, ctx)
                case 'SMS': {
                    def m = P_SMS.matcher(msg)
                    if (!m.find()) return null
                    def session = ctx.session(m.group(1))
                    if (!session) return null
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos)
                    pos.set(Position.KEY_RESULT, m.group(2))
                    return pos
                }
                default: {
                    def pos = parseGpsFields(values, 3, ctx, imei, type)
                    if (pos && type == 'V1') {
                        ctx.ack("*HQ,${imei},V4,V1,${utcDateTimeNow()}#")
                    }
                    return pos
                }
            }
        }

        encode { cmd, ctx ->
            def id = ctx.deviceId()
            def t  = ctx.utcTime()
            def interval = Math.max(ctx.freq(), 0)

            switch (cmd.type) {
                case TYPE_ALARM_ARM:         return "*HQ,${id},SCF,${t},0,0#"
                case TYPE_ALARM_DISARM:      return "*HQ,${id},SCF,${t},1,1#"
                case TYPE_ENGINE_STOP:       return "*HQ,${id},S20,${t},1,1#"
                case TYPE_ENGINE_RESUME:     return "*HQ,${id},S20,${t},1,0#"
                case TYPE_POSITION_SINGLE:   return "*HQ,${id},CR#"
                case TYPE_POSITION_PERIODIC: return ctx.alternative()
                        ? "*HQ,${id},D1,${t},${interval}#"
                        : "*HQ,${id},S71,${t},22,${interval}#"
                case TYPE_POSITION_STOP:     return ctx.alternative()
                        ? "*HQ,${id},D1,${t},0#"
                        : "*HQ,${id},S71,${t},22,0#"
                case TYPE_REBOOT_DEVICE:     return "*HQ,${id},CQ,${t}#"
                case TYPE_GET_DEVICE_STATUS: return "*HQ,${id},INFO#"
                case TYPE_SET_CONNECTION:    return ctx.data()
                case TYPE_CUSTOM:            return ctx.data()
                default:                     return null
            }
        }
    }
}
