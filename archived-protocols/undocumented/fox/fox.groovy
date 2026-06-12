// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Fox GPS tracker driver.
 *
 * '</fox>'-terminated XML text frames. Message format:
 *   <fox id="<imei>" data="<statusId>,<AV>,<ddmmyy>,<hhmmss>,<lat>,<NS>,<lon>,<EW>,<spd>,<crs>,<cell>,<in> <pwr> <temp> <rpm> <fuel> <adc1> <adc2> <out> <odo>,<status>">
 *
 * Coordinates: DEG_MIN format. Speed in km/h. Input/output as binary strings.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def DATA_PATTERN = Pattern.compile(
    /^(\d+),([AV]),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),(\d+\.?\d*)?,(\d+\.?\d*)?,(?:[^,]*),([01]+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d+) ([01]+) (\d+),(.+)/)

protocol("fox") {

    port 5105

    variant("main") {

        frame '<' as char, readUntil("</fox>")

        decode { msg, ctx ->
            def idM   = (msg =~ /\bid="([^"]+)"/)
            def dataM = (msg =~ /\bdata="([^"]+)"/)
            if (!idM || !dataM) return null

            def session = ctx.session(idM[0][1])
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def m = DATA_PATTERN.matcher(dataM[0][1])
            if (!m.find()) return null

            pos.set(Position.KEY_STATUS, m.group(1).toInteger())
            pos.valid = m.group(2) == 'A'

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60.0
            pos.longitude = m.group(14) == 'W' ? -lon : lon

            if (m.group(15)) pos.speed  = UnitsConverter.knotsFromKph(m.group(15).toDouble())
            if (m.group(16)) pos.course = m.group(16).toDouble()

            pos.set(Position.KEY_INPUT,       Integer.parseInt(m.group(17), 2))
            pos.set(Position.KEY_POWER,       m.group(18).toDouble() / 10.0)
            pos.set(Position.PREFIX_TEMP + 1, m.group(19).toInteger())
            pos.set(Position.KEY_RPM,         m.group(20).toInteger())
            pos.set(Position.KEY_FUEL,        m.group(21).toInteger())
            pos.set(Position.PREFIX_ADC + 1,  m.group(22).toInteger())
            pos.set(Position.PREFIX_ADC + 2,  m.group(23).toInteger())
            pos.set(Position.KEY_OUTPUT,      Integer.parseInt(m.group(24), 2))
            pos.set(Position.KEY_ODOMETER,    m.group(25).toInteger())
            pos.set("statusData",             m.group(26))

            return pos
        }
    }
}
