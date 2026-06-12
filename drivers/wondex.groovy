// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Wondex GPS tracker driver.
 *
 * Two frame types on a single port (5032):
 *
 *   Binary keep-alive (0xD0 first byte, exactly 8 bytes):
 *     Identifies the device; device ID is extracted from bytes 0-7.
 *     Server must echo the 8 bytes back to the device.
 *
 *   Text position (CRLF-terminated):
 *     <id>,<yyyyMMddHHmmss>,<lon>,<lat>,<speed>,<course>,<alt>,<sats>,<event>
 *       [,<battery>V][,<odometer>][,<input>][,<adc1>][,<adc2>][,<output>]
 *     All fields after event are optional.
 *     Speed is in km/h.
 *
 *   Text command response (CRLF-terminated):
 *     $OK:<value>  /  $ERR:<value>  /  $MSG:<value>
 *     No device ID — uses the existing channel session.
 *
 * Device ID calculation from 8-byte keep-alive:
 *   Interpret bytes 0-7 as big-endian long, reverse byte order,
 *   then take the upper 32 bits as an unsigned integer.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    /^[^\d]*(\d+),(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(-?[\d.]+),(-?[\d.]+),(\d+),(\d+),(-?[\d.]+),(\d+),(\d+),?(?:([\d.]+)V,)?([\d.]+)?,?(\d+)?,?([\d.]+)?,?([\d.]+)?,?(\d+)?/)

protocol("wondex") {

    port 5032

    // ------------------------------------------------------------------
    // Binary keep-alive: 8 bytes, first byte 0xD0
    // ------------------------------------------------------------------
    variant("keepalive") {

        maxFrameLength 8
        frame 0xD0 as byte, readFixed(8)

        decode { buf, ctx ->
            def bytes = buf.readBytes(8)

            // Reconstruct the big-endian long from the 8 bytes
            long val = 0
            for (int i = 0; i < 8; i++) val = (val << 8) | (bytes[i] & 0xFF)

            // Java: ((Long.reverseBytes(val)) >> 32) & 0xFFFFFFFFL
            long reversed = Long.reverseBytes(val)
            long deviceId = (reversed >> 32) & 0xFFFFFFFFL

            // Register the device session
            ctx.session(String.valueOf(deviceId))

            // Echo the keep-alive back to the device
            ctx.ack(bytes)

            return null
        }
    }

    // ------------------------------------------------------------------
    // Text: position messages and command responses
    // ------------------------------------------------------------------
    variant("text") {

        maxFrameLength 512
        frame readLine()

        decode { msg, ctx ->

            // Command response — look up existing channel session
            if (msg.startsWith('$OK:') || msg.startsWith('$ERR:') || msg.startsWith('$MSG:')) {
                def session = ctx.session()
                if (!session) return null
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_RESULT, msg)
                return pos
            }

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

            pos.longitude = m.group(8).toDouble()
            pos.latitude  = m.group(9).toDouble()
            pos.speed     = UnitsConverter.knotsFromKph(m.group(10).toDouble())
            pos.course    = m.group(11).toDouble()
            pos.altitude  = m.group(12).toDouble()

            int sats = m.group(13).toInteger()
            pos.valid = sats != 0
            pos.set(Position.KEY_SATELLITES, sats)

            pos.set(Position.KEY_EVENT, m.group(14))

            if (m.group(15) != null) {
                pos.set(Position.KEY_BATTERY, m.group(15).toDouble())
            }
            if (m.group(16) != null) {
                pos.set(Position.KEY_ODOMETER, m.group(16).toDouble() * 1000)
            }
            if (m.group(17) != null) {
                pos.set(Position.KEY_INPUT, m.group(17))
            }
            if (m.group(18) != null) {
                pos.set(Position.PREFIX_ADC + 1, m.group(18))
            }
            if (m.group(19) != null) {
                pos.set(Position.PREFIX_ADC + 2, m.group(19))
            }
            if (m.group(20) != null) {
                pos.set(Position.KEY_OUTPUT, m.group(20))
            }

            return pos
        }
    }
}
