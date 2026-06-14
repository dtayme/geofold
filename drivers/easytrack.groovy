// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * EasyTrack GPS tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/easytrack/
 *
 * '#'-terminated text frames, all starting with '*'.
 * Message format: *<mfr>,<imei>,<type>,[fields...]
 *
 * Type dispatch:
 *   OB         — OBD diagnostic data
 *   JZ         — cell tower fallback location; sends *ET,<imei>,JZ,undefined# query
 *   TX/MQ      — echo the message back with '#' appended
 *   E3+4G ACKs — for HB, CC, AM, DW, JZ on E3+4G devices, also send an ACK variant
 *   everything else — GPS location report
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN = Pattern.compile(
        /\*.{2},[^,]+,([^,]{2}),([AV]),/
                + /([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2}),/
                + /([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2}),/
                + /([0-9a-fA-F])([0-9a-fA-F]{7}),/
                + /([0-9a-fA-F])([0-9a-fA-F]{7}),/
                + /([0-9a-fA-F]{4}),([0-9a-fA-F]{4}),/
                + /([0-9a-fA-F]{8}),/
                + /([0-9a-fA-F]+),(\d+\.?\d*),/
                + /([0-9a-fA-F]+),([0-9a-fA-F]+)/
                + /(?:,([0-9a-fA-F]+)(?:,\d+,([0-9a-fA-F]*),([0-9a-fA-F]+),(\d+\.\d+),(\d+))?)?/)

def PATTERN_CELL = Pattern.compile(
        /\*.{2},[^,]+,JZ,[01],(\d+),(\d+),(\d+),(\d+)/)

def PATTERN_OBD = Pattern.compile(
        '\\*.{2},[^,]+,OB,BD\\$V(\\d+\\.\\d);R(\\d+);S(\\d+);P(\\d+\\.\\d);'
        + 'O(\\d+\\.\\d);C(\\d+);L(\\d+\\.\\d);[XY][MH]\\d+\\.\\d+;'
        + 'M\\d+\\.?\\d*;F(\\d+\\.\\d+);T(\\d+);')

def E3_ACK_TYPES = ['HB', 'CC', 'AM', 'DW', 'JZ'] as Set

def decodeStatus = { pos, long status, String model ->
    if (status & 0x02000000L) pos.addAlarm(ALARM_GEOFENCE_ENTER)
    if (status & 0x04000000L) pos.addAlarm(ALARM_GEOFENCE_EXIT)
    if (status & 0x08000000L) pos.addAlarm(ALARM_LOW_BATTERY)
    if ((status & 0x10000000L) || (status & 0x00000008L)) pos.addAlarm(ALARM_JAMMING)
    if (status & 0x20000000L) pos.addAlarm(ALARM_VIBRATION)
    if (status & 0x80000000L) pos.addAlarm(ALARM_OVERSPEED)
    if ((status & 0x00010000L) || (status & 0x00000400L)) pos.addAlarm(ALARM_SOS)
    if (status & 0x00040000L) pos.addAlarm('E3+4G' == model ? ALARM_TAMPERING : ALARM_POWER_CUT)
    if (status & 0x00004000L) pos.addAlarm(ALARM_LOW_POWER)
    if (status & 0x00008000L) pos.addAlarm(ALARM_TEMPERATURE)
    if (status & 0x00000100L) pos.addAlarm(ALARM_REMOVING)
    if (status & 0x00000001L) pos.addAlarm(ALARM_BRAKING)
    if (status & 0x00000002L) pos.addAlarm(ALARM_ACCELERATION)
    pos.set(Position.KEY_BLOCKED,  (status & 0x00080000L) > 0)
    pos.set(Position.KEY_IGNITION, (status & 0x00800000L) > 0)
    pos.set(Position.KEY_STATUS, status)
}

protocol("easytrack") {

    port 5082

    variant("main") {

        frame '*' as char, readUntil('#')
        matches { msg -> msg.startsWith("*") }

        decode { msg, ctx ->
            def parts = msg.split(',')
            if (parts.length < 3) return null
            def imei = parts[1]
            def type = parts[2].length() >= 2 ? parts[2].substring(0, 2) : parts[2]

            def session = ctx.session(imei)
            if (!session) return null

            def model = session.model

            if (type == 'TX' || type == 'MQ') {
                ctx.ack(msg + '#')
            } else if ('E3+4G' == model && E3_ACK_TYPES.contains(type)) {
                int tIdx = msg.indexOf(',' + type + ',')
                if (tIdx >= 0) ctx.ack(msg.substring(0, tIdx + type.length() + 3) + 'ACK#')
            }

            if (type == 'JZ') {
                if (parts.length >= 4 && parts[3].toInteger() > 0) {
                    ctx.ack("*ET,${imei},JZ,undefined#")
                }
                def m = PATTERN_CELL.matcher(msg)
                if (!m.find()) return null
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.network = new Network(CellTower.from(
                        m.group(3).toInteger(), m.group(4).toInteger(),
                        m.group(2).toInteger(), m.group(1).toInteger()))
                return pos
            }

            if (type == 'OB') {
                def m = PATTERN_OBD.matcher(msg)
                if (!m.find()) return null
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos)
                pos.set(Position.KEY_BATTERY,          Double.parseDouble(m.group(1)))
                pos.set(Position.KEY_RPM,              m.group(2).toInteger())
                pos.set(Position.KEY_OBD_SPEED,        m.group(3).toInteger())
                pos.set(Position.KEY_THROTTLE,         Double.parseDouble(m.group(4)))
                pos.set(Position.KEY_ENGINE_LOAD,      Double.parseDouble(m.group(5)))
                pos.set(Position.KEY_COOLANT_TEMP,     m.group(6).toInteger())
                pos.set(Position.KEY_FUEL,             Double.parseDouble(m.group(7)))
                pos.set(Position.KEY_FUEL_CONSUMPTION, Double.parseDouble(m.group(8)))
                pos.set(Position.KEY_HOURS,            m.group(9).toInteger())
                return pos
            }

            // Location decode
            def m = PATTERN.matcher(msg)
            if (!m.find()) return null

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_COMMAND, m.group(1))
            pos.valid = m.group(2) == 'A'

            pos.time = new DateBuilder()
                    .setDate(Integer.parseInt(m.group(3), 16),
                             Integer.parseInt(m.group(4), 16),
                             Integer.parseInt(m.group(5), 16))
                    .setTime(Integer.parseInt(m.group(6), 16),
                             Integer.parseInt(m.group(7), 16),
                             Integer.parseInt(m.group(8), 16))
                    .getDate()

            int latDir = Integer.parseInt(m.group(9), 16)
            double lat = Integer.parseInt(m.group(10), 16) / 600000.0
            pos.latitude = BitUtil.check(latDir, 3) ? -lat : lat

            int lonDir = Integer.parseInt(m.group(11), 16)
            double lon = Integer.parseInt(m.group(12), 16) / 600000.0
            pos.longitude = BitUtil.check(lonDir, 3) ? -lon : lon

            pos.speed  = UnitsConverter.knotsFromKph(Integer.parseInt(m.group(13), 16) / 100.0)
            double course = Integer.parseInt(m.group(14), 16) / 100.0
            if (course < 360) pos.course = course

            long status = Long.parseLong(m.group(15), 16)
            decodeStatus(pos, status, model)

            pos.set(Position.KEY_RSSI,  Integer.parseInt(m.group(16), 16))
            pos.set(Position.KEY_POWER, Double.parseDouble(m.group(17)))

            if ('E3+4G' == model) {
                pos.set(Position.KEY_INDEX,    Integer.parseInt(m.group(18), 16))
            } else {
                pos.set(Position.KEY_FUEL,     Integer.parseInt(m.group(18), 16))
            }
            pos.set(Position.KEY_ODOMETER, Long.parseLong(m.group(19), 16) * 100)

            if (m.group(20) != null) {
                pos.altitude = Integer.parseInt(m.group(20), 16)
            }
            if (m.group(21) != null) {
                if ('E3+4G' == model) {
                    pos.set(Position.KEY_HOURS, Long.parseLong(m.group(21), 16) * 60000)
                } else {
                    pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(21))
                }
            }
            if (m.group(22) != null) pos.set(Position.PREFIX_TEMP + 1, Integer.parseInt(m.group(22), 16) / 100.0)
            if (m.group(23) != null) pos.set(Position.PREFIX_ADC + 1, Double.parseDouble(m.group(23)))
            if (m.group(24) != null) pos.set(Position.KEY_SATELLITES, m.group(24).toInteger())

            return pos
        }
    }
}
