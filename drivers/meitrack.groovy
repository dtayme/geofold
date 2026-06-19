// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Meitrack GPS tracker driver.
 *
 * Framing: `$$X<decimal_length>,<rest>` where total = commaOffset + decimal_length.
 * Text format (AAA, AFF, D82, etc.): comma-delimited ASCII.
 * Binary CCC: batch of 0x34-byte little-endian position records.
 * Binary CCE: TLV batch records with 4 param-size passes (1/2/4/variable bytes).
 *
 * Checksum in commands: `Checksum.sum(prefix)` (byte sum, decimal).
 */

import org.traccar.driver.BufReader
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

def PATTERN = Pattern.compile(
    "\\$\\$." +                                                         // flag
    "\\d+," +                                                           // length
    "(\\d+)," +                                                         // 1: imei
    "[0-9a-fA-F]{3}," +                                                // command
    "(?:\\d+,)?" +                                                      // optional counter
    "(\\d+)," +                                                         // 2: event
    "(-?\\d+\\.\\d+)," +                                               // 3: latitude
    "(-?\\d+\\.\\d+)," +                                               // 4: longitude
    "(\\d{2})(\\d{2})(\\d{2})" +                                      // 5,6,7: yymmdd
    "(\\d{2})(\\d{2})(\\d{2})," +                                     // 8,9,10: hhmmss
    "([AV])," +                                                         // 11: validity
    "(\\d+)," +                                                         // 12: satellites
    "(\\d+)," +                                                         // 13: rssi
    "(\\d+\\.?\\d*)," +                                                // 14: speed
    "(\\d+)," +                                                         // 15: course
    "(\\d+\\.?\\d*)," +                                                // 16: hdop
    "(-?\\d+)," +                                                       // 17: altitude
    "(\\d+)," +                                                         // 18: odometer
    "(\\d+)," +                                                         // 19: runtime
    "(\\d+)\\|" +                                                       // 20: mcc
    "(\\d+)\\|" +                                                       // 21: mnc
    "([0-9a-fA-F]+)?\\|" +                                             // 22: lac
    "([0-9a-fA-F]+)?," +                                               // 23: cid
    "([0-9a-fA-F]{2})" +                                               // 24: input
    "([0-9a-fA-F]{2})," +                                              // 25: output
    "(?:" +
        "(\\d+\\.\\d+)\\|(\\d+\\.\\d+)\\|\\d+\\.\\d+\\|\\d+\\.\\d+\\|\\d+\\.\\d+," +  // 26,27: decimal bat/power
        "|([0-9a-fA-F]+)?\\|([0-9a-fA-F]+)?\\|([0-9a-fA-F]+)?\\|([0-9a-fA-F]+)\\|([0-9a-fA-F]+)?," + // 28-32: hex adc/bat/power
    ")" +
    "(?:" +
        "(?:([^,]+),)?" +                                               // 33: event specific (optional)
        "[^,]*," +                                                       // reserved
        "(\\d+)?," +                                                    // 34: protocol
        "([0-9a-fA-F]{4})?" +                                          // 35: fuel
        "(?:,([0-9a-fA-F]{6}(?:\\|[0-9a-fA-F]{6})*)?" +              // 36: temperature entries
            "(?:,(\\d+),([^*]*))?" +                                    // 37,38: data count + data
        ")?" +
        "|.*" +                                                          // catch-all
    ")" +
    "\\*[0-9a-fA-F]{2}" +
    "(?:\\r\\n)?",
    Pattern.DOTALL
)

def decodeAlarm = { int event ->
    switch (event) {
        case 1:   return Position.ALARM_SOS
        case 17:  return Position.ALARM_LOW_BATTERY
        case 18:  return Position.ALARM_LOW_POWER
        case 19:  return Position.ALARM_OVERSPEED
        case 20:  return Position.ALARM_GEOFENCE_ENTER
        case 21:  return Position.ALARM_GEOFENCE_EXIT
        case 22:  return Position.ALARM_POWER_RESTORED
        case 23:  return Position.ALARM_POWER_CUT
        case 36:  return Position.ALARM_TOW
        case 44:  return Position.ALARM_JAMMING
        case 78:  return Position.ALARM_ACCIDENT
        case 90:
        case 91:  return Position.ALARM_CORNERING
        case 129: return Position.ALARM_BRAKING
        case 130: return Position.ALARM_ACCELERATION
        case 135: return Position.ALARM_FATIGUE_DRIVING
        default:  return null
    }
}

def decodeDataFields = { Position pos, String[] values ->
    if (values.length > 1 && !values[1].isEmpty()) {
        pos.set("tempData", values[1])
    }
    if (values.length > 5 && !values[5].isEmpty()) {
        String[] data = values[5].split("\\|")
        boolean started = data[0].charAt(1) == '0'
        pos.set("taximeterOn", started)
        pos.set("taximeterStart", data[1])
        if (data.length > 2) {
            pos.set("taximeterEnd", data[2])
            pos.set("taximeterDistance", data[3].toInteger())
            pos.set("taximeterFare", data[4].toInteger())
            pos.set("taximeterTrip", data[5])
            pos.set("taximeterWait", data[6])
        }
    }
}

def decodeRegular = { String text, ctx ->
    def m = PATTERN.matcher(text)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    Position pos = ctx.newPosition()

    int event = m.group(2).toInteger()
    pos.set(Position.KEY_EVENT, event)
    def alarm = decodeAlarm(event)
    if (alarm) pos.addAlarm(alarm)

    pos.setLatitude(m.group(3).toDouble())
    pos.setLongitude(m.group(4).toDouble())

    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(m.group(7).toInteger() + 2000, m.group(6).toInteger() - 1, m.group(5).toInteger(),
            m.group(8).toInteger(), m.group(9).toInteger(), m.group(10).toInteger())
    cal.set(Calendar.MILLISECOND, 0)
    pos.setTime(cal.getTime())

    pos.setValid(m.group(11) == "A")
    pos.set(Position.KEY_SATELLITES, m.group(12).toInteger())
    int rssi = m.group(13).toInteger()

    pos.setSpeed(UnitsConverter.knotsFromKph(m.group(14).toDouble()))
    pos.setCourse(m.group(15).toDouble())
    pos.set(Position.KEY_HDOP, m.group(16).toDouble())
    pos.setAltitude(m.group(17).toDouble())
    pos.set(Position.KEY_ODOMETER, m.group(18).toLong())
    pos.set("runtime", m.group(19))

    int mcc = m.group(20).toInteger()
    int mnc = m.group(21).toInteger()
    int lac = m.group(22) ? Integer.parseInt(m.group(22), 16) : 0
    int cid = m.group(23) ? Integer.parseInt(m.group(23), 16) : 0
    if (mcc != 0 && mnc != 0) {
        pos.setNetwork(new Network(CellTower.from(mcc, mnc, lac, cid, rssi)))
    }

    pos.set(Position.KEY_INPUT, Integer.parseInt(m.group(24), 16))
    pos.set(Position.KEY_OUTPUT, Integer.parseInt(m.group(25), 16))

    if (m.group(26) != null) {
        pos.set(Position.KEY_BATTERY, m.group(26).toDouble())
        pos.set(Position.KEY_POWER, m.group(27).toDouble())
    } else {
        if (m.group(28)) pos.set(Position.PREFIX_ADC + 1, Integer.parseInt(m.group(28), 16))
        if (m.group(29)) pos.set(Position.PREFIX_ADC + 2, Integer.parseInt(m.group(29), 16))
        if (m.group(30)) pos.set(Position.PREFIX_ADC + 3, Integer.parseInt(m.group(30), 16))
        pos.set(Position.KEY_BATTERY, Integer.parseInt(m.group(31), 16) / 100.0)
        pos.set(Position.KEY_POWER, m.group(32) ? Integer.parseInt(m.group(32), 16) / 100.0 : 0.0)
    }

    String eventData = m.group(33)
    if (eventData != null && !eventData.isEmpty()) {
        if (event == 37) {
            pos.set(Position.KEY_DRIVER_UNIQUE_ID, eventData)
        } else {
            pos.set("eventData", eventData)
        }
    }

    int protocol = m.group(34) ? m.group(34).toInteger() : 0

    if (m.group(35) != null) {
        String fuel = m.group(35)
        pos.set(Position.KEY_FUEL,
            Integer.parseInt(fuel.substring(0, 2), 16) + Integer.parseInt(fuel.substring(2), 16) / 100.0)
    }

    if (m.group(36) != null) {
        for (String temp : m.group(36).split("\\|")) {
            int idx = Integer.parseInt(temp.substring(0, 2), 16)
            if (protocol >= 3) {
                double value = (short) Integer.parseInt(temp.substring(2), 16)
                pos.set(Position.PREFIX_TEMP + idx, value / 100.0)
            } else {
                double value = Byte.parseByte(temp.substring(2, 4), 16)
                value += (value < 0 ? -0.01 : 0.01) * Integer.parseInt(temp.substring(4), 16)
                pos.set(Position.PREFIX_TEMP + idx, value)
            }
        }
    }

    if (m.group(37) != null && m.group(38) != null) {
        decodeDataFields(pos, m.group(38).split(","))
    }

    pos
}

def decodeBinaryC = { BufReader buf, String imei, String flag, ctx ->
    def session = ctx.session(imei)
    if (!session) return null

    // Skip: commaOff+1 (past comma) + 15 (IMEI) + 1 (,) + 3 (CCC) + 1 (,) + 2+2+4 (header)
    int commaOff = 0
    while (commaOff < 15 && buf.getUByte(commaOff) != 0x2C) commaOff++
    buf.skip(commaOff + 1 + 15 + 1 + 3 + 1 + 2 + 2 + 4)

    List positions = []
    while (buf.remaining() >= 0x34) {
        Position pos = ctx.newPosition()

        pos.set(Position.KEY_EVENT, buf.readUByte())

        pos.setLatitude(buf.readIntLE() / 1000000.0)
        pos.setLongitude(buf.readIntLE() / 1000000.0)

        pos.setTime(new java.util.Date((946684800L + buf.readUIntLE()) * 1000L))

        pos.setValid(buf.readUByte() == 1)
        pos.set(Position.KEY_SATELLITES, buf.readUByte())
        int rssi = buf.readUByte()

        pos.setSpeed(UnitsConverter.knotsFromKph(buf.readUShortLE()))
        pos.setCourse(buf.readUShortLE())
        pos.set(Position.KEY_HDOP, buf.readUShortLE() / 10.0)
        pos.setAltitude(buf.readUShortLE())
        pos.set(Position.KEY_ODOMETER, buf.readUIntLE())
        pos.set("runtime", buf.readUIntLE())

        int mcc = buf.readUShortLE()
        int mnc = buf.readUShortLE()
        int lac = buf.readUShortLE()
        int cid = buf.readUShortLE()
        pos.setNetwork(new Network(CellTower.from(mcc, mnc, lac, cid, rssi)))

        pos.set(Position.KEY_STATUS, buf.readUShortLE())
        pos.set(Position.PREFIX_ADC + 1, buf.readUShortLE())
        pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 100.0)
        pos.set(Position.KEY_POWER, buf.readUShortLE())
        buf.skip(4) // geo-fence

        positions.add(pos)
    }

    int count = positions.size()
    String ackStr = "@@${flag}${27 + count.intdiv(10)},${imei},CCC,${count}*"
    ctx.ack(ackStr + Checksum.sum(ackStr) + "\r\n")

    for (Position p : positions) ctx.emit(p)
    return null
}

def decodeBinaryE = { BufReader buf, String imei, ctx ->
    def session = ctx.session(imei)
    if (!session) return null

    buf.readUIntLE()  // remaining cache
    int count = (int) buf.readUShortLE()

    for (int i = 0; i < count; i++) {
        Position pos = ctx.newPosition()
        Network network = new Network()

        int dataLength = (int) buf.readUShortLE()
        int remainingAtEnd = buf.remaining() - dataLength
        buf.readUShortLE()  // record index

        // 1-byte params
        int paramCount = buf.readUByte()
        for (int j = 0; j < paramCount; j++) {
            int id = buf.getUByte(0) == 0xFE ? (int) buf.readUShort() : buf.readUByte()
            switch (id) {
                case 0x01: pos.set(Position.KEY_EVENT, buf.readUByte()); break
                case 0x05: pos.setValid(buf.readUByte() > 0); break
                case 0x06: pos.set(Position.KEY_SATELLITES, buf.readUByte()); break
                case 0x07: pos.set(Position.KEY_RSSI, buf.readUByte()); break
                case 0x14: pos.set(Position.KEY_OUTPUT, buf.readUByte()); break
                case 0x15: pos.set(Position.KEY_INPUT, buf.readUByte()); break
                case 0x47:
                    int lockState = buf.readUByte()
                    if (lockState > 0) pos.set(Position.KEY_LOCK, lockState == 2)
                    break
                case 0x97: pos.set(Position.KEY_THROTTLE, buf.readUByte()); break
                case 0x9D: pos.set(Position.KEY_FUEL, buf.readUByte()); break
                case 0xFE69: pos.set(Position.KEY_BATTERY_LEVEL, buf.readUByte()); break
                default: buf.readUByte(); break
            }
        }

        // 2-byte params (LE short)
        paramCount = buf.readUByte()
        for (int j = 0; j < paramCount; j++) {
            int id = buf.getUByte(0) == 0xFE ? (int) buf.readUShort() : buf.readUByte()
            switch (id) {
                case 0x08: pos.setSpeed(UnitsConverter.knotsFromKph(buf.readUShortLE())); break
                case 0x09: pos.setCourse(buf.readUShortLE()); break
                case 0x0A: pos.set(Position.KEY_HDOP, buf.readUShortLE()); break
                case 0x0B: pos.setAltitude(buf.readShortLE()); break
                case 0x16: pos.set(Position.PREFIX_ADC + 1, buf.readUShortLE() / 100.0); break
                case 0x17: pos.set(Position.PREFIX_ADC + 2, buf.readUShortLE() / 100.0); break
                case 0x19: pos.set(Position.KEY_BATTERY, buf.readUShortLE() / 100.0); break
                case 0x1A: pos.set(Position.KEY_POWER, buf.readUShortLE() / 100.0); break
                case 0x29: pos.set(Position.KEY_FUEL, buf.readUShortLE() / 100.0); break
                case 0x40: pos.set(Position.KEY_EVENT, buf.readUShortLE()); break
                case 0x91:
                case 0x92: pos.set(Position.KEY_OBD_SPEED, buf.readUShortLE()); break
                case 0x98: pos.set(Position.KEY_FUEL_USED, buf.readUShortLE()); break
                case 0x99: pos.set(Position.KEY_RPM, buf.readUShortLE()); break
                case 0x9C: pos.set(Position.KEY_COOLANT_TEMP, buf.readUShortLE()); break
                case 0x9F: pos.set(Position.PREFIX_TEMP + 1, buf.readUShortLE()); break
                case 0xC9: pos.set(Position.KEY_FUEL_CONSUMPTION, buf.readUShortLE()); break
                default: buf.readUShortLE(); break
            }
        }

        // 4-byte params (LE int)
        paramCount = buf.readUByte()
        for (int j = 0; j < paramCount; j++) {
            int id = buf.getUByte(0) == 0xFE ? (int) buf.readUShort() : buf.readUByte()
            switch (id) {
                case 0x02: pos.setLatitude(buf.readIntLE() / 1000000.0); break
                case 0x03: pos.setLongitude(buf.readIntLE() / 1000000.0); break
                case 0x04: pos.setTime(new java.util.Date((946684800L + buf.readUIntLE()) * 1000L)); break
                case 0x0C: pos.set(Position.KEY_ODOMETER, buf.readUIntLE()); break
                case 0x0D: pos.set("runtime", buf.readUIntLE()); break
                case 0x25: pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(buf.readUIntLE())); break
                case 0x9B: pos.set(Position.KEY_OBD_ODOMETER, buf.readUIntLE()); break
                case 0xA0: pos.set(Position.KEY_FUEL_USED, buf.readUIntLE() / 1000.0); break
                case 0xA2: pos.set(Position.KEY_FUEL_CONSUMPTION, buf.readUIntLE() / 100.0); break
                case 0xFEF4: pos.set(Position.KEY_HOURS, buf.readUIntLE() * 60000L); break
                default: buf.readUIntLE(); break
            }
        }

        // variable-length params
        paramCount = buf.readUByte()
        for (int j = 0; j < paramCount; j++) {
            int id = buf.getUByte(0) == 0xFE ? (int) buf.readUShort() : buf.readUByte()
            int length = buf.readUByte()
            if (id >= 0x1D && id <= 0x25) {
                String hex = buf.readHex(6)
                String mac = (0..5).collect { hex.substring(it * 2, it * 2 + 2) }.join(':')
                network.addWifiAccessPoint(WifiAccessPoint.from(mac, buf.readShortLE()))
            } else if (id == 0x0E || id == 0x0F || id == 0x10 || id == 0x12 || id == 0x13) {
                network.addCellTower(CellTower.from(
                    buf.readUShortLE(), buf.readUShortLE(),
                    buf.readUShortLE(), buf.readUIntLE(), buf.readShortLE()))
            } else if (id >= 0x2A && id <= 0x31) {
                buf.readUByte()  // label
                pos.set(Position.PREFIX_TEMP + (id - 0x2A), buf.readShortLE() / 100.0)
            } else if (id == 0x4B) {
                buf.skip(length)
            } else if (id == 0xFE31) {
                int alarmProtocol = buf.readUByte()
                pos.set("alarmType", buf.readUByte())
                if (alarmProtocol == 0x02 && length > 3) {
                    String file = buf.readString(length - 2)
                    String folder = file.substring(0, 8).replaceAll("(\\d{4})(\\d{2})(\\d{2})", '$1-$2-$3')
                    pos.set(Position.KEY_IMAGE, folder + "/" + file)
                } else {
                    buf.skip(length - 2)
                }
            } else if (id == 0xFE73) {
                buf.readUByte()  // version
                String name = buf.readString(buf.readUByte())
                pos.set("tagName", name)
                buf.skip(6)  // mac
                pos.set("tagBattery", buf.readUByte())
                pos.set("tagTemp", buf.readShortLE() / 256.0)
                pos.set("tagHumidity", buf.readShortLE() / 256.0)
                buf.readUShortLE()  // high temp threshold
                buf.readUShortLE()  // low temp threshold
                buf.readUShortLE()  // high humidity threshold
                buf.readUShortLE()  // low humidity threshold
            } else if (id == 0xFEA8) {
                for (int k = 1; k <= 3; k++) {
                    int flag = buf.readUByte()
                    int level = buf.readUByte()
                    if (flag > 0) {
                        String key = k == 1 ? Position.KEY_BATTERY_LEVEL : "battery${k}Level"
                        pos.set(key, level)
                    }
                }
                buf.readUByte()  // battery alert
            } else {
                buf.skip(length)
            }
        }

        // snap to end of record
        if (buf.remaining() > remainingAtEnd) {
            buf.skip(buf.remaining() - remainingAtEnd)
        }

        if (network.getCellTowers() != null || network.getWifiAccessPoints() != null) {
            pos.setNetwork(network)
        }
        ctx.emit(pos)
    }
    return null
}

protocol("meitrack") {

    port 5020

    commands(
        Command.TYPE_CUSTOM,
        Command.TYPE_POSITION_SINGLE,
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME,
        Command.TYPE_ALARM_ARM,
        Command.TYPE_ALARM_DISARM,
        Command.TYPE_REQUEST_PHOTO,
        Command.TYPE_SEND_SMS
    )

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 10) return null
            // Find first comma (length field ends here)
            int commaOff = -1
            for (int i = 3; i < Math.min(fb.readableBytes(), 12); i++) {
                if (fb.getUByte(i) == 0x2C) { commaOff = i; break }
            }
            if (commaOff < 0) return null
            // Parse decimal from bytes 3..commaOff-1
            int len = 0
            for (int i = 3; i < commaOff; i++) {
                int d = fb.getUByte(i) - 0x30
                if (d < 0 || d > 9) return null
                len = len * 10 + d
            }
            int total = commaOff + len
            return fb.readableBytes() >= total ? total : null
        }

        decode { msg, ctx ->
            BufReader buf = msg as BufReader
            int total = buf.remaining()

            // peek flag char and find IMEI + type without consuming
            char flagChar = (char) buf.getUByte(2)
            byte[] peek = buf.getBytes(0, Math.min(total, 40))
            String prefix = new String(peek, StandardCharsets.US_ASCII)

            int c1 = prefix.indexOf(',')
            if (c1 < 0) return null
            int c2 = prefix.indexOf(',', c1 + 1)
            if (c2 < 0) return null
            String imei = prefix.substring(c1 + 1, Math.min(c1 + 16, prefix.length()))
            int c3 = prefix.indexOf(',', c2 + 1)
            String type = c3 >= 0 ? prefix.substring(c2 + 1, c3) : prefix.substring(c2 + 1)

            switch (type) {
                case "AAC":
                    String ack = "@@z27,${imei},AAC,1*"
                    ctx.ack(ack + Checksum.sum(ack) + "\r\n")
                    return null

                case "D00":
                    return null  // photo chunk — no writeMediaFile equivalent

                case "D03":
                    return null  // photo start — no photo support

                case "D82":
                    def session = ctx.session(imei)
                    if (!session) return null
                    Position pos = ctx.newPosition()
                    ctx.lastLocation(pos)
                    // result = substring from c2+1 to before '*xx'
                    byte[] all = buf.getBytes(0, total)
                    String full = new String(all, StandardCharsets.US_ASCII)
                    int starIdx = full.indexOf('*', c2 + 1)
                    String result = starIdx >= 0 ? full.substring(c2 + 1, starIdx) : full.substring(c2 + 1)
                    pos.set(Position.KEY_RESULT, result)
                    return pos

                case "CCC":
                    return decodeBinaryC(buf, imei, String.valueOf(flagChar), ctx)

                case "CCE":
                    // advance to binary data: skip past comma+IMEI+,CCE,
                    buf.skip(c1 + 1 + 15 + 1 + 3 + 1)  // past `,IMEI,CCE,`
                    return decodeBinaryE(buf, imei, ctx)

                default:
                    byte[] allBytes = buf.getBytes(0, total)
                    String text = new String(allBytes, StandardCharsets.US_ASCII)
                    return decodeRegular(text, ctx)
            }
        }

        encode { cmd, ctx ->
            String uniqueId = ctx.session().deviceId
            def formatCmd = { String content ->
                int length = 1 + uniqueId.length() + 1 + content.length() + 5
                String r = "@@A${String.format('%02d', length)},${uniqueId},${content}*"
                r + Checksum.sum(r) + "\r\n"
            }
            switch (cmd.type) {
                case Command.TYPE_CUSTOM:         return formatCmd(cmd.getString(Command.KEY_DATA))
                case Command.TYPE_POSITION_SINGLE: return formatCmd("A10")
                case Command.TYPE_ENGINE_STOP:    return formatCmd("C01,0,12222")
                case Command.TYPE_ENGINE_RESUME:  return formatCmd("C01,0,02222")
                case Command.TYPE_ALARM_ARM:      return formatCmd("C01,0,22122")
                case Command.TYPE_ALARM_DISARM:   return formatCmd("C01,0,22022")
                case Command.TYPE_REQUEST_PHOTO:
                    int idx = cmd.getInteger(Command.KEY_INDEX)
                    return formatCmd("D03,${idx > 0 ? idx : 1},camera_picture.jpg")
                case Command.TYPE_SEND_SMS:
                    return formatCmd("C02,0,${cmd.getString(Command.KEY_PHONE)},${cmd.getString(Command.KEY_MESSAGE)}")
                default: return null
            }
        }
    }
}
