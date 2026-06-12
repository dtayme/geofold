// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Laipac GPS tracker driver (AVL100, AVL110, AVL120, SF-Lite and compatible).
 *
 * Three message types (newline-terminated):
 *
 *   $ECHK,...         — echo heartbeat (echo back as-is)
 *   $EAVSYS,...*xx    — device info (ICCID, phone, firmware)
 *   $AVRMC,...*xx     — position report with alarms, sensors, optional cell tower
 *
 * Encoder note: device password defaults to "00000000". Set the `password`
 * attribute on the device in Traccar to use a per-device password; the encoder
 * reads it via ctx.devicePassword('00000000').
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.regex.Pattern

def EAVSYS = Pattern.compile(
    /^\$EAVSYS,([^,]+),([0-9]+),(\+?[0-9]+)?,(?:[^,]*),([^,]*)\*([0-9a-fA-F]{2})/)

def AVRMC = Pattern.compile(
    /^\$AVRMC,([^,]+),(\d{2})(\d{2})(\d{2}),([AVRPavrp]),(\d{2})(\d{2}\.[\d]+),([NS]),(\d{3})(\d{2}\.[\d]+),([EW]),(\d+\.[\d]+),(\d+\.[\d]+),(\d{2})(\d{2})(\d{2}),([0-9A-Za-z]),([\d.]+),(\d+),(\d),(\d+),(\d+)(?:,([0-9a-fA-F]{1,4})([0-9a-fA-F]{4}),(\d{1,3})(\d{3}))?(?:,[^*]*)?\*([0-9a-fA-F]{2})/)

// NMEA XOR checksum for response messages
def nmea = { String body ->
    int cs = 0
    body.each { c -> cs ^= (c as char) as int }
    String.format('*%02X\r\n', cs)
}

// Alarm lookup by event character
def decodeAlarm = { String event ->
    switch (event) {
        case 'Z': return ALARM_LOW_BATTERY
        case 'Y': return ALARM_TOW
        case 'X': return ALARM_GEOFENCE_ENTER
        case 'T': return ALARM_TAMPERING
        case 'H': return ALARM_POWER_OFF
        case '8': return ALARM_VIBRATION
        case '7': case '4': return ALARM_GEOFENCE_EXIT
        case '6': return ALARM_OVERSPEED
        case '5': return ALARM_POWER_CUT
        case '3': return ALARM_SOS
        default:  return null
    }
}

// Send event acknowledgement for events that require device confirmation
def sendEventResponse = { String event, String password, ctx ->
    String code = null
    switch (event) {
        case '3': code = 'd'; break
        case 'M': code = 'm'; break
        case 'S': case 'T': code = 't'; break
        case 'X': case '4': code = 'x'; break
        case 'Y': code = 'y'; break
        case 'Z': code = 'z'; break
    }
    if (code) {
        String body = "AVCFG,${password},${code}"
        ctx.ack("\$${body}${nmea(body)}")
    }
}

protocol("laipac") {

    port 5003

    variant("main") {

        // Longest message: $AVRMC with optional cell tower, ~250 bytes.
        maxFrameLength 512
        frame readLine()

        matches { msg -> msg.startsWith('$ECHK') || msg.startsWith('$EAVSYS') || msg.startsWith('$AVRMC') }

        decode { msg, ctx ->

            // --- ECHK heartbeat: echo back and return nothing ---
            if (msg.startsWith('$ECHK')) {
                ctx.ack(msg + '\r\n')
                return null
            }

            // --- EAVSYS device info ---
            if (msg.startsWith('$EAVSYS')) {
                def m = EAVSYS.matcher(msg)
                if (!m.find()) return null
                def session = ctx.session(m.group(1))
                if (!session) return null
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_ICCID,      m.group(2))
                pos.set(Position.KEY_PHONE,       m.group(3))
                pos.set(Position.KEY_VERSION_FW,  m.group(4))
                return pos
            }

            // --- AVRMC position report ---
            def m = AVRMC.matcher(msg)
            if (!m.find()) return null

            def imei = m.group(1)
            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def db = new DateBuilder()
                    .setTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())

            String status = m.group(5)
            String upperStatus = status.toUpperCase(Locale.ROOT)
            pos.valid = upperStatus == 'A' || upperStatus == 'R' || upperStatus == 'P'
            pos.set(Position.KEY_STATUS, status)

            // NMEA ddmm.mmmm → decimal degrees
            pos.latitude  = m.group(6).toInteger()  + m.group(7).toDouble()  / 60.0
            if (m.group(8)  == 'S') pos.latitude  = -pos.latitude
            pos.longitude = m.group(9).toInteger()  + m.group(10).toDouble() / 60.0
            if (m.group(11) == 'W') pos.longitude = -pos.longitude

            pos.speed  = m.group(12).toDouble()
            pos.course = m.group(13).toDouble()

            db.setDateReverse(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
            pos.time = db.getDate()

            String event = m.group(17)
            pos.addAlarm(decodeAlarm(event))

            // Event codes A-D and O-R represent digital input states
            if (event.length() == 1) {
                char ec = event.charAt(0)
                if (ec >= 'A' && ec <= 'D') {
                    int v = ec - 'A'
                    pos.set(Position.PREFIX_IN + '1', (v & 1) != 0)
                    pos.set(Position.PREFIX_IN + '2', (v & 2) != 0)
                } else if (ec >= 'O' && ec <= 'R') {
                    int v = ec - 'O'
                    pos.set(Position.PREFIX_IN + '1', (v & 1) != 0)
                    pos.set(Position.PREFIX_IN + '2', (v & 2) != 0)
                } else {
                    pos.set(Position.KEY_EVENT, event)
                }
            } else {
                pos.set(Position.KEY_EVENT, event)
            }

            // Battery: device sends "37.00" → strip '.' → "3700" / 1000.0 = 3.7V
            String battStr = m.group(18)
            pos.set(Position.KEY_BATTERY, battStr.replaceAll(/\./, '').toLong() / 1000.0)

            pos.set(Position.KEY_ODOMETER, m.group(19).toLong() * 1000)
            pos.set(Position.KEY_GPS,      m.group(20).toInteger())
            pos.set(Position.PREFIX_ADC + '1', m.group(21).toInteger() / 1000.0)
            pos.set(Position.PREFIX_ADC + '2', m.group(22).toInteger() / 1000.0)

            // Optional cell tower
            if (m.group(23)) {
                int lac = Integer.parseInt(m.group(23), 16)
                int cid = Integer.parseInt(m.group(24), 16)
                int mcc = m.group(25).toInteger()
                int mnc = m.group(26).toInteger()
                if (lac != 0 || cid != 0) {
                    pos.network = new Network(CellTower.from(mcc, mnc, lac, cid))
                }
            }

            // ACK if status is lowercase (device expects acknowledgement)
            if (Character.isLowerCase(status.charAt(0))) {
                String checksum = m.group(27)
                String body = "EAVACK,${event},${checksum}"
                ctx.ack("\$${body}${nmea(body)}")
            }

            // Event response (alarm confirmation) — uses per-device password if set
            String password = ctx.deviceAttrs(session).password('00000000')
            sendEventResponse(event, password, ctx)

            return pos
        }

        encode { cmd, ctx ->
            def id  = ctx.deviceId()
            def pwd = ctx.devicePassword('00000000')

            def build = { String body ->
                int cs = 0
                body.each { c -> cs ^= (c as char) as int }
                "\$${body}${String.format('*%02X\r\n', cs)}"
            }

            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return build(ctx.data())
                case TYPE_POSITION_SINGLE:
                    return build("AVREQ,${pwd},1")
                case TYPE_REBOOT_DEVICE:
                    return build("AVRESET,${id},${pwd}")
                default:
                    return null
            }
        }
    }
}
