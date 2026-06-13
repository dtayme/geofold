// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * IntelliTrac text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/intellitrac/
 *
 * Supports line-delimited tracking records, optional J1939 fields, and sync
 * packet echo.
 */

import org.traccar.helper.Parser
import org.traccar.helper.PatternBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

def PATTERN = new PatternBuilder()
        .expression(".+,").optional()
        .number("(d+),")
        .number("(dddd)(dd)(dd)")
        .number("(dd)(dd)(dd),")
        .number("(-?d+.d+),")
        .number("(-?d+.d+),")
        .number("(d+.?d*),")
        .number("(d+.?d*),")
        .number("(-?d+.?d*),")
        .number("(d+),")
        .number("(d+),")
        .number("(d+),")
        .number("(d+),?")
        .number("(d+.d+)?,?")
        .number("(d+.d+)?,?")
        .groupBegin()
        .number("d{14},d+,")
        .number("(d+),")
        .number("(d+),")
        .number("(-?d+),")
        .number("(d+),")
        .number("(d+),")
        .number("(-?d+),")
        .number("(d+),")
        .number("(d+),")
        .number("(d+),")
        .number("(d+)")
        .groupEnd("?")
        .any()
        .compile()

def alarm = { int value ->
    switch (value) {
        case 164: return ALARM_GEOFENCE_ENTER
        case 165: return ALARM_GEOFENCE_EXIT
        case 168:
        case 169: return ALARM_LOW_POWER
        case 170: return ALARM_POWER_OFF
        case 176: return ALARM_POWER_RESTORED
        case 180: return ALARM_FALL_DOWN
        case 225: return ALARM_JAMMING
        case 995: return ALARM_SOS
        default: return null
    }
}

def setIfPresent = { pos, String key, value ->
    if (value != null) {
        pos.set(key, value)
    }
}

protocol("intellitrac") {
    port 5037

    variant("sync") {
        frame 0xFA as byte, readFixed(8)
        decode { buf, ctx ->
            ctx.ack(buf.readBytes(8))
            null
        }
    }

    variant("main") {
        frame readLine()
        matches { msg -> msg instanceof String && (msg =~ /^(?:[$]RP:[^,]+,|[^,\r\n]+,)?\d+,\d{14},/).find() }
        decode { msg, ctx ->
            def parser = new Parser(PATTERN, msg)
            if (!parser.matches()) return null

            def session = ctx.session(parser.next())
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.time = parser.nextDateTime()
            pos.valid = true
            pos.longitude = parser.nextDouble()
            pos.latitude = parser.nextDouble()
            pos.speed = UnitsConverter.knotsFromKph(parser.nextDouble())
            pos.course = parser.nextDouble()
            pos.altitude = parser.nextDouble()
            pos.set(Position.KEY_SATELLITES, parser.nextInt())

            int event = parser.nextInt()
            pos.addAlarm(alarm(event))
            pos.set(Position.KEY_EVENT, event)
            pos.set(Position.KEY_INPUT, parser.nextInt())
            pos.set(Position.KEY_OUTPUT, parser.nextInt())
            setIfPresent(pos, Position.PREFIX_ADC + 1, parser.nextDouble())
            setIfPresent(pos, Position.PREFIX_ADC + 2, parser.nextDouble())

            if (parser.hasNext(10)) {
                pos.set(Position.KEY_OBD_SPEED, parser.nextInt())
                pos.set(Position.KEY_RPM, parser.nextInt())
                pos.set("coolant", parser.nextInt())
                pos.set(Position.KEY_FUEL, parser.nextInt())
                pos.set(Position.KEY_FUEL_CONSUMPTION, parser.nextInt())
                pos.set(Position.PREFIX_TEMP + 1, parser.nextInt())
                pos.set("chargerPressure", parser.nextInt())
                pos.set("tpl", parser.nextInt())
                pos.set(Position.KEY_AXLE_WEIGHT, parser.nextInt())
                pos.set(Position.KEY_OBD_ODOMETER, parser.nextInt())
            }

            pos
        }
    }
}
