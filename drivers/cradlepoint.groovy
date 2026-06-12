/**
 * Cradlepoint GPS tracker driver.
 *
 * Newline-terminated text frames. Single message format:
 *   <id>,<hhmmss>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,<speed>?,<course>?,<carrier>?,<serdis>?,
 *   <rsrp>?,<rssi>?,<rsrq>?,<ecio>?,<wan_ip>?
 *
 * Time only (no date in frame); date part is taken from the current wall clock.
 * Many trailing fields are optional. Coordinates in deg+min format.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^([^,]+),(\d{1,6}),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]+)?,([\d.]+)?,([^,]*)?,([^,]*)?,(-?\d+)?,(-?\d+)?,(-?\d+)?,([^,]*)?,?([^,]*)?$/)

protocol("cradlepoint") {

    port 5118

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid = true

            int time = m.group(2).toInteger()
            pos.time = new DateBuilder()
                    .setHour(time / 10000)
                    .setMinute((time / 100) % 100)
                    .setSecond(time % 100)
                    .getDate()

            double lat = m.group(3).toInteger() + m.group(4).toDouble() / 60
            pos.latitude = m.group(5) == 'S' ? -lat : lat

            double lon = m.group(6).toInteger() + m.group(7).toDouble() / 60
            pos.longitude = m.group(8) == 'W' ? -lon : lon

            pos.speed  = (m.group(9)  != null && !m.group(9).isEmpty())  ? m.group(9).toDouble()  : 0
            pos.course = (m.group(10) != null && !m.group(10).isEmpty()) ? m.group(10).toDouble() : 0

            if (m.group(11) != null && !m.group(11).isEmpty()) pos.set("carrid", m.group(11))
            if (m.group(12) != null && !m.group(12).isEmpty()) pos.set("serdis", m.group(12))
            if (m.group(13) != null && !m.group(13).isEmpty()) pos.set("rsrp",    m.group(13).toInteger())
            if (m.group(14) != null && !m.group(14).isEmpty()) pos.set(Position.KEY_RSSI, m.group(14).toInteger())
            if (m.group(15) != null && !m.group(15).isEmpty()) pos.set("rsrq",    m.group(15).toInteger())
            if (m.group(16) != null && !m.group(16).isEmpty()) pos.set("ecio",    m.group(16))

            return pos
        }
    }
}
