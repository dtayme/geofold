/**
 * SmartSole GPS tracker driver.
 *
 * '$'-terminated text frames. Single message format:
 *   #GTXRP=<imei>,<reportType>,<yymmdd><hhmmss>,<lon>,<lat>,<alt>,<speed_kph>,<valid>,
 *   <sats>,<hdop>,<yymmdd><hhmmss>,<battery>,<status>$
 *
 * Two timestamps: fix time (GPS) and device time (local clock), both yymmdd hhmmss.
 * First coordinate group is longitude; second is latitude (per original decoder assignment).
 * Speed in km/h (no conversion applied by original decoder).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^#GTXRP=(\d+),\d+,(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(-?[\d.]+),(-?[\d.]+),(-?\d+),(\d+),([01]),(\d+),([\d.]+),(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([\d.]+),(\d+)/)

protocol("smartsole") {

    port 5178

    variant("main") {

        frame readUntil('$')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.setFixTime(new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate())

            // Original decoder assigns first value to latitude, second to longitude
            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.altitude  = m.group(10).toDouble()
            pos.speed     = m.group(11).toDouble()
            pos.valid     = m.group(12).toInteger() == 1

            pos.set(Position.KEY_SATELLITES, m.group(13).toInteger())
            pos.set(Position.KEY_HDOP,       m.group(14).toDouble())

            pos.setDeviceTime(new DateBuilder()
                    .setDate(m.group(15).toInteger(), m.group(16).toInteger(), m.group(17).toInteger())
                    .setTime(m.group(18).toInteger(), m.group(19).toInteger(), m.group(20).toInteger())
                    .getDate())

            pos.set(Position.KEY_BATTERY, m.group(21).toDouble())
            pos.set(Position.KEY_STATUS,  m.group(22).toInteger())

            return pos
        }
    }
}
