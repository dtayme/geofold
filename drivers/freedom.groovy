/**
 * Freedom GPS tracker driver.
 *
 * Single-line text format (newline-terminated):
 *   IMEI,<imei>,<yyyy>/<MM>/<dd>, <hh>:<mm>:<ss>, <NS>, Lat:<dd><mm.mmmm>, <EW>, Lon:<ddd><mm.mmmm>, Spd:<speed>
 *
 * Coordinates: deg+min with hemisphere suffix. Speed stored as-is (no unit conversion).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^IMEI,(\d+),(\d{4})\/(\d{2})\/(\d{2}),\s+(\d{2}):(\d{2}):(\d{2}),\s+([NS]),\s+Lat:(\d{2})(\d+\.\d+),\s+([EW]),\s+Lon:(\d{3})(\d+\.\d+),\s+Spd:([\d.]+)/)

protocol("freedom") {

    port 5066

    variant("main") {

        maxFrameLength 256
        frame readLine()

        matches { msg -> msg.startsWith('IMEI,') }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid = true

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            pos.speed = m.group(14).toDouble()

            return pos
        }
    }
}
