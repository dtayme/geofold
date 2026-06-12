/**
 * Arknav GPS tracker driver.
 *
 * CR-terminated text frames. Single pattern:
 *   <imei>,<idcode>,<status>,<version>,<AV>,<ddmm.d+>,<NS>,<dddmm.d+>,<EW>,
 *   <speed>,<course>,<hdop>,<hh>:<mm>:<ss> <dd>-<mm>-<yy>,...
 *
 * Coordinates in deg+min format. Time in HMS_DMY order (time then date).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(\d+),.{6},\d{3},L\d{3},([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),([\d.]+),([\d.]+),([\d.]+),(\d{2}):(\d{2}):(\d{2}) (\d{2})-(\d{2})-(\d{2}),/)

protocol("arknav") {

    port 5107

    variant("main") {

        maxFrameLength 256
        frame readUntil("\r")

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2) == 'A'

            double lat = m.group(3).toInteger() + m.group(4).toDouble() / 60
            pos.latitude = m.group(5) == 'S' ? -lat : lat

            double lon = m.group(6).toInteger() + m.group(7).toDouble() / 60
            pos.longitude = m.group(8) == 'W' ? -lon : lon

            pos.speed  = m.group(9).toDouble()
            pos.course = m.group(10).toDouble()
            pos.set(Position.KEY_HDOP, m.group(11).toDouble())

            // Time: hh:mm:ss (12-14), Date: dd-mm-yy (15-17)
            pos.time = new DateBuilder()
                    .setTime(m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
                    .setDateReverse(m.group(15).toInteger(), m.group(16).toInteger(), m.group(17).toInteger())
                    .getDate()

            return pos
        }
    }
}
