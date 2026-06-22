// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * OsmAnd / Traccar Client driver.
 *
 * Source documentation:
 *   https://www.traccar.org/osmand/
 *
 * HTTP GET/POST protocol used by the OsmAnd Android app and Traccar Client.
 * Supports two message formats:
 *   - Query-string: params in URI or form-encoded body; cell/wifi network data;
 *     queued command returned in response body.
 *   - JSON body (BackgroundGeolocation format): coords/battery/activity sub-objects.
 *
 * Config:
 *   osmand.minAccuracy  — minimum accuracy (metres) to record in JSON mode (default 0)
 */

import org.traccar.helper.DateUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.time.ZoneId
import java.time.format.DateTimeFormatter

def DATE_FORMAT = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss').withZone(ZoneId.systemDefault())

def parseTimestamp = { String value ->
    try {
        long ts = Long.parseLong(value)
        if (ts < 0x7fffffffL) ts *= 1000
        new Date(ts)
    } catch (NumberFormatException ignored) {
        if (value.contains('T')) DateUtil.parseDate(value)
        else DateUtil.parse(DATE_FORMAT, value)
    }
}

protocol("osmand") {

    port 5055
    transport 'http'

    variant("query") {

        matches { req ->
            def ct = req.contentType()
            ct == null || !ct.startsWith('application/json')
        }

        decode { req, ctx ->
            def params = req.params()
            if (params.isEmpty()) params = req.bodyParams()

            def pos = ctx.newPosition()
            pos.valid = true

            def network = new Network()
            Double latitude = null
            Double longitude = null

            for (def entry : params.entrySet()) {
                for (def value : entry.value) {
                    switch (entry.key) {
                        case 'id':
                        case 'deviceid':
                            def session = ctx.session(value)
                            if (!session) { ctx.badRequest(); return null }
                            pos.deviceId = session.deviceId
                            break
                        case 'notificationToken':
                            break
                        case 'valid':
                            pos.valid = value == 'true' || value == '1'
                            break
                        case 'timestamp':
                            pos.time = parseTimestamp(value)
                            break
                        case 'lat':
                            latitude = Double.parseDouble(value)
                            break
                        case 'lon':
                            longitude = Double.parseDouble(value)
                            break
                        case 'location':
                            def parts = value.split(',')
                            latitude = Double.parseDouble(parts[0])
                            longitude = Double.parseDouble(parts[1])
                            break
                        case 'cell':
                            def c = value.split(',')
                            if (c.length > 4) {
                                network.addCellTower(CellTower.from(
                                    c[0].toInteger(), c[1].toInteger(),
                                    c[2].toInteger(), c[3].toInteger(), c[4].toInteger()))
                            } else {
                                network.addCellTower(CellTower.from(
                                    c[0].toInteger(), c[1].toInteger(),
                                    c[2].toInteger(), c[3].toInteger()))
                            }
                            break
                        case 'wifi':
                            def w = value.split(',')
                            network.addWifiAccessPoint(WifiAccessPoint.from(
                                w[0].replace('-', ':'), w[1].toInteger()))
                            break
                        case 'speed':
                            pos.speed = Double.parseDouble(value)
                            break
                        case 'bearing':
                        case 'heading':
                            pos.course = Double.parseDouble(value)
                            break
                        case 'altitude':
                            pos.altitude = Double.parseDouble(value)
                            break
                        case 'accuracy':
                            pos.accuracy = Double.parseDouble(value)
                            break
                        case 'hdop':
                            pos.set(Position.KEY_HDOP, Double.parseDouble(value))
                            break
                        case 'batt':
                            pos.set(Position.KEY_BATTERY_LEVEL, Double.parseDouble(value))
                            break
                        case 'driverUniqueId':
                            pos.set(Position.KEY_DRIVER_UNIQUE_ID, value)
                            break
                        case 'charge':
                            pos.set(Position.KEY_CHARGE, Boolean.parseBoolean(value))
                            break
                        default:
                            try {
                                pos.set(entry.key, Double.parseDouble(value))
                            } catch (NumberFormatException ignored) {
                                switch (value) {
                                    case 'true':  pos.set(entry.key, true);  break
                                    case 'false': pos.set(entry.key, false); break
                                    default:      pos.set(entry.key, value); break
                                }
                            }
                            break
                    }
                }
            }

            if (pos.deviceId == 0) { ctx.badRequest(); return null }
            if (pos.fixTime == null) pos.time = new Date()
            if (network.cellTowers != null || network.wifiAccessPoints != null) pos.network = network

            if (latitude != null && longitude != null) {
                pos.latitude = latitude
                pos.longitude = longitude
            } else {
                ctx.lastLocation(pos, pos.deviceTime)
            }

            def cmd = ctx.nextQueuedCommand(pos.deviceId)
            if (cmd != null) {
                ctx.ok(cmd.getString('data'))
            } else {
                ctx.ok()
            }
            return pos
        }
    }

    variant("json") {

        matches { req -> req.contentType()?.startsWith('application/json') }

        decode { req, ctx ->
            def root = req.jsonObject()
            def session = ctx.session(root.getString('device_id'))
            if (!session) { ctx.notFound(); return null }

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            def location = root.getJsonObject('location')
            pos.time = DateUtil.parseDate(location.getString('timestamp'))

            double minAccuracy = ctx.configString('minAccuracy', '0').toDouble()

            if (location.containsKey('coords')) {
                def coords = location.getJsonObject('coords')
                pos.valid = true
                pos.latitude = coords.getJsonNumber('latitude').doubleValue()
                pos.longitude = coords.getJsonNumber('longitude').doubleValue()
                double speed = coords.getJsonNumber('speed').doubleValue()
                if (speed >= 0) pos.speed = UnitsConverter.knotsFromMps(speed)
                double heading = coords.getJsonNumber('heading').doubleValue()
                if (heading >= 0) pos.course = heading
                double accuracy = coords.getJsonNumber('accuracy').doubleValue()
                if (accuracy >= minAccuracy) pos.accuracy = accuracy
                pos.altitude = coords.getJsonNumber('altitude').doubleValue()
            } else {
                ctx.lastLocation(pos, null)
            }

            if (location.containsKey('event'))     pos.set(Position.KEY_EVENT, location.getString('event'))
            if (location.containsKey('is_moving')) pos.set(Position.KEY_MOTION, location.getBoolean('is_moving'))
            if (location.containsKey('odometer'))  pos.set(Position.KEY_ODOMETER, location.getInt('odometer'))
            if (location.containsKey('mock'))      pos.set('mock', location.getBoolean('mock'))
            if (location.containsKey('activity'))  pos.set('activity', location.getJsonObject('activity').getString('type'))

            if (location.containsKey('battery')) {
                def battery = location.getJsonObject('battery')
                double level = battery.getJsonNumber('level').doubleValue()
                if (level >= 0) pos.set(Position.KEY_BATTERY_LEVEL, (int)(level * 100))
                if (battery.getBoolean('is_charging')) pos.set(Position.KEY_CHARGE, true)
            }

            if (location.containsKey('alarm')) {
                pos.set(Position.KEY_ALARM, location.getString('alarm'))
            } else if (location.containsKey('extras')) {
                def extras = location.getJsonObject('extras')
                if (extras.containsKey('alarm')) pos.set(Position.KEY_ALARM, extras.getString('alarm'))
            }

            ctx.ok()
            return pos
        }
    }
}
