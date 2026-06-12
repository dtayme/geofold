/**
 * Sanav GPS tracker driver.
 *
 * Newline-terminated text frames (no frame decoder). Message format:
 *   imei[:=]<imei>&?rmc[:=]$GPRMC,<hhmmss>.<ms>,<AV>,<lat>,<NS>,<lon>,<EW>,<spd>,<crs>,<ddmmyy>,...[*xx,<status>,<io_hex>]
 *
 * IO hex bits: 0-4=IN1-5, 5=ignition, 6=OUT1, 7=OUT2, 8=charge, !9=LOW_BATTERY.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /imei[:=](\d+)&?rmc[:=]\$GPRMC,(\d{2})(\d{2})(\d{2})\.\d+,([AV]),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]+),([\d.]+)?,(\d{2})(\d{2})(\d{2})(?:[^*]*\*[0-9a-fA-F]{2},[^,]+,([0-9a-fA-F]+))?/)

protocol("sanav") {

    port 5051

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            pos.valid = m.group(5) == 'A'

            double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60.0
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            pos.speed  = m.group(12).toDouble()
            if (m.group(13)) pos.course = m.group(13).toDouble()

            db.setDateReverse(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
            pos.time = db.getDate()

            if (m.group(17)) {
                int io = Integer.parseInt(m.group(17), 16)
                (1..5).each { i -> pos.set(Position.PREFIX_IN + i, checkBit(io, i - 1)) }
                pos.set(Position.KEY_IGNITION, checkBit(io, 5))
                pos.set(Position.PREFIX_OUT + 1, checkBit(io, 6))
                pos.set(Position.PREFIX_OUT + 2, checkBit(io, 7))
                pos.set(Position.KEY_CHARGE,  checkBit(io, 8))
                if (!checkBit(io, 9)) pos.addAlarm(Position.ALARM_LOW_BATTERY)
            }

            return pos
        }
    }
}
