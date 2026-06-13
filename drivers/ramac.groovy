// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * RAMAC P1 multi-event callback driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/ramac/
 *
 * HTTP JSON callback protocol. Handles status, GPS, and alert payloads from
 * the documented multi-event endpoint and returns the required CaseID/EventID
 * JSON acknowledgement.
 */

import org.traccar.model.Position

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

def DATE_FORMAT = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss')

def parseDate = { String value ->
    Date.from(LocalDateTime.parse(value, DATE_FORMAT).atZone(ZoneId.systemDefault()).toInstant())
}

protocol("ramac") {

    port 5251
    transport 'http'

    variant("json") {

        matches { req -> req.method() == 'POST' }

        decode { req, ctx ->
            def root = req.jsonObject()

            def session = ctx.session(root.getString('DeviceId'))
            if (!session) {
                ctx.badRequest()
                return null
            }

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            pos.set(Position.KEY_TYPE, root.getInt('PacketType'))
            pos.set(Position.KEY_INDEX, root.getInt('SeqNumber'))
            pos.deviceTime = parseDate(root.getString('UpdateDate'))

            int alert = root.getInt('Alert')
            if (alert > 0) {
                pos.set('alert', alert)
                String alertMessage = root.getString('AlertMessage')
                if (!alertMessage.isEmpty()) {
                    pos.set('alertMessage', alertMessage)
                }
            }

            if (root.containsKey('GpsEvent')) {
                pos.set('gpsEvent', root.getInt('GpsEvent'))
                if (root.containsKey('GpsEventText')) {
                    pos.set('gpsEventText', root.getString('GpsEventText'))
                }
            }

            if (root.containsKey('Event')) {
                pos.set(Position.KEY_EVENT, root.getInt('Event'))
            }
            if (root.containsKey('BatteryPercentage')) {
                pos.set(Position.KEY_BATTERY_LEVEL, root.getInt('BatteryPercentage'))
            }
            if (root.containsKey('Battery')) {
                pos.set(Position.KEY_BATTERY, root.getJsonNumber('Battery').doubleValue())
            }

            pos.set('deviceType', root.getString('DeviceTypeText'))

            if (root.containsKey('Latitude') && root.containsKey('Longitude')) {
                pos.valid = true
                pos.fixTime = root.containsKey('LocationDateTime')
                        ? parseDate(root.getString('LocationDateTime'))
                        : pos.deviceTime
                pos.latitude = root.getJsonNumber('Latitude').doubleValue()
                pos.longitude = root.getJsonNumber('Longitude').doubleValue()
            } else {
                ctx.lastLocation(pos, pos.deviceTime)
            }

            ctx.json(200, '{"CaseID":1,"EventID":1}')
            return pos
        }
    }
}
