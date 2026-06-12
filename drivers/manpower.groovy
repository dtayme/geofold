/**
 * ManPower GPS tracker driver.
 *
 * ';'-terminated text frames. Single message format:
 *   simei:<imei>,<f1>,<f2>,<status>,<n1>,<n2>,<f3>,<yymmdd><hhmmss>,<AV>,
 *   <ddmm.mmmm>,<NS>,<dddmm.mmmm>,<EW>?,<speed>,...
 *
 * Coordinates in deg+min format.
 */

import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^simei:(\d+),[^,]*,[^,]*,([^,]*),\d+,\d+,[\d.]+,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW])?,(\d+\.?\d*),/)

protocol("manpower") {

    port 5042

    variant("main") {

        frame readUntil(';')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_STATUS, m.group(2))

            pos.time = new org.traccar.helper.DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            pos.valid = m.group(9) == 'A'

            double lat = m.group(10).toInteger() + m.group(11).toDouble() / 60
            pos.latitude = m.group(12) == 'S' ? -lat : lat

            double lon = m.group(13).toInteger() + m.group(14).toDouble() / 60
            pos.longitude = m.group(15) == 'W' ? -lon : lon

            pos.speed = m.group(16).toDouble()

            return pos
        }
    }
}
