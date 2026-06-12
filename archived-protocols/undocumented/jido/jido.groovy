// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Jido GPS tracker driver.
 *
 * '#'-terminated text frames starting with '*'. Message format:
 *   *<imei>,<cmd>[,<AV>],<ddmmyy>,<hhmmss>,<latDeg><latMin>,<NS>,<lonDeg><lonMin>,<EW>[,<spd>,<odo>,<crs>,<alt>,<sats>,,,[<charging>,<batPct>,<mode>,<locked>,...]]<xx>#
 *
 * Alarm types: 3=LOW_BATTERY, 4=TAMPERING.
 * Speed in km/h when extended data present.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\*(\d+),(\d+),(?:([AV]),)?(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),(?:(\d+),(\d+),(\d+),(-?\d+),(\d+),\d+,\d+,([01]),(\d+),([YKN]),([01]),[^,]*,[^,]*,[^,]*|[^,]*),([0-9a-fA-F]{2})#/)

protocol("jido") {

    port 5237

    variant("main") {

        frame '*' as char, readUntil("#")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            int cmd = m.group(2).toInteger()
            switch (cmd) {
                case 3: pos.addAlarm(Position.ALARM_LOW_BATTERY); break
                case 4: pos.addAlarm(Position.ALARM_TAMPERING); break
            }

            pos.valid = m.group(3) ? m.group(3) == 'A' : true

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDateReverse(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                    .setTime(m.group(7).toInteger(), m.group(8).toInteger(), m.group(9).toInteger())
                    .getDate()

            double lat = m.group(10).toInteger() + m.group(11).toDouble() / 60.0
            pos.latitude = m.group(12) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + m.group(14).toDouble() / 60.0
            pos.longitude = m.group(15) == 'W' ? -lon : lon

            if (m.group(16)) {
                pos.speed    = UnitsConverter.knotsFromKph(m.group(16).toInteger())
                pos.set(Position.KEY_ODOMETER, m.group(17).toInteger())
                pos.course   = m.group(18).toInteger()
                pos.altitude = m.group(19).toInteger()
                pos.set(Position.KEY_SATELLITES,    m.group(20).toInteger())
                pos.set(Position.KEY_CHARGE,        m.group(21).toInteger() > 0)
                pos.set(Position.KEY_BATTERY_LEVEL, m.group(22).toInteger())
                pos.set("mode",                     m.group(23))
                pos.set(Position.KEY_BLOCKED,       m.group(24).toInteger() > 0)
            }

            return pos
        }
    }
}
