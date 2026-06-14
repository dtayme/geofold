// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Xirgo GPS tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/xirgo/
 *
 * '##'-terminated text frames (terminator stripped by framing).
 * All messages start with '$$'.
 *
 * Three modes, selected in priority order:
 *   1. Custom form   — if config key 'xirgo.form' is set, parse by field names
 *   2. Auto-detect   — first message determines old vs new fixed format; result
 *                      cached per-channel in ctx.store()
 *
 * New format fields (after $$imei,event,date,time):
 *   lat,lon,alt,speed,<skip accel>,<skip decel>,<skip unknown>,
 *   course,sats,hdop,odometer,fuelConsumption,battery,gsm,gps[,optional block]
 *
 * Old format fields (after $$imei,event,date,time):
 *   lat,lon,alt,speed,course,sats,hdop,battery,gsm,odometer,gps
 *
 * Speed in mph (fixed format); GSPT field in custom form is kph.
 * For UDP channels, sends "!UDP_ACK,<event>,<gps>" acknowledgement.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN_OLD = Pattern.compile(
        '\\$\\$(\\d+),(\\d+),(\\d{4})/(\\d{2})/(\\d{2}),(\\d{2}):(\\d{2}):(\\d{2}),'
        + '(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),'
        + '(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+),(\\d+\\.?\\d*),(\\d+\\.\\d+),(\\d+),(\\d+\\.?\\d*),(\\d+).*')

def PATTERN_NEW = Pattern.compile(
        '\\$\\$(\\d+),(\\d+),(\\d{4})/(\\d{2})/(\\d{2}),(\\d{2}):(\\d{2}):(\\d{2}),'
        + '(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*),'
        + '(\\d+\\.?\\d*),\\d+\\.?\\d*,\\d+\\.?\\d*,\\d+,'
        + '(\\d+\\.?\\d*),(\\d+),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.\\d+),(\\d+),(\\d+)'
        + '(?:,\\d,([01])([01])([01])([01]),(\\d+\\.?\\d*),(\\d+\\.?\\d*),\\d+,'
        + '(\\d+),(\\d+),(\\d+),(-?\\d+),(\\d+),(\\d+),(-?\\d+))?.*')

def PATTERN_ACK = Pattern.compile('\\$\\$\\d+,(\\d+),.+,(\\d+)')

def decodeEvent = { pos, int event ->
    pos.set(Position.KEY_EVENT, event)
    if (event in [4001, 4003, 6011, 6013])       pos.set(Position.KEY_IGNITION, true)
    else if (event in [4002, 4004, 6012, 6014])  pos.set(Position.KEY_IGNITION, false)
    else if (event == 4005)  pos.set(Position.KEY_CHARGE, false)
    else if (event == 6002)  pos.addAlarm(ALARM_OVERSPEED)
    else if (event == 6006)  pos.addAlarm(ALARM_ACCELERATION)
    else if (event == 6007)  pos.addAlarm(ALARM_BRAKING)
    else if (event == 6008)  pos.addAlarm(ALARM_LOW_POWER)
    else if (event == 6009)  pos.addAlarm(ALARM_POWER_CUT)
    else if (event == 6010)  pos.addAlarm(ALARM_POWER_RESTORED)
    else if (event == 6016)  pos.addAlarm(ALARM_IDLE)
    else if (event == 6017)  pos.addAlarm(ALARM_TOW)
    else if (event in [6030, 6071]) pos.set(Position.KEY_MOTION, true)
    else if (event == 6031)  pos.set(Position.KEY_MOTION, false)
    else if (event == 6032)  pos.addAlarm(ALARM_PARKING)
    else if (event == 6090)  pos.addAlarm(ALARM_REMOVING)
    else if (event == 6091)  pos.addAlarm(ALARM_LOW_BATTERY)
}

def decodeCustom = { String msg, String form, ctx ->
    String[] keys   = form.split(',')
    String[] values = msg.replace('$$', '').replace('##', '').split(',')
    if (values.length < keys.length) return null

    def pos     = ctx.newPosition()
    def session = null
    def cal     = Calendar.getInstance(TimeZone.getTimeZone('UTC'))

    for (int i = 0; i < keys.length; i++) {
        switch (keys[i]) {
            case 'UID': case 'IM':
                session = ctx.session(values[i])
                if (session) pos.deviceId = session.deviceId
                break
            case 'EV':
                decodeEvent(pos, values[i].toInteger())
                break
            case 'D':
                def parts = values[i].split('/')
                cal.set(Calendar.MONTH,        parts[0].toInteger() - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[1].toInteger())
                cal.set(Calendar.YEAR,         parts[2].toInteger())
                break
            case 'T':
                def parts = values[i].split(':')
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInteger())
                cal.set(Calendar.MINUTE,      parts[1].toInteger())
                cal.set(Calendar.SECOND,      parts[2].toInteger())
                cal.set(Calendar.MILLISECOND, 0)
                break
            case 'LT':  pos.latitude  = values[i].toDouble();  break
            case 'LN':  pos.longitude = values[i].toDouble();  break
            case 'AL':  pos.altitude  = values[i].toInteger(); break
            case 'GSPT':
                pos.speed = UnitsConverter.knotsFromKph(values[i].toDouble())
                break
            case 'HD':
                pos.course = values[i].contains('.')
                        ? values[i].toDouble()
                        : values[i].toInteger() / 10.0
                break
            case 'SV':  pos.set(Position.KEY_SATELLITES, values[i].toInteger()); break
            case 'BV':  pos.set(Position.KEY_BATTERY,    values[i].toDouble());  break
            case 'CQ':  pos.set(Position.KEY_RSSI,       values[i].toInteger()); break
            case 'MI':  pos.set(Position.KEY_ODOMETER,   values[i].toInteger()); break
            case 'GS':  pos.valid = values[i].toInteger() == 3;                  break
            case 'SI':  pos.set(Position.KEY_ICCID, values[i]);                  break
            case 'IG':
                int ig = values[i].toInteger()
                if (ig > 0) pos.set(Position.KEY_IGNITION, ig == 1)
                break
            case 'OT':  pos.set(Position.KEY_OUTPUT, values[i].toInteger()); break
        }
    }

    pos.time = cal.time
    return (session && pos.deviceId > 0) ? pos : null
}

def decodeFixed = { String msg, newFmt, session, ctx ->
    def m = newFmt ? PATTERN_NEW.matcher(msg) : PATTERN_OLD.matcher(msg)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    decodeEvent(pos, m.group(2).toInteger())

    def cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    cal.set(m.group(3).toInteger(), m.group(4).toInteger() - 1, m.group(5).toInteger(),
            m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.time = cal.time

    pos.latitude  = m.group(9).toDouble()
    pos.longitude = m.group(10).toDouble()
    pos.altitude  = m.group(11).toDouble()
    pos.speed     = UnitsConverter.knotsFromMph(m.group(12).toDouble())
    pos.course    = m.group(13).toDouble()

    pos.set(Position.KEY_SATELLITES, m.group(14).toInteger())
    pos.set(Position.KEY_HDOP,       m.group(15).toDouble())

    if (newFmt) {
        pos.set(Position.KEY_ODOMETER,
                UnitsConverter.metersFromMiles(m.group(16).toDouble()))
        pos.set(Position.KEY_FUEL_CONSUMPTION, m.group(17))
        pos.set(Position.KEY_BATTERY,          m.group(18).toDouble())
        pos.set(Position.KEY_RSSI,             m.group(19).toDouble())
        pos.valid = m.group(20).toInteger() == 1

        if (m.group(21) != null) {
            pos.set(Position.PREFIX_IN  + 1, m.group(21).toInteger())
            pos.set(Position.PREFIX_IN  + 2, m.group(22).toInteger())
            pos.set(Position.PREFIX_IN  + 3, m.group(23).toInteger())
            pos.set(Position.PREFIX_OUT + 1, m.group(24).toInteger())
            pos.set(Position.PREFIX_ADC + 1, m.group(25).toDouble())
            pos.set(Position.KEY_FUEL,
                    m.group(26).toDouble())
            pos.set(Position.KEY_HOURS,
                    UnitsConverter.msFromHours(m.group(27).toInteger()))
            pos.set('oilPressure',     m.group(28).toInteger())
            pos.set('oilLevel',        m.group(29).toInteger())
            pos.set('oilTemp',         m.group(30).toInteger())
            pos.set('coolantPressure', m.group(31).toInteger())
            pos.set('coolantLevel',    m.group(32).toInteger())
            pos.set('coolantTemp',     m.group(33).toInteger())
        }
    } else {
        pos.set(Position.KEY_BATTERY,  m.group(16).toDouble())
        pos.set(Position.KEY_RSSI,     m.group(17).toDouble())
        pos.set(Position.KEY_ODOMETER,
                UnitsConverter.metersFromMiles(m.group(18).toDouble()))
        pos.valid = m.group(19).toInteger() == 1
    }

    return pos
}

protocol("xirgo") {

    port 5081
    commands TYPE_OUTPUT_CONTROL

    variant("main") {

        frame readUntil("##")
        matches { msg -> msg.startsWith('$$') }

        decode { msg, ctx ->
            if (ctx.isUdp()) {
                def am = PATTERN_ACK.matcher(msg)
                if (am.find()) {
                    ctx.ack("!UDP_ACK,${am.group(1)},${am.group(2)}")
                }
            }

            String form = ctx.configString('form', null)
            if (form != null) {
                return decodeCustom(msg, form, ctx)
            }

            def store  = ctx.store()
            def newFmt = store.get('newFormat')

            if (newFmt == null) {
                def m = PATTERN_NEW.matcher(msg)
                if (m.matches()) {
                    newFmt = Boolean.TRUE
                    store.put('newFormat', newFmt)
                    def session = ctx.session(m.group(1))
                    if (!session) return null
                    return decodeFixed(msg, newFmt, session, ctx)
                }
                m = PATTERN_OLD.matcher(msg)
                if (m.matches()) {
                    newFmt = Boolean.FALSE
                    store.put('newFormat', newFmt)
                    def session = ctx.session(m.group(1))
                    if (!session) return null
                    return decodeFixed(msg, newFmt, session, ctx)
                }
                return null
            }

            def m = (newFmt ? PATTERN_NEW : PATTERN_OLD).matcher(msg)
            if (!m.matches()) return null
            def session = ctx.session(m.group(1))
            if (!session) return null
            return decodeFixed(msg, newFmt, session, ctx)
        }

        encode { cmd, ctx ->
            switch (cmd.type) {
                case TYPE_OUTPUT_CONTROL:
                    return "+XT:7005,${cmd.getInteger('data') + 1},1"
                default:
                    return null
            }
        }
    }
}
