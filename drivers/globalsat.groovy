// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GlobalSat text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/globalsat/
 *
 * Supports configurable GS original formats, alternative $ reports, ACKs, cell
 * information, voltage parsing, alarms, and command encoding.
 */

import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.Parser
import org.traccar.helper.PatternBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position

def bit = { int value, int index -> (value & (1 << index)) != 0 }

def voltage = { String value ->
    if (value.endsWith("mV")) return Integer.parseInt(value.substring(0, value.length() - 2)) / 1000.0
    if (value.endsWith("V")) return Double.parseDouble(value.substring(0, value.length() - 1))
    null
}

def alarm = { int value ->
    if (bit(value, 0)) return ALARM_SOS
    if (bit(value, 3) || bit(value, 4)) return ALARM_GEOFENCE
    if (bit(value, 5)) return ALARM_OVERSPEED
    if (bit(value, 6)) return ALARM_POWER_CUT
    if (bit(value, 7)) return ALARM_LOW_POWER
    null
}

def PATTERN = new PatternBuilder()
        .text('$')
        .number('(d+),')
        .number('d+,')
        .number('(d+),')
        .number('(dd)(dd)(dd),')
        .number('(dd)(dd)(dd),')
        .expression('([EW])')
        .number('(ddd)(dd.d+),')
        .expression('([NS])')
        .number('(dd)(dd.d+),')
        .number('(d+.?d*),')
        .number('(d+.?d*),')
        .number('(d+.?d*)?,')
        .number('(d+)[,*]')
        .number('(d+.?d*)')
        .compile()

def decodeOriginal = { String sentence, ctx ->
    ctx.ack("ACK\r")

    String format
    if (sentence.startsWith("GSr") || sentence.startsWith("GSb")) {
        format = ctx.configString("format0", "")
    } else if (sentence.startsWith("GSh")) {
        format = ctx.configString("format1", "")
    } else {
        return null
    }

    if (!format.contains("B") || !format.contains("S")
            || !(format.contains("1") || format.contains("2") || format.contains("3"))
            || !(format.contains("6") || format.contains("7") || format.contains("8"))) {
        return null
    }

    if (format.contains("*")) {
        format = format.substring(0, format.indexOf('*'))
        sentence = sentence.substring(0, sentence.indexOf('*'))
    }

    String[] values = sentence.split(",")
    def pos = ctx.newPosition()
    def cellTower = new CellTower()

    for (int formatIndex = 0, valueIndex = 1; formatIndex < format.length() && valueIndex < values.length; formatIndex++) {
        String value = values[valueIndex].replace("\"", "")

        switch (format.charAt(formatIndex)) {
            case 'S':
                def session = ctx.session(value)
                if (!session) return null
                pos.deviceId = session.deviceId
                break
            case 'A':
                pos.valid = value.isEmpty() ? false : Integer.parseInt(value) != 1
                break
            case 'B':
                def dateBuilder = new DateBuilder()
                        .setDay(Integer.parseInt(value.substring(0, 2)))
                        .setMonth(Integer.parseInt(value.substring(2, 4)))
                        .setYear(Integer.parseInt(value.substring(4)))
                value = values[++valueIndex]
                pos.time = dateBuilder
                        .setHour(Integer.parseInt(value.substring(0, 2)))
                        .setMinute(Integer.parseInt(value.substring(2, 4)))
                        .setSecond(Integer.parseInt(value.substring(4)))
                        .getDate()
                break
            case 'C':
                valueIndex++
                break
            case '1':
                double longitude = Double.parseDouble(value.substring(1))
                pos.longitude = value.charAt(0) == 'W' ? -longitude : longitude
                break
            case '2':
                double longitude = Double.parseDouble(value.substring(4)) / 60.0 + Integer.parseInt(value.substring(1, 4))
                pos.longitude = value.charAt(0) == 'W' ? -longitude : longitude
                break
            case '3':
                pos.longitude = Double.parseDouble(value) / 1000000.0
                break
            case '6':
                double latitude = Double.parseDouble(value.substring(1))
                pos.latitude = value.charAt(0) == 'S' ? -latitude : latitude
                break
            case '7':
                double latitude = Double.parseDouble(value.substring(3)) / 60.0 + Integer.parseInt(value.substring(1, 3))
                pos.latitude = value.charAt(0) == 'S' ? -latitude : latitude
                break
            case '8':
                pos.latitude = Double.parseDouble(value) / 1000000.0
                break
            case 'G':
                pos.altitude = Double.parseDouble(value)
                break
            case 'H':
                pos.speed = Double.parseDouble(value)
                break
            case 'I':
                pos.speed = UnitsConverter.knotsFromKph(Double.parseDouble(value))
                break
            case 'J':
                pos.speed = UnitsConverter.knotsFromMph(Double.parseDouble(value))
                break
            case 'K':
                pos.course = Double.parseDouble(value)
                break
            case 'L':
                pos.set(Position.KEY_SATELLITES, Integer.parseInt(value))
                break
            case 'P':
                if (value.length() == 4) pos.addAlarm(alarm(Integer.parseInt(value, 16)))
                break
            case 'Z':
                if (!value.isEmpty()) pos.set("geofence", value)
                break
            case 'Y':
                int io = Integer.parseInt(value, 16)
                pos.set(Position.PREFIX_IN + 1, bit(io, 1))
                pos.set(Position.KEY_MOTION, bit(io, 7))
                pos.set(Position.PREFIX_OUT + 1, bit(io, 9))
                pos.set(Position.KEY_IGNITION, bit(io, 13))
                pos.set(Position.KEY_CHARGE, bit(io, 15))
                break
            case 'c':
                cellTower.signalStrength = Integer.parseInt(value)
                break
            case 'm':
                pos.set(Position.KEY_POWER, voltage(value))
                break
            case 'n':
            case 'N':
                pos.set(Position.KEY_BATTERY, voltage(value))
                break
            case 't':
                cellTower.mobileCountryCode = Integer.parseInt(value)
                break
            case 'u':
                cellTower.mobileNetworkCode = Integer.parseInt(value)
                break
            case 'v':
                cellTower.locationAreaCode = Integer.parseInt(value, 16)
                break
            case 'w':
                cellTower.cellId = Long.parseLong(value, 16)
                break
        }

        valueIndex++
    }

    if (cellTower.cellId != null) {
        pos.network = new Network(cellTower)
    }
    pos
}

def decodeAlternative = { String sentence, ctx ->
    def parser = new Parser(PATTERN, sentence)
    if (!parser.matches()) return null

    def session = ctx.session(parser.next())
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.valid = parser.next() != "1"
    pos.time = parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS)
    pos.longitude = parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN)
    pos.latitude = parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN)
    pos.altitude = parser.nextDouble(0)
    pos.speed = parser.nextDouble(0)
    pos.course = parser.nextDouble(0)
    pos.set(Position.KEY_SATELLITES, parser.nextInt(0))
    pos.set(Position.KEY_HDOP, parser.nextDouble())
    pos
}

protocol("globalsat") {
    port 5043
    commands TYPE_CUSTOM, TYPE_ALARM_DISMISS, TYPE_OUTPUT_CONTROL

    variant("main") {
        frame readUntilKeep('!')
        matches { msg -> msg instanceof String && (msg.startsWith("GS") || msg.startsWith('$')) }

        decode { raw, ctx ->
            String sentence = raw.trim()
            if (sentence.startsWith("GS")) return decodeOriginal(sentence, ctx)
            if (sentence.startsWith('$')) return decodeAlternative(sentence, ctx)
            null
        }

        encode { cmd, ctx ->
            String formatted = null
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    formatted = "GSC,${ctx.deviceId()},${ctx.data()}"
                    break
                case TYPE_ALARM_DISMISS:
                    formatted = "GSC,${ctx.deviceId()},Na"
                    break
                case TYPE_OUTPUT_CONTROL:
                    formatted = "GSC,${ctx.deviceId()},Lo(${cmd.getInteger(Command.KEY_INDEX)},${ctx.data()})"
                    break
            }
            formatted != null ? formatted + Checksum.nmea(formatted) + '!' : null
        }
    }
}
