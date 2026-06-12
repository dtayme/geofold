// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Example driver for the fictional "SimpleTrack" GPS tracker protocol.
 *
 * This file documents every driver DSL feature. Use it as a starting point
 * when adding support for a new device. Delete what you don't need; the only
 * required pieces are protocol(), at least one variant(), matches, and decode.
 *
 * Additional real-world examples (more complex but production-tested):
 *   drivers/mictrack.groovy — two variants on one port, model-aware alarms
 *   drivers/tlt2h.groovy    — batch multi-position frames using ctx.emit()
 *   drivers/h02.groovy      — many message subtypes, status bitmask decoding
 *   drivers/laipac.groovy   — per-device password via ctx.deviceAttrs(session)
 *
 * For binary protocols (non-ASCII start byte, BufReader in decode closure)
 * see the commented skeleton at the bottom of this file and the Binary protocols
 * section of docs/driver-development.md.
 *
 * -----------------------------------------------------------------------
 * Wire protocol (for reference — replace with your device's actual spec)
 * -----------------------------------------------------------------------
 *
 * Device → Server (position report, newline terminated):
 *   $TRACK,<imei>,<hhmmss>,<ddmmyy>,<lat>,<lon>,<speed_kph>,<course>,<event>*<checksum>\r\n
 *
 *   <event>  NORMAL | SOS | LOW_BAT | TOWED | OVERSPD | IGNOFF | IGNON
 *
 * Example:
 *   $TRACK,123456789012345,143022,110624,36.5000,-97.0000,0.0,180.0,NORMAL*2F\r\n
 *
 * Server → Device (acknowledgement):
 *   $ACK,<imei>*<checksum>\r\n
 *
 * Server → Device (commands):
 *   $CMD,<imei>,REBOOT*<checksum>\r\n
 *   $CMD,<imei>,TRACK,<seconds>*<checksum>\r\n
 *   $CMD,<imei>,SERVER,<host>,<port>*<checksum>\r\n
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// Pre-compile the message pattern once at script load time — not inside a
// closure, so it is only compiled once, not on every incoming message.
// ---------------------------------------------------------------------------

// $TRACK,IMEI,HHMMSS,DDMMYY,LAT,LON,SPEED,COURSE,EVENT*CS
def PATTERN = Pattern.compile(
    /^\$TRACK,(\d{15}),(\d{2})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),([-\d.]+),([-\d.]+),([\d.]+),([\d.]+),([A-Z_]+)\*([0-9A-Fa-f]{2})/)

// ---------------------------------------------------------------------------
// Shared helper: compute XOR checksum over the bytes between $ and *
// ---------------------------------------------------------------------------

def checksum = { String msg ->
    int star = msg.lastIndexOf('*')
    if (star < 1) return false
    int expected = Integer.parseInt(msg[(star + 1)..(star + 2)], 16)
    int actual   = msg[1..(star - 1)].bytes.inject(0) { acc, b -> acc ^ b }
    actual == expected
}

// Helper that builds an outgoing message with a valid checksum
def buildCmd = { String body ->
    int cs = body.bytes.inject(0) { acc, b -> acc ^ b }
    "\$${body}*${String.format('%02X', cs)}\r\n"
}

// ---------------------------------------------------------------------------
// Protocol definition
// ---------------------------------------------------------------------------

protocol("example") {

    // Default port — the operator can override this in traccar.xml with:
    //   <entry key='example.port'>5200</entry>
    port 5200

    // -----------------------------------------------------------------------
    // Main variant — handles all SimpleTrack messages.
    //
    // frame readLine()     No frameByteHint here (no hint = fallback/default).
    //                      The '$' could be used as the hint if this driver
    //                      shared a port with another protocol:
    //                        frame '$' as char, readLine()
    // -----------------------------------------------------------------------
    variant("main") {

        // Optional maximum size for one complete incoming frame. If omitted,
        // driver.frameMaxLength from traccar.xml is used.
        maxFrameLength 2048

        // Use newline framing (strip trailing \r).
        frame readLine()

        // Return true for any message this variant should handle.
        // Keep this fast — it runs on every incoming message on this port.
        matches { msg -> msg.startsWith('$TRACK,') }

        // ----------------------------------------------------------------
        // Alarm map
        //
        // Maps the device's event code strings to Traccar alarm constants.
        // The >> operator adds one entry. Both the event code and the alarm
        // constant are strings; the constants are pre-defined in DriverDSL
        // so you don't need to import anything.
        //
        // For model-aware mappings pass a closure instead of a constant:
        //   "DEF" >> { model -> model == "V2" ? ALARM_REMOVING : ALARM_POWER_CUT }
        // ----------------------------------------------------------------
        alarms {
            "SOS"    >> ALARM_SOS
            "LOW_BAT">> ALARM_LOW_BATTERY
            "TOWED"  >> ALARM_TOW
            "OVERSPD">> ALARM_OVERSPEED
        }

        // ----------------------------------------------------------------
        // Decode closure
        //
        // msg — the raw trimmed message string extracted by the frame decoder
        // ctx — DecodeContext: session(id), session(), newPosition(), ack(), alarm(), lastLocation()
        //
        // Return a populated Position or null (null keeps the connection alive
        // but stores nothing — use it for heartbeats and ACK-only messages).
        // ----------------------------------------------------------------
        decode { msg, ctx ->

            // 1. Validate checksum before doing any work
            if (!checksum(msg)) return null

            // 2. Parse the message
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def imei    = m.group(1)
            def hhmmss  = [m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger()]
            def ddmmyy  = [m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger()]
            def lat     = m.group(8).toDouble()
            def lon     = m.group(9).toDouble()
            def speed   = m.group(10).toDouble()
            def course  = m.group(11).toDouble()
            def event   = m.group(12)

            // 3. Resolve the device session (returns null if IMEI is unknown/blocked)
            //    ctx.session(imei)  — look up/register by device ID (most messages)
            //    ctx.session()      — look up existing channel session (follow-up messages
            //                        with no device ID, e.g. command responses)
            def session = ctx.session(imei)
            if (!session) return null

            // 4. Send acknowledgement back to the device
            ctx.ack(buildCmd("ACK,${imei}"))

            // 5. Build the position
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            // Timestamp from device — DateBuilder handles century (yy → yyyy)
            pos.time = new DateBuilder()
                    .setTime(hhmmss[0], hhmmss[1], hhmmss[2])
                    .setDateReverse(ddmmyy[0], ddmmyy[1], ddmmyy[2])
                    .getDate()

            // Coordinates — already in decimal degrees for this protocol
            pos.valid     = true
            pos.latitude  = lat
            pos.longitude = lon

            // Speed: device sends km/h; Traccar stores knots
            pos.speed  = UnitsConverter.knotsFromKph(speed)
            pos.course = course

            // Alarm — resolved from the alarms{} block above
            pos.addAlarm(ctx.alarm(event))

            // Ignition state from the event code (these events carry no alarm)
            if (event == 'IGNOFF') pos.set(Position.KEY_IGNITION, false)
            if (event == 'IGNON') pos.set(Position.KEY_IGNITION, true)

            // SimpleTrack sends one fix per frame, so we return it directly.
            // For protocols that batch multiple fixes in one frame, call
            // ctx.emit(pos) for each record and return null at the end.
            // See drivers/tlt2h.groovy for a real example of that pattern.
            return pos
        }

        // ----------------------------------------------------------------
        // Encode closure
        //
        // cmd — the Command object from Traccar (cmd.type, cmd.attributes)
        // ctx — EncodeContext: deviceId(), utcTime(), freq(), server(), port(),
        //       data(), clamp(), devicePassword(), deviceModel(), deviceAttrs()
        //
        // Return the string (or byte[]) to send to the device, or null if
        // the command type is not supported.
        // ----------------------------------------------------------------
        encode { cmd, ctx ->
            def id = ctx.deviceId()

            // Per-device password — reads the 'password' attribute from the
            // Traccar device record, walking device → group → server → config.
            // Falls back to '000000' if nothing is configured.
            def pwd = ctx.devicePassword('000000')

            switch (cmd.type) {
                // Pass-through: the operator supplies the full raw command string
                case TYPE_CUSTOM:
                    return buildCmd("CMD,${id},${ctx.data()}")

                // Request one immediate position report
                case TYPE_POSITION_SINGLE:
                    return buildCmd("CMD,${id},${pwd},POLL")

                // Reboot the device
                case TYPE_REBOOT_DEVICE:
                    return buildCmd("CMD,${id},${pwd},REBOOT")

                // Set periodic reporting interval (frequency attribute is in seconds)
                case TYPE_POSITION_PERIODIC:
                    return buildCmd("CMD,${id},${pwd},TRACK,${ctx.freq()}")

                // Update the server address the device reports to
                case TYPE_SET_CONNECTION:
                    return buildCmd("CMD,${id},${pwd},SERVER,${ctx.server()},${ctx.port()}")

                // Not supported by this device — return null to skip silently
                default:
                    return null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat variant (optional second variant on the same port)
    //
    // The device sends a periodic heartbeat that needs an ACK but carries no
    // position. Demonstrating a second variant that shares the same port.
    //
    // Device sends: $HB,<imei>*<checksum>\r\n
    // -----------------------------------------------------------------------
    variant("heartbeat") {

        frame readLine()    // no hint — evaluated after "main" (order matters)

        matches { msg -> msg.startsWith('$HB,') }

        // No alarms block needed — this variant never produces alarms.

        decode { msg, ctx ->
            // $HB,IMEI*CS
            def parts = msg.split('[,*]')
            if (parts.length < 2) return null
            if (!checksum(msg)) return null

            def imei = parts[1]
            if (!ctx.session(imei)) return null

            // ACK the heartbeat; return null so nothing is stored
            ctx.ack(buildCmd("ACK,${imei}"))
            return null
        }

        // Heartbeat variant has no encode closure — no commands go this route.
    }
}

// ===========================================================================
// BINARY PROTOCOL SKELETON (commented out — copy and adapt as needed)
//
// Use this pattern when the device speaks a binary protocol:
//   - Non-ASCII start byte (e.g. 0x78 0x78, 0x24 0x24, 0x7e)
//   - Length field in the header rather than a text terminator
//   - Numeric fields (big/little-endian int, BCD, raw bytes)
//
// The decode closure receives a BufReader instead of a String.
// See docs/driver-development.md → "Binary protocols" for the full API.
// ===========================================================================

/*

import org.traccar.model.Position

protocol("mybin") {

    port 5098

    variant("main") {

        // Batch/binary protocols can opt into a larger frame limit when needed.
        maxFrameLength 65536

        // 0x78 start byte; length field at offset 2 (after 2 start bytes),
        // 2 bytes wide; +1 for a checksum byte not counted by the length field.
        //   Total frame = 2 + 2 + bodyLength + 1
        frame 0x78 as byte, readLengthField(2, 2, 1)

        // No `matches` closure needed for binary variants — the frameByteHint
        // (0x78) already uniquely identifies them.

        decode { buf, ctx ->

            buf.skip(2)                         // 0x78 0x78 start bytes
            int msgType = buf.readUShort()       // message type
            int bodyLen = buf.readUShort()       // body length (excludes checksum)
            String imei = buf.readBcd(15)        // 15-digit IMEI in BCD (8 bytes)
            buf.skip(2)                         // serial number

            def session = ctx.session(imei)
            if (!session) return null

            // --- Verify checksum before consuming the body ---
            // (example: XOR all bytes from start up to checksum byte)

            if (msgType == 0x0200) {            // GPS position report

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                long gpsInfo  = buf.readUInt()
                pos.valid     = BufReader.checkBit((int) gpsInfo, 0)
                pos.set(Position.KEY_SATELLITES, (int) ((gpsInfo >> 1) & 0x0F))

                pos.latitude  = buf.readInt()  / 1000000.0
                pos.longitude = buf.readInt()  / 1000000.0
                pos.speed     = buf.readUShort() / 10.0
                pos.course    = buf.readUShort() / 10.0

                // --- Binary ACK ---
                int seq = buf.readUShort()
                ctx.ack([0x78, 0x78,
                         0x00, 0x05,              // body length = 5
                         (msgType >> 8) & 0xFF, msgType & 0xFF,
                         (seq >> 8) & 0xFF, seq & 0xFF,
                         0x00,                    // checksum placeholder
                         0x0D, 0x0A] as byte[])

                return pos
            }

            return null
        }

        encode { cmd, ctx ->
            def pwd = ctx.devicePassword('000000')
            // Build and return a byte[] command
            return null
        }
    }
}

*/
