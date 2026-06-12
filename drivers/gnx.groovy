// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GNX GPS tracker driver.
 *
 * All messages start with '$GNX_<TYPE>,'. Known types: MIF, LOC, DIO, ...
 *
 * Location block (shared by all types):
 *   <imei>,<length>,<history>,<hhmmss>,<ddmmyy>,<hhmmss>,<ddmmyy>,<valid>,
 *   <lat.dddddd>,<NS>,<lon.dddddd>,<EW>,...
 *
 * MIF-specific fields after location: <valid_card>,[0|1], <rfid>,
 *
 * Times are in GMT+5:30 (IST). Coordinates in decimal degrees.
 * history=1 → archive record (KEY_ARCHIVE=true).
 */

import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

// Shared location capture block (19 groups)
def LOCATION = /(\d+),\d+,([01]),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),(\d),(\d+\.\d+),([NS]),(\d+\.\d+),([EW]),/

def PATTERN_MIF   = Pattern.compile(/^\$GNX_MIF,/ + LOCATION + /[01],([^,]+),/)
def PATTERN_OTHER = Pattern.compile(/^\$GNX_\w{3},/ + LOCATION)

def IST = TimeZone.getTimeZone("GMT+5:30")

def makeDate = { h, mi, s, d, mo, y ->
    def cal = Calendar.getInstance(IST)
    cal.set(2000 + y, mo - 1, d, h, mi, s)
    cal.set(Calendar.MILLISECOND, 0)
    cal.getTime()
}

protocol("gnx") {

    port 5106

    variant("main") {

        maxFrameLength 512
        frame readLine()

        matches { msg -> msg.startsWith('$GNX_') }

        decode { msg, ctx ->
            String type = msg.substring(5, 8)

            def m = (type == 'MIF' ? PATTERN_MIF : PATTERN_OTHER).matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(2) == '1') pos.set(Position.KEY_ARCHIVE, true)

            // Device time: groups 3-5 (hhmmss), 6-8 (ddmmyy)
            pos.deviceTime = makeDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger(),
                                      m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
            // Fix time: groups 9-11 (hhmmss), 12-14 (ddmmyy)
            pos.fixTime    = makeDate(m.group(9).toInteger(), m.group(10).toInteger(), m.group(11).toInteger(),
                                      m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())

            pos.valid = m.group(15).toInteger() != 0

            double lat = m.group(16).toDouble()
            pos.latitude = m.group(17) == 'S' ? -lat : lat

            double lon = m.group(18).toDouble()
            pos.longitude = m.group(19) == 'W' ? -lon : lon

            if (type == 'MIF') {
                pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(20))
            }

            return pos
        }
    }
}
