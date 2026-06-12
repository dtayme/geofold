// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Kenji GPS tracker driver.
 *
 * Newline-terminated text frames starting with '>'. Message format:
 *   >C<id>,M<alarm6hex>,O<out4hex>,I<in4hex>,D<hhmmss>,<AV>,<NS><latDeg><latMin>,<EW><lonDeg><lonMin>,T<spd>,H<crs>,Y<ddmmyy>,G<sats>
 *
 * Alarm bits (M field): 2=SOS, 4=LOW_BATTERY, 6=MOVEMENT, 1/10/11=VIBRATION.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^>C(\d{6}),M([0-9a-fA-F]{6}),O([0-9a-fA-F]{4}),I([0-9a-fA-F]{4}),D(\d{2})(\d{2})(\d{2}),([AV]),([NS])(\d{2})(\d{2}\.\d+),([EW])(\d{3})(\d{2}\.\d+),T([\d.]+),H([\d.]+),Y(\d{2})(\d{2})(\d{2}),G(\d+)/)

protocol("kenji") {

    port 5102

    variant("main") {

        frame '>' as char, readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            int alarm = Integer.parseInt(m.group(2), 16)
            if      (checkBit(alarm, 2))  pos.addAlarm(Position.ALARM_SOS)
            else if (checkBit(alarm, 4))  pos.addAlarm(Position.ALARM_LOW_BATTERY)
            else if (checkBit(alarm, 6))  pos.addAlarm(Position.ALARM_MOVEMENT)
            else if (checkBit(alarm, 1) || checkBit(alarm, 10) || checkBit(alarm, 11))
                                          pos.addAlarm(Position.ALARM_VIBRATION)

            pos.set(Position.KEY_OUTPUT, Integer.parseInt(m.group(3), 16))
            pos.set(Position.KEY_INPUT,  Integer.parseInt(m.group(4), 16))

            def db = new DateBuilder()
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())

            pos.valid = m.group(8) == 'A'

            double lat = m.group(10).toInteger() + m.group(11).toDouble() / 60.0
            pos.latitude = m.group(9) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + m.group(14).toDouble() / 60.0
            pos.longitude = m.group(12) == 'W' ? -lon : lon

            pos.speed  = m.group(15).toDouble()
            pos.course = m.group(16).toDouble()

            db.setDateReverse(m.group(17).toInteger(), m.group(18).toInteger(), m.group(19).toInteger())
            pos.time = db.getDate()

            pos.set(Position.KEY_SATELLITES, m.group(20).toInteger())

            return pos
        }
    }
}
