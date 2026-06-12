/**
 * Pretrace GPS tracker driver.
 *
 * ')'-terminated text frames. Message format:
 *   (<imei>U<type><gps>[AV]<yymmdd><hhmmss><lat><NS><lon><EW><speed><course><altHex><odoHex><satHex><hdop><rssi><state>,&[data]^<xx>)
 *
 * Coordinates in DEG_MIN format. Optional trailing data section: comma-separated
 * tokens starting with P (power/battery), T (temperature), F (fuel), R (driver ID).
 * Encoder wraps commands as "(<id><data>^<xorChecksum>)".
 */

import org.traccar.helper.Checksum
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^\((\d{15})U\d{3}\d([AV])(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}\.\d{4})([NS])(\d{3})(\d{2}\.\d{4})([EW])(\d{3})(\d{3})([0-9a-fA-F]{3})([0-9a-fA-F]{8})([0-9a-fA-F])(\d{2})(\d{2})(.{8}),&(.+)?\^[0-9a-fA-F]{2}/)

protocol("pretrace") {

    port 5133

    variant("main") {

        frame readUntil(')')

        decode { msg, ctx ->
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.valid = m.group(2) == 'A'

            pos.time = new DateBuilder()
                    .setDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger())
                    .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
                    .getDate()

            double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60.0
            pos.latitude = m.group(11) == 'S' ? -lat : lat

            double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60.0
            pos.longitude = m.group(14) == 'W' ? -lon : lon

            pos.speed    = UnitsConverter.knotsFromKph(m.group(15).toInteger())
            pos.course   = m.group(16).toInteger()
            pos.altitude = Integer.parseInt(m.group(17), 16)

            pos.set(Position.KEY_ODOMETER,   Integer.parseInt(m.group(18), 16))
            pos.set(Position.KEY_SATELLITES, Integer.parseInt(m.group(19), 16))
            pos.set(Position.KEY_HDOP,       m.group(20).toInteger())
            pos.set(Position.KEY_RSSI,       m.group(21).toInteger())

            if (m.group(23) != null) {
                for (String item : m.group(23).split(",")) {
                    if (item.isEmpty()) continue
                    switch (item[0]) {
                        case 'P':
                            if (item[1] == '1') {
                                if (item.length() >= 5 && item[4] == '%') {
                                    pos.set(Position.KEY_BATTERY_LEVEL, item[2..3].toInteger())
                                } else {
                                    pos.set(Position.KEY_BATTERY, Integer.parseInt(item.substring(2), 16) / 100.0)
                                }
                            } else {
                                pos.set(Position.KEY_POWER, Integer.parseInt(item.substring(2), 16) / 100.0)
                            }
                            break
                        case 'T':
                            double temperature = Integer.parseInt(item.substring(2), 16) * 0.25
                            if (item[1] == '1') {
                                pos.set(Position.KEY_DEVICE_TEMP, temperature)
                            } else {
                                pos.set(Position.PREFIX_TEMP + item[1].toInteger(), temperature)
                            }
                            break
                        case 'F':
                            pos.set('fuel' + item[1].toInteger(), Integer.parseInt(item.substring(2), 16) / 100.0)
                            break
                        case 'R':
                            pos.set(Position.KEY_DRIVER_UNIQUE_ID, item.substring(3))
                            break
                    }
                }
            }

            return pos
        }

        encode { cmd, ctx ->
            def uniqueId = ctx.deviceId()
            String content
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    content = uniqueId + ctx.data()
                    break
                case TYPE_POSITION_PERIODIC:
                    def freq = ctx.freq()
                    content = uniqueId + "D221${freq},${freq},,"
                    break
                default:
                    return null
            }
            return String.format("(%s^%02X)", content, Checksum.xor(content))
        }
    }
}
