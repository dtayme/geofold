// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Cguard GPS tracker driver.
 *
 * Newline-terminated text frames. Two message types:
 *  - ID:<imei> or IDRO:<imei> → register session, return null
 *  - NV:<yymmdd> <hhmmss>:<lon>:<lat>:<spd>:<acc>:<crs>[:<alt>] → position
 *  - BC:<yymmdd> <hhmmss>:<key>:<val>:... → status
 *
 * Note: longitude comes before latitude in NV messages.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Locale
import java.util.regex.Pattern

def PATTERN_NV = Pattern.compile(
    /^NV:(\d{2})(\d{2})(\d{2}) (\d{2})(\d{2})(\d{2}):(-?[\d.]+):(-?[\d.]+):([\d.]+):(?:NAN|([\d.]+)):(?:NAN|([\d.]+))(?::(?:NAN|([\d.]+)))?/)

def PATTERN_BC = Pattern.compile(
    /^BC:(\d{2})(\d{2})(\d{2}) (\d{2})(\d{2})(\d{2}):(.+)/)

protocol("cguard") {

    port 5123

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            if (msg.startsWith('ID:') || msg.startsWith('IDRO:')) {
                ctx.session(msg.substring(msg.indexOf(':') + 1))
                return null
            }

            def session = ctx.session()
            if (!session) return null

            if (msg.startsWith('NV:')) {
                def m = PATTERN_NV.matcher(msg)
                if (!m.find()) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                pos.time = new org.traccar.helper.DateBuilder()
                        .setDate(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
                        .setTime(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                        .getDate()

                pos.valid = true
                pos.longitude = m.group(7).toDouble()
                pos.latitude  = m.group(8).toDouble()
                pos.speed     = UnitsConverter.knotsFromKph(m.group(9).toDouble())
                if (m.group(10)) pos.accuracy = m.group(10).toDouble()
                if (m.group(11)) pos.course    = m.group(11).toDouble()
                if (m.group(12)) pos.altitude  = m.group(12).toDouble()

                return pos

            } else if (msg.startsWith('BC:')) {
                def m = PATTERN_BC.matcher(msg)
                if (!m.find()) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                pos.time = new org.traccar.helper.DateBuilder()
                        .setDate(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
                        .setTime(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                        .getDate()
                pos.valid = false

                String[] parts = m.group(7).split(':')
                for (int i = 0; i < parts.length / 2; i++) {
                    String key   = parts[i * 2]
                    String value = parts[i * 2 + 1]
                    switch (key) {
                        case 'CSQ1': pos.set(Position.KEY_RSSI, value.toInteger()); break
                        case 'NSQ1': pos.set(Position.KEY_SATELLITES, value.toInteger()); break
                        case 'BAT1':
                            if (value.contains('.')) pos.set(Position.KEY_BATTERY, value.toDouble())
                            else pos.set(Position.KEY_BATTERY_LEVEL, value.toInteger())
                            break
                        case 'PWR1': pos.set(Position.KEY_POWER, value.toDouble()); break
                        default: pos.set(key.toLowerCase(Locale.ROOT), value); break
                    }
                }

                return pos
            }

            return null
        }
    }
}
