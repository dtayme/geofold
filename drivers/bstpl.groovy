/**
 * BSTPL GPS tracker driver.
 *
 * '#'-terminated text frames. Single message format:
 *   BSTPL$<type>,<id>,[AV],<ddmmyy>,<hhmmss>,<lat>,<NS>,<lon>,<EW>,
 *   <speed>,<odo>,<course>,<sats>,<variant-fields>#
 *
 * Two field variants follow the fixed header:
 *   v1 (boxOpen,rssi,charge,ignition,engine,locked,adc,_,battery,fw,iccid,power)
 *   v2 (rssi,boxOpen,battery,charge,ignition,power,alt,_,fw,iccid,{rpm,canOdo,fuel,gear,engTemp,coolTemp})
 *
 * Coordinates are decimal degrees (DEG_HEM). Speed in km/h. Odometer in km (converted to m).
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^BSTPL[$](\d),([^,]+),([AV]),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([\d.]+),([0NS]),([\d.]+),([0EW]),(\d+),(\d+),(\d+),(\d+),(?:([01]),(\d+),([01]),([01]),([01]),([01]),([\d.]+),\d+,([\d.]+),([^,]+),([^,]+),([\d.]+)|(\d+),([01]),([\d.]+),([01]),([01]),([\d.]+),(\d+),[01],([^,]+),([^,]+),\{(\d+),(\d+),(\d+),(\d+),([\d.]+),([\d.]+)\})/)

def decodeAlarm(int type) {
    switch (type) {
        case 4: return Position.ALARM_LOW_BATTERY
        case 5: return Position.ALARM_ACCELERATION
        case 6: return Position.ALARM_BRAKING
        case 7: return Position.ALARM_OVERSPEED
        case 9: return Position.ALARM_SOS
        default: return null
    }
}

protocol("bstpl") {

    port 5241

    variant("main") {

        frame readUntil('#')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            int type = m.group(1).toInteger()

            def session = ctx.session(m.group(2))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.addAlarm(decodeAlarm(type))

            pos.valid = m.group(3) == 'A'

            pos.time = new DateBuilder()
                    .setDateReverse(m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
                    .setTime(m.group(7).toInteger(), m.group(8).toInteger(), m.group(9).toInteger())
                    .getDate()

            double lat = m.group(10).toDouble()
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            double lon = m.group(12).toDouble()
            pos.longitude = m.group(13) == 'W' ? -lon : lon

            pos.speed  = UnitsConverter.knotsFromKph(m.group(14).toInteger())
            pos.set(Position.KEY_ODOMETER, m.group(15).toLong() * 1000)
            pos.course = m.group(16).toInteger()
            pos.set(Position.KEY_SATELLITES, m.group(17).toInteger())

            if (m.group(18) != null) {
                // variant 1
                boolean boxOpen = m.group(18) == '1'
                if (type == 8 && boxOpen) pos.addAlarm(Position.ALARM_TAMPERING)
                pos.set("boxOpen", boxOpen)

                pos.set(Position.KEY_RSSI, m.group(19).toInteger())

                boolean charge = m.group(20) == '1'
                if (type == 3) pos.addAlarm(charge ? Position.ALARM_POWER_RESTORED : Position.ALARM_POWER_CUT)
                pos.set(Position.KEY_CHARGE,   charge)
                pos.set(Position.KEY_IGNITION, m.group(21) == '1')
                pos.set("engine",              m.group(22) == '1')
                pos.set(Position.KEY_BLOCKED,  m.group(23) == '1')
                pos.set(Position.PREFIX_ADC + 1, m.group(24).toDouble())
                pos.set(Position.KEY_BATTERY,    m.group(25).toDouble())
                pos.set(Position.KEY_VERSION_FW, m.group(26))
                pos.set(Position.KEY_ICCID,      m.group(27))
                pos.set(Position.KEY_POWER,      m.group(28).toDouble())
            } else {
                // variant 2
                pos.set(Position.KEY_RSSI,    m.group(29).toInteger())
                pos.set("boxOpen",            m.group(30) == '1')
                pos.set(Position.KEY_BATTERY, m.group(31).toDouble())
                pos.set(Position.KEY_CHARGE,  m.group(32) == '1')
                pos.set(Position.KEY_IGNITION, m.group(33) == '1')
                pos.set(Position.KEY_POWER,    m.group(34).toDouble())
                pos.altitude = m.group(35).toInteger()
                pos.set(Position.KEY_VERSION_FW, m.group(36))
                pos.set(Position.KEY_ICCID,      m.group(37))

                pos.set(Position.KEY_RPM,         m.group(38).toInteger())
                pos.set(Position.KEY_OBD_ODOMETER, m.group(39).toInteger())
                pos.set(Position.KEY_FUEL,         m.group(40).toInteger())
                pos.set("gear",                    m.group(41).toInteger())
                pos.set(Position.KEY_ENGINE_TEMP,  m.group(42).toDouble())
                pos.set(Position.KEY_COOLANT_TEMP, m.group(43).toDouble())
            }

            return pos
        }
    }
}
