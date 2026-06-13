// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * V680 text tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/v680/
 *
 * Handles login/session messages and location reports with either explicit
 * device IDs or an existing channel session. TCP frames are delimited by "##";
 * UDP packets carry whole reports.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def REPORT = Pattern.compile(
        /^(?:#(\d+)#([^#]*)#)?(\d+)#([^#]+)#([^#]+)#(\d+)#([^#]+)?#?(?:[^#]+#)?(\d+\.\d+),([EW]),(\d+\.\d+),([NS]),(\d+\.\d+),(\d+\.?\d*)?#(\d{2})(\d{2})(\d{2})#(\d{2})(\d{2})(\d{2}).*/)

def convertCoordinate = { double value ->
    int degrees = (int) (value / 100.0)
    (value - degrees * 100) / 60.0 + degrees
}

protocol("v680") {

    port 5016
    transport 'tcp', 'udp'

    variant("main") {

        maxFrameLength 2048
        frame readUntil('##')

        matches { msg -> msg.startsWith('#') || msg ==~ /^\d+#.*/ }

        decode { msg, ctx ->
            String sentence = msg.trim()

            if (sentence.length() == 16) {
                ctx.session(sentence.substring(1))
                return null
            }

            def m = REPORT.matcher(sentence)
            if (!m.matches()) return null

            def session = m.group(1) ? ctx.session(m.group(1)) : ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(2)) {
                pos.set('user', m.group(2))
            }
            pos.valid = m.group(3).toInteger() > 0
            pos.set('password', m.group(4))
            pos.set(Position.KEY_EVENT, m.group(5))
            pos.set('packet', m.group(6))
            if (m.group(7)) {
                pos.set('lbsData', m.group(7))
            }

            double lon = m.group(8).toDouble()
            boolean west = m.group(9) == 'W'
            double lat = m.group(10).toDouble()
            boolean south = m.group(11) == 'S'

            if (lat > 90 || lon > 180) {
                lon = convertCoordinate(lon)
                lat = convertCoordinate(lat)
            }

            pos.longitude = west ? -lon : lon
            pos.latitude = south ? -lat : lat
            pos.speed = m.group(12).toDouble()
            pos.course = m.group(13) ? m.group(13).toDouble() : 0

            int day = m.group(14).toInteger()
            int month = m.group(15).toInteger()
            if (day == 0 && month == 0) return null

            pos.time = new DateBuilder()
                    .setDate(m.group(16).toInteger(), month, day)
                    .setTime(m.group(17).toInteger(), m.group(18).toInteger(), m.group(19).toInteger())
                    .getDate()

            return pos
        }
    }
}
