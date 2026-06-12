/**
 * Carscop GPS tracker driver.
 *
 * '^'-terminated text frames. The device ID (IMEI) is embedded in the stream
 * as "UB05<15-digit-imei>" on login; subsequent position frames carry no ID
 * so the existing channel session is used.
 *
 * Position frame starts with '*' followed by arbitrary header data, then:
 *   <hhmmss><AV><ddmm.mmmm><NS><dddmm.mmmm><EW><sss.d><yymmdd><ddd.dd>[<status8>L<odo6>]
 *
 * Coordinates in deg+min format. Optional status/odometer block at end.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\*.*?(\d{2})(\d{2})(\d{2})([AV])(\d{2})(\d{2}\.\d{4})([NS])(\d{3})(\d{2}\.\d{4})([EW])(\d{3}\.\d)(\d{2})(\d{2})(\d{2})(\d{3}\.\d{2})(?:(\d{8})L(\d{6}))?/)

protocol("carscop") {

    port 5040

    variant("main") {

        frame readUntil('^')

        decode { msg, ctx ->
            String imei = null
            int idx = msg.indexOf('UB05')
            if (idx >= 0 && idx + 19 <= msg.length()) {
                imei = msg.substring(idx + 4, idx + 19)
            }
            def session = imei ? ctx.session(imei) : ctx.session()
            if (!session) return null

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())

            pos.valid = m.group(4) == 'A'

            double lat = m.group(5).toInteger() + m.group(6).toDouble() / 60
            pos.latitude = m.group(7) == 'S' ? -lat : lat

            double lon = m.group(8).toInteger() + m.group(9).toDouble() / 60
            pos.longitude = m.group(10) == 'W' ? -lon : lon

            pos.speed = m.group(11).toDouble()

            db.setDate(m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
            pos.time = db.getDate()

            pos.course = m.group(15).toDouble()

            if (m.group(16) != null) {
                pos.set(Position.KEY_STATUS,   m.group(16))
                pos.set(Position.KEY_ODOMETER, m.group(17).toInteger())
            }

            return pos
        }
    }
}
