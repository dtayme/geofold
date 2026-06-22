// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Globalstar SmartOne / AtlasTrax driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/globalstar/
 *   archived-protocols/globalstar/
 *
 * HTTP POST protocol with two message formats:
 *   - XML: stuMessages/stuMessage nodes with binary hex payload (main Globalstar format).
 *   - JSON: entry.devices array (SmartOne web-push variant).
 *
 * Device model "AtlasTrax" (case-insensitive) activates the AtlasTrax payload layout,
 * which differs from the standard SmartOne layout in flags, speed, and I/O fields.
 *
 * Binary payload layout (after 0x prefix):
 *   Byte 0 — flags (type bits, valid, I/O, alarms vary by model)
 *   Bytes 1-3 — latitude  (24-bit unsigned: value * 90 / 2^23, wrap at 90)
 *   Bytes 4-6 — longitude (24-bit unsigned: value * 180 / 2^23, wrap at 180)
 *   Remaining bytes vary by device type/model.
 */

import io.netty.buffer.Unpooled
import org.traccar.helper.BitUtil
import org.traccar.helper.DataConverter
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

def DATE_FORMAT = DateTimeFormatter
    .ofPattern('dd/MM/yyyy hh:mm:ss z', Locale.ENGLISH)
    .withZone(ZoneId.systemDefault())

protocol("globalstar") {

    port 5185
    transport 'http'

    variant("xml") {

        matches { req -> !'application/json'.equals(req.header('Content-Type')) }

        decode { req, ctx ->
            def root = new XmlSlurper().parseText(req.content())
            def messageId = root.@messageID.text()

            root.stuMessage.each { node ->
                def session = ctx.session(node.esn.text())
                if (!session) return

                def attrs = ctx.deviceAttrs(session)
                boolean atlas = 'atlaxtrax'.equalsIgnoreCase(attrs.model())

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                pos.time     = new Date(node.unixTime.text().toLong() * 1000)

                def payloadHex = node.payload.text()
                if (payloadHex.length() < 2) return
                def buf = Unpooled.wrappedBuffer(DataConverter.parseHex(payloadHex.substring(2)))

                try {
                    int flags = buf.readUnsignedByte()
                    int type

                    if (atlas) {
                        type = BitUtil.to(flags, 1)
                        pos.valid = true
                        pos.set(Position.PREFIX_IN + '1', !BitUtil.check(flags, 1))
                        pos.set(Position.PREFIX_IN + '2', !BitUtil.check(flags, 2))
                        pos.set(Position.KEY_CHARGE, !BitUtil.check(flags, 3))
                        if (BitUtil.check(flags, 4)) pos.addAlarm(Position.ALARM_VIBRATION)
                        pos.course = BitUtil.from(flags, 5) * 45
                    } else {
                        type = BitUtil.to(flags, 2)
                        if (BitUtil.check(flags, 2)) pos.set('batteryReplace', true)
                        pos.valid = !BitUtil.check(flags, 3)
                    }

                    double lat = buf.readUnsignedMedium() * 90.0 / (1 << 23)
                    pos.latitude  = lat > 90 ? lat - 180 : lat

                    double lon = buf.readUnsignedMedium() * 180.0 / (1 << 23)
                    pos.longitude = lon > 180 ? lon - 360 : lon

                    int speed = 0
                    if (atlas) {
                        speed = buf.readUnsignedByte()
                        pos.speed = UnitsConverter.knotsFromKph(speed)
                        pos.set('batteryReplace', BitUtil.check(buf.readUnsignedByte(), 7))
                    } else if (type == 0) {
                        pos.set(Position.KEY_INPUT, BitUtil.to(buf.readUnsignedByte(), 4))
                        int other = buf.readUnsignedByte()
                        if (BitUtil.check(other, 4)) pos.addAlarm(Position.ALARM_VIBRATION)
                        pos.set(Position.KEY_MOTION, BitUtil.check(other, 6))
                    }

                    if (speed != 0xff) ctx.emit(pos)
                } finally {
                    buf.release()
                }
            }

            def now = DATE_FORMAT.format(Instant.now())
            ctx.xml(200, """<?xml version="1.0" encoding="UTF-8" standalone="no"?><stuResponseMsg xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://cody.glpconnect.com/XSD/StuResponse_Rev1_0.xsd" deliveryTimeStamp="${now}" messageID="00000000000000000000000000000000" correlationID="${messageId}"><state>pass</state><stateMessage>Store OK</stateMessage></stuResponseMsg>""")
            return null
        }
    }

    variant("json") {

        matches { req -> 'application/json'.equals(req.header('Content-Type')) }

        decode { req, ctx ->
            def root    = req.jsonObject()
            def devices = root.getJsonObject('entry').getJsonArray('devices')

            devices.each { data ->
                def deviceIdentify = data.getJsonObject('deviceIdentify')
                def session = ctx.session(deviceIdentify.getString('esn'))
                if (!session) return

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                pos.time     = new Date(deviceIdentify.getJsonNumber('unixTime').longValue() * 1000)

                def gpsCoordinate = data.getJsonObject('gpsCoordinate')
                def deviceInfo    = data.getJsonObject('deviceInfo')

                def lat = gpsCoordinate.getString('latitude')
                def lon = gpsCoordinate.getString('longitude')
                if (lat && lon) {
                    pos.valid     = 'Valid' == deviceInfo.getString('gpsDataValid')
                    pos.latitude  = Double.parseDouble(lat)
                    pos.longitude = Double.parseDouble(lon)
                } else {
                    ctx.lastLocation(pos, pos.deviceTime)
                }

                if ('Low' == deviceInfo.getString('batteryStatus')) {
                    pos.set(Position.KEY_ALARM, Position.ALARM_LOW_BATTERY)
                }

                ctx.emit(pos)
            }

            ctx.json(200, '{"status":"ok"}')
            return null
        }
    }
}
