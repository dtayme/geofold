/**
 * Cautela GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   <type>,<imei>,<dd>,<mm>,<yy>,<lat>,<lon>,<hhmm>,...
 *
 * Date uses reversed format (ddmmyy via setDateReverse). Time has no seconds field.
 * Coordinates are signed decimal degrees directly.
 * Fix is always marked valid.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d+),(\d+),(\d{2}),(\d{2}),(\d{2}),(-?[\d.]+),(-?[\d.]+),(\d{2})(\d{2}),/)

protocol("cautela") {

    port 5160

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

            pos.latitude  = m.group(6).toDouble()
            pos.longitude = m.group(7).toDouble()

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setHour(m.group(8).toInteger())
                    .setMinute(m.group(9).toInteger())
                    .getDate()

            return pos
        }
    }
}
