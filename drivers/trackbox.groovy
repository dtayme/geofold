/**
 * Trackbox GPS tracker driver.
 *
 * Newline-terminated text frames. Two message types:
 *
 *   a=connect&...&i=<id>  — login: registers session; server responds with "=OK=\r\n".
 *
 *   <hhmmss.sss>,<ddmm.mmmm><NS>,<dddmm.mmmm><EW>,<hdop>,<alt>,<fix>,<course>,<speed_kph>,<speed_kn>,<ddmmyy>,<sats>
 *                         — position report (uses existing channel session);
 *                           server responds with "=OK=\r\n".
 *
 * Fix type: 0 = no fix, >0 = valid.
 * Date uses "reverse" format (ddmmyy) via setDateReverse.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d{2})(\d{2})(\d{2})\.(\d{3}),(\d{2})(\d{2}\.\d+)([NS]),(\d{3})(\d{2}\.\d+)([EW]),([\d.]+),(-?[\d.]+),(\d),([\d.]+),[\d.]+,([\d.]+),(\d{2})(\d{2})(\d{2}),(\d+)/)

protocol("trackbox") {

    port 5068

    variant("main") {

        maxFrameLength 256
        frame readLine()

        decode { msg, ctx ->

            if (msg.startsWith('a=connect')) {
                int idx = msg.indexOf('i=')
                if (idx < 0) return null
                String id = msg.substring(idx + 2)
                if (ctx.session(id)) ctx.ack("=OK=\r\n")
                return null
            }

            def session = ctx.session()
            if (!session) return null

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            ctx.ack("=OK=\r\n")

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(1).toInteger(), m.group(2).toInteger(),
                             m.group(3).toInteger(), m.group(4).toInteger())

            double lat = m.group(5).toInteger() + m.group(6).toDouble() / 60
            pos.latitude = m.group(7) == 'S' ? -lat : lat

            double lon = m.group(8).toInteger() + m.group(9).toDouble() / 60
            pos.longitude = m.group(10) == 'W' ? -lon : lon

            pos.set(Position.KEY_HDOP, m.group(11).toDouble())
            pos.altitude = m.group(12).toDouble()

            int fix = m.group(13).toInteger()
            pos.set(Position.KEY_GPS, fix)
            pos.valid = fix > 0

            pos.course = m.group(14).toDouble()
            pos.speed  = m.group(15).toDouble()

            db.setDateReverse(m.group(16).toInteger(), m.group(17).toInteger(), m.group(18).toInteger())
            pos.time = db.getDate()

            pos.set(Position.KEY_SATELLITES, m.group(19).toInteger())

            return pos
        }
    }
}
