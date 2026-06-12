// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Gotop GPS tracker driver.
 *
 * Single-line CSV format (newline-terminated):
 *   <imei>,<type>,[AV],DATE:yyMMdd,TIME:hhmmss,LAT:dd.dddddd[NS],LO[NT]:ddd.dddddd[EW],Speed:kph[,battery-rssi[,alt,hdop]][,extra]
 *
 * Coordinate format: decimal degrees with hemisphere suffix (N/S/E/W).
 * DATE/TIME labels are optional — some firmware omits them.
 * Speed is in km/h and is stored as-is (no unit conversion in the original decoder).
 *
 * Alarm types:
 *   CMD-KEY     → SOS
 *   ALM-Bx      → GEOFENCE_ENTER if x is odd, GEOFENCE_EXIT if x is even
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d+),([^,]+),([AV]),(?:DATE:)?(\d{2})(\d{2})(\d{2}),(?:TIME:)?(\d{2})(\d{2})(\d{2}),(?:LAT:)?(\d+\.\d+)([NS]),(?:LO[NT]:)?(\d+\.\d+)([EW]),(?:Speed:)?(\d+\.?\d*)(?:,(\d+)-(\d+)(?:,(-?\d+\.?\d*),(\d+\.\d+))?)?/)

protocol("gotop") {

    port 5050

    variant("main") {

        maxFrameLength 512
        frame readLine()

        matches { msg -> msg =~ /^\d{10,15},/ }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            // Alarm from type field
            def type = m.group(2)
            if (type == 'CMD-KEY') {
                pos.addAlarm(ALARM_SOS)
            } else if (type.startsWith('ALM-B')) {
                def digit = Character.getNumericValue(type.charAt(5))
                pos.addAlarm(digit % 2 > 0 ? ALARM_GEOFENCE_ENTER : ALARM_GEOFENCE_EXIT)
            }

            pos.valid = m.group(3) == 'A'

            pos.time = new DateBuilder()
                    .setDate(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                    .setTime(m.group(7).toInteger(), m.group(8).toInteger(), m.group(9).toInteger())
                    .getDate()

            double lat = m.group(10).toDouble()
            if (m.group(11) == 'S') lat = -lat
            pos.latitude = lat

            double lon = m.group(12).toDouble()
            if (m.group(13) == 'W') lon = -lon
            pos.longitude = lon

            pos.speed = UnitsConverter.knotsFromKph(m.group(14).toDouble())

            if (m.group(15) != null) {
                pos.set(Position.KEY_BATTERY_LEVEL, m.group(15).toInteger())
                pos.set(Position.KEY_RSSI,          m.group(16).toInteger())
            }
            if (m.group(17) != null) {
                pos.altitude = m.group(17).toDouble()
                pos.set(Position.KEY_HDOP, m.group(18).toDouble())
            }

            return pos
        }
    }
}
