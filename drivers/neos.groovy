/**
 * Neos GPS tracker driver.
 *
 * UDP, newline-terminated text frames. Always ack '$OK!'. Message format:
 *   ><id>,<status>,<valid>,<yymmdd>,<hhmmss>,<EW><lonDeg><lonMin>,<NS><latDeg><latMin>,...,<spd>,<crs>,<rssi>,...,<adc>-<bat>,0,d,<in8bits>*xx!...
 *
 * Note: longitude comes before latitude in the message.
 */

import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^>(\d{8}),\d+,([01]),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([EW])(\d+)(\d{2}\.\d+),([NS])(\d+)(\d{2}\.\d+),[^,]*,(\d+),(\d+),(\d+),[^,]*,(\d+)-(\d+),0,\d,([01]{8})\*/)

protocol("neos") {

    port 5183

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            ctx.ack('$OK!')

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2).toInteger() > 0

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lon = m.group(10).toInteger() + m.group(11).toDouble() / 60.0
            pos.longitude = m.group(9) == 'W' ? -lon : lon

            double lat = m.group(13).toInteger() + m.group(14).toDouble() / 60.0
            pos.latitude = m.group(12) == 'S' ? -lat : lat

            pos.speed  = m.group(15).toInteger()
            pos.course = m.group(16).toInteger()

            pos.set(Position.KEY_RSSI,         m.group(17).toInteger())
            pos.set(Position.PREFIX_ADC + 1,   m.group(18).toInteger())
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(19).toInteger())
            pos.set(Position.KEY_INPUT,        Integer.parseInt(m.group(20), 2))

            return pos
        }
    }
}
