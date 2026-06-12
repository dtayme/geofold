/**
 * Ennfu GPS tracker driver.
 *
 * '$'-terminated text frames. Single message format:
 *   Ennfu:<imei>,<hhmmss.dd>,<AV>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>?,<course>?,
 *   <ddmmyy>,<rssi>,<battery>,<batt_level>,<fw_version>$
 *
 * Coordinates in deg+min format. Date ddmmyy (setDateReverse). Speed/course optional.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^Ennfu:(\d+),(\d{2})(\d{2})(\d{2})\.\d{2},([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),([\d.]+)?,([\d.]+)?,(\d{2})(\d{2})(\d{2}),(\d+),([\d.]+),([\d.]+),(V[\d.]+)/)

protocol("ennfu") {

    port 5220

    variant("main") {

        frame readUntil('$')

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

            double lat = m.group(6).toInteger() + m.group(7).toDouble() / 60
            pos.latitude = m.group(8) == 'S' ? -lat : lat

            double lon = m.group(9).toInteger() + m.group(10).toDouble() / 60
            pos.longitude = m.group(11) == 'W' ? -lon : lon

            if (m.group(12) != null) pos.speed  = m.group(12).toDouble()
            if (m.group(13) != null) pos.course = m.group(13).toDouble()

            db.setDateReverse(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
            pos.time = db.getDate()

            pos.set(Position.KEY_RSSI,          m.group(17).toInteger())
            pos.set(Position.KEY_BATTERY,        m.group(18).toDouble())
            pos.set(Position.KEY_BATTERY_LEVEL,  m.group(19).toDouble())
            pos.set(Position.KEY_VERSION_FW,     m.group(20))

            return pos
        }
    }
}
