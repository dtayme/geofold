// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Haicom GPS tracker driver.
 *
 * '*'-terminated text frames (checksum stripped). Single message format:
 *   $GPRS<imei>,<ver>,<yymmdd>,<hhmmss>,<flags><latDeg><latFrac><lonDeg><lonFrac>,<spd>,<crs>,<status>,<gprs>,<ps>,<sw>,<relay>[LH]{2}#V<bat>
 *
 * Flags nibble: bit0=valid, bit1=lon-east, bit2=lat-north.
 * Coordinates: degrees + raw_minute_fraction / 60000 (raw_frac = mm.ddd * 1000).
 * Speed and course in tenths.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$GPRS(\d+),([^,]+),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d)(\d{2})(\d{5})(\d{3})(\d{5}),(\d+),(\d+),(\d+),(\d+)?,(\d+)?,(\d+),(\d+)(?:[LH]{2})?#V(\d+)/)

protocol("haicom") {

    port 5063

    variant("main") {

        frame '$' as char, readUntil("*")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_VERSION_FW, m.group(2))

            pos.time = new DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            int flags = m.group(9).toInteger()
            pos.valid = (flags & 1) != 0

            double lat = m.group(10).toInteger() + m.group(11).toInteger() / 60000.0
            pos.latitude = (flags & 4) != 0 ? lat : -lat

            double lon = m.group(12).toInteger() + m.group(13).toInteger() / 60000.0
            pos.longitude = (flags & 2) != 0 ? lon : -lon

            pos.speed  = m.group(14).toDouble() / 10.0
            pos.course = m.group(15).toDouble() / 10.0

            pos.set(Position.KEY_STATUS,  m.group(16))
            if (m.group(17)) pos.set("gprsCount",         m.group(17))
            if (m.group(18)) pos.set("powersaveCountdown", m.group(18))
            pos.set(Position.KEY_INPUT,   m.group(19))
            pos.set(Position.KEY_OUTPUT,  m.group(20))
            pos.set(Position.KEY_BATTERY, m.group(21).toDouble() / 10.0)

            return pos
        }
    }
}
