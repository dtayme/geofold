/**
 * Siwi GPS tracker driver.
 *
 * Single-line format (newline-terminated, may end with '!'):
 *   $<type>,<id>,<unit>,<reason>,<cmd_code>,<cmd_val>,<ignition>,<power_cut>,<flags>,<???>,
 *   <odometer>,<speed_kph>,<sats>,<AV>,<lat>,<lon>,<alt>,<course>,
 *   <hhmmss>,<ddmmyy>,<signal>,<gsm_status>,<err_code>,<int_status>,
 *   <battery_mv>,<adc_x100>,<inputs>,<s1>,<s2>,<s3>,<s4>,<hw_ver>,<sw_ver>,...
 *
 * Time is in Asia/Kolkata timezone (IST, UTC+5:30).
 * Battery stored as volts (battery_mv / 1000). ADC as raw/100.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\$[A-Z]+,(\d+),\d+,([A-Z]),\d*,[^,]*,([01]),[01],\d+,[^,]+,(\d+),(\d+),(\d+),([AV]),(-?[\d.]+),(-?[\d.]+),(-?\d+),(\d+),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),\d+,\d+,\d+,\d+,(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),([^,]+),([^,]+),/)

def IST = TimeZone.getTimeZone("Asia/Kolkata")

protocol("siwi") {

    port 5135

    variant("main") {

        maxFrameLength 512
        frame readLine()

        matches { msg -> msg =~ /^\$[A-Z]+,\d+/ }

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_EVENT,    m.group(2))
            pos.set(Position.KEY_IGNITION, m.group(3) == '1')
            pos.set(Position.KEY_ODOMETER, m.group(4).toInteger())

            pos.speed    = UnitsConverter.knotsFromKph(m.group(5).toInteger())
            pos.set(Position.KEY_SATELLITES, m.group(6).toInteger())
            pos.valid    = m.group(7) == 'A'
            pos.latitude  = m.group(8).toDouble()
            pos.longitude = m.group(9).toDouble()
            pos.altitude  = m.group(10).toDouble()
            pos.course    = m.group(11).toDouble()

            // Time in IST: hhmmss (12-14) then ddmmyy (15-17)
            def cal = Calendar.getInstance(IST)
            cal.set(2000 + m.group(17).toInteger(), m.group(16).toInteger() - 1, m.group(15).toInteger(),
                    m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger())
            cal.set(Calendar.MILLISECOND, 0)
            pos.time = cal.getTime()

            pos.set(Position.KEY_BATTERY, m.group(18).toInteger() / 1000.0)
            pos.set(Position.PREFIX_ADC + 1, m.group(19).toInteger() / 100.0)
            pos.set(Position.KEY_INPUT, m.group(20).toInteger())

            for (int i = 1; i <= 4; i++) {
                int val = m.group(20 + i).toInteger()
                if (val != 0) pos.set(Position.PREFIX_IO + i, val)
            }

            pos.set(Position.KEY_VERSION_HW, m.group(25))
            pos.set(Position.KEY_VERSION_FW, m.group(26))

            return pos
        }
    }
}
