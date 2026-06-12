// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Gpsmta GPS tracker driver.
 *
 * UDP datagrams, newline frame for TCP. Single message format:
 *   <uid> <unixtime> <lat> <lon> <speed> <course> <accuracy> <alt> <flags> <battery> <temp> <charging>
 *
 * Device expects the unix timestamp echoed back as acknowledgement.
 */

import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^([^ ]+) (\d+) (-?\d+\.\d+) (-?\d+\.\d+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d)/)

protocol("gpsmta") {

    port 5038

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def time = m.group(2)
            pos.time = new Date(Long.parseLong(time) * 1000L)

            pos.latitude  = m.group(3).toDouble()
            pos.longitude = m.group(4).toDouble()
            pos.speed     = m.group(5).toInteger()
            pos.course    = m.group(6).toInteger()
            pos.accuracy  = m.group(7).toInteger()
            pos.altitude  = m.group(8).toInteger()

            pos.set(Position.KEY_STATUS,        m.group(9).toInteger())
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(10).toInteger())
            pos.set(Position.PREFIX_TEMP + 1,   m.group(11).toInteger())
            pos.set(Position.KEY_CHARGE,        m.group(12).toInteger() == 1)

            ctx.ack(time)

            return pos
        }
    }
}
