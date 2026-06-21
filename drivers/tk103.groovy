// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.DataConverter
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

// ── patterns ──────────────────────────────────────────────────────────────

// Main position pattern: device ID either 12-char fixed (no comma) or variable+comma
// date: YY MM DD (non-alternative) or DD MM YY (alternative)
def PATTERN = Pattern.compile(
    "\\(?" +
    "(?:(.{12})|([^,]+),)" +                            // g1: 12-char id  OR g2: var id
    "(.{4}),?" +                                        // g3: command
    "(?:(\\d*)|,ALARM,(\\d),\\d+,)" +                  // g4: data  OR g5: alarm type
    "(\\d\\d)(\\d\\d)(\\d\\d),?" +                     // g6,g7,g8: date
    "([AV]),?" +                                        // g9: validity
    " *(\\d*)(\\d\\d\\.\\d+)" +                        // g10,g11: lat
    "([NS]),?" +                                        // g12
    " *(\\d*)(\\d\\d\\.\\d+)" +                        // g13,g14: lon
    "([EW]),?" +                                        // g15
    "([ \\d.]{1,5})(?:\\d*,)?" +                       // g16: speed km/h
    "(\\d\\d)(\\d\\d)(\\d\\d),?" +                     // g17,g18,g19: time
    "(?:" +
      "(?:([ \\d.]{6})|(\\d\\d)),?" +                  // g20 or g21: course (padded or 2-dig)
      "([01])" +                                        // g22: charge
      "([01])" +                                        // g23: ignition
      "([0-9A-Fa-f])" +                                // g24: io1
      "([0-9A-Fa-f])" +                                // g25: io2
      "([0-9A-Fa-f])" +                                // g26: io3
      "([0-9A-Fa-f]{3})" +                             // g27: fuel
      "L([0-9A-Fa-f]+)" +                              // g28: odometer
      "|" +
      "(\\d+\\.\\d+)" +                                // g29: alternate course
    ")?" +
    "[\\s\\S]*?" +
    "([+-]\\d\\d\\d\\.\\d)?" +                         // g30: temperature
    "\\)?",
    Pattern.DOTALL
)

def PATTERN_BATTERY = Pattern.compile(
    "\\(?(\\d+)," +                                    // g1: device id
    "ZC20," +
    "(\\d\\d)(\\d\\d)(\\d\\d)," +                      // g2,g3,g4: date DDMMYY
    "(\\d\\d)(\\d\\d)(\\d\\d)," +                      // g5,g6,g7: time HHMMSS
    "(\\d+)," +                                        // g8: battery level
    "(\\d+)," +                                        // g9: battery voltage
    "(\\d+)," +                                        // g10: power voltage
    "\\d+[\\s\\S]*"
)

def PATTERN_CELL = Pattern.compile(
    "(?s)\\((\\d{12}).{4}(?:\\d{15})?,([\\s\\S]+),(\\d{8})\\)"
)

def PATTERN_NETWORK = Pattern.compile(
    "\\(?(\\d{12})BZ00," +                             // g1: device id
    "(\\d+),(\\d+)," +                                 // g2: mcc, g3: mnc
    "([0-9A-Fa-f]+),([0-9A-Fa-f]+)" +                 // g4: lac, g5: cid
    "[\\s\\S]*"
)

def PATTERN_LBSWIFI = Pattern.compile(
    "\\(?(\\d+)," +                                    // g1: device id
    "(.{4})," +                                        // g2: command
    "(\\d+),(\\d+),(\\d+),(\\d+)," +                   // g3:mcc g4:mnc g5:lac g6:cid
    "(\\d+)," +                                        // g7: wifi count
    "((?:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\*[-+]?\\d+\\*\\d+,)*)?" + // g8: wifi macs
    "(\\d\\d)(\\d\\d)(\\d\\d)," +                      // g9,g10,g11: date DDMMYY
    "(\\d\\d)(\\d\\d)(\\d\\d)" +                       // g12,g13,g14: time HHMMSS
    "[\\s\\S]*"
)

def PATTERN_COMMAND_RESULT = Pattern.compile(
    "\\(?(\\d+)," +                                    // g1: device id
    ".{4}," +                                          // command
    "(\\d\\d)(\\d\\d)(\\d\\d)," +                      // g2,g3,g4: date DDMMYY
    "(\\d\\d)(\\d\\d)(\\d\\d)," +                      // g5,g6,g7: time HHMMSS
    '\\$([\\s\\S]*?)(?:\\$|$)'                         // g8: message
)

def PATTERN_VIN = Pattern.compile(
    "\\((\\d+)BV00(.{17})\\)"
)

// ── helpers ────────────────────────────────────────────────────────────────

def nmea = { String d, String m, String hemi ->
    double v = (d ? Integer.parseInt(d) : 0) + Double.parseDouble(m) / 60.0
    (hemi == "S" || hemi == "W") ? -v : v
}

def mkDate = { int a, int b, int c, boolean alt, int h, int mi, int s ->
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    if (alt) {
        // alternative: DDMMYY → day=a, month=b, year=c
        cal.set(Calendar.YEAR, 2000 + c)
        cal.set(Calendar.MONTH, b - 1)
        cal.set(Calendar.DAY_OF_MONTH, a)
    } else {
        // standard: YYMMDD → year=a, month=b, day=c
        cal.set(Calendar.YEAR, 2000 + a)
        cal.set(Calendar.MONTH, b - 1)
        cal.set(Calendar.DAY_OF_MONTH, c)
    }
    cal.set(Calendar.HOUR_OF_DAY, h)
    cal.set(Calendar.MINUTE, mi)
    cal.set(Calendar.SECOND, s)
    cal.set(Calendar.MILLISECOND, 0)
    cal.getTime()
}

def mkDateDMY = { int d, int m, int y, int h, int mi, int s ->
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(2000 + y, m - 1, d, h, mi, s)
    cal.set(Calendar.MILLISECOND, 0)
    cal.getTime()
}

def decodeAlarm = { int v ->
    switch (v) {
        case 1: return Position.ALARM_ACCIDENT
        case 2: return Position.ALARM_SOS
        case 3: return Position.ALARM_VIBRATION
        case 4: return Position.ALARM_LOW_SPEED
        case 5: return Position.ALARM_OVERSPEED
        case 6: return Position.ALARM_GEOFENCE_EXIT
        default: return null
    }
}

def decodeType = { pos, String type, String data ->
    switch (type) {
        case "BQ81":
            if (data != null) {
                switch (data.trim().toInteger()) {
                    case 0: pos.addAlarm(Position.ALARM_LOW_BATTERY); break
                    case 1: pos.addAlarm(Position.ALARM_OVERSPEED); break
                    case 2: pos.addAlarm(Position.ALARM_IDLE); break
                    case 3: pos.addAlarm(Position.ALARM_ACCELERATION); break
                    case 4: pos.addAlarm(Position.ALARM_BRAKING); break
                    case 5: pos.addAlarm(Position.ALARM_TEMPERATURE); break
                }
            }
            break
        case "BO01":
            if (data) pos.addAlarm(decodeAlarm(data.charAt(0) - (int)'0'))
            break
        case "ZC11": case "DW31": case "DW51": pos.addAlarm(Position.ALARM_MOVEMENT); break
        case "ZC12": case "DW32": case "DW52": pos.addAlarm(Position.ALARM_LOW_BATTERY); break
        case "ZC13": case "DW33": case "DW53": pos.addAlarm(Position.ALARM_POWER_CUT); break
        case "ZC15": case "DW35": case "DW55": pos.set(Position.KEY_IGNITION, true); break
        case "ZC16": case "DW36": case "DW56": pos.set(Position.KEY_IGNITION, false); break
        case "ZC29": case "DW42": case "DW62": pos.set(Position.KEY_IGNITION, true); break
        case "ZC17": case "DW37": case "DW57": pos.addAlarm(Position.ALARM_REMOVING); break
        case "ZC25": case "DW3E": case "DW5E": pos.addAlarm(Position.ALARM_SOS); break
        case "ZC26": case "DW3F": case "DW5F": pos.addAlarm(Position.ALARM_TAMPERING); break
        case "ZC27": case "DW40": case "DW60": pos.addAlarm(Position.ALARM_LOW_POWER); break
    }
}

def decodeBatteryLevel = { int v ->
    switch (v) {
        case 6: return 100
        case 5: return 80
        case 4: return 50
        case 3: return 20
        case 2: return 10
        default: return null
    }
}

def readUShortLE = { byte[] b, int off ->
    (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
}

def readUIntLE = { byte[] b, int off ->
    (long)(b[off] & 0xFF) |
    ((long)(b[off + 1] & 0xFF) << 8) |
    ((long)(b[off + 2] & 0xFF) << 16) |
    ((long)(b[off + 3] & 0xFF) << 24)
}

// ── sub-decoders ───────────────────────────────────────────────────────────

def decodeBattery = { String sentence, ctx ->
    def m = PATTERN_BATTERY.matcher(sentence)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, mkDateDMY(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger(),
                                    m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger()))
    int lvl = m.group(8).toInteger()
    if (lvl != 255) pos.set(Position.KEY_BATTERY_LEVEL, decodeBatteryLevel(lvl))
    int batt = m.group(9).toInteger()
    if (batt != 65535) pos.set(Position.KEY_BATTERY, batt / 100.0)
    int pwr = m.group(10).toInteger()
    if (pwr != 65535) pos.set(Position.KEY_POWER, pwr / 10.0)
    pos
}

def decodeCell = { String sentence, ctx ->
    def m = PATTERN_CELL.matcher(sentence)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    Network network = new Network()
    m.group(2).split("\n").each { String cell ->
        cell = cell.trim()
        if (cell.startsWith("{") && cell.endsWith("}")) {
            String[] vals = cell.substring(1, cell.length() - 1).split(",")
            network.addCellTower(CellTower.from(
                vals[0].toInteger(), vals[1].toInteger(),
                vals[2].toInteger(), vals[3].toInteger()))
        }
    }
    pos.setNetwork(network)
    pos.set(Position.KEY_ODOMETER, Long.parseLong(m.group(3), 16))
    pos
}

def decodeNetwork = { String sentence, ctx ->
    def m = PATTERN_NETWORK.matcher(sentence)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    pos.setNetwork(new Network(CellTower.from(
        m.group(2).toInteger(), m.group(3).toInteger(),
        Integer.parseInt(m.group(4), 16), Integer.parseInt(m.group(5), 16))))
    pos
}

def decodeLbsWifi = { String sentence, ctx ->
    def m = PATTERN_LBSWIFI.matcher(sentence)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    decodeType(pos, m.group(2), "0")
    ctx.lastLocation(pos, null)
    Network network = new Network()
    network.addCellTower(CellTower.from(
        m.group(3).toInteger(), m.group(4).toInteger(),
        m.group(5).toInteger(), m.group(6).toInteger()))
    int wifiCount = m.group(7).toInteger()
    String wifiStr = m.group(8)
    if (wifiStr) {
        String[] macs = wifiStr.split(",")
        if (macs.length == wifiCount) {
            macs.each { String mac ->
                String[] info = mac.split("\\*")
                network.addWifiAccessPoint(WifiAccessPoint.from(
                    info[0], info[1].toInteger(), info[2].toInteger()))
            }
        }
    }
    if (network.getCellTowers() != null || network.getWifiAccessPoints() != null) {
        pos.setNetwork(network)
    }
    pos.setTime(mkDateDMY(m.group(9).toInteger(), m.group(10).toInteger(), m.group(11).toInteger(),
                          m.group(12).toInteger(), m.group(13).toInteger(), m.group(14).toInteger()))
    pos
}

def decodeCommandResult = { String sentence, ctx ->
    def m = PATTERN_COMMAND_RESULT.matcher(sentence)
    if (!m.find()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, mkDateDMY(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger(),
                                    m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger()))
    pos.set(Position.KEY_RESULT, m.group(8))
    pos
}

def decodeVin = { String sentence, ctx ->
    def m = PATTERN_VIN.matcher(sentence)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    pos.set(Position.KEY_VIN, m.group(2))
    pos
}

def decodeBms = { String sentence, ctx ->
    if (sentence.length() < 17) return null
    String id = sentence.substring(1, 13)
    def session = ctx.session(id)
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    String payload = sentence.substring(17, sentence.length() - 1)
    boolean isBs50 = sentence.substring(13, 17) == "BS50"
    if (isBs50) {
        byte[] b = DataConverter.parseHex(payload)
        int batteryCount = b[3] & 0xFF
        for (int i = 1; i <= 24; i++) {
            int off = 4 + (i - 1) * 2
            int voltage = readUShortLE(b, off)
            if (i <= batteryCount) pos.set("battery$i", voltage / 1000.0)
        }
        // offset 52 = 4 + 48
        pos.set(Position.KEY_CHARGE, (b[52] & 0xFF) == 0)
        pos.set("current", readUShortLE(b, 53) / 10.0)
        pos.set(Position.KEY_BATTERY, readUShortLE(b, 55) / 100.0)
        pos.set(Position.KEY_BATTERY_LEVEL, b[57] & 0xFF)
        pos.set("batteryOverheat", (b[58] & 0xFF) > 0)
        pos.set("chargeProtection", (b[59] & 0xFF) > 0)
        pos.set("dischargeProtection", (b[60] & 0xFF) > 0)
        // b[61]=dropLine, b[62]=balanced (skip)
        pos.set("cycles", readUShortLE(b, 63))
        pos.set("faultAlarm", b[65] & 0xFF)
        // b[66..71] = skip 6
        int temperatureCount = b[72] & 0xFF
        pos.set("powerTemp", (b[73] & 0xFF) - 40)
        pos.set("equilibriumTemp", (b[74] & 0xFF) - 40)
        for (int i = 1; i <= 7; i++) {
            int temp = (b[74 + i] & 0xFF) - 40
            if (i <= temperatureCount) pos.set("batteryTemp$i", temp)
        }
        pos.set("calibrationCapacity", readUShortLE(b, 82) / 100.0)
        pos.set("dischargeCapacity", readUIntLE(b, 84))
    } else {
        // BS51: CSV of hex_key:hex_value pairs
        payload.split(",").each { String pair ->
            if (!pair.contains(":")) return
            String[] kv = pair.split(":")
            int key = Integer.parseInt(kv[0], 16)
            byte[] b = DataConverter.parseHex(kv[1])
            switch (key) {
                case 0x90:
                    pos.set("cumulativeVoltage", readUShortLE(b, 0) / 10.0)
                    pos.set("gatherVoltage", readUShortLE(b, 2) / 10.0)
                    pos.set("current", (readUShortLE(b, 4) - 30000) / 10.0)
                    pos.set("soc", readUShortLE(b, 6) / 10.0)
                    break
                case 0x91:
                    pos.set("maxCellVoltage", readUShortLE(b, 0) / 1000.0)
                    pos.set("maxCellVoltageCount", b[2] & 0xFF)
                    pos.set("minCellVoltage", readUShortLE(b, 3) / 1000.0)
                    pos.set("minCellVoltageCount", b[5] & 0xFF)
                    break
                case 0x92:
                    pos.set("maxTemp", (b[0] & 0xFF) - 40)
                    pos.set("maxTempCount", b[1] & 0xFF)
                    pos.set("minTemp", (b[2] & 0xFF) - 40)
                    pos.set("minTempCount", b[3] & 0xFF)
                    break
                case 0x96:
                    // b[0] = frame byte (skip), rest are cell temps
                    for (int j = 1; j < b.length; j++) {
                        pos.set("cellTemp$j", (b[j] & 0xFF) - 40)
                    }
                    break
            }
        }
    }
    pos
}

def decodeMain = { String sentence, ctx ->
    def m = PATTERN.matcher(sentence)
    if (!m.matches()) return null

    String id = m.group(1) != null ? m.group(1) : m.group(2)
    boolean alternative = (m.group(2) != null)

    def session = ctx.session(id)
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    String type = m.group(3)
    String data = m.group(4) != null ? m.group(4) : m.group(5)
    decodeType(pos, type, data)

    int d1 = m.group(6).toInteger()
    int d2 = m.group(7).toInteger()
    int d3 = m.group(8).toInteger()
    int th = m.group(17).toInteger()
    int tm = m.group(18).toInteger()
    int ts = m.group(19).toInteger()
    pos.setTime(mkDate(d1, d2, d3, alternative, th, tm, ts))

    pos.setValid(m.group(9) == "A")
    pos.setLatitude(nmea(m.group(10), m.group(11), m.group(12)))
    pos.setLongitude(nmea(m.group(13), m.group(14), m.group(15)))
    pos.setSpeed(UnitsConverter.knotsFromKph(m.group(16).trim().toDouble()))

    // course: g20 (padded 6) or g21 (2-digit) from IO block, or g29 (alternate)
    if (m.group(20) != null) pos.setCourse(m.group(20).trim().toDouble())
    if (m.group(21) != null) pos.setCourse(m.group(21).toDouble())

    if (m.group(22) != null) {
        // IO block present
        pos.set(Position.KEY_CHARGE, m.group(22).toInteger() == 0)
        pos.set(Position.KEY_IGNITION, m.group(23).toInteger() == 1)

        int mask1 = Integer.parseInt(m.group(24), 16)
        pos.set(Position.PREFIX_IN + 2, BitUtil.check(mask1, 0) ? 1 : 0)
        pos.set("panic", BitUtil.check(mask1, 1) ? 1 : 0)
        pos.set(Position.PREFIX_OUT + 2, BitUtil.check(mask1, 2) ? 1 : 0)
        if (BitUtil.check(mask1, 3)) pos.set(Position.KEY_BLOCKED, 1)

        int mask2 = Integer.parseInt(m.group(25), 16)
        for (int i = 0; i < 3; i++) {
            if (BitUtil.check(mask2, i)) pos.set("hs" + (3 - i), 1)
        }
        if (BitUtil.check(mask2, 3)) pos.set(Position.KEY_DOOR, 1)

        int mask3 = Integer.parseInt(m.group(26), 16)
        for (int i = 1; i <= 3; i++) {
            if (BitUtil.check(mask3, i)) pos.set("ls" + (3 - i + 1), 1)
        }

        pos.set(Position.KEY_FUEL, Integer.parseInt(m.group(27), 16))
        pos.set(Position.KEY_ODOMETER, Long.parseLong(m.group(28), 16))
    }

    if (m.group(29) != null) pos.setCourse(m.group(29).toDouble())
    if (m.group(30) != null) pos.set(Position.PREFIX_TEMP + 1, m.group(30).toDouble())

    pos
}

// ── protocol ───────────────────────────────────────────────────────────────

protocol("tk103") {
    port 5002

    commands(
        Command.TYPE_CUSTOM,
        Command.TYPE_GET_VERSION,
        Command.TYPE_REBOOT_DEVICE,
        Command.TYPE_SET_ODOMETER,
        Command.TYPE_POSITION_SINGLE,
        Command.TYPE_POSITION_PERIODIC,
        Command.TYPE_POSITION_STOP,
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME,
        Command.TYPE_OUTPUT_CONTROL
    )

    variant("main") {

        matches { msg -> msg.contains("(") }

        scriptedFrame { fb ->
            int n = fb.readableBytes()
            if (n < 2) return null
            // find opening (
            int start = -1
            for (int i = 0; i < n; i++) {
                if (fb.getUByte(i) == 0x28) { start = i; break }
            }
            if (start < 0) return null
            // find closing ) with even-$ count (cumulative across )-candidates)
            int dollars = 0
            int scanPos = start
            while (true) {
                int nextEnd = -1
                for (int i = scanPos; i < n; i++) {
                    if (fb.getUByte(i) == 0x29) { nextEnd = i; break }
                }
                if (nextEnd < 0) break
                for (int j = scanPos; j < nextEnd; j++) {
                    if (fb.getUByte(j) == 0x24) dollars++
                }
                if ((dollars & 1) == 0) return nextEnd + 1
                scanPos = nextEnd + 1
            }
            null
        }

        decode { msg, ctx ->
            String raw
            if (msg instanceof BufReader) {
                BufReader b = msg
                raw = b.readString(b.readableBytes())
            } else {
                raw = msg.toString()
            }
            int paren = raw.indexOf('(')
            if (paren < 0) return null
            String sentence = raw.substring(paren)

            // BP00/BP05 handshake
            if (sentence.length() >= 17) {
                String devId = sentence.substring(1, 13)
                String msgType = sentence.substring(13, 17)
                if (msgType == "BP00") {
                    ctx.ack("($devId AP01HSO)")
                    return null
                } else if (msgType == "BP05") {
                    ctx.ack("($devId AP05)")
                }
            }

            // dispatch
            if (sentence.indexOf('{') > 0 && sentence.indexOf('}') > 0) {
                return decodeCell(sentence, ctx)
            } else if (sentence.contains("ZC20")) {
                return decodeBattery(sentence, ctx)
            } else if (sentence.contains("BZ00")) {
                return decodeNetwork(sentence, ctx)
            } else if (sentence.contains("ZC03")) {
                return decodeCommandResult(sentence, ctx)
            } else if (sentence.contains("DW5")) {
                return decodeLbsWifi(sentence, ctx)
            } else if (sentence.contains("BV00")) {
                return decodeVin(sentence, ctx)
            } else if (sentence.contains("BS50") || sentence.contains("BS51")) {
                return decodeBms(sentence, ctx)
            }

            decodeMain(sentence, ctx)
        }

        encode { cmd, ctx ->
            String uid = ctx.uniqueId()
            switch (cmd.type) {
                case Command.TYPE_CUSTOM:
                    return "(${uid}${cmd.attributes.getOrDefault(Command.KEY_DATA, '')})"
                case Command.TYPE_GET_VERSION:
                    return "(${uid}AP07)"
                case Command.TYPE_REBOOT_DEVICE:
                    return "(${uid}AT00)"
                case Command.TYPE_SET_ODOMETER:
                    return "(${uid}AX01)"
                case Command.TYPE_POSITION_SINGLE:
                    return "(${uid}AP00)"
                case Command.TYPE_POSITION_PERIODIC:
                    int freq = (cmd.attributes.getOrDefault(Command.KEY_FREQUENCY, 0) as int)
                    return "(${uid}AR00${String.format('%04X', freq)}0000)"
                case Command.TYPE_POSITION_STOP:
                    return "(${uid}AR0000000000)"
                case Command.TYPE_ENGINE_STOP:
                    return "(${uid}AV010)"
                case Command.TYPE_ENGINE_RESUME:
                    return "(${uid}AV011)"
                case Command.TYPE_OUTPUT_CONTROL:
                    return "(${uid}AV00${cmd.attributes.getOrDefault(Command.KEY_DATA, '0')})"
                default:
                    return null
            }
        }
    }
}
