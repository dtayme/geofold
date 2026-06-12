/**
 * Appello GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   FOLLOWIT,<imei>,<yymmddhhmmss>|UTCTIME,<lat>,<lon>,<speed>,<course>,<sats>,<alt>,<FL>,...
 *
 * Date/time is optional; when "UTCTIME" appears instead, ctx.lastLocation is used.
 * GPS state: F = fixed (valid), L = lost (invalid).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^FOLLOWIT,(\d+),(?:(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\.?\d*|UTCTIME),(-?[\d.]+),(-?[\d.]+),(\d+),(\d+),(\d+),(-?\d+),([FL]),/)

protocol("appello") {

    port 5109

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            if (m.group(2) != null) {
                pos.time = new DateBuilder()
                        .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                        .getDate()
            } else {
                ctx.lastLocation(pos)
            }

            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.speed     = m.group(10).toDouble()
            pos.course    = m.group(11).toDouble()

            pos.set(Position.KEY_SATELLITES, m.group(12).toInteger())
            pos.altitude = m.group(13).toDouble()

            pos.valid = m.group(14) == 'F'

            return pos
        }
    }
}
