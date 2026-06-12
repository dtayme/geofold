// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Oko GPS tracker driver.
 *
 * '}'-terminated text frames starting with '{'. Message format:
 *   {[<imei>,]<hhmmss>[.ss],<AV>,<latDeg><latMin>,<NS>,<lonDeg><lonMin>,<EW>,<spd>,<crs>,<ddmmyy>,<sats>,<adc|hex>,<event_hex>,<pwr|hex>,<memFlag>[,<io_hex>]
 *
 * IMEI is optional; if absent use existing session. ADC and power can be decimal or 2-hex-digit.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\{(?:(\d{15}),)?(\d{2})(\d{2})(\d{2})(?:\.\d+)?,([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),([\d.]+)?,?([\d.]+)?,?(\d{2})(\d{2})(\d{2}),(\d+),([\d.]+|[0-9a-fA-F]{2}),([0-9a-fA-F]{2}),([\d.]+|[0-9a-fA-F]{2}),\d(?:,([0-9a-fA-F]+))?/)

protocol("oko") {

    port 5152

    variant("main") {

        frame '{' as char, readUntil("}")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = m.group(1) ? ctx.session(m.group(1)) : ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            pos.valid = m.group(5) == 'A'

            double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60.0
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            if (m.group(12)) pos.speed  = m.group(12).toDouble()
            if (m.group(13)) pos.course = m.group(13).toDouble()

            db.setDateReverse(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
            pos.time = db.getDate()

            pos.set(Position.KEY_SATELLITES, m.group(17).toInteger())

            Closure decodeVoltage = { String v ->
                v.contains('.') ? v.toDouble() : Integer.parseInt(v, 16) / 10.0
            }
            pos.set(Position.PREFIX_ADC + 1, decodeVoltage(m.group(18)))
            pos.set(Position.KEY_EVENT,      m.group(19))
            pos.set(Position.KEY_POWER,      decodeVoltage(m.group(20)))
            if (m.group(21)) pos.set(Position.KEY_INPUT, Integer.parseInt(m.group(21), 16))

            return pos
        }
    }
}
