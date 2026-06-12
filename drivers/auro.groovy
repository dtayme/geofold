/**
 * Auro GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   M<index>T<phone>I<imei>E<n>W*****<local_time>.{8}#.{8}<status>
 *   <±lonDeg><lonMinInt><lonMinFrac><±latDeg><latMinInt><latMinFrac>
 *   <ddmmyyyy><hhmmss><course><n6><speed><n><battery><charging>
 *
 * Coordinates in HEM_DEG_MIN_MIN format: sign (+ or -) + 3-digit degrees +
 * 2-digit integer minutes + 4-digit minute fraction (e.g. "+01234567890").
 * Speed in km/h. Date in DMY order (day, month, 4-digit year).
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^M(\d{4})T\d+I(\d+)E\d+W\*{5}\d{12}.{8}#.{8}\d{10}([-+])(\d{3})(\d{2})(\d{4})([-+])(\d{3})(\d{2})(\d{4})(\d{2})(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})(\d{3})\d{6}(\d{3})\d(\d{2})([01])/)

protocol("auro") {

    port 5096

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(2))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid = true

            pos.set(Position.KEY_INDEX, m.group(1).toInteger())

            // Longitude: sign(3), degrees(4), min_int(5), min_frac(6)
            double lonSign = m.group(3) == '+' ? 1.0 : -1.0
            double lon = m.group(4).toInteger() + (m.group(5).toInteger() + m.group(6).toInteger() / 10000.0) / 60.0
            pos.longitude = lonSign * lon

            // Latitude: sign(7), degrees(8), min_int(9), min_frac(10)
            double latSign = m.group(7) == '+' ? 1.0 : -1.0
            double lat = m.group(8).toInteger() + (m.group(9).toInteger() + m.group(10).toInteger() / 10000.0) / 60.0
            pos.latitude = latSign * lat

            // Date: dd(11) mm(12) yyyy(13), Time: hh(14) mm(15) ss(16)
            pos.time = new DateBuilder()
                    .setDateReverse(m.group(11).toInteger(), m.group(12).toInteger(), m.group(13).toInteger())
                    .setTime(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
                    .getDate()

            pos.course = m.group(17).toDouble()
            pos.speed  = UnitsConverter.knotsFromKph(m.group(18).toDouble())

            pos.set(Position.KEY_BATTERY, m.group(19).toInteger())
            pos.set(Position.KEY_CHARGE,  m.group(20) == '1')

            return pos
        }
    }
}
