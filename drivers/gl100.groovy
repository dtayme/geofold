/**
 * Queclink GL100 / GL200-family driver (text variant).
 *
 * Handles the +RESP:GT* position reports sent by GL100, GL200, and compatible
 * Queclink trackers using the text protocol.
 *
 * Frame format (newline-terminated):
 *   +RESP:GT<type>,<imei>,{<num>,<reserved>,<value> | <phone>},<fix>,<speed>,<course>,<altitude>,<accuracy>,<lon>,<lat>,<yyyyMMddHHmmss>,...
 *
 * Heartbeat: AT+GTHBD=<tag>,<imei>,... → respond with +RESP:GTHBD,GPRS ACTIVE,<tag>,<imei>,...\0
 *
 * GPS validity: 0 = valid, 1 = invalid (inverted from typical).
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

// Two alternative field groups follow the IMEI:
//   numeric: <number>,<reserved>,<value>  (e.g. "1,0,0")
//   phone:   <phone_number>               (e.g. "02132523415" or empty)
def PATTERN = Pattern.compile(
    /^\+RESP:GT[^,]+,(\d{15}),(?:\d+,\d,\d+|[^,]*),([01]),([\d.]+),(\d+),(-?[\d.]+),[\d.]*,(-?[\d.]+),(-?[\d.]+),(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),/)

protocol("gl100") {

    port 5003

    variant("main") {

        maxFrameLength 512
        frame readLine()

        matches { msg -> msg.startsWith('+RESP:GT') || msg.startsWith('AT+GTHBD=') }

        decode { msg, ctx ->

            // Heartbeat — respond and return no position
            if (msg.startsWith('AT+GTHBD=')) {
                // Response mirrors everything between 'AT+GTHBD=' and the last comma
                def body = msg.substring(9, msg.lastIndexOf(','))
                ctx.ack("+RESP:GTHBD,GPRS ACTIVE,${body}\0")
                return null
            }

            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def session = ctx.session(m.group(1))
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            // 0 = valid fix, 1 = no fix (inverted)
            pos.valid  = m.group(2) == '0'
            pos.speed  = m.group(3).toDouble()
            pos.course = m.group(4).toDouble()
            pos.altitude  = m.group(5).toDouble()
            pos.longitude = m.group(6).toDouble()
            pos.latitude  = m.group(7).toDouble()

            pos.time = new DateBuilder()
                    .setDate(m.group(8).toInteger(), m.group(9).toInteger(), m.group(10).toInteger())
                    .setTime(m.group(11).toInteger(), m.group(12).toInteger(), m.group(13).toInteger())
                    .getDate()

            return pos
        }
    }
}
