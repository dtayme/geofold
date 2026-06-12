// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Flextrack GPS tracker driver.
 *
 * Messages are CR-terminated. Two message types:
 *
 *   LOGON — device identification:
 *     <index>,LOGON,<nodeId>,<iccid>
 *     Server responds with: <index>,ACK\r
 *     Registers the device session with iccid (preferred) or nodeId.
 *
 *   UNITSTAT — position report (uses existing channel session):
 *     <index>,UNITSTAT,<yyyyMMdd>,<hhmmss>,<nodeId>,<NS><deg>.<min>,<EW><deg>.<min>,
 *     <speed_kph>,<course>,<sats>,<battery>,<rssi>,<hex_status>,<mcc><mnc>,<alt>,
 *     <hdop_x10>,<hex_cell>,<gps_fix_time>,<hex_lac>,<odometer>
 *
 * Coordinates: hemisphere + degrees + '.' + decimal minutes (e.g. N55.46.0812).
 * HDOP stored as value / 10.0. Cell tower included in network info.
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.regex.Pattern

def PATTERN_LOGON = Pattern.compile(
    /^(-?\d+),LOGON,(\d+),(\d+)/)

def PATTERN_UNIT = Pattern.compile(
    /^(-?\d+),UNITSTAT,(\d{4})(\d{2})(\d{2}),(\d{2})(\d{2})(\d{2}),\d+,([NS])(\d+)\.(\d+\.\d+),([EW])(\d+)\.(\d+\.\d+),(\d+),(\d+),(\d+),(\d+),(-?\d+),([0-9a-fA-F]+),(\d{3})(\d{2}),(-?\d+),(\d+),([0-9a-fA-F]+),\d+,([0-9a-fA-F]+),(\d+)/)

protocol("flextrack") {

    port 5090

    variant("main") {

        maxFrameLength 512
        frame readUntil("\r")

        decode { msg, ctx ->

            if (msg.contains('LOGON')) {
                def m = PATTERN_LOGON.matcher(msg)
                if (!m.find()) return null

                ctx.ack(m.group(1) + ",ACK\r")

                String nodeId = m.group(2)
                String iccid  = m.group(3)

                // Register session with iccid preferred, fall back to nodeId
                ctx.session(iccid) ?: ctx.session(nodeId)

            } else if (msg.contains('UNITSTAT')) {
                def session = ctx.session()
                if (!session) return null

                def m = PATTERN_UNIT.matcher(msg)
                if (!m.find()) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                ctx.ack(m.group(1) + ",ACK\r")

                pos.time = new DateBuilder()
                        .setDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
                        .setTime(m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
                        .getDate()

                pos.valid = true

                double lat = m.group(9).toInteger() + m.group(10).toDouble() / 60
                pos.latitude = m.group(8) == 'S' ? -lat : lat

                double lon = m.group(12).toInteger() + m.group(13).toDouble() / 60
                pos.longitude = m.group(11) == 'W' ? -lon : lon

                pos.speed  = UnitsConverter.knotsFromKph(m.group(14).toInteger())
                pos.course = m.group(15).toDouble()

                pos.set(Position.KEY_SATELLITES, m.group(16).toInteger())
                pos.set(Position.KEY_BATTERY,    m.group(17).toInteger())

                int rssi = m.group(18).toInteger()
                pos.set(Position.KEY_STATUS, Integer.parseInt(m.group(19), 16))

                int mcc = m.group(20).toInteger()
                int mnc = m.group(21).toInteger()

                pos.altitude = m.group(22).toInteger()
                pos.set(Position.KEY_HDOP, m.group(23).toInteger() / 10.0)

                int cell = Integer.parseInt(m.group(24), 16)
                int lac  = Integer.parseInt(m.group(25), 16)

                pos.set(Position.KEY_ODOMETER, m.group(26).toInteger())

                pos.network = new Network(CellTower.from(mcc, mnc, lac, cell, rssi))

                return pos
            }

            return null
        }
    }
}
