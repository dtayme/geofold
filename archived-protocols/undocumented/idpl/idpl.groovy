// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * IDPL GPS tracker driver.
 *
 * Newline-terminated text frames. Message format:
 *   *ID<cmd>,<imei>,<ddmmyy>,<hhmmss>,<AV>,<lat>,<NS>,<lon>,<EW>,<spd>,<crs>,<sats>,<rsm>,<vstatus>,<pwr>,<bat>,<sos>,<tamper>,<ac>,<ign>,<out>,<adc1>,<adc2>,<fw>,<type>,<crc>#
 *
 * Coordinates in DEG_MIN_MIN_HEM format (deg + (int_min + frac/10^len) / 60).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\*ID(\d+),(\d+),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([AV]),(\d{2})(\d{2})\.?(\d+),([NS]),(\d{3})(\d{2})\.?(\d+),([EW]),([\d.]+),([\d.]+),(\d{1,2}),(\d{1,3}),([ANS]),([01]),(\d\.\d{2}),([01]),([01]),([01])([01]),([012]),(\d{1,3}),(\d{1,3}),([0-9A-Z]{3}),([LR]),([0-9a-fA-F]{4})#/)

protocol("idpl") {

    port 5110

    variant("main") {

        frame '*' as char, readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(2))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_TYPE, m.group(1).toInteger())

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.valid = m.group(9) == 'A'

            String latFrac = m.group(12)
            double lat = m.group(10).toInteger() + (m.group(11).toInteger() + latFrac.toInteger() / Math.pow(10, latFrac.length())) / 60.0
            pos.latitude = m.group(13) == 'S' ? -lat : lat

            String lonFrac = m.group(16)
            double lon = m.group(14).toInteger() + (m.group(15).toInteger() + lonFrac.toInteger() / Math.pow(10, lonFrac.length())) / 60.0
            pos.longitude = m.group(17) == 'W' ? -lon : lon

            pos.speed  = m.group(18).toDouble()
            pos.course = m.group(19).toDouble()

            pos.set(Position.KEY_SATELLITES,  m.group(20).toInteger())
            pos.set(Position.KEY_RSSI,        m.group(21).toInteger())
            pos.set("vehicleStatus",          m.group(22))
            pos.set(Position.KEY_POWER,       m.group(23).toInteger())
            pos.set(Position.KEY_BATTERY,     m.group(24).toDouble())
            if (m.group(25).toInteger() == 1) pos.addAlarm(Position.ALARM_SOS)
            pos.set("acStatus",               m.group(27).toInteger())
            pos.set(Position.KEY_IGNITION,    m.group(28).toInteger() == 1)
            pos.set(Position.KEY_OUTPUT,      m.group(29).toInteger())
            pos.set(Position.PREFIX_ADC + 1,  m.group(30).toInteger())
            pos.set(Position.PREFIX_ADC + 2,  m.group(31).toInteger())
            pos.set(Position.KEY_VERSION_FW,  m.group(32))
            pos.set(Position.KEY_ARCHIVE,     m.group(33) == 'R')

            return pos
        }
    }
}
