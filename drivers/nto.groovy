// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Nto GPS tracker driver.
 *
 * '&'-terminated text frames. Single message format:
 *   ^NB,<imei>,<type>,<ddmmyy>,<hhmmss>,<AVM>,<NS>,<ddmm.d+>,<EW>,<dddmm.d+>,<speed>,<course>,<statushex>,...&
 *
 * Latitude/longitude in HEM_DEG_MIN format (hemisphere first).
 * Status is a 48-bit hex field; several bits map to alarms.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\^NB,(\d+),(\w{3}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([AVM]),([NS]),(\d{2})(\d{2}\.\d+),([EW]),(\d{3})(\d{2}\.\d+),([\d.]+),(\d+),([0-9a-fA-F]+),/)

protocol("nto") {

    port 5250

    variant("main") {

        frame '^' as char, readUntil("&")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_TYPE, m.group(2))

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.valid = m.group(9) == 'A'

            double lat = m.group(11).toInteger() + m.group(12).toDouble() / 60.0
            pos.latitude = m.group(10) == 'S' ? -lat : lat

            double lon = m.group(14).toInteger() + m.group(15).toDouble() / 60.0
            pos.longitude = m.group(13) == 'W' ? -lon : lon

            pos.speed  = m.group(16).toDouble()
            pos.course = m.group(17).toInteger()

            long status = Long.parseLong(m.group(18), 16)
            pos.set(Position.KEY_STATUS, status)

            if ((status & (1L << 1)) != 0)  pos.alarm = ALARM_JAMMING
            if ((status & (1L << 25)) != 0) pos.alarm = ALARM_POWER_CUT
            if ((status & (1L << 26)) != 0) pos.alarm = ALARM_OVERSPEED
            if ((status & (1L << 27)) != 0) pos.alarm = ALARM_VIBRATION
            if ((status & (1L << 28)) != 0) pos.alarm = ALARM_GEOFENCE_ENTER
            if ((status & (1L << 29)) != 0) pos.alarm = ALARM_GEOFENCE_EXIT
            if ((status & (1L << 32)) != 0) pos.alarm = ALARM_LOW_BATTERY
            if ((status & (1L << 36)) != 0) pos.alarm = ALARM_DOOR

            return pos
        }
    }
}
