// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * IVT401 GPS tracker driver.
 *
 * ';'-terminated text frames starting with '('. Message format:
 *   (TL[ABLN],<imei>,<ddmmyy>,<hhmmss>,<lat>,<lon>,<spd_kph>,<crs>,<alt>,<sats>,<gps>,<rssi>,<inputs>,<outputs>,<adc>,<pwr>,<bat>,<pcbtemp>,<temp>,<motion>,<accel>,<tilt>,<trip>,<odo>[,<alarms>...]
 *
 * Speed in km/h. Multi-sensor temperature 'M±v1±v2...' or single value.
 * Optional extended alarm block: overspeed, harsh_driving(0-3), lowbat, power_cut, tow, driver_id.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\(TL[ABLN],(\d+),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([-+][\d.]+),([-+][\d.]+),(\d+),(\d+),(-?[\d.]+),\d+,(\d),(\d+),(\d+),(\d+),([\d.]+),([\d.]+),([\d.]+),(-?[\d.]+),([^,]+),(\d+),([\d.]+),(-?\d+),(\d+),(\d+)(?:,([01]),(?:[01],){4}([0-3]),(?:[01],){2}([01]),([01]),(?:[01],){1}([01]),[01],[128],([^,]+)?,\d+)?/)

protocol("ivt401") {

    port 5153

    variant("main") {

        frame '(' as char, readUntil(";")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(10).toInteger())
            pos.course    = m.group(11).toInteger()
            pos.altitude  = m.group(12).toDouble()
            pos.valid     = m.group(13).toInteger() > 0

            pos.set(Position.KEY_RSSI, m.group(14).toInteger())

            String input = m.group(15)
            input.eachWithIndex { c, i ->
                int v = Character.getNumericValue(c as char)
                if (v < 2) pos.set(Position.PREFIX_IN + (i + 1), v > 0)
            }

            String output = m.group(16)
            output.eachWithIndex { c, i ->
                pos.set(Position.PREFIX_OUT + (i + 1), Character.getNumericValue(c as char) > 0)
            }

            pos.set(Position.PREFIX_ADC + 1,  m.group(17).toDouble())
            pos.set(Position.KEY_POWER,        m.group(18).toDouble())
            pos.set(Position.KEY_BATTERY,      m.group(19).toDouble())
            pos.set(Position.KEY_DEVICE_TEMP,  m.group(20).toDouble())

            String temp = m.group(21)
            if (temp.startsWith('M')) {
                def vals = temp.substring(1).split(/(?=[+-])/)
                vals.eachWithIndex { v, i -> pos.set(Position.PREFIX_TEMP + (i + 1), v.toDouble()) }
            } else {
                pos.set(Position.PREFIX_TEMP + 1, temp.toDouble())
            }

            pos.set(Position.KEY_MOTION,   m.group(22).toInteger() > 0)
            pos.set(Position.KEY_ACCELERATION, m.group(23).toDouble())
            pos.set(Position.KEY_ODOMETER, m.group(27).toLong())

            if (m.group(28)) {
                if (m.group(28).toInteger() == 1) pos.addAlarm(Position.ALARM_OVERSPEED)
                switch (m.group(29).toInteger()) {
                    case 1: pos.addAlarm(Position.ALARM_ACCELERATION); break
                    case 2: pos.addAlarm(Position.ALARM_BRAKING); break
                    case 3: pos.addAlarm(Position.ALARM_CORNERING); break
                }
                if (m.group(30).toInteger() == 1) pos.addAlarm(Position.ALARM_LOW_BATTERY)
                if (m.group(31).toInteger() == 1) pos.addAlarm(Position.ALARM_POWER_CUT)
                if (m.group(32).toInteger() == 1) pos.addAlarm(Position.ALARM_TOW)
                if (m.group(33)) pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(33))
            }

            return pos
        }
    }
}
