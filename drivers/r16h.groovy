// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * R16h GPS tracker driver.
 *
 * '$'-terminated text frames starting with '@'. Two message types:
 *  - LINK:  @LINK,<imei> → register session, return null
 *  - GPSD:  @GPSD,<imei>,<RS>,<yyyymmdd>,<hhmmss>,<lat>,<NS>,<lon>,<EW>,<spd>,<crs>,<alt>,<bat>,<strap>,<alarm>
 *
 * Coordinates: decimal degrees with hemisphere suffix (DEG_HEM).
 * Speed in km/h converted to knots. Strap 'L'=locked.
 * Alarms: SOS, LBT=LOW_BATTERY, ULK=REMOVING.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^@GPSD,(\d+),([RS]),(\d{4})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([\d.]+),([NS]),([\d.]+),([EW]),(\d+),(\d+),(-?\d+),(\d+),([LR]),(\w*)/)

protocol("r16h") {

    port 5266

    variant("main") {

        frame '@' as char, readUntil("$")

        decode { msg, ctx ->
            String sentence = msg.trim()

            if (sentence.startsWith('@LINK,')) {
                ctx.session(sentence.substring('@LINK,'.length()).trim())
                return null
            }

            def m = PATTERN.matcher(sentence)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(2) == 'S') pos.set(Position.KEY_ARCHIVE, true)

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.valid = true

            double lat = m.group(9).toDouble()
            pos.latitude = m.group(10) == 'S' ? -lat : lat

            double lon = m.group(11).toDouble()
            pos.longitude = m.group(12) == 'W' ? -lon : lon

            pos.speed    = UnitsConverter.knotsFromKph(m.group(13).toInteger())
            pos.course   = m.group(14).toInteger()
            pos.altitude = m.group(15).toInteger()

            pos.set(Position.KEY_BATTERY_LEVEL, m.group(16).toInteger())
            pos.set("strapLocked", m.group(17) == 'L')

            switch (m.group(18)) {
                case 'SOS': pos.addAlarm(Position.ALARM_SOS); break
                case 'LBT': pos.addAlarm(Position.ALARM_LOW_BATTERY); break
                case 'ULK': pos.addAlarm(Position.ALARM_REMOVING); break
            }

            return pos
        }
    }
}
