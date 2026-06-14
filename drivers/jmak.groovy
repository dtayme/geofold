// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * JMAK GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/jmak/ (Java reference)
 *
 * Three frame types on the same port:
 *   {…}   — JSON heartbeat/registration; balanced-brace framing; ACK only
 *   ^…$   — alternate keep-alive format; delimited by '$'; ACK only
 *   ~…$   — position/event data; delimited by '$'
 *
 * Position frame format: ~<mask>;<serial>;<imei>;<fields...>[|<can-fields...>]$
 *   The 64-bit hex mask controls which optional fields are present in each
 *   section.  The CAN section (after '|') carries OBD/CAN bus data.
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

def decodeEvent = { pos, String[] v ->
    int idx = 0
    long mask = Long.parseLong(v[idx++], 16)

    if (BitUtil.check(mask, 0))  idx++                  // serial number
    if (BitUtil.check(mask, 1))  idx++                  // imei (used for session lookup)
    if (BitUtil.check(mask, 2))  pos.set(Position.PREFIX_COUNT, Long.parseLong(v[idx++]))
    if (BitUtil.check(mask, 3)) {
        def nick = v[idx++]
        if (nick != 'NULL') pos.set('nickname', nick)
    }
    if (BitUtil.check(mask, 4))  pos.time      = new Date(Long.parseLong(v[idx++]))
    if (BitUtil.check(mask, 5))  pos.latitude  = v[idx++].toDouble()
    if (BitUtil.check(mask, 6))  pos.longitude = v[idx++].toDouble()
    if (BitUtil.check(mask, 7))  pos.altitude  = v[idx++].toDouble()
    if (BitUtil.check(mask, 8))  idx++                  // reserved
    if (BitUtil.check(mask, 9))  pos.speed  = UnitsConverter.knotsFromKph(v[idx++].toDouble())
    if (BitUtil.check(mask, 10)) pos.set(Position.KEY_SATELLITES, v[idx++].toInteger())
    if (BitUtil.check(mask, 11)) pos.set(Position.KEY_RSSI,       v[idx++].toInteger())
    if (BitUtil.check(mask, 12)) pos.set(Position.KEY_HDOP,       v[idx++].toDouble())
    if (BitUtil.check(mask, 13)) pos.course = v[idx++].toDouble()
    if (BitUtil.check(mask, 14)) pos.set(Position.KEY_IGNITION,   v[idx++].toInteger() == 1)
    if (BitUtil.check(mask, 15)) pos.set('backup',                v[idx++].toInteger() == 1)
    if (BitUtil.check(mask, 16)) pos.set(Position.KEY_HOURS,      v[idx++].toDouble() * 3600000)
    if (BitUtil.check(mask, 17)) pos.set(Position.KEY_ODOMETER,   v[idx++].toDouble() * 1000)

    int eventId = 0
    int eventStatus = 0
    String eventName = null
    if (BitUtil.check(mask, 18)) { eventId     = v[idx++].toInteger(); pos.set(Position.KEY_EVENT, eventId) }
    if (BitUtil.check(mask, 19)) { eventStatus = v[idx++].toInteger(); pos.set('eventStatus', eventStatus) }
    if (BitUtil.check(mask, 20)) { eventName   = v[idx++];              pos.set('eventName', eventName) }

    if (eventId == 126 && eventStatus == 4 && eventName) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, eventName)
    }

    if (BitUtil.check(mask, 21)) pos.set(Position.KEY_VIN,      v[idx++].toInteger())
    if (BitUtil.check(mask, 22)) pos.set(Position.KEY_BATTERY,  v[idx++].toInteger())
    if (BitUtil.check(mask, 23)) pos.set(Position.KEY_OPERATOR, v[idx++])
    if (BitUtil.check(mask, 24)) idx++                          // cellular technology
    if (BitUtil.check(mask, 25)) pos.deviceTime = new Date(Long.parseLong(v[idx++]))
    if (BitUtil.check(mask, 26)) pos.valid      = v[idx++].toInteger() >= 1
    if (BitUtil.check(mask, 27)) {
        int io = v[idx++].toInteger()
        pos.set(Position.PREFIX_IN + 1,  BitUtil.check(io, 0))
        pos.set(Position.PREFIX_IN + 2,  BitUtil.check(io, 1))
        pos.set(Position.PREFIX_OUT + 1, BitUtil.check(io, 2))
        pos.set(Position.PREFIX_OUT + 2, BitUtil.check(io, 3))
    }
    if (BitUtil.check(mask, 28)) pos.set(Position.KEY_DRIVER_UNIQUE_ID, v[idx++])
    if (BitUtil.check(mask, 29)) idx++                          // transparent message length
    if (BitUtil.check(mask, 30)) pos.set('message', v[idx++])
    if (BitUtil.check(mask, 31)) idx++
    if (BitUtil.check(mask, 32)) idx++
    if (BitUtil.check(mask, 33)) idx++
    if (BitUtil.check(mask, 34)) idx++
}

def decodeCan = { pos, String[] v ->
    int idx = 0
    long mask = Long.parseLong(v[idx++], 16)

    if (BitUtil.check(mask, 0))  pos.set(Position.KEY_OBD_ODOMETER,    v[idx++].toDouble() * 1000)
    if (BitUtil.check(mask, 1))  pos.set(Position.KEY_HOURS,            v[idx++].toDouble() * 3600000)
    if (BitUtil.check(mask, 2))  pos.set(Position.KEY_OBD_SPEED,        v[idx++].toDouble())
    if (BitUtil.check(mask, 3))  pos.set(Position.KEY_RPM,              v[idx++].toDouble())
    if (BitUtil.check(mask, 4))  pos.set('canStatus',                   v[idx++].toInteger())
    if (BitUtil.check(mask, 5))  idx++
    if (BitUtil.check(mask, 6))  pos.set(Position.KEY_THROTTLE,         v[idx++].toDouble())
    if (BitUtil.check(mask, 7))  idx++
    if (BitUtil.check(mask, 8))  pos.set(Position.KEY_FUEL,             v[idx++].toDouble())
    if (BitUtil.check(mask, 9))  idx++
    if (BitUtil.check(mask, 10)) pos.set('autonomy',                    v[idx++].toDouble())
    if (BitUtil.check(mask, 11)) idx++
    if (BitUtil.check(mask, 12)) pos.set(Position.KEY_FUEL_CONSUMPTION, v[idx++].toDouble())
    if (BitUtil.check(mask, 13)) pos.set(Position.KEY_FUEL_USED,        v[idx++].toDouble())
    if (BitUtil.check(mask, 14)) pos.set('oilTemperature',              v[idx++].toDouble())
}

protocol("jmak") {

    port 5259

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() == 0) return null
            int first = fb.getUByte(0)
            if (first == (int) '{') {
                // Balanced-brace JSON frame
                int depth = 0
                for (int i = 0; i < fb.readableBytes(); i++) {
                    int c = fb.getUByte(i)
                    if (c == (int) '{') depth++
                    else if (c == (int) '}') {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                return null
            }
            if (first == (int) '~' || first == (int) '^') {
                int endIdx = fb.indexOf((int) '$')
                if (endIdx < 0) return null
                return endIdx + 1     // consume through '$'
            }
            return null
        }

        matches { msg ->
            def s = msg.toString()
            s.startsWith('~') || s.startsWith('{') || s.startsWith('^')
        }

        decode { msg, ctx ->
            def text = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()

            if (text.startsWith('{') || text.startsWith('^')) {
                ctx.ack('ACK')
                return null
            }

            if (!text.startsWith('~')) return null

            // Strip ~ prefix and trailing $
            String body = text.substring(1)
            if (body.endsWith('$')) body = body.substring(0, body.length() - 1)

            String[] parts = body.split('\\|', -1)
            String[] values = parts[0].split(';', -1)

            def session = ctx.session(values[2])
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            decodeEvent(pos, values)

            if (parts.length >= 2) {
                decodeCan(pos, parts[1].split(';', -1))
            }

            ctx.ack('ACK')
            return pos
        }
    }
}
