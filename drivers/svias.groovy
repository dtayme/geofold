/**
 * Svias GPS tracker driver.
 *
 * ']'-terminated text frames starting with '['. Always ack '@'. Message format:
 *   [<hw>,<sw>,<idx>,<imei>,<hmeter>,<dmmyy>,<hmmss>,<latSign><latDeg><latMin><latFrac5>,<lonSign><lonDeg><lonMin><lonFrac5>,<spd100>,<crs100>,<odo>,<in>,<out>,d,d,<pwr_mV>,<bat_pct>,<rssi>...]
 *
 * Coordinate format HEM_DEG_MIN_MIN: latSign*(deg + (int_min + frac/100000) / 60).
 * Speed and course are in units of 1/100 km/h and 1/100 degrees.
 * Valid from output bit 0; SOS alarm from input bit 0.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\[\d{4},\d{4},\d+,(\d+),\d+,(\d+)(\d{2})(\d{2}),(\d+)(\d{2})(\d{2}),(-?)(\d+)(\d{2})(\d{5}),(-?)(\d+)(\d{2})(\d{5}),(\d+),(\d+),(\d+),(\d+),(\d+),\d,\d,(\d+),(\d+),(\d+)/)

protocol("svias") {

    port 5168

    variant("main") {

        frame '[' as char, readUntil("]")

        decode { msg, ctx ->
            ctx.ack('@')

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDateReverse(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            double latSign = m.group(8) == '-' ? -1.0 : 1.0
            double lat = m.group(9).toInteger() + (m.group(10).toInteger() + m.group(11).toInteger() / 100000.0) / 60.0
            pos.latitude = latSign * lat

            double lonSign = m.group(12) == '-' ? -1.0 : 1.0
            double lon = m.group(13).toInteger() + (m.group(14).toInteger() + m.group(15).toInteger() / 100000.0) / 60.0
            pos.longitude = lonSign * lon

            pos.speed  = UnitsConverter.knotsFromKph(m.group(16).toInteger() / 100.0)
            pos.course = m.group(17).toInteger() / 100.0

            pos.set(Position.KEY_ODOMETER, m.group(18).toInteger() * 100)

            int input  = m.group(19).toInteger()
            int output = m.group(20).toInteger()

            if (checkBit(input, 0)) pos.addAlarm(Position.ALARM_SOS)
            pos.set(Position.KEY_IGNITION, checkBit(input, 4))
            pos.valid = checkBit(output, 0)

            pos.set(Position.KEY_POWER,         m.group(21).toInteger() / 1000.0)
            pos.set(Position.KEY_BATTERY_LEVEL, m.group(22).toInteger())
            pos.set(Position.KEY_RSSI,          m.group(23).toInteger())

            return pos
        }
    }
}
