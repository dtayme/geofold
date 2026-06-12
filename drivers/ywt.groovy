/**
 * YWT GPS tracker driver.
 *
 * Newline-terminated text frames starting with '%'. Two message types:
 *  - Sync:     %SN... → ack %AT+SN=<substring from ':' to 4th ','> and return null
 *  - Position: %<type>,<unit>:<sub>,<yymmdd><hhmmss>,<EW><lon>,<NS><lat>,<alt>?,<spd>,<crs>,<sats>,<reportId>,<status>
 *
 * KP/EP position reports get ack %AT+<type>=<reportId>\r\n.
 * Coordinates: HEM_DEG decimal degrees (e.g. E112.345678).
 * Valid when satellites > 0.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^%(..),(\d+):\d+,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([EW])([\d.]+),([NS])([\d.]+),(\d+)?,(\d+),(\d+),(\d+),([^,]+),([-0-9a-fA-F]+)/)

protocol("ywt") {

    port 5035

    variant("main") {

        frame '%' as char, readLine()

        decode { msg, ctx ->
            if (msg.startsWith('%SN')) {
                int start = msg.indexOf(':')
                int end = start
                for (int i = 0; i < 4; i++) {
                    int next = msg.indexOf(',', end + 1)
                    if (next == -1) { end = msg.length(); break }
                    end = next
                }
                ctx.ack('%AT+SN=' + msg.substring(start, end))
                return null
            }

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(2))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            String type = m.group(1)

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lon = m.group(10).toDouble()
            pos.longitude = m.group(9) == 'W' ? -lon : lon

            double lat = m.group(12).toDouble()
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            pos.altitude = m.group(13) ? m.group(13).toDouble() : 0

            pos.speed  = UnitsConverter.knotsFromKph(m.group(14).toDouble())
            pos.course = m.group(15).toDouble()

            int sats = m.group(16).toInteger()
            pos.valid = sats > 0
            pos.set(Position.KEY_SATELLITES, sats)

            String reportId = m.group(17)
            pos.set(Position.KEY_STATUS, m.group(18))

            if ((type == 'KP' || type == 'EP')) {
                ctx.ack('%AT+' + type + '=' + reportId + '\r\n')
            }

            return pos
        }
    }
}
