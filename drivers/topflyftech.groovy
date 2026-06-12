/**
 * Topflyftech GPS tracker driver.
 *
 * ')'-terminated text frames. Single message format:
 *   (<imei>,<other>,<yymmdd><hhmmss><AV><ddmm.mmmm><NS><dddmm.mmmm><EW><speed><course>)
 *
 * The frame includes the leading '(' but the ')' is stripped by readUntil.
 * Coordinates in deg+min format.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\((\d+).*?(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})([AV])(\d{2})(\d{2}\.\d+)([NS])(\d{3})(\d{2}\.\d+)([EW])([\d.]+)([\d.]+)/)

protocol("topflyftech") {

    port 5047

    variant("main") {

        frame readUntil(')')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.valid = m.group(8) == 'A'

            double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60
            pos.longitude = m.group(14) == 'W' ? -lon : lon

            pos.speed  = m.group(15).toDouble()
            pos.course = m.group(16).toDouble()

            return pos
        }
    }
}
