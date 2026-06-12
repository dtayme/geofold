/**
 * Xt013 GPS tracker driver.
 *
 * Newline-terminated text frames. Optional login prefix:
 *   HI,<n>TK,<imei>,<yymmdd><hhmmss>,<±lat>,<±lon>,<speed_kph>,<course>,<n>,<alt>,
 *   <FL>,<n>,<gps_sats>,<lac_hex>,<cid_hex>,<gsm_rssi>,<label>,<battery>,<charge>,...
 *
 * Coordinates are signed decimal degrees. Speed in km/h. Fix: F = fixed, L = lost.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^(?:HI,\d+)?TK,(\d+),(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([+-][\d.]+),([+-][\d.]+),(\d+),(\d+),\d+,(\d+),([FL]),\d+,(\d+),[0-9a-fA-F]+,[0-9a-fA-F]+,(\d+),[^,]*,([\d.]+),(\d),/)

protocol("xt013") {

    port 5076

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.time = new DateBuilder()
                    .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                    .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                    .getDate()

            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(10).toDouble())
            pos.course    = m.group(11).toDouble()
            pos.altitude  = m.group(12).toDouble()
            pos.valid     = m.group(13) == 'F'

            pos.set(Position.KEY_SATELLITES, m.group(14).toInteger())
            pos.set(Position.KEY_RSSI,       m.group(15).toInteger())
            pos.set(Position.KEY_BATTERY,    m.group(16).toDouble())
            pos.set(Position.KEY_CHARGE,     m.group(17) == '1')

            return pos
        }
    }
}
