// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Startek GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/startek/ (Java reference)
 *
 * Variable-length ASCII TCP protocol on port 5222.
 * Frame: &&<idx:1><len:N>,<imei>,<type:3hex>,<content><checksum:2hex>\r\n
 *   <len> = 1(idx) + imei.length + 1(',') + payload.length
 *   total frame = comma_offset + 4 + <len>
 *
 * Message types:
 *   000 — position report (CSV)
 *   710 — serial/CAN frames (T1 engine data, T2 counters)
 *   other — pass-through result; KEY_RESULT set to content
 *
 * Commands (server → device): $$:<len>,<imei>,<payload><sum2hex>\r\n
 *
 * Supported commands:
 *   TYPE_CUSTOM, TYPE_OUTPUT_CONTROL, TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone

def decodeAlarm = { int event ->
    switch (event) {
        case 1:  return Position.ALARM_SOS
        case 5:
        case 6:  return Position.ALARM_DOOR
        case 17: return Position.ALARM_LOW_POWER
        case 18: return Position.ALARM_POWER_CUT
        case 19: return Position.ALARM_POWER_RESTORED
        case 39: return Position.ALARM_ACCELERATION
        case 40: return Position.ALARM_BRAKING
        case 41: return Position.ALARM_CORNERING
        default: return null
    }
}

def decodePositionContent = { pos, String content ->
    def f = content.split(',', -1)
    if (f.length < 18) return

    int fi = 0
    int event = f[fi++].toInteger()
    String eventData = fi < f.length ? f[fi++] : ''

    pos.set(Position.KEY_EVENT, event)
    if (event == 53) {
        if (eventData && !eventData.isEmpty()) {
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, eventData)
        }
    } else {
        String alarm = decodeAlarm(event)
        if (alarm) pos.set(Position.KEY_ALARM, alarm)
    }

    // Date/time as YYMMDDHHMMSS — 12 chars, no internal commas
    String dt = fi < f.length ? f[fi++] : ''
    if (dt.length() >= 12) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
        cal.set(Calendar.YEAR,         2000 + dt.substring(0, 2).toInteger())
        cal.set(Calendar.MONTH,        dt.substring(2, 4).toInteger() - 1)
        cal.set(Calendar.DAY_OF_MONTH, dt.substring(4, 6).toInteger())
        cal.set(Calendar.HOUR_OF_DAY,  dt.substring(6, 8).toInteger())
        cal.set(Calendar.MINUTE,       dt.substring(8, 10).toInteger())
        cal.set(Calendar.SECOND,       dt.substring(10, 12).toInteger())
        cal.set(Calendar.MILLISECOND,  0)
        pos.time = cal.getTime()
    }

    pos.valid = fi < f.length ? f[fi++] == 'A' : false
    if (fi < f.length) pos.latitude  = f[fi++].toDouble()
    if (fi < f.length) pos.longitude = f[fi++].toDouble()
    if (fi < f.length) pos.set(Position.KEY_SATELLITES, f[fi++].toInteger())
    if (fi < f.length) pos.set(Position.KEY_HDOP, f[fi++].toDouble())
    if (fi < f.length) pos.speed  = UnitsConverter.knotsFromKph(f[fi++].toInteger())
    if (fi < f.length) pos.course = f[fi++].toDouble()
    if (fi < f.length) pos.altitude = f[fi++].toDouble()
    if (fi < f.length) pos.set(Position.KEY_ODOMETER, f[fi++].toLong())

    // Cell tower: mcc|mnc|lac|cid (pipe-separated) then rssi as next comma field
    if (fi < f.length) {
        String[] cell = f[fi++].split('\\|')
        int rssi = fi < f.length ? f[fi++].toInteger() : 0
        if (cell.length >= 4) {
            pos.network = new Network(CellTower.from(
                cell[0].toInteger(), cell[1].toInteger(),
                Integer.parseInt(cell[2], 16), Integer.parseInt(cell[3], 16),
                rssi))
        }
    }

    if (fi < f.length) pos.set(Position.KEY_STATUS, Integer.parseInt(f[fi++], 16))

    if (fi + 1 < f.length) {
        int inputs  = Integer.parseInt(f[fi++], 16)
        int outputs = Integer.parseInt(f[fi++], 16)
        pos.set(Position.KEY_IGNITION, BitUtil.check(inputs, 1))
        pos.set(Position.KEY_DOOR,     BitUtil.check(inputs, 2))
        pos.set(Position.KEY_INPUT,    inputs)
        pos.set(Position.KEY_OUTPUT,   outputs)
    }

    // power|battery[|adc2|adc3|...] — pipe-separated within one comma field
    if (fi < f.length) {
        String[] pwr = f[fi++].split('\\|')
        if (pwr.length >= 1) pos.set(Position.KEY_POWER,   Integer.parseInt(pwr[0], 16) / 100.0)
        if (pwr.length >= 2) pos.set(Position.KEY_BATTERY, Integer.parseInt(pwr[1], 16) / 100.0)
        for (int i = 2; i < pwr.length; i++) {
            pos.set(Position.PREFIX_ADC + i, Integer.parseInt(pwr[i], 16) / 100.0)
        }
    }

    if (fi >= f.length) return
    fi++  // extended counter — present but not captured

    // Fuel: pipe-separated "IIXXXX" entries (II=2-digit decimal index, XXXX=hex value/10)
    if (fi < f.length) {
        String fuelField = f[fi++]
        if (!fuelField.isEmpty()) {
            for (String entry : fuelField.split('\\|')) {
                if (entry.length() >= 3) {
                    int idx = entry.substring(0, 2).toInteger()
                    int val = Integer.parseInt(entry.substring(2), 16)
                    pos.set('fuel' + idx, val / 10.0)
                }
            }
        }
    }

    // Temperature: pipe-separated "IIXXXX" entries; 16-bit value with bit-15 sign
    if (fi < f.length) {
        String tempField = f[fi++]
        if (!tempField.isEmpty()) {
            for (String entry : tempField.split('\\|')) {
                if (entry.length() >= 3) {
                    int idx = entry.substring(0, 2).toInteger()
                    int val = Integer.parseInt(entry.substring(2), 16)
                    double converted = BitUtil.to(val, 15)
                    if (BitUtil.check(val, 15)) converted = -converted
                    pos.set(Position.PREFIX_TEMP + idx, converted / 10.0)
                }
            }
        }
    }

    // OBD: pipe-separated rpm|load|maf|pressure|airtemp|throttle|coolant|fuelcons|fuellevel
    if (fi < f.length) {
        String obdField = f[fi++]
        if (!obdField.isEmpty()) {
            String[] obd = obdField.split('\\|', -1)
            int oi = 0
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set(Position.KEY_RPM,             obd[oi].toInteger()); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set(Position.KEY_ENGINE_LOAD,      obd[oi].toInteger()); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set('airFlow',                     obd[oi].toInteger()); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set('airPressure',                 obd[oi].toInteger()); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set('airTemp',                     obd[oi].toInteger() - 40); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set(Position.KEY_THROTTLE,         obd[oi].toInteger()); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set(Position.KEY_COOLANT_TEMP,     obd[oi].toInteger() - 40); oi++
            if (oi < obd.length && !obd[oi].isEmpty()) pos.set(Position.KEY_FUEL_CONSUMPTION, obd[oi].toInteger() / 10.0); oi++
            if (oi < obd.length && obd[oi] =~ /^\d+[%L]$/) {
                pos.set(Position.KEY_FUEL, obd[oi].replaceAll('[%L]', '').toInteger())
            }
        }
    }

    // Driver ID: any field with 20+ chars
    if (fi < f.length && f[fi].length() >= 20) {
        pos.set(Position.KEY_DRIVER_UNIQUE_ID, f[fi])
        fi++
    }

    // Hours
    if (fi < f.length && f[fi] =~ /^\d+$/) {
        pos.set(Position.KEY_HOURS, f[fi].toLong() * 1000L)
    }
}

def decodeSerial = { pos, String content ->
    for (String frame : content.split('\r\n')) {
        if (frame.isEmpty()) continue
        String[] vals = frame.split(',')
        switch (vals[0]) {
            case 'T1':
                if (vals.length > 2)  pos.set(Position.KEY_RPM,          vals[2].toDouble())
                if (vals.length > 4)  pos.set(Position.KEY_FUEL,          vals[4].toDouble())
                if (vals.length > 10) pos.set(Position.KEY_COOLANT_TEMP,  vals[10].toInteger())
                if (vals.length > 12) pos.set('torque',                   vals[12].toInteger())
                if (vals.length > 14) pos.set(Position.KEY_POWER,         vals[14].toDouble())
                if (vals.length > 16) pos.set('oilTemp',                  vals[16].toDouble())
                if (vals.length > 18) pos.set(Position.KEY_THROTTLE,      vals[18].toDouble())
                if (vals.length > 23) pos.set('oilPressure',              vals[23].toInteger())
                if (vals.length > 26) {
                    int ign = vals[26].toInteger()
                    if (ign < 2) pos.set(Position.KEY_IGNITION, ign > 0)
                }
                if (vals.length > 28) pos.set('catalystLevel',            vals[28].toDouble())
                break
            case 'T2':
                if (vals.length > 1)  pos.set(Position.KEY_ODOMETER,         vals[1].toDouble() * 1000)
                if (vals.length > 17) pos.set(Position.KEY_HOURS,            vals[17].toInteger())
                if (vals.length > 19) pos.set(Position.KEY_FUEL_CONSUMPTION, vals[19].toDouble())
                if (vals.length > 21) pos.set(Position.KEY_FUEL_USED,        vals[21].toDouble())
                break
        }
    }
}

protocol("startek") {

    port 5222

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 10) return null
            int dividerIndex = fb.indexOf((int) ',', 3)
            if (dividerIndex < 0) return null
            int payloadLen = new String(fb.bytes(3, dividerIndex - 3)).toInteger()
            int total = dividerIndex + 4 + payloadLen
            if (fb.readableBytes() < total) return null
            return total
        }

        matches { msg ->
            msg.toString().startsWith('&&')
        }

        commands TYPE_CUSTOM, TYPE_OUTPUT_CONTROL, TYPE_ENGINE_STOP, TYPE_ENGINE_RESUME

        encode { cmd, ctx ->
            def imei = ctx.deviceId()
            def buildCmd = { String payload ->
                int length = 1 + imei.length() + 1 + payload.length()
                String sentence = '$$:' + length + ',' + imei + ',' + payload
                return sentence + Checksum.sum(sentence) + '\r\n'
            }
            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return buildCmd(cmd.getString('data'))
                case TYPE_OUTPUT_CONTROL:
                    return buildCmd('900,' + cmd.getInteger('index') + ',' + cmd.getString('data'))
                case TYPE_ENGINE_STOP:
                    return buildCmd('900,1,1')
                case TYPE_ENGINE_RESUME:
                    return buildCmd('900,1,0')
                default:
                    return null
            }
        }

        decode { msg, ctx ->
            String text = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()

            def m = text =~ /(?s)&&.\d+,(\d+),([0-9a-fA-F]{3}),(.+)[0-9a-fA-F]{2}\r?\n?/
            if (!m) return null

            String imei    = m[0][1]
            String type    = m[0][2]
            String content = m[0][3]

            def session = ctx.session(imei)
            if (!session) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            switch (type) {
                case '000':
                    decodePositionContent(pos, content)
                    return pos
                case '710':
                    decodeSerial(pos, content)
                    return pos
                default:
                    pos.set(Position.KEY_TYPE,   type)
                    pos.set(Position.KEY_RESULT, content)
                    return pos
            }
        }
    }
}
