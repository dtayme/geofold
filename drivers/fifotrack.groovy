// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Fifotrack GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/fifotrack/ (Java reference)
 *
 * ASCII, comma-delimited frames:
 *   $$<length>,<imei>,<index>,<type>,...,*<checksum>\r\n
 *
 * <length> is the number of bytes from just after the first comma through
 * the checksum digits (inclusive). Frames are terminated by a 2-byte CRLF
 * delimiter that is consumed but not part of the decoded payload.
 *
 * Message types:
 *   A00/A01  legacy location report
 *   A03      new-format location report (cell/battery/status + GPS or wifi)
 *   B03      command result ("OK"/error code)
 *   D05/D06  photo request ack / binary photo chunk — unsupported, see below
 *
 * Photo retrieval (D05/D06) requires saving binary JPEG chunks to a media
 * file; the driver DSL has no equivalent of Java's writeMediaFile(), so —
 * consistent with the gps103 migration — these packets are simply not
 * decoded (return null). The REQUEST_PHOTO command can still be sent.
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

def DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)

def PATTERN = Pattern.compile(
    /\$\$\d+,(\d+),[0-9a-fA-F]+,[^,]+,(\d+)?,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([AV]),(-?\d+\.\d+),(-?\d+\.\d+),(\d+),(\d+),(-?\d+),(\d+),(\d+),([0-9a-fA-F]+),([0-9a-fA-F]+)?,([0-9a-fA-F]+)?,(\d+)\|(\d+)\|([0-9a-fA-F]+)\|([0-9a-fA-F]+),([0-9a-fA-F|]+)(?:,([^,]+),([^*]*))?.*/,
    Pattern.DOTALL)

def PATTERN_NEW = Pattern.compile(
    /\$\$\d+,(\d+),([0-9a-fA-F]+),A03,(\d+)?,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(\d+)\|(\d+)\|([0-9a-fA-F]+)\|([0-9a-fA-F]+),(\d+\.\d+),(\d+),([0-9a-fA-F]+),(?:0,([AV]),(\d+),(\d+),(-?\d+\.\d+),(-?\d+\.\d+)|1,([^*]+))\*[0-9a-fA-F]{2}/,
    Pattern.DOTALL)

def PATTERN_RESULT = Pattern.compile(
    /\$\$\d+,(\d+),.*,([A-Z]+)\*[0-9a-fA-F]{2}/,
    Pattern.DOTALL)

def parseDateTime = { String yy, String mo, String dd, String hh, String mi, String ss ->
    new DateBuilder()
            .setDate(yy.toInteger(), mo.toInteger(), dd.toInteger())
            .setTime(hh.toInteger(), mi.toInteger(), ss.toInteger())
            .getDate()
}

def decodeAlarm = { Integer alarm ->
    if (alarm == null) return null
    switch (alarm) {
        case 2: return ALARM_SOS
        case 14: return ALARM_LOW_POWER
        case 15: return ALARM_POWER_CUT
        case 16: return ALARM_POWER_RESTORED
        case 17: return ALARM_LOW_BATTERY
        case 18: return ALARM_OVERSPEED
        case 20: return ALARM_GPS_ANTENNA_CUT
        case 21: return ALARM_VIBRATION
        case 23: return ALARM_ACCELERATION
        case 24: return ALARM_BRAKING
        case 27: return ALARM_FATIGUE_DRIVING
        case 30:
        case 32: return ALARM_JAMMING
        case 31: return ALARM_FALL_DOWN
        case 33: return ALARM_GEOFENCE_EXIT
        case 34: return ALARM_GEOFENCE_ENTER
        case 35: return ALARM_IDLE
        case 40:
        case 41: return ALARM_TEMPERATURE
        case 53: return ALARM_POWER_ON
        case 54: return ALARM_POWER_OFF
        default: return null
    }
}

def sendResponse = { ctx, String imei, String content ->
    int length = 1 + imei.length() + 1 + content.length()
    String response = String.format("##%02d,%s,%s*", length, imei, content)
    response += Checksum.sum(response)
    ctx.ack(response + "\r\n")
}

def decodeResult = { String sentence, ctx ->
    def m = PATTERN_RESULT.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_RESULT, m.group(2))

    return pos
}

def decodeLocationNew = { String sentence, ctx ->
    def m = PATTERN_NEW.matcher(sentence)
    if (!m.matches()) return null

    String imei = m.group(1)
    def session = ctx.session(imei)
    if (!session) return null

    String index = m.group(2)

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm(m.group(3) ? m.group(3).toInteger() : null))

    Date deviceTime = parseDateTime(m.group(4), m.group(5), m.group(6), m.group(7), m.group(8), m.group(9))
    pos.deviceTime = deviceTime

    def network = new Network()
    network.addCellTower(CellTower.from(
            m.group(10).toInteger(), m.group(11).toInteger(),
            Integer.parseInt(m.group(12), 16), Integer.parseInt(m.group(13), 16)))

    pos.set(Position.KEY_BATTERY, m.group(14).toDouble())
    pos.set(Position.KEY_BATTERY_LEVEL, m.group(15).toInteger())
    pos.set(Position.KEY_STATUS, Integer.parseInt(m.group(16), 16))

    if (m.group(17)) {
        pos.valid = m.group(17) == 'A'
        pos.fixTime = deviceTime
        pos.speed = UnitsConverter.knotsFromKph(m.group(18).toInteger())
        pos.set(Position.KEY_SATELLITES, m.group(19).toInteger())
        pos.latitude = m.group(20).toDouble()
        pos.longitude = m.group(21).toDouble()
    } else {
        ctx.lastLocation(pos, deviceTime)
        m.group(22).split(/\|/).each { point ->
            def wifi = point.split(':')
            String mac = wifi[0].replaceAll('(..)', '$1:')
            network.addWifiAccessPoint(WifiAccessPoint.from(mac.substring(0, mac.length() - 1), wifi[1].toInteger()))
        }
    }

    pos.network = network

    String response = index + ",A03," + DATE_FORMAT.format(Instant.now())
    sendResponse(ctx, imei, response)

    return pos
}

def decodeLocation = { String sentence, ctx ->
    def m = PATTERN.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm(m.group(2) ? m.group(2).toInteger() : null))

    pos.time = parseDateTime(m.group(3), m.group(4), m.group(5), m.group(6), m.group(7), m.group(8))

    pos.valid = m.group(9) == 'A'
    pos.latitude = m.group(10).toDouble()
    pos.longitude = m.group(11).toDouble()
    pos.speed = UnitsConverter.knotsFromKph(m.group(12).toInteger())
    pos.course = m.group(13).toInteger()
    pos.altitude = m.group(14).toInteger()

    pos.set(Position.KEY_ODOMETER, m.group(15).toLong())
    pos.set(Position.KEY_HOURS, m.group(16).toLong() * 1000)

    long status = Long.parseLong(m.group(17), 16)
    pos.set(Position.KEY_RSSI, BitUtil.between(status, 3, 8))
    pos.set(Position.KEY_SATELLITES, BitUtil.from(status, 28))
    pos.set(Position.KEY_STATUS, status)

    if (m.group(18)) pos.set(Position.KEY_INPUT, Integer.parseInt(m.group(18), 16))
    if (m.group(19)) pos.set(Position.KEY_OUTPUT, Integer.parseInt(m.group(19), 16))

    pos.network = new Network(CellTower.from(
            m.group(20).toInteger(), m.group(21).toInteger(),
            Integer.parseInt(m.group(22), 16), Integer.parseInt(m.group(23), 16)))

    m.group(24).split(/\|/).eachWithIndex { value, i ->
        pos.set(Position.PREFIX_ADC + (i + 1), Integer.parseInt(value, 16))
    }

    String rfid = m.group(25)
    if (rfid) {
        if (rfid.matches(/\p{XDigit}+/)) {
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(Integer.parseInt(rfid, 16)))
        } else {
            pos.set(Position.KEY_CARD, rfid)
        }
    }

    String sensors = m.group(26)
    if (sensors) {
        sensors.split(/\|/).eachWithIndex { value, i -> pos.set(Position.PREFIX_IO + (i + 1), value) }
    }

    return pos
}

def formatCommand = { String imei, String content ->
    int length = 1 + imei.length() + 3 + content.length()
    String result = String.format("##%02d,%s,1,%s*", length, imei, content)
    result + Checksum.sum(result) + "\r\n"
}

protocol("fifotrack") {

    port 5124
    commands TYPE_CUSTOM, TYPE_REQUEST_PHOTO

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 10) return null
            int index = fb.indexOf(',' as char)
            if (index < 0) return null
            int n = Integer.parseInt(fb.ascii(2, index - 2))
            int length = index + 3 + n
            if (fb.readableBytes() >= length + 2) {
                return frameResult(length + 2, fb.bytes(0, length))
            }
            return null
        }

        decode { msg, ctx ->
            def buf = msg as org.traccar.driver.BufReader
            String sentence = buf.readString(buf.readableBytes())

            int i1 = sentence.indexOf(',')
            int i2 = i1 < 0 ? -1 : sentence.indexOf(',', i1 + 1)
            int i3 = i2 < 0 ? -1 : sentence.indexOf(',', i2 + 1)
            if (i1 < 0 || i2 < 0 || i3 < 0 || sentence.length() < i3 + 4) return null
            String type = sentence.substring(i3 + 1, i3 + 4)

            if (type.startsWith('B')) {
                return decodeResult(sentence, ctx)
            } else if (type == 'D05' || type == 'D06') {
                return null
            } else if (type == 'A03') {
                return decodeLocationNew(sentence, ctx)
            } else {
                return decodeLocation(sentence, ctx)
            }
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return formatCommand(ctx.deviceId(), ctx.data())
                case TYPE_REQUEST_PHOTO:
                    return formatCommand(ctx.deviceId(), "D05,3")
            }
            return null
        }
    }
}
