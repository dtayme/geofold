// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Wialon IPS / Wialon Retranslator driver.
 *
 * Newline-delimited text frames. Message structure:
 *   [counter.version;][imei]#type#data
 *
 * Types:
 *   L  — login (data: [version;]imei[;password][;checksum])
 *   P  — ping (heartbeat)
 *   D  — data (full position record)
 *   SD — short data (position without extended fields)
 *   B  — batch (pipe-separated D records, all marked KEY_ARCHIVE)
 *   M  — message (command response, no GPS)
 *
 * ACK format: #A<type>#[number]\r\n
 * IMEI is stored in ctx.store() after a successful L message so that
 * subsequent D/SD/B/M frames that omit the outer imei field can still
 * look it up.
 */

import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.helper.UnitsConverter

import java.util.Locale
import java.util.regex.Pattern

// Full position pattern with optional extended block.
// Groups 1-3: date ddmmyy; 4-6: time HHmmss; 7-8: lat deg+min; 9: N/S;
// 10-11: lon deg+min; 12: E/W; 13: speed; 14: course; 15: altitude; 16: sats;
// 17: hdop; 18: inputs; 19: outputs; 20: adcs; 21: ibutton; 22: params
def PATTERN = Pattern.compile(
        /(?:NA|(\d{2})(\d{2})(\d{2}));/
        + /(?:NA|(\d{2})(\d{2})(\d{2}));/
        + /(?:NA|(\d+)(\d{2}\.\d+));/
        + /(?:NA|([NS]));/
        + /(?:NA|(\d+)(\d{2}\.\d+));/
        + /(?:NA|([EW]));/
        + /(?:NA|(\d+\.?\d*))?;/
        + /(?:NA|(\d+\.?\d*))?;/
        + /(?:NA|(-?\d+\.?\d*));/
        + /(?:NA|(\d+))/
        + /(?:;(?:NA|(\d+\.?\d*));/
        + /(?:NA|(\d+));/
        + /(?:NA|(\d+));/
        + /(?:NA|([^;]*));/
        + /(?:NA|([^;]*));/
        + /(?:NA|([^;]*)))?.*/)

def PATTERN_PARAM = Pattern.compile(/(.*):[1-3]:(.*)/)

def decodeCellData = { Position pos, Network network, String suffix ->
    if (pos.hasAttribute('mnc' + suffix) && pos.hasAttribute('mcc' + suffix)
            && pos.hasAttribute('lac' + suffix) && pos.hasAttribute('cell_id' + suffix)) {
        network.addCellTower(CellTower.from(
                pos.removeInteger('mcc' + suffix),
                pos.removeInteger('mnc' + suffix),
                pos.removeInteger('lac' + suffix),
                (long) pos.getLong('cell_id' + suffix)))
        return true
    }
    return false
}

def decodePosition = { String data, session, ctx, pattern, paramPattern ->
    def m = pattern.matcher(data)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    Date posTime
    if (m.group(1) != null) {
        def cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
        cal.set(2000 + m.group(3).toInteger(),
                m.group(2).toInteger() - 1,
                m.group(1).toInteger(),
                m.group(4).toInteger(),
                m.group(5).toInteger(),
                m.group(6).toInteger())
        cal.set(Calendar.MILLISECOND, 0)
        posTime = cal.time
    } else {
        posTime = new Date()
    }
    pos.time = posTime

    if (m.group(7) != null) {
        double lat = m.group(7).toDouble() + m.group(8).toDouble() / 60.0
        pos.latitude = 'S' == m.group(9) ? -lat : lat
        double lon = m.group(10).toDouble() + m.group(11).toDouble() / 60.0
        pos.longitude = 'W' == m.group(12) ? -lon : lon
        pos.speed = UnitsConverter.knotsFromKph(m.group(13) != null ? m.group(13).toDouble() : 0.0)
        pos.course = m.group(14) != null ? m.group(14).toDouble() : 0.0
        pos.altitude = m.group(15) != null ? m.group(15).toDouble() : 0.0
    } else {
        ctx.lastLocation(pos, posTime)
    }

    if (m.group(16) != null) {
        int sats = m.group(16).toInteger()
        pos.valid = sats >= 3
        pos.set(Position.KEY_SATELLITES, sats)
    }

    if (m.group(17) != null) pos.set(Position.KEY_HDOP, m.group(17).toDouble())
    if (m.group(18) != null) pos.set(Position.KEY_INPUT, m.group(18))
    if (m.group(19) != null) pos.set(Position.KEY_OUTPUT, m.group(19))

    if (m.group(20) != null && !m.group(20).isEmpty()) {
        def adcs = m.group(20).split(',')
        for (int i = 0; i < adcs.length; i++) {
            pos.set(Position.PREFIX_ADC + (i + 1), adcs[i])
        }
    }

    if (m.group(21) != null && !m.group(21).isEmpty()) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(21))
    }

    if (m.group(22) != null) {
        def network = new Network()
        m.group(22).split(',').each { param ->
            def pm = paramPattern.matcher(param)
            if (pm.matches()) {
                def key = pm.group(1).toLowerCase(Locale.ROOT)
                def val = pm.group(2)
                try {
                    pos.set(key, Double.parseDouble(val))
                } catch (NumberFormatException ignored) {
                    if ('true'.equalsIgnoreCase(val)) pos.set(key, true)
                    else if ('false'.equalsIgnoreCase(val)) pos.set(key, false)
                    else pos.set(key, val)
                }
            }
        }
        if (pos.hasAttribute('accuracy')) pos.accuracy = pos.removeDouble('accuracy')
        if (pos.hasAttribute('bat')) pos.set(Position.KEY_BATTERY_LEVEL, pos.removeInteger('bat'))
        if (pos.hasAttribute('temp')) pos.set(Position.KEY_DEVICE_TEMP, pos.removeInteger('temp'))

        decodeCellData(pos, network, '')
        for (int i = 1; i <= 9; i++) {
            if (!decodeCellData(pos, network, String.valueOf(i))) break
        }
        if (network.cellTowers != null) pos.network = network
    }

    return pos
}

protocol("wialon") {

    port 20332
    commands TYPE_REBOOT_DEVICE, TYPE_SEND_USSD, TYPE_IDENTIFICATION, TYPE_OUTPUT_CONTROL

    variant("main") {

        frame readLine()
        matches { msg -> msg.contains('#') }

        decode { msg, ctx ->
            // Strip optional counter.version; prefix
            def bare = msg.replaceFirst(/^\d+\.\d+;/, '')

            int hash1 = bare.indexOf('#')
            if (hash1 < 0) return null
            int hash2 = bare.indexOf('#', hash1 + 1)

            String outerImei = hash1 > 0 ? bare.substring(0, hash1) : null
            String type = hash2 >= 0 ? bare.substring(hash1 + 1, hash2) : bare.substring(hash1 + 1)
            String data = hash2 >= 0 ? bare.substring(hash2 + 1) : ''

            switch (type) {

                case 'L': {
                    def parts = data.split(';')
                    def loginImei = parts[0].contains('.') ? (parts.length > 1 ? parts[1] : null) : parts[0]
                    if (!loginImei) return null
                    def session = ctx.session(loginImei)
                    if (!session) return null
                    ctx.store().put('imei', loginImei)
                    ctx.ack("#AL#1\r\n")
                    return null
                }

                case 'P': {
                    ctx.ack("#AP#\r\n")
                    return null
                }

                case 'D':
                case 'SD': {
                    def id = outerImei ?: (String) ctx.store().get('imei')
                    if (!id) return null
                    def session = ctx.session(id)
                    if (!session) return null
                    def pos = decodePosition(data, session, ctx, PATTERN, PATTERN_PARAM)
                    if (pos) {
                        ctx.ack("#A${type}#1\r\n")
                        return pos
                    }
                    return null
                }

                case 'B': {
                    def id = outerImei ?: (String) ctx.store().get('imei')
                    if (!id) return null
                    def session = ctx.session(id)
                    if (!session) return null
                    def records = data.split(/\|/)
                    for (String record : records) {
                        def pos = decodePosition(record, session, ctx, PATTERN, PATTERN_PARAM)
                        if (pos) {
                            pos.set(Position.KEY_ARCHIVE, true)
                            ctx.emit(pos)
                        }
                    }
                    ctx.ack("#AB#${records.length}\r\n")
                    return null
                }

                case 'M': {
                    def id = outerImei ?: (String) ctx.store().get('imei')
                    if (!id) return null
                    def session = ctx.session(id)
                    if (!session) return null
                    def pos = ctx.newPosition()
                    pos.deviceId = session.deviceId
                    ctx.lastLocation(pos, new Date())
                    pos.valid = false
                    pos.set(Position.KEY_RESULT, data)
                    ctx.ack("#AM#1\r\n")
                    return pos
                }

                default:
                    return null
            }
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_REBOOT_DEVICE:    return "reboot\r\n"
                case TYPE_SEND_USSD:        return "USSD:${cmd.getString('phone')}\r\n"
                case TYPE_IDENTIFICATION:   return "VER?\r\n"
                case TYPE_OUTPUT_CONTROL:   return "L${cmd.getInteger('index')}=${cmd.getString('data')}\r\n"
                default:                    return null
            }
        }
    }
}
