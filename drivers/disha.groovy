// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Disha GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   $A#A#<imei>#<AV>#<hhmmss>#<ddmmyy>#<lat>#<NS>#<lon>#<EW>#<spd>#<crs>#<sats>#<hdop>#<rssi>#<pwr>#<bat>#<adc1>#<adc2>#<dayDist>#<odo>#<inputs>*
 *
 * Power mode 2 = charging. Odometer in km (driver stores metres).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$A#A#(\d+)#([AVMX])#(\d{2})(\d{2})(\d{2})#(\d{2})(\d{2})(\d{2})#(\d{2})(\d{2}\.\d+)#([NS])#(\d{3})(\d{2}\.\d+)#([EW])#([\d.]+)#([\d.]+)#(\d+)#([\d.]+)#(\d+)#([012])#(\d+)#(\d+)#(\d+)#[\d.]+#([\d.]+)#([01]+)\*/)

protocol("disha") {

    port 5097

    variant("main") {

        frame '$' as char, readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2) == 'A'

            pos.time = new DateBuilder()
                    .setTime(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setDateReverse(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60.0
            pos.longitude = m.group(14) == 'W' ? -lon : lon

            pos.speed  = m.group(15).toDouble()
            pos.course = m.group(16).toDouble()

            pos.set(Position.KEY_SATELLITES,  m.group(17).toInteger())
            pos.set(Position.KEY_HDOP,        m.group(18).toDouble())
            pos.set(Position.KEY_RSSI,        m.group(19).toDouble())
            pos.set(Position.KEY_CHARGE,      m.group(20).toInteger() == 2)
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(21).toInteger())
            pos.set(Position.PREFIX_ADC + 1,  m.group(22).toInteger())
            pos.set(Position.PREFIX_ADC + 2,  m.group(23).toInteger())
            pos.set(Position.KEY_ODOMETER,    m.group(24).toDouble() * 1000)
            pos.set(Position.KEY_INPUT,       m.group(25))

            return pos
        }
    }
}
