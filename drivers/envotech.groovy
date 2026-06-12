// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Envotech GPS tracker driver.
 *
 * "'"‐terminated text frames starting with '$'. Message format:
 *   $<mode2>,<hw3>,<event_hex>,<group_hex>,<device_id>,<ddmmyyhhmmss>,xx<rssi2><mcc5><pwr3><bat3><in2><out2>[<fuel3>][<weight3>],<status8>,[^']*'<ddmmyyhhmmss><fix><latDeg><latFrac5><NS><lonDeg><lonFrac5><EW><spd3><crs3>
 *
 * Two separate timestamps: deviceTime and fixTime.
 * Coordinates: DEG_DEG_HEM format (deg + frac/100000).
 * Alarms: event 0x60=LOCK, 0x61=UNLOCK.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$\d{2}...,([0-9a-fA-F]+),(?:[0-9a-fA-F]+),([0-9a-fA-F]+),(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),\S{2}(\d{2})\d{5}(\d{3})(\d{3})([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{3})?([0-9a-fA-F]{3})?,[0-9a-fA-F]{8},[^']*'(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d)(\d+)(\d{5})([NS])(\d+)(\d{5})([EW])(\d{3})(\d{3})/)

protocol("envotech") {

    port 5240

    variant("main") {

        frame '$' as char, readUntil("'")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(2))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            int event = Integer.parseInt(m.group(1), 16)
            switch (event) {
                case 0x60: pos.addAlarm(Position.ALARM_LOCK); break
                case 0x61: pos.addAlarm(Position.ALARM_UNLOCK); break
            }
            pos.set(Position.KEY_EVENT, event)

            pos.deviceTime = new DateBuilder()
                    .setDateReverse(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.set(Position.KEY_RSSI,    m.group(9).toInteger())
            pos.set(Position.KEY_POWER,   m.group(10).toInteger() / 100.0)
            pos.set(Position.KEY_BATTERY, m.group(11).toInteger() / 100.0)
            pos.set(Position.KEY_INPUT,   Integer.parseInt(m.group(12), 16))
            pos.set(Position.PREFIX_OUT,  Integer.parseInt(m.group(13), 16))
            if (m.group(14)) pos.set(Position.KEY_FUEL, Integer.parseInt(m.group(14), 16))
            if (m.group(15)) pos.set("weight",          Integer.parseInt(m.group(15), 16))

            pos.fixTime = new DateBuilder()
                    .setDateReverse(m.group(16).toInteger(), m.group(17).toInteger(), m.group(18).toInteger())
                    .setTime(m.group(19).toInteger(), m.group(20).toInteger(), m.group(21).toInteger())
                    .getDate()

            pos.valid = m.group(22).toInteger() > 0

            double lat = m.group(23).toInteger() + m.group(24).toInteger() / 100000.0
            pos.latitude = m.group(25) == 'S' ? -lat : lat

            double lon = m.group(26).toInteger() + m.group(27).toInteger() / 100000.0
            pos.longitude = m.group(28) == 'W' ? -lon : lon

            pos.speed  = m.group(29).toInteger()
            pos.course = m.group(30).toInteger()

            return pos
        }
    }
}
