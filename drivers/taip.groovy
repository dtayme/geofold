// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * TAIP text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/taip/
 *
 * Supports REV/RPV/RGP/RCQ/RCV/RBR/RUS/RPI reports, semicolon attributes,
 * ACK generation, and both TCP and UDP listeners.
 */

import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.DateUtil
import org.traccar.helper.Parser
import org.traccar.helper.PatternBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Date

def bit = { int value, int index -> (value & (1 << index)) != 0 }

def PATTERN = new PatternBuilder()
        .groupBegin()
        .expression('R[EP]V')
        .groupBegin()
        .number('(dd)')
        .number('(dddd)')
        .number('(d)')
        .groupEnd('?')
        .number('(d{5})')
        .or()
        .expression('(?:RGP|RCQ|RCV|RBR|RUS00|RPI),?')
        .number('(dd)?')
        .number('(dd)(dd)(dd)')
        .number('(dd)(dd)(dd)')
        .groupEnd()
        .groupBegin()
        .number('([-+]dd)(d{5})')
        .number('([-+]ddd)(d{5})')
        .or()
        .number('([-+])(dd)(dd.dddd)')
        .number('([-+])(ddd)(dd.dddd)')
        .groupEnd()
        .number('(ddd)')
        .number('(ddd)')
        .groupBegin()
        .number('([023])')
        .number('xx')
        .number('(xx)')
        .groupBegin()
        .number(',d+')
        .number(',(d+)')
        .number(',(d{4})(d{4})')
        .number(',(d+)')
        .groupBegin()
        .number(',([-+]?d+.?d*)')
        .number(',([-+]?d+.?d*)')
        .groupEnd('?')
        .number(',(xx)')
        .or()
        .number('(dd)')
        .number('(dd)')
        .groupEnd()
        .or()
        .groupBegin()
        .number('(xx)')
        .number('xx')
        .number('(ddd)')
        .number('(x{8})')
        .number('[01]')
        .groupBegin()
        .number('([023])')
        .number('(dd)')
        .number('dd')
        .number('xxxx')
        .number('[01]')
        .number('[0-5]')
        .number('(dd)')
        .groupBegin()
        .number('([-+]dddd)')
        .number('xx')
        .number('([-+]dddd)')
        .number('xx')
        .groupEnd('?')
        .groupEnd('?')
        .groupEnd('?')
        .groupEnd()
        .any()
        .compile()

def gpsTime = { long week, long day, long seconds ->
    new DateBuilder().setDate(1980, 1, 6)
            .addMillis(((week * 7 + day) * 24 * 60 * 60 + seconds) * 1000)
            .getDate()
}

def todayTime = { long seconds ->
    Date date = new DateBuilder(new Date()).setTime(0, 0, 0, 0).addMillis(seconds * 1000).getDate()
    DateUtil.correctDay(date)
}

def alarm = { int value ->
    switch (value) {
        case 0x01: return ALARM_SOS
        case 0x02: return ALARM_POWER_CUT
        default: return null
    }
}

def alarm2 = { int value ->
    switch (value) {
        case 22: return ALARM_ACCELERATION
        case 23: return ALARM_BRAKING
        case 24: return ALARM_ACCIDENT
        case 26:
        case 28: return ALARM_CORNERING
        default: return null
    }
}

def setIfPresent = { pos, String key, value ->
    if (value != null) pos.set(key, value)
}

def decodeAttributes = { String sentence, pos, ctx ->
    String[] attributes = null
    int begin = sentence.indexOf(';')
    if (begin != -1) {
        int end = sentence.indexOf('<', begin)
        if (end == -1) end = sentence.length()
        attributes = sentence.substring(begin).substring(0, end - begin).split(';')
    }

    String uniqueId = null
    def session = null
    String messageIndex = null
    boolean indexFirst = true

    if (attributes != null) {
        for (String attribute : attributes) {
            int index = attribute.indexOf('=')
            if (index != -1) {
                String key = attribute.substring(0, index).toLowerCase(Locale.ROOT)
                String value = attribute.substring(index + 1)
                switch (key) {
                    case 'id':
                        uniqueId = value
                        session = ctx.session(value)
                        if (session) pos.deviceId = session.deviceId
                        if (messageIndex == null) indexFirst = false
                        break
                    case 'io':
                        pos.set(Position.KEY_IGNITION, bit(value.charAt(0) - (char) '0', 0))
                        pos.set(Position.KEY_CHARGE, bit(value.charAt(0) - (char) '0', 1))
                        pos.set(Position.KEY_OUTPUT, value.charAt(1) - (char) '0')
                        pos.set(Position.KEY_INPUT, value.charAt(2) - (char) '0')
                        break
                    case 'ix':
                        pos.set(Position.PREFIX_IO + 1, value)
                        break
                    case 'ad':
                        pos.set(Position.PREFIX_ADC + 1, Integer.parseInt(value))
                        break
                    case 'sv':
                        pos.set(Position.KEY_SATELLITES, Integer.parseInt(value))
                        break
                    case 'bl':
                        pos.set(Position.KEY_BATTERY, Integer.parseInt(value) / 1000.0)
                        break
                    case 'vo':
                        pos.set(Position.KEY_ODOMETER, Long.parseLong(value))
                        break
                    default:
                        pos.set(key, value)
                        break
                }
            } else if (attribute.startsWith('#')) {
                messageIndex = attribute
            }
        }
    }

    if (session) {
        if (messageIndex != null) {
            String response
            if (messageIndex.startsWith('#IP')) {
                response = ">SAK;ID=${uniqueId};${messageIndex}<"
            } else {
                response = indexFirst ? ">ACK;${messageIndex};ID=${uniqueId};" : ">ACK;ID=${uniqueId};${messageIndex};"
                int checksum = Checksum.xor(response + "*")
                response += String.format("*%02X", checksum) + "<"
            }
            ctx.ack(response)
        } else {
            ctx.ack(uniqueId)
        }
        return pos
    }

    null
}

protocol("taip") {
    port 5031
    transport 'tcp', 'udp'

    variant("main") {
        frame readUntilKeep('<')
        matches { msg -> msg instanceof String && msg.trim().contains('>R') }

        decode { raw, ctx ->
            String sentence = raw.trim()
            int begin = sentence.indexOf('>')
            if (begin != -1) sentence = sentence.substring(begin + 1)

            def parser = new Parser(PATTERN, sentence)
            if (!parser.matches()) return null

            def pos = ctx.newPosition()
            Boolean valid = null
            Integer event = null

            if (parser.hasNext(3)) {
                event = parser.nextInt()
                pos.time = gpsTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0))
            } else if (parser.hasNext()) {
                pos.time = todayTime(parser.nextInt(0))
            }

            if (parser.hasNext()) event = parser.nextInt()
            if (parser.hasNext(6)) pos.time = parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS)

            if (parser.hasNext(4)) {
                pos.latitude = parser.nextCoordinate(Parser.CoordinateFormat.DEG_DEG)
                pos.longitude = parser.nextCoordinate(Parser.CoordinateFormat.DEG_DEG)
            }
            if (parser.hasNext(6)) {
                pos.latitude = parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN)
                pos.longitude = parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN)
            }

            pos.speed = UnitsConverter.knotsFromMph(parser.nextDouble(0))
            pos.course = parser.nextDouble(0)

            if (parser.hasNext(2)) {
                valid = parser.nextInt() > 0
                int input = parser.nextHexInt()
                pos.set(Position.KEY_IGNITION, bit(input, 7))
                pos.set(Position.KEY_INPUT, input)
            }

            if (parser.hasNext(7)) {
                pos.set(Position.KEY_ODOMETER, parser.nextInt())
                pos.set(Position.KEY_POWER, parser.nextInt() / 100.0)
                pos.set(Position.KEY_BATTERY, parser.nextInt() / 100.0)
                pos.set(Position.KEY_RPM, parser.nextInt())
                setIfPresent(pos, Position.PREFIX_TEMP + 1, parser.nextDouble())
                setIfPresent(pos, Position.PREFIX_TEMP + 2, parser.nextDouble())
                event = parser.nextHexInt()
            }

            if (parser.hasNext(2)) {
                event = parser.nextInt()
                pos.set(Position.KEY_HDOP, parser.nextInt())
            }

            if (parser.hasNext(3)) {
                pos.set(Position.KEY_INPUT, parser.nextHexInt(0))
                pos.set(Position.KEY_BATTERY, parser.nextInt(0))
                pos.set(Position.KEY_ODOMETER, parser.nextLong(16, 0))
            }

            if (parser.hasNext(3)) {
                valid = parser.nextInt() > 0
                pos.set(Position.KEY_PDOP, parser.nextInt())
                pos.set(Position.KEY_RSSI, parser.nextInt())
            }
            if (parser.hasNext(2)) {
                pos.set(Position.PREFIX_TEMP + 1, parser.nextInt() / 100.0)
                pos.set(Position.PREFIX_TEMP + 2, parser.nextInt() / 100.0)
            }

            pos.valid = valid == null || valid

            if (event != null) {
                pos.set(Position.KEY_EVENT, event)
                if (sentence.length() > 5 && sentence.charAt(5) == ',') {
                    pos.addAlarm(alarm2(event))
                } else {
                    pos.addAlarm(alarm(event))
                }
            }

            decodeAttributes(sentence, pos, ctx)
        }
    }
}
