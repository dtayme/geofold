// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Mobilogix MT2000 text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/mobilogix/
 *
 * Supports bracketed report frames, status ACKs, alarms, last-location fallback,
 * and documented command encoders.
 */

import org.traccar.helper.Parser
import org.traccar.helper.PatternBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.text.SimpleDateFormat

def PATTERN = new PatternBuilder()
        .text("[")
        .number("(dddd)-(dd)-(dd) ")
        .number("(dd):(dd):(dd),")
        .number("Td+,")
        .number("(d),")
        .expression("[^,]+,")
        .expression("([^,]+),")
        .number("(xx),")
        .number("(d+.d+)")
        .groupBegin()
        .text(",")
        .number("(d)")
        .number("(d)")
        .number("(d),")
        .number("(-?d+.d+),")
        .number("(-?d+.d+),")
        .number("(d+.?d*),")
        .number("(d+.?d*)")
        .groupEnd("?")
        .any()
        .compile()

def check = { int value, int bit -> (value & (1 << bit)) != 0 }
def alarm = { String type ->
    switch (type) {
        case "T8": return ALARM_LOW_BATTERY
        case "T9": return ALARM_VIBRATION
        case "T10": return ALARM_POWER_CUT
        case "T11": return ALARM_LOW_POWER
        case "T12": return ALARM_GEOFENCE_EXIT
        case "T13": return ALARM_OVERSPEED
        case "T15": return ALARM_TOW
        default: return null
    }
}

def commandFrame = { String param ->
    "[${new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new Date())},${param}]"
}

protocol("mobilogix") {
    port 5216
    commands TYPE_CUSTOM, TYPE_ENGINE_RESUME, TYPE_ENGINE_STOP, TYPE_POSITION_SINGLE, TYPE_REBOOT_DEVICE

    variant("main") {
        frame readUntil(']')
        matches { msg -> msg instanceof String && msg.trim().startsWith('[') }

        decode { raw, ctx ->
            String sentence = raw.trim()
            int typeEnd = sentence.indexOf(',', 21)
            if (typeEnd < 0) return null
            String type = sentence.substring(21, typeEnd)

            if (!type.equals("T6")) {
                String time = sentence.substring(1, 20)
                ctx.ack(type.equals("T1") ? "[${time},S1,1]" : "[${time},S${type.substring(1)}]")
            }

            def parser = new Parser(PATTERN, sentence)
            if (!parser.matches()) return null

            def pos = ctx.newPosition()
            pos.deviceTime = parser.nextDateTime()
            if (parser.nextInt() == 0) pos.set(Position.KEY_ARCHIVE, true)

            def session = ctx.session(parser.next())
            if (!session) return null
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_TYPE, type)
            pos.addAlarm(alarm(type))

            int status = parser.nextHexInt()
            pos.set(Position.KEY_BLOCKED, !check(status, 0))
            if (check(status, 1)) pos.set(Position.KEY_CHARGE, true)
            pos.set(Position.KEY_IGNITION, check(status, 2))
            pos.set(Position.KEY_MOTION, check(status, 3))
            pos.set(Position.KEY_STATUS, status)
            pos.set(Position.KEY_BATTERY, parser.nextDouble())

            if (parser.hasNext(7)) {
                pos.set(Position.KEY_SATELLITES, parser.nextInt())
                pos.set(Position.KEY_RSSI, 6 * parser.nextInt() - 111)
                pos.valid = parser.nextInt() > 0
                pos.fixTime = pos.deviceTime
                pos.latitude = parser.nextDouble()
                pos.longitude = parser.nextDouble()
                pos.speed = UnitsConverter.knotsFromKph(parser.nextDouble())
                pos.course = parser.nextDouble()
            } else {
                ctx.lastLocation(pos, pos.deviceTime)
            }

            pos
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_CUSTOM: return commandFrame(ctx.data())
                case TYPE_ENGINE_RESUME: return commandFrame("S6,RELAY=0")
                case TYPE_ENGINE_STOP: return commandFrame("S6,RELAY=1")
                case TYPE_POSITION_SINGLE: return commandFrame("S4,1,1")
                case TYPE_REBOOT_DEVICE: return commandFrame("S7")
                default: return null
            }
        }
    }
}
