// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Globalstar GPS device protocol driver.
 *
 * HTTP protocol supporting JSON and XML payloads from Globalstar satellite devices.
 * AtlasTrax model is detected via session.getModel().
 */

import org.traccar.model.Position
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.helper.DataConverter
import io.netty.buffer.Unpooled

import jakarta.json.JsonObject

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import org.w3c.dom.Node
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

def DATE_FORMAT = DateTimeFormatter
        .ofPattern("dd/MM/yyyy hh:mm:ss z", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance()
builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
builderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
builderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
builderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
builderFactory.setXIncludeAware(false)
builderFactory.setExpandEntityReferences(false)
DocumentBuilder docBuilder = builderFactory.newDocumentBuilder()
XPath xPath = XPathFactory.newInstance().newXPath()

def decodeJson = { req, ctx ->
    try {
        def root = req.jsonObject()
        def devices = root.getJsonObject("entry").getJsonArray("devices")
        List<Position> positions = []

        for (JsonObject data : devices.getValuesAs(JsonObject.class)) {
            def deviceIdentify = data.getJsonObject("deviceIdentify")
            def session = ctx.session(deviceIdentify.getString("esn"))
            if (!session) continue

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.time = new Date(deviceIdentify.getJsonNumber("unixTime").longValue() * 1000)

            def gpsCoordinate = data.getJsonObject("gpsCoordinate")
            def deviceInfo = data.getJsonObject("deviceInfo")

            String latitude = gpsCoordinate.getString("latitude")
            String longitude = gpsCoordinate.getString("longitude")
            if (!latitude.isEmpty() && !longitude.isEmpty()) {
                pos.valid = deviceInfo.getString("gpsDataValid").equals("Valid")
                pos.latitude = Double.parseDouble(latitude)
                pos.longitude = Double.parseDouble(longitude)
            } else {
                ctx.lastLocation(pos, pos.deviceTime)
            }

            if (deviceInfo.getString("batteryStatus").equals("Low")) {
                pos.set(Position.KEY_ALARM, Position.ALARM_LOW_BATTERY)
            }

            positions.add(pos)
        }

        ctx.json(200, '{"status":"ok"}')
        return !positions.isEmpty() ? (positions.size() == 1 ? positions[0] : positions) : null
    } catch (Exception e) {
        ctx.badRequest()
        return null
    }
}

def decodeXml = { req, ctx ->
    try {
        def document = docBuilder.parse(new java.io.ByteArrayInputStream(req.bytes()))
        def messageExpression = xPath.compile("//stuMessages/stuMessage")
        NodeList nodes = (NodeList) messageExpression.evaluate(document, XPathConstants.NODESET)

        List<Position> positions = []

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i)
            String esn = xPath.evaluate("esn", node)
            def session = ctx.session(esn)
            if (!session) continue

            boolean isAtlasTrax = "AtlasTrax".equalsIgnoreCase(session.getModel())
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId

            long unixTime = Long.parseLong(xPath.evaluate("unixTime", node))
            pos.time = new Date(unixTime * 1000)

            String payload = xPath.evaluate("payload", node)
            def buf = Unpooled.wrappedBuffer(DataConverter.parseHex(payload.substring(2)))

            int flags = buf.readUnsignedByte()
            int type

            if (isAtlasTrax) {
                type = BitUtil.to(flags, 1)
                pos.valid = true
                pos.set(Position.PREFIX_IN + 1, !BitUtil.check(flags, 1))
                pos.set(Position.PREFIX_IN + 2, !BitUtil.check(flags, 2))
                pos.set(Position.KEY_CHARGE, !BitUtil.check(flags, 3))
                if (BitUtil.check(flags, 4)) pos.addAlarm(Position.ALARM_VIBRATION)
                pos.course = BitUtil.from(flags, 5) * 45
            } else {
                type = BitUtil.to(flags, 2)
                if (BitUtil.check(flags, 2)) pos.set("batteryReplace", true)
                pos.valid = !BitUtil.check(flags, 3)
            }

            double lat = buf.readUnsignedMedium() * 90.0 / (1 << 23)
            pos.latitude = lat > 90 ? lat - 180 : lat

            double lon = buf.readUnsignedMedium() * 180.0 / (1 << 23)
            pos.longitude = lon > 180 ? lon - 360 : lon

            int speed = 0
            if (isAtlasTrax) {
                speed = buf.readUnsignedByte()
                pos.speed = UnitsConverter.knotsFromKph(speed)
                pos.set("batteryReplace", BitUtil.check(buf.readUnsignedByte(), 7))
            } else if (type == 0) {
                pos.set(Position.KEY_INPUT, BitUtil.to(buf.readUnsignedByte(), 4))
                int other = buf.readUnsignedByte()
                if (BitUtil.check(other, 4)) pos.addAlarm(Position.ALARM_VIBRATION)
                pos.set(Position.KEY_MOTION, BitUtil.check(other, 6))
            }

            if (speed != 0xff) {
                positions.add(pos)
            }
        }

        // Build and send XML acknowledgement
        def responseDoc = docBuilder.newDocument()
        def rootEl = responseDoc.createElement("stuResponseMsg")
        rootEl.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        rootEl.setAttribute("xsi:noNamespaceSchemaLocation", "http://cody.glpconnect.com/XSD/StuResponse_Rev1_0.xsd")
        rootEl.setAttribute("deliveryTimeStamp", DATE_FORMAT.format(Instant.now()))
        rootEl.setAttribute("messageID", "00000000000000000000000000000000")
        def msgIdAttr = document.getFirstChild()?.getAttributes()?.getNamedItem("messageID")
        if (msgIdAttr) rootEl.setAttribute("correlationID", msgIdAttr.getNodeValue())
        responseDoc.appendChild(rootEl)
        def stateEl = responseDoc.createElement("state")
        stateEl.appendChild(responseDoc.createTextNode("pass"))
        rootEl.appendChild(stateEl)
        def stateMsgEl = responseDoc.createElement("stateMessage")
        stateMsgEl.appendChild(responseDoc.createTextNode("Store OK"))
        rootEl.appendChild(stateMsgEl)

        Transformer transformer = TransformerFactory.newInstance().newTransformer()
        StringWriter sw = new StringWriter()
        transformer.transform(new DOMSource(responseDoc), new StreamResult(sw))
        ctx.binary(200, sw.toString().getBytes("UTF-8"), "text/xml")

        return !positions.isEmpty() ? (positions.size() == 1 ? positions[0] : positions) : null
    } catch (Exception e) {
        ctx.badRequest()
        return null
    }
}

protocol("globalstar") {

    port 5253
    transport 'http'

    variant("json") {
        matches { req -> req.contentType()?.contains("application/json") ?: false }
        decode { req, ctx -> decodeJson(req, ctx) }
    }

    variant("xml") {
        matches { req -> true }
        decode { req, ctx -> decodeXml(req, ctx) }
    }
}
