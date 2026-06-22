// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * OsmAnd GPS tracking protocol driver.
 *
 * HTTP protocol supporting JSON (OsmAnd background service) and query-string
 * (OsmAnd custom URL / generic HTTP) formats.
 */

import org.traccar.model.Position
import org.traccar.model.Network
import org.traccar.model.CellTower
import org.traccar.model.WifiAccessPoint
import org.traccar.helper.DateUtil
import org.traccar.helper.UnitsConverter
import jakarta.json.JsonObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

def DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

def decodeJson = { req, ctx ->
    try {
        def root = req.jsonObject()
        def session = ctx.session(root.getString("device_id"))
        if (!session) {
            ctx.badRequest()
            return null
        }

        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId

        def location = root.getJsonObject("location")
        pos.time = DateUtil.parseDate(location.getString("timestamp"))

        if (location.containsKey("coords")) {
            def coordinates = location.getJsonObject("coords")
            pos.valid = true
            pos.latitude = coordinates.getJsonNumber("latitude").doubleValue()
            pos.longitude = coordinates.getJsonNumber("longitude").doubleValue()
            double speed = coordinates.getJsonNumber("speed").doubleValue()
            if (speed >= 0) pos.speed = UnitsConverter.knotsFromMps(speed)
            double heading = coordinates.getJsonNumber("heading").doubleValue()
            if (heading >= 0) pos.course = heading
            double accuracy = coordinates.getJsonNumber("accuracy").doubleValue()
            if (accuracy >= 0) pos.accuracy = accuracy
            pos.altitude = coordinates.getJsonNumber("altitude").doubleValue()
        } else {
            ctx.lastLocation(pos, null)
        }

        if (location.containsKey("event")) pos.set(Position.KEY_EVENT, location.getString("event"))
        if (location.containsKey("is_moving")) pos.set(Position.KEY_MOTION, location.getBoolean("is_moving"))
        if (location.containsKey("odometer")) pos.set(Position.KEY_ODOMETER, location.getInt("odometer"))
        if (location.containsKey("mock")) pos.set("mock", location.getBoolean("mock"))
        if (location.containsKey("activity")) {
            pos.set("activity", location.getJsonObject("activity").getString("type"))
        }
        if (location.containsKey("battery")) {
            def battery = location.getJsonObject("battery")
            double level = battery.getJsonNumber("level").doubleValue()
            if (level >= 0) pos.set(Position.KEY_BATTERY_LEVEL, (int) (level * 100))
            if (battery.getBoolean("is_charging")) pos.set(Position.KEY_CHARGE, true)
        }
        if (location.containsKey("alarm")) {
            pos.set(Position.KEY_ALARM, location.getString("alarm"))
        } else if (location.containsKey("extras")) {
            def extras = location.getJsonObject("extras")
            if (extras.containsKey("alarm")) pos.set(Position.KEY_ALARM, extras.getString("alarm"))
        }

        ctx.ok()
        return pos
    } catch (Exception e) {
        ctx.badRequest()
        return null
    }
}

def decodeQuery = { req, ctx ->
    try {
        def params = req.params()
        def pos = ctx.newPosition()
        pos.valid = true

        Network network = new Network()
        Double latitude = null
        Double longitude = null

        for (def entry : params.entrySet()) {
            for (String value : entry.getValue()) {
                switch (entry.getKey()) {
                    case "id":
                    case "deviceid":
                        def session = ctx.session(value)
                        if (!session) {
                            ctx.badRequest()
                            return null
                        }
                        pos.deviceId = session.deviceId
                        break
                    case "valid":
                        pos.valid = Boolean.parseBoolean(value) || "1".equals(value)
                        break
                    case "timestamp":
                        try {
                            long ts = Long.parseLong(value)
                            if (ts < Integer.MAX_VALUE) ts *= 1000
                            pos.time = new Date(ts)
                        } catch (NumberFormatException ignored) {
                            if (value.contains("T")) {
                                pos.time = DateUtil.parseDate(value)
                            } else {
                                pos.time = DateUtil.parse(DATE_FORMAT, value)
                            }
                        }
                        break
                    case "lat":
                        latitude = Double.parseDouble(value)
                        break
                    case "lon":
                        longitude = Double.parseDouble(value)
                        break
                    case "location":
                        def parts = value.split(",")
                        latitude = Double.parseDouble(parts[0])
                        longitude = Double.parseDouble(parts[1])
                        break
                    case "cell":
                        def cell = value.split(",")
                        if (cell.length > 4) {
                            network.addCellTower(CellTower.from(
                                    Integer.parseInt(cell[0]), Integer.parseInt(cell[1]),
                                    Integer.parseInt(cell[2]), Integer.parseInt(cell[3]),
                                    Integer.parseInt(cell[4])))
                        } else {
                            network.addCellTower(CellTower.from(
                                    Integer.parseInt(cell[0]), Integer.parseInt(cell[1]),
                                    Integer.parseInt(cell[2]), Integer.parseInt(cell[3])))
                        }
                        break
                    case "wifi":
                        def wifi = value.split(",")
                        network.addWifiAccessPoint(WifiAccessPoint.from(
                                wifi[0].replace('-', ':'), Integer.parseInt(wifi[1])))
                        break
                    case "speed":
                        pos.speed = Double.parseDouble(value)
                        break
                    case "bearing":
                    case "heading":
                        pos.course = Double.parseDouble(value)
                        break
                    case "altitude":
                        pos.altitude = Double.parseDouble(value)
                        break
                    case "accuracy":
                        pos.accuracy = Double.parseDouble(value)
                        break
                    case "hdop":
                        pos.set(Position.KEY_HDOP, Double.parseDouble(value))
                        break
                    case "batt":
                        pos.set(Position.KEY_BATTERY_LEVEL, Double.parseDouble(value))
                        break
                    case "driverUniqueId":
                        pos.set(Position.KEY_DRIVER_UNIQUE_ID, value)
                        break
                    case "charge":
                        pos.set(Position.KEY_CHARGE, Boolean.parseBoolean(value))
                        break
                    default:
                        try {
                            pos.set(entry.getKey(), Double.parseDouble(value))
                        } catch (NumberFormatException ignored) {
                            switch (value) {
                                case "true": pos.set(entry.getKey(), true); break
                                case "false": pos.set(entry.getKey(), false); break
                                default: pos.set(entry.getKey(), value)
                            }
                        }
                }
            }
        }

        if (pos.fixTime == null) {
            pos.time = new Date()
        }

        if (network.getCellTowers() != null || network.getWifiAccessPoints() != null) {
            pos.network = network
        }

        if (latitude != null && longitude != null) {
            pos.latitude = latitude
            pos.longitude = longitude
        } else {
            ctx.lastLocation(pos, pos.deviceTime)
        }

        if (pos.deviceId > 0) {
            ctx.ok()
            return pos
        } else {
            ctx.badRequest()
            return null
        }
    } catch (Exception e) {
        ctx.badRequest()
        return null
    }
}

protocol("osmand") {

    port 5055
    transport 'http'

    variant("json") {
        matches { req -> req.contentType()?.contains("application/json") ?: false }
        decode { req, ctx -> decodeJson(req, ctx) }
    }

    variant("query") {
        matches { req -> true }
        decode { req, ctx -> decodeQuery(req, ctx) }
    }
}
