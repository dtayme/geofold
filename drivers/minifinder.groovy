// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * MiniFinder GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/minifinder/ (Java reference)
 *
 * ';'-terminated text frames (terminator stripped by framing).
 * Login '!1,<imei>[,...]' registers the device; subsequent
 * messages look up the existing channel session.
 *
 * Message types:
 *   !1  – login (IMEI is second comma-token)
 *   !3  – command response
 *   !4  – SOS phone numbers
 *   !5  – GSM/battery status
 *   !A  – position (date/time/lat/lon only)
 *   !B  – buffered position (+speed/course/flags/alt/bat/sats/hdop)
 *   !C  – live position (+speed/course/flags/alt/bat)
 *   !D  – live position, same fields as B
 *
 * Speed in km/h. Flags word (hex) encodes GPS validity, alarms, RSSI, charge.
 * Default device password: 123456.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.TimeZone

def decodeFlags = { pos, int flags ->
    pos.valid = (flags & 3) > 0
    if (checkBit(flags, 1))    pos.set(Position.KEY_APPROXIMATE, true)
    if (checkBit(flags, 2))    pos.addAlarm(ALARM_FAULT)
    if (checkBit(flags, 6))    pos.addAlarm(ALARM_SOS)
    if (checkBit(flags, 7))    pos.addAlarm(ALARM_OVERSPEED)
    if (checkBit(flags, 8))    pos.addAlarm(ALARM_FALL_DOWN)
    if ((flags & 0xE00) != 0)  pos.addAlarm(ALARM_GEOFENCE)
    if (checkBit(flags, 12))   pos.addAlarm(ALARM_LOW_BATTERY)
    if ((flags & 0xC000) != 0) pos.addAlarm(ALARM_MOVEMENT)
    pos.set(Position.KEY_RSSI,   (flags >> 16) & 0x1F)
    pos.set(Position.KEY_CHARGE, checkBit(flags, 22))
}

def parseFix = { pos, String[] v, int base ->
    def d   = v[base].split('/')
    def t   = v[base + 1].split(':')
    def cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    cal.set(2000 + d[2].toInteger(), d[1].toInteger() - 1, d[0].toInteger(),
            t[0].toInteger(), t[1].toInteger(), t[2].toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.time      = cal.time
    pos.latitude  = v[base + 2].toDouble()
    pos.longitude = v[base + 3].toDouble()
}

def parseState = { pos, String[] v, int base ->
    pos.speed  = UnitsConverter.knotsFromKph(v[base].toDouble())
    double course = v[base + 1].toDouble()
    pos.course = course > 360 ? 0 : course
    decodeFlags(pos, (int) Long.parseLong(v[base + 2], 16))
    pos.altitude = v[base + 3].toDouble()
    pos.set(Position.KEY_BATTERY_LEVEL, v[base + 4].toInteger())
}

protocol("minifinder") {

    port 5062
    commands TYPE_SET_TIMEZONE, TYPE_VOICE_MONITORING, TYPE_ALARM_SPEED,
             TYPE_ALARM_GEOFENCE, TYPE_ALARM_VIBRATION, TYPE_SET_AGPS,
             TYPE_ALARM_FALL, TYPE_MODE_POWER_SAVING, TYPE_MODE_DEEP_SLEEP,
             TYPE_SOS_NUMBER, TYPE_SET_INDICATOR

    variant("main") {

        frame readUntil(";")
        matches { msg -> msg.startsWith('!') }

        decode { msg, ctx ->
            String type = msg.length() > 1 ? msg.substring(1, 2) : ''
            String[] v  = msg.split(',')

            if (type == '1') {
                if (v.length >= 2) ctx.session(v[1])
                return null
            }

            def session = ctx.session()
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.set(Position.KEY_TYPE, type)

            switch (type) {
                case '3':
                    ctx.lastLocation(pos)
                    pos.set(Position.KEY_RESULT, msg.substring(3))
                    return pos

                case '4':
                    ctx.lastLocation(pos)
                    for (int i = 1; i <= 3; i++) {
                        if (v.length > i + 1 && !v[i + 1].isEmpty())
                            pos.set('phone' + i, v[i + 1])
                    }
                    return pos

                case '5':
                    ctx.lastLocation(pos)
                    if (v.length > 1) pos.set(Position.KEY_RSSI,          v[1].toInteger())
                    if (v.length > 3) pos.set(Position.KEY_BATTERY_LEVEL, v[3].toInteger())
                    return pos

                case 'A':
                    if (v.length < 5) return null
                    parseFix(pos, v, 1)
                    return pos

                case 'C':
                    if (v.length < 10) return null
                    parseFix(pos, v, 1)
                    parseState(pos, v, 5)
                    return pos

                case 'B': case 'D':
                    if (v.length < 13) return null
                    parseFix(pos, v, 1)
                    parseState(pos, v, 5)
                    pos.set(Position.KEY_SATELLITES,         v[10].toInteger())
                    pos.set(Position.KEY_SATELLITES_VISIBLE, v[11].toInteger())
                    pos.set(Position.KEY_HDOP,               v[12].toDouble())
                    return pos

                default:
                    return null
            }
        }

        encode { cmd, ctx ->
            def pwd = ctx.devicePassword('123456')
            switch (cmd.type) {
                case TYPE_SET_TIMEZONE:
                    def tz  = TimeZone.getTimeZone(cmd.attributes['timezone']?.toString() ?: 'UTC')
                    def hrs = tz.rawOffset.intdiv(3600000)
                    return "${pwd}L${String.format('%+03d', hrs)}"
                case TYPE_VOICE_MONITORING:
                    return "${pwd}P${cmd.attributes['enable'] ? '1' : '0'}"
                case TYPE_ALARM_SPEED:
                    return "${pwd}J1${cmd.attributes['data']}"
                case TYPE_ALARM_GEOFENCE:
                    return "${pwd}R1${cmd.attributes['radius']}"
                case TYPE_ALARM_VIBRATION:
                    return "${pwd}W1,${cmd.attributes['data']}"
                case TYPE_SET_AGPS:
                    return "${pwd}AGPS${cmd.attributes['enable'] ? '1' : '0'}"
                case TYPE_ALARM_FALL:
                    return "${pwd}F${cmd.attributes['enable'] ? '1' : '0'}"
                case TYPE_MODE_POWER_SAVING:
                    return "${pwd}SP${cmd.attributes['enable'] ? '1' : '0'}"
                case TYPE_MODE_DEEP_SLEEP:
                    return "${pwd}DS${cmd.attributes['enable'] ? '1' : '0'}"
                case TYPE_SOS_NUMBER:
                    def idx = ['A', 'B', 'C'][(int)(cmd.attributes['index'] ?: 0)] ?: 'A'
                    return "${pwd}${idx}1,${cmd.attributes['phone']}"
                case TYPE_SET_INDICATOR:
                    return "${pwd}LED${cmd.attributes['data']}"
                default:
                    return null
            }
        }
    }
}
