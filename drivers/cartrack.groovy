// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * CarTrack GPS tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/cartrack/ITP AVL Command List GPRS.pdf
 *
 * Device-to-server frames:
 *   $$<id>&A<cmd>&B<GPRMC-like data>&C<io>&D<odometer>&E<alarm>[&Y<adc>]##
 *
 * The document uses ASCII hex symbols 0-9 and :-? for packed nibbles in
 * odometer and other numeric fields. Both realtime (##) and stored (####)
 * terminators are accepted by the ## frame delimiter; stored frames are marked
 * when the payload still ends in ## after delimiter stripping.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def POSITION = Pattern.compile(
    /^\$\$(\d+)\?*.*?&A(\d{4})&B(\d{2})(\d{2})(\d{2})\.(\d{3}),([AV]),(\d{2})(\d{2}\.\d{4}),([NS]),(\d{3})(\d{2}\.\d{4}),([EW]),(\d+\.\d*)?,(\d+\.\d*)?,(\d{2})(\d{2})(\d{2}).*?&C([^&]*)&D([^&]*)&E([^&]*)(?:&Y([^&]*))?/)

def LINK = Pattern.compile(/^\$\$(\d+)\?*.*?&A(5000|0000|1111)(?:##)?$/)

def asciiHex = { String value ->
    if (!value) return null
    Long.parseLong(value
        .replace(':', 'A')
        .replace(';', 'B')
        .replace('<', 'C')
        .replace('=', 'D')
        .replace('>', 'E')
        .replace('?', 'F'), 16)
}

def coordinate = { String deg, String min, String hemisphere ->
    double value = deg.toInteger() + min.toDouble() / 60.0
    (hemisphere == 'S' || hemisphere == 'W') ? -value : value
}

def deviceId = { String id ->
    id.padRight(14, '?').take(14)
}

def commandFrame = { String id, String code, String data = '' ->
    "@@${deviceId(id)}&A${code}${data ?: ''}##"
}

protocol("cartrack") {

    port 5061

    commands TYPE_CUSTOM,
             TYPE_POSITION_SINGLE,
             TYPE_POSITION_PERIODIC,
             TYPE_POSITION_STOP,
             TYPE_SET_SPEED_LIMIT,
             TYPE_SET_ODOMETER,
             TYPE_OUTPUT_CONTROL,
             TYPE_MODE_POWER_SAVING,
             TYPE_GET_DEVICE_STATUS

    variant("main") {

        frame '$' as char, readUntil('##')

        matches { msg -> msg.startsWith('$$') }

        decode { msg, ctx ->
            boolean archived = msg.endsWith('##')
            if (archived) {
                msg = msg.substring(0, msg.length() - 2)
            }

            def lm = LINK.matcher(msg)
            if (lm.matches()) {
                def session = ctx.session(lm.group(1))
                if (!session) return null
                if (lm.group(2) == '5000') {
                    ctx.ack(commandFrame(lm.group(1), '4000', '01'))
                }
                return null
            }

            def m = POSITION.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.set(Position.KEY_ARCHIVE, archived)
            pos.set(Position.KEY_COMMAND, m.group(2))

            pos.time = new DateBuilder()
                    .setTime(m.group(3).toInteger(), m.group(4).toInteger(),
                            m.group(5).toInteger(), m.group(6).toInteger())
                    .setDateReverse(m.group(16).toInteger(), m.group(17).toInteger(), m.group(18).toInteger())
                    .getDate()

            pos.valid = m.group(7) == 'A'
            pos.latitude = coordinate(m.group(8), m.group(9), m.group(10))
            pos.longitude = coordinate(m.group(11), m.group(12), m.group(13))
            pos.speed = m.group(14) ? m.group(14).toDouble() : 0
            pos.course = m.group(15) ? m.group(15).toDouble() : 0

            pos.set(Position.PREFIX_IO + 1, m.group(19))
            def odometer = asciiHex(m.group(20))
            if (odometer != null) {
                pos.set(Position.KEY_ODOMETER, odometer)
            }

            def alarm = m.group(21)
            pos.set(Position.KEY_STATUS, alarm)
            if (alarm?.length() >= 1 && alarm.charAt(0) == '1' as char) {
                pos.addAlarm(ALARM_POWER_CUT)
            }
            if (alarm?.length() >= 8) {
                pos.set(Position.KEY_MOTION, alarm.charAt(7) == '0' as char)
            }

            def adc = m.group(22)
            if (adc) {
                pos.set(Position.PREFIX_ADC + 1, adc.length() >= 4 ? asciiHex(adc.substring(0, 4)) : adc)
                if (adc.length() >= 8) {
                    pos.set(Position.PREFIX_ADC + 2, asciiHex(adc.substring(4, 8)))
                }
            }

            return pos
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return ctx.data()
                case TYPE_POSITION_SINGLE:
                    return commandFrame(ctx.deviceId(), '4101')
                case TYPE_POSITION_PERIODIC:
                    return commandFrame(ctx.deviceId(), '4102', String.format('%04d', ctx.freq()))
                case TYPE_POSITION_STOP:
                    return commandFrame(ctx.deviceId(), '4102', '0000')
                case TYPE_SET_SPEED_LIMIT:
                    return commandFrame(ctx.deviceId(), '4105', String.format('%03d', ctx.clamp(ctx.data() ? ctx.data().toLong() : 0, 0, 255)))
                case TYPE_SET_ODOMETER:
                    long meters = ctx.data() ? ctx.data().toLong() : 0
                    return commandFrame(ctx.deviceId(), '4307', String.format('%08X', meters).replace('A', ':')
                            .replace('B', ';').replace('C', '<').replace('D', '=').replace('E', '>').replace('F', '?'))
                case TYPE_OUTPUT_CONTROL:
                    return commandFrame(ctx.deviceId(), '4115', ctx.data())
                case TYPE_MODE_POWER_SAVING:
                    return commandFrame(ctx.deviceId(), '4113', ctx.data())
                case TYPE_GET_DEVICE_STATUS:
                    return commandFrame(ctx.deviceId(), '9014')
            }
            return null
        }
    }
}
