/**
 * Homtecs GPS tracker driver.
 *
 * UDP datagrams, newline frame for TCP. Single message format:
 *   <id>_R<mac8hex>,<yymmdd>,<hhmmss>.<frac>,<sats>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>,<course>,<fix>,<hdop>,<alt>
 *
 * Returns null if fix=0 (no valid GPS lock).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^([^_]+)_R([0-9a-fA-F]{8}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2})\.\d+,(\d+),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),([\d.]+)?,([\d.]+)?,(\d),([\d.]+)?,([\d.]+)?/)

protocol("homtecs") {

    port 5104

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.set(Position.KEY_SATELLITES, m.group(9).toInteger())

            double lat = m.group(10).toInteger() + m.group(11).toDouble() / 60.0
            pos.latitude = m.group(12) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + m.group(14).toDouble() / 60.0
            pos.longitude = m.group(15) == 'W' ? -lon : lon

            pos.speed  = m.group(16) ? m.group(16).toDouble() : 0.0
            pos.course = m.group(17) ? m.group(17).toDouble() : 0.0

            pos.valid = m.group(18).toInteger() > 0

            if (m.group(19)) pos.set(Position.KEY_HDOP, m.group(19).toDouble())
            if (m.group(20)) pos.altitude = m.group(20).toDouble()

            return pos
        }
    }
}
