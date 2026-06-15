// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Watch GPS tracker driver (kids smartwatches, TK/SG/ZJ series).
 *
 * Source documentation:
 *   archived-protocols/watch/ (Java reference)
 *
 * Text TCP protocol on port 5093.
 * Frame: [manufacturer*id*[index*]len*type[,content]]
 *   Brackets are balanced-counted for framing.
 *   Escape sequences: }01→}, }02→[, }03→], }04→,, }05→*
 *
 * Message types (device → server):
 *   INIT                         — request init; ACK "INIT,1"
 *   LK[,steps,?,battery]        — heartbeat; ACK "LK", optional position
 *   UD/UD2/UD3/UD_LTE/UD_WCDMA — position data
 *   AL/AL_LTE/AL_*              — alarm + position; ACK "AL"
 *   WT                           — watch position
 *   TKQ / TKQ2                  — voice query; ACK type
 *   PULSE/HEART/BLOOD/BPHRT     — health data (heart rate / blood pressure)
 *   TEMP / btemp2               — temperature
 *   oxygen                       — blood oxygen
 *   img / TK / JXTK             — media (not supported, returns null)
 *
 * Optional index field (hasIndex): detected when the field after id produces
 * 5 `*`-delimited parts and the 4th is exactly 4 hex digits (the length field).
 * Per-channel manufacturer and hasIndex are stored in ctx.store() for ACKs;
 * command encoding defaults to "CS" manufacturer (standard watch default).
 *
 * Supported commands: CUSTOM, POSITION_SINGLE, SOS_NUMBER, ALARM_SOS,
 *   ALARM_BATTERY, REBOOT_DEVICE, POWER_OFF, ALARM_REMOVE, SILENCE_TIME,
 *   ALARM_CLOCK, SET_PHONEBOOK, MESSAGE, POSITION_PERIODIC, SET_TIMEZONE,
 *   SET_INDICATOR
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

def PATTERN_POSITION = Pattern.compile(
        '(\\d{2})(\\d{2})(\\d{2}),' +       // date ddmmyy
        '(\\d{2})(\\d{2})(\\d{2}),' +       // time hhmmss
        '([AV]),' +                           // validity
        ' *(-?\\d+\\.?\\d*),' +              // latitude
        '([NS])?,' +
        ' *(-?\\d+\\.?\\d*),' +              // longitude
        '([EW])?,' +
        '(\\d+\\.?\\d*),' +                  // speed (kph)
        '(\\d+\\.?\\d*),' +                  // course
        '(-?\\d+\\.?\\d*),' +               // altitude
        '(\\d+),' +                          // satellites
        '(\\d+),' +                          // rssi
        '(\\d+),' +                          // battery level
        '(\\d+),' +                          // steps
        '\\d+,' +                            // tumbles (skip)
        '([0-9a-fA-F]+),' +                  // status hex
        '(.*)'                               // cell and wifi data
)

def decodeAlarm = { int status ->
    if (BitUtil.check(status, 0))  return Position.ALARM_LOW_BATTERY
    if (BitUtil.check(status, 1))  return Position.ALARM_GEOFENCE_EXIT
    if (BitUtil.check(status, 2))  return Position.ALARM_GEOFENCE_ENTER
    if (BitUtil.check(status, 14)) return Position.ALARM_POWER_CUT
    if (BitUtil.check(status, 16)) return Position.ALARM_SOS
    if (BitUtil.check(status, 17)) return Position.ALARM_LOW_BATTERY
    if (BitUtil.check(status, 18)) return Position.ALARM_GEOFENCE_EXIT
    if (BitUtil.check(status, 19)) return Position.ALARM_GEOFENCE_ENTER
    if (BitUtil.check(status, 20)) return Position.ALARM_REMOVING
    if (BitUtil.check(status, 21) || BitUtil.check(status, 22)) return Position.ALARM_FALL_DOWN
    return null
}

def hasHexLetter = { String s ->
    for (char c : s.toCharArray()) {
        if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) return true
    }
    return false
}

def decodeNetwork = { String trailing ->
    String[] vals = trailing.split(',', -1)
    if (vals.length == 0) return null

    // Skip parsing when 4th value is a MAC-style hex string (wifi-first format)
    if (vals.length >= 4 && hasHexLetter(vals[3])) return null

    Network network = new Network()
    int idx = 0

    int cellCount = 0
    try { cellCount = vals[idx] as int } catch (ignored) {}
    idx++

    if (cellCount > 0 && idx < vals.length) {
        idx++ // timing advance
        int mcc = (idx < vals.length && vals[idx]) ? (vals[idx] as int) : 0; idx++
        int mnc = (idx < vals.length && vals[idx]) ? (vals[idx] as int) : 0; idx++

        for (int i = 0; i < cellCount; i++) {
            if (idx + 2 >= vals.length) break
            String lacStr = vals[idx++]
            String cidStr = vals[idx++]
            String rssiStr = (idx < vals.length) ? vals[idx++] : ''
            int lac = hasHexLetter(lacStr) ? Integer.parseInt(lacStr, 16) : (lacStr as int)
            int cid = hasHexLetter(cidStr) ? Integer.parseInt(cidStr, 16) : (cidStr as int)
            if (rssiStr) {
                network.addCellTower(CellTower.from(mcc, mnc, lac, cid, rssiStr as int))
            } else {
                network.addCellTower(CellTower.from(mcc, mnc, lac, cid))
            }
        }
    }

    if (idx < vals.length && vals[idx]) {
        int wifiCount = 0
        try { wifiCount = vals[idx] as int } catch (ignored) {}
        idx++
        for (int i = 0; i < wifiCount; i++) {
            if (idx + 2 >= vals.length) break
            idx++ // wifi name
            String mac  = (idx < vals.length) ? vals[idx++] : ''
            String rssi = (idx < vals.length) ? vals[idx++] : ''
            if (mac && mac != '0' && rssi) {
                network.addWifiAccessPoint(WifiAccessPoint.from(mac, rssi as int))
            }
        }
    }

    return (network.cellTowers || network.wifiAccessPoints) ? network : null
}

def decodePosition = { String data, session, ctx ->
    Matcher m = PATTERN_POSITION.matcher(data)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    int day   = m.group(1) as int
    int month = m.group(2) as int
    int year  = 2000 + (m.group(3) as int)
    int hour  = m.group(4) as int
    int min   = m.group(5) as int
    int sec   = m.group(6) as int
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    cal.set(year, month - 1, day, hour, min, sec)
    cal.set(Calendar.MILLISECOND, 0)
    pos.time = cal.getTime()

    pos.valid = m.group(7) == 'A'

    double lat = m.group(8) as double
    String latHem = m.group(9) ?: 'N'
    if (lat >= 0 && latHem == 'S') lat = -lat
    pos.latitude = lat

    double lon = m.group(10) as double
    String lonHem = m.group(11) ?: 'E'
    if (lon >= 0 && lonHem == 'W') lon = -lon
    pos.longitude = lon

    pos.speed    = UnitsConverter.knotsFromKph(m.group(12) as double)
    pos.course   = m.group(13) as double
    pos.altitude = m.group(14) as double

    pos.set(Position.KEY_SATELLITES,    m.group(15) as int)
    pos.set(Position.KEY_RSSI,          m.group(16) as int)
    pos.set(Position.KEY_BATTERY_LEVEL, m.group(17) as int)
    pos.set(Position.KEY_STEPS,         m.group(18) as int)

    int status = Integer.parseInt(m.group(19), 16)
    String alarm = decodeAlarm(status)
    if (alarm) pos.set(Position.KEY_ALARM, alarm)
    if (BitUtil.check(status, 4)) pos.set(Position.KEY_MOTION, true)

    String trailing = m.group(20)
    if (trailing) {
        Network net = decodeNetwork(trailing)
        if (net) pos.network = net
    }

    return pos
}

protocol("watch") {

    port 5093

    variant("main") {

        frame scriptedFrame { fb ->
            int brackets = 0
            for (int i = fb.readerIndex(); i < fb.writerIndex(); i++) {
                byte b = fb.getByte(i)
                if (b == 0x5B) brackets++       // '['
                else if (b == 0x5D) brackets--  // ']'
                if (brackets == 0 && i > fb.readerIndex()) {
                    return i + 1 - fb.readerIndex()
                }
            }
            return null
        }

        matches { msg -> msg.toString().startsWith('[') }

        commands TYPE_CUSTOM, TYPE_POSITION_SINGLE, TYPE_SOS_NUMBER,
                 TYPE_ALARM_SOS, TYPE_ALARM_BATTERY, TYPE_REBOOT_DEVICE,
                 TYPE_POWER_OFF, TYPE_ALARM_REMOVE, TYPE_SILENCE_TIME,
                 TYPE_ALARM_CLOCK, TYPE_SET_PHONEBOOK, TYPE_MESSAGE,
                 TYPE_POSITION_PERIODIC, TYPE_SET_TIMEZONE, TYPE_SET_INDICATOR

        encode { cmd, ctx ->
            // Manufacturer defaults to "CS" (Java Watch default when no pipeline state)
            String uniqueId = ctx.deviceId()

            String content = null
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    content = cmd.attributes['data']?.toString()
                    break
                case TYPE_POSITION_SINGLE:
                    content = 'CR'
                    break
                case TYPE_SOS_NUMBER:
                    content = "SOS${cmd.attributes['index']},${cmd.attributes['phone']}"
                    break
                case TYPE_ALARM_SOS:
                    content = "SOSSMS,${cmd.attributes['enable'] ? '1' : '0'}"
                    break
                case TYPE_ALARM_BATTERY:
                    content = "LOWBAT,${cmd.attributes['enable'] ? '1' : '0'}"
                    break
                case TYPE_REBOOT_DEVICE:
                    content = 'RESET'
                    break
                case TYPE_POWER_OFF:
                    content = 'POWEROFF'
                    break
                case TYPE_ALARM_REMOVE:
                    content = "REMOVE,${cmd.attributes['enable'] ? '1' : '0'}"
                    break
                case TYPE_SILENCE_TIME:
                    content = "SILENCETIME,${cmd.attributes['data']}"
                    break
                case TYPE_ALARM_CLOCK:
                    content = "REMIND,${cmd.attributes['data']}"
                    break
                case TYPE_SET_PHONEBOOK:
                    content = "PHB,${cmd.attributes['data']}"
                    break
                case TYPE_MESSAGE:
                    byte[] msgBytes = cmd.attributes['message'].toString()
                            .getBytes(StandardCharsets.UTF_16BE)
                    String hex = msgBytes.collect { String.format('%02x', it & 0xff) }.join('')
                    content = "MESSAGE,${hex}"
                    break
                case TYPE_POSITION_PERIODIC:
                    content = "UPLOAD,${cmd.attributes['frequency']}"
                    break
                case TYPE_SET_TIMEZONE:
                    String tzId = cmd.attributes['timezone']?.toString() ?: 'UTC'
                    TimeZone tz = TimeZone.getTimeZone(tzId)
                    double offsetHours = tz.getRawOffset() / 3600000.0d
                    DecimalFormat fmt = new DecimalFormat(
                            '+#.##;-#.##', DecimalFormatSymbols.getInstance(Locale.US))
                    String tzStr = fmt.format(offsetHours)
                    String lang  = cmd.attributes['language']?.toString() ?: ''
                    content = "LZ,${lang},${tzStr}"
                    break
                case TYPE_SET_INDICATOR:
                    content = "FLOWER,${cmd.attributes['data']}"
                    break
                default:
                    return null
            }
            if (!content) return null

            return "[CS*${uniqueId}*${String.format('%04x', content.length())}*${content}]"
                    .getBytes(StandardCharsets.US_ASCII)
        }

        decode { msg, ctx ->
            // Convert frame bytes to string; scriptedFrame yields BufReader
            String raw = (msg instanceof BufReader)
                    ? msg.readString(msg.remaining())
                    : msg.toString()

            // Apply Watch escape sequences
            raw = raw.replace('}05', '*').replace('}04', ',')
                     .replace('}03', ']').replace('}02', '[').replace('}01', '}')

            if (!raw.startsWith('[') || !raw.endsWith(']')) return null
            String inner = raw[1..-2]   // strip '[' and ']'

            // Split into at most 5 fields (manufacturer, id, [index,] len, type+content)
            String[] parts = inner.split('\\*', 5)
            if (parts.length < 4) return null

            String manufacturer = parts[0]
            String deviceId     = parts[1]

            // Detect optional index: present when we get 5 parts and parts[3] is 4 hex digits
            boolean hasIndex      = parts.length == 5 && (parts[3] ==~ /[0-9a-fA-F]{4}/)
            String indexField     = hasIndex ? parts[2] : null
            String typeAndContent = hasIndex ? parts[4] : parts[3]

            String type, content
            int commaIdx = typeAndContent.indexOf(',')
            if (commaIdx >= 0) {
                type    = typeAndContent[0..<commaIdx]
                content = typeAndContent[(commaIdx + 1)..-1]
            } else {
                type    = typeAndContent
                content = ''
            }

            def session = ctx.session(deviceId)
            if (!session) return null

            // Store per-channel state for ACK formatting
            ctx.store()['manufacturer'] = manufacturer
            ctx.store()['hasIndex']     = hasIndex
            ctx.store()['indexField']   = indexField

            def buildAckFrame = { String ackContent ->
                String stored_mfr = ctx.store()['manufacturer'] ?: manufacturer
                String stored_idx = ctx.store()['indexField']
                if (stored_idx) {
                    "[${stored_mfr}*${deviceId}*${stored_idx}*${String.format('%04x', ackContent.length())}*${ackContent}]"
                } else {
                    "[${stored_mfr}*${deviceId}*${String.format('%04x', ackContent.length())}*${ackContent}]"
                }
            }

            def sendAck = { String ackContent ->
                ctx.ack(buildAckFrame(ackContent).getBytes(StandardCharsets.US_ASCII))
            }

            if (type == 'INIT') {

                sendAck('INIT,1')
                return null

            } else if (type == 'LK') {

                sendAck('LK')
                if (content) {
                    String[] vals = content.split(',', -1)
                    if (vals.length >= 3) {
                        def pos = ctx.newPosition()
                        pos.deviceId = session.deviceId
                        ctx.lastLocation(pos)
                        try { pos.set(Position.KEY_STEPS,         vals[0] as int) } catch (ignored) {}
                        try { pos.set(Position.KEY_BATTERY_LEVEL, vals[2] as int) } catch (ignored) {}
                        return pos
                    }
                }
                return null

            } else if (type.startsWith('UD') || type.startsWith('AL') || type.startsWith('WT')) {

                def pos = decodePosition(content, session, ctx)
                if (type.startsWith('AL')) {
                    if (pos && !pos.attributes.containsKey(Position.KEY_ALARM)) {
                        pos.set(Position.KEY_ALARM, Position.ALARM_GENERAL)
                    }
                    sendAck('AL')
                }
                return pos

            } else if (type == 'TKQ' || type == 'TKQ2') {

                sendAck(type)
                return null

            } else if (type.equalsIgnoreCase('PULSE') || type.equalsIgnoreCase('HEART')
                    || type.equalsIgnoreCase('BLOOD') || type.equalsIgnoreCase('BPHRT')
                    || type.equalsIgnoreCase('TEMP')  || type.equalsIgnoreCase('btemp2')
                    || type.equalsIgnoreCase('oxygen')) {

                if (content) {
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos, new Date())
                    String[] vals = content.split(',', -1)
                    int vi = 0
                    String tl = type.toLowerCase()
                    if (tl == 'temp') {
                        pos.set(Position.PREFIX_TEMP + 1, vals[vi] as double)
                    } else if (tl == 'btemp2') {
                        if (vi < vals.length && (vals[vi++] as int) > 0) {
                            if (vi < vals.length) pos.set(Position.PREFIX_TEMP + 1, vals[vi] as double)
                        }
                    } else if (tl == 'oxygen') {
                        vi++
                        if (vi < vals.length) pos.set('bloodOxygen', vals[vi] as int)
                    } else {
                        if (tl == 'bphrt' || tl == 'blood') {
                            if (vi < vals.length) pos.set('pressureHigh', vals[vi++])
                            if (vi < vals.length) pos.set('pressureLow', vals[vi++])
                        }
                        if (vi < vals.length) {
                            try { pos.set(Position.KEY_HEART_RATE, vals[vi] as int) } catch (ignored) {}
                        }
                    }
                    return pos
                }
                return null

            } else if (type == 'img' || type == 'TK' || type == 'JXTK') {

                // Media types require file storage — not supported in driver DSL
                if (type == 'JXTK') sendAck('JXTKR,1')
                return null

            }

            return null
        }
    }
}
