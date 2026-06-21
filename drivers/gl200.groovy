// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

// ── protocol version → model name ─────────────────────────────────────────

def PROTOCOL_MODELS = [
    "02": "GL200", "04": "GV200", "06": "GV300", "08": "GMT100",
    "09": "GV50P", "0F": "GV55", "10": "GV55 LITE", "11": "GL500",
    "1A": "GL300", "1F": "GV500", "21": "GL200", "25": "GV300",
    "27": "GV300W", "28": "GL300VC", "2C": "GL300W", "2D": "GV500VC",
    "2F": "GV55", "4F": "GV56", "30": "GL300", "31": "GV65",
    "35": "GV200", "36": "GV500", "3F": "GMT100", "40": "GL500",
    "41": "GV75W", "42": "GT501", "44": "GL530", "45": "GB100",
    "50": "GV55W", "52": "GL50", "55": "GL50B", "5E": "GV500MAP",
    "6E": "GV310LAU", "BD": "CV200", "C2": "GV600M", "C3": "GL320M",
    "DC": "GV600MG", "DE": "GL500M", "DF": "CV100LG", "F1": "GV350M",
    "F8": "GV800W", "FC": "GV600W", "802004": "GV58LAU",
    "802005": "GV355CEU", "80201E": "GV30CEU"
]

def getModel = { String ver ->
    if (!ver) return ""
    String pre = ver.length() > 6 ? ver.substring(0, 6) : ver.substring(0, Math.min(2, ver.length()))
    PROTOCOL_MODELS[pre.toUpperCase(Locale.ROOT)] ?: ""
}

// ── helpers ────────────────────────────────────────────────────────────────

def makeUtcSdf = {
    def s = new SimpleDateFormat("yyyyMMddHHmmss")
    s.timeZone = TimeZone.getTimeZone("UTC")
    s
}
def utcSdf = makeUtcSdf()

def parseDate14 = { String s ->
    if (!s || s.length() < 14) return null
    try { utcSdf.parse(s) } catch (Exception ignored) { null }
}

def parseHours = { String s ->
    if (!s) return null
    def parts = s.split(":")
    long ms = Long.parseLong(parts[0]) * 3600000L
    if (parts.length > 1) ms += Long.parseLong(parts[1]) * 60000L
    if (parts.length > 2) ms += Long.parseLong(parts[2]) * 1000L
    ms
}

def decodeStatusBits = { pos, long val ->
    long ign = BitUtil.between(val, 16, 24)
    if      (BitUtil.check(ign, 4)) pos.set(Position.KEY_IGNITION, false)
    else if (BitUtil.check(ign, 5)) pos.set(Position.KEY_IGNITION, true)
    long inp = BitUtil.between(val, 8, 16)
    long out = BitUtil.to(val, 8)
    pos.set(Position.KEY_INPUT, inp)
    pos.set(Position.PREFIX_IN + "1", BitUtil.check(inp, 1))
    pos.set(Position.PREFIX_IN + "2", BitUtil.check(inp, 2))
    pos.set(Position.KEY_OUTPUT, out)
    pos.set(Position.PREFIX_OUT + "1", BitUtil.check(out, 0))
    pos.set(Position.PREFIX_OUT + "2", BitUtil.check(out, 1))
}

def decodeAnalog = { pos, int idx, String adc ->
    if (!adc) return
    if (adc.startsWith("F")) {
        pos.set("fuel" + idx, Integer.parseInt(adc.substring(1)))
    } else {
        pos.set(Position.PREFIX_ADC + idx, Integer.parseInt(adc) / 1000.0)
    }
}

// binary time: year(2), month(1), day(1), hour(1), min(1), sec(1)
def decodeBinTime = { BufReader buf ->
    int yr = buf.readUShort()
    int mo = buf.readUByte() - 1
    int da = buf.readUByte()
    int hh = buf.readUByte()
    int mm = buf.readUByte()
    int ss = buf.readUByte()
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(yr, mo, da, hh, mm, ss)
    cal.set(Calendar.MILLISECOND, 0)
    cal.getTime()
}

// index-based location parse used by ERI / IGN style handlers
// returns next index after consuming all location fields
def decodeLocIdx = { pos, String model, String[] v, int idx ->
    if (idx >= v.length) return idx
    if (!v[idx].isEmpty()) pos.set(Position.KEY_HDOP, Double.parseDouble(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty())
        pos.setSpeed(UnitsConverter.knotsFromKph(Double.parseDouble(v[idx])))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) pos.setCourse(Integer.parseInt(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) pos.setAltitude(Double.parseDouble(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) {
        pos.setValid(true)
        pos.setLongitude(Double.parseDouble(v[idx++]))
        if (idx < v.length) pos.setLatitude(Double.parseDouble(v[idx++]))
        if (idx < v.length) pos.setTime(parseDate14(v[idx++]))
    } else {
        idx += 3
    }
    // cell: mcc, mnc, lac(hex), cid(hex)
    Network net = new Network()
    if (idx + 3 < v.length && !v[idx].isEmpty()) {
        try {
            net.addCellTower(CellTower.from(
                Integer.parseInt(v[idx]), Integer.parseInt(v[idx + 1]),
                Integer.parseInt(v[idx + 2], 16), Long.parseLong(v[idx + 3], 16)))
        } catch (Exception ignored) {}
    }
    idx += 4
    if (net.getCellTowers() != null) pos.setNetwork(net)
    if (model.startsWith("GL5")) idx += 2  // csq rssi/ber
    if (!model.equals("GL320M") && idx < v.length - 2 && !v[idx].isEmpty()) {
        try {
            int appendMask = Integer.parseInt(v[idx++])
            if (BitUtil.check(appendMask, 0) && idx < v.length)
                pos.set(Position.KEY_SATELLITES, Integer.parseInt(v[idx++]))
            if (BitUtil.check(appendMask, 1) && idx < v.length)
                idx++  // trigger type
        } catch (Exception ignored) { idx++ }
    } else if (model.equals("GL320M")) {
        idx++
    }
    idx
}

// ── BINARY DECODERS ────────────────────────────────────────────────────────

def decodeBinLocation = { BufReader buf, ctx ->
    int type = buf.readUByte()
    buf.readUInt()    // mask
    buf.readUShort()  // length
    buf.readUByte()   // device type
    buf.readUShort()  // protocol version
    buf.readUShort()  // firmware version
    String imei = String.format("%015d", buf.readLong())
    def session = ctx.session(imei)
    if (session == null) return null
    int battery  = buf.readUByte()
    int power    = buf.readUShort()
    if (type == 8) { buf.readUByte(); buf.readUByte() }  // MSG_RSP_GEO reserved
    buf.readUByte()  // motion status
    int satellites = buf.readUByte()
    if (type != 100) buf.readUByte()  // index (not for COMPRESSED)
    if (type == 3) {  // MSG_RSP_LCB: skip phone digits
        buf.readUByte()  // phone length byte
        while (true) {
            int b = buf.readUByte()
            if ((b & 0xF) == 0xF || (b & 0xF0) == 0xF0) break
        }
    }

    if (type == 100) {  // MSG_RSP_COMPRESSED
        int count = buf.readUShort()
        int speed = 0, heading = 0, latitude = 0, longitude = 0
        long time = 0L
        def positions = []
        for (int i = 0; i < count; i++) {
            if (time > 0L) time += 1L
            int attr = buf.getUByte(0) >> 6
            if (attr == 1) {
                int b0 = buf.readUByte(), b1 = buf.readUByte(), b2 = buf.readUByte()
                int bits24 = (b0 << 16) | (b1 << 8) | b2
                speed     = (bits24 >> 9) & 0xFFF
                heading   = bits24 & 0x1FF
                longitude = buf.readInt()
                latitude  = buf.readInt()
                if (time == 0L) time = buf.readUInt() & 0xFFFFFFFFL
            } else if (attr == 2) {
                int b0 = buf.readUByte(), b1 = buf.readUByte(), b2 = buf.readUByte()
                int b3 = buf.readUByte(), b4 = buf.readUByte()
                long bits = ((long)b0 << 32) | ((long)b1 << 24) | ((long)b2 << 16) | ((long)b3 << 8) | b4
                int ds = (int)((bits >> 30) & 0x7F); if (ds >= 64) ds -= 128
                int dh = (int)((bits >> 23) & 0x7F); if (dh >= 64) dh -= 128
                int dl = (int)((bits >> 11) & 0xFFF); if (dl >= 2048) dl -= 4096
                int da2 = (int)(bits & 0x7FF);         if (da2 >= 1024) da2 -= 2048
                speed += ds; heading += dh; longitude += dl; latitude += da2
            } else {
                buf.readUByte(); continue
            }
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.setValid(true)
            pos.setTime(new Date(time * 1000L))
            pos.setSpeed(UnitsConverter.knotsFromKph(speed / 10.0))
            pos.setCourse(heading)
            pos.setLongitude(longitude / 1000000.0)
            pos.setLatitude(latitude / 1000000.0)
            positions.add(pos)
        }
        return positions
    } else {
        int count = buf.readUByte()
        def positions = []
        for (int i = 0; i < count; i++) {
            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.set(Position.KEY_BATTERY_LEVEL, battery)
            pos.set(Position.KEY_POWER, power / 1000.0)
            pos.set(Position.KEY_SATELLITES, satellites)
            int hdop = buf.readUByte()
            pos.setValid(hdop > 0)
            pos.set(Position.KEY_HDOP, hdop)
            int med = ((buf.readUByte() & 0xFF) << 16) | ((buf.readUByte() & 0xFF) << 8) | (buf.readUByte() & 0xFF)
            pos.setSpeed(UnitsConverter.knotsFromKph(med / 10.0))
            pos.setCourse(buf.readUShort())
            pos.setAltitude(buf.readShort())
            pos.setLongitude(buf.readInt() / 1000000.0)
            pos.setLatitude(buf.readInt() / 1000000.0)
            pos.setTime(decodeBinTime(buf))
            pos.setNetwork(new Network(CellTower.from(
                buf.readUShort(), buf.readUShort(), buf.readUShort(), buf.readUShort())))
            buf.readUByte()  // reserved
            positions.add(pos)
        }
        return positions
    }
}

def decodeBinEvent = { BufReader buf, ctx ->
    int type = buf.readUByte()
    buf.readUInt()    // mask
    buf.readUShort()  // length
    buf.readUByte()   // device type
    buf.readUShort()  // protocol version
    def pos = ctx.newPosition()
    pos.set(Position.KEY_VERSION_FW, String.valueOf(buf.readUShort()))
    String imei = String.format("%015d", buf.readLong())
    def session = ctx.session(imei)
    if (session == null) return null
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_BATTERY_LEVEL, buf.readUByte())
    pos.set(Position.KEY_POWER, buf.readUShort() / 1000.0)
    buf.readUByte()  // motion status
    pos.set(Position.KEY_SATELLITES, buf.readUByte())
    // type-specific skip
    switch (type) {
        case 6:  buf.readUShort(); break                                          // BPL: backup battery
        case 45: case 46: buf.readUShort(); buf.readUByte(); buf.readUInt(); break// VGN/VGF
        case 15: buf.readUShort(); buf.readUByte(); break                         // UPD
        case 17: buf.readUInt(); break                                            // IDF
        case 21: buf.readUByte(); buf.readUInt(); break                           // GSS
        case 26: buf.readUShort(); buf.readUByte(); buf.readUByte(); buf.readUInt(); buf.readUInt(); break  // GES
        case 31: buf.readUByte(); buf.readUByte(); break                          // GPJ
        case 35: buf.readUByte(); break                                           // RMD
        case 33: buf.readUByte(); break                                           // JDS
        case 23: buf.readUByte(); break                                           // CRA
        case 34: buf.readUByte(); buf.readUShort(); break                         // UPC
    }
    buf.readUByte()  // count
    int hdop = buf.readUByte()
    pos.setValid(hdop > 0)
    pos.set(Position.KEY_HDOP, hdop)
    int med = ((buf.readUByte() & 0xFF) << 16) | ((buf.readUByte() & 0xFF) << 8) | (buf.readUByte() & 0xFF)
    pos.setSpeed(UnitsConverter.knotsFromKph(med / 10.0))
    pos.setCourse(buf.readUShort())
    pos.setAltitude(buf.readShort())
    pos.setLongitude(buf.readInt() / 1000000.0)
    pos.setLatitude(buf.readInt() / 1000000.0)
    pos.setTime(decodeBinTime(buf))
    pos.setNetwork(new Network(CellTower.from(
        buf.readUShort(), buf.readUShort(), buf.readUShort(), buf.readUShort())))
    buf.readUByte()  // reserved
    pos
}

def decodeBinInformation = { BufReader buf, ctx ->
    int type = buf.readUByte()
    buf.readUInt()    // mask
    buf.readUShort()  // length
    String imei = String.format("%015d", buf.readLong())
    def session = ctx.session(imei)
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    buf.readUByte()   // device type
    buf.readUShort()  // protocol version
    pos.set(Position.KEY_VERSION_FW, String.valueOf(buf.readUShort()))
    if (type == 6) {  // MSG_INF_VER
        buf.readUShort(); buf.readUShort(); buf.readUShort()
    }
    buf.readUByte()  // motion status
    buf.readUByte()  // reserved
    pos.set(Position.KEY_SATELLITES, buf.readUByte())
    buf.readUByte()  // mode
    buf.skip(7)      // last fix time
    buf.readUByte(); buf.readUByte()
    buf.readUShort() // response report mask
    buf.readUShort() // ign interval
    buf.readUShort() // igf interval
    buf.readUInt()   // reserved
    buf.readUByte()  // reserved
    if (type == 7) {  // MSG_INF_BAT
        pos.set(Position.KEY_CHARGE, buf.readUByte() != 0)
        pos.set(Position.KEY_POWER, buf.readUShort() / 1000.0)
        pos.set(Position.KEY_BATTERY, buf.readUShort() / 1000.0)
        pos.set(Position.KEY_BATTERY_LEVEL, buf.readUByte())
    }
    buf.skip(10)  // iccid
    if (type == 5) {  // MSG_INF_CSQ
        pos.set(Position.KEY_RSSI, buf.readUByte())
        buf.readUByte()
    }
    buf.readUByte()  // time zone flags
    buf.readUShort() // time zone offset
    if (type == 10) {  // MSG_INF_GIR
        buf.readUByte(); buf.readUByte()
        pos.setNetwork(new Network(CellTower.from(
            buf.readUShort(), buf.readUShort(), buf.readUShort(), buf.readUShort())))
        buf.readUByte(); buf.readUByte()
    }
    ctx.lastLocation(pos, decodeBinTime(buf))
    pos
}

// ── TEXT DECODE HELPERS ────────────────────────────────────────────────────

def LON_PATTERN = ~/^-?\d{1,3}\.\d{6}$/

// find index where location block starts (lon is at idx+4 relative to hdop)
def findLocStart = { String[] v, int from ->
    for (int i = from; i < v.length - 5; i++) {
        if (v[i] ==~ LON_PATTERN) return Math.max(from, i - 4)
    }
    -1
}

// parse one location block (12 fields: hdop..rssi_odo), return next idx
def parseLocBlock = { pos, String[] v, int idx, ctx ->
    if (idx >= v.length) return idx
    if (!v[idx].isEmpty()) pos.set(Position.KEY_HDOP, Double.parseDouble(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty())
        pos.setSpeed(UnitsConverter.knotsFromKph(Double.parseDouble(v[idx])))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) pos.setCourse(Integer.parseInt(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) pos.setAltitude(Double.parseDouble(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) {
        pos.setValid(true)
        pos.setLongitude(Double.parseDouble(v[idx++]))
        if (idx < v.length) pos.setLatitude(Double.parseDouble(v[idx++]))
        if (idx < v.length) pos.setTime(parseDate14(v[idx++]))
    } else {
        idx += 3
        ctx.lastLocation(pos, null)
    }
    // mcc, mnc, lac, cid, rssi/odo
    Network net = new Network()
    if (idx + 3 < v.length && !v[idx].isEmpty()) {
        try {
            int mcc = Integer.parseInt(v[idx])
            int mnc = Integer.parseInt(v[idx + 1])
            String lacStr = v[idx + 2]; String cidStr = v[idx + 3]
            if (!lacStr.isEmpty() && !cidStr.isEmpty()) {
                try {
                    net.addCellTower(CellTower.from(mcc, mnc,
                        Integer.parseInt(lacStr, 16), Long.parseLong(cidStr, 16)))
                } catch (Exception ignored) {
                    net.addCellTower(CellTower.from(mcc, mnc,
                        Integer.parseInt(lacStr), Integer.parseInt(cidStr)))
                }
            }
        } catch (Exception ignored) {}
    }
    idx += 4
    if (net.getCellTowers() != null) pos.setNetwork(net)
    if (idx < v.length && !v[idx].isEmpty()) {
        // rssi (int) or odometer (float d+.d)
        String f = v[idx]
        if (f.contains(".")) pos.set(Position.KEY_ODOMETER, Double.parseDouble(f) * 1000)
    }
    idx + 1
}

// ── TEXT MESSAGE HANDLERS ──────────────────────────────────────────────────

// +ACK and +BUFF:GT / +RESP:GT — heartbeat and command responses
def decodeAck = { String[] v, ctx ->
    String header = v[0]
    if (header.equals("+ACK:GTHBD")) {
        ctx.ack("+SACK:GTHBD," + v[1] + "," + v[v.length - 1] + "\$")
        return null
    }
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, parseDate14(v[v.length - 2]))
    pos.setValid(false)
    pos.set(Position.KEY_RESULT, v[0])
    pos
}

// FRI / CTN / RTL / GEO / DOG / STR — standard position report (possibly batch)
def decodeFri = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    // detect optional VIN at v[3] (17 uppercase alphanumeric chars)
    int nameIdx = (v[3] ==~ /[0-9A-Z]{17}/) ? 4 : 3
    Integer power = null
    if (nameIdx + 1 < v.length && !v[nameIdx + 1].isEmpty()) {
        try { power = Integer.parseInt(v[nameIdx + 1]) } catch (Exception ignored) {}
    }
    // find where location block starts by scanning for longitude pattern
    int locStart = findLocStart(v, nameIdx + 1)
    if (locStart < 0) return null
    int count = 1
    try { count = Integer.parseInt(v[locStart - 1]) } catch (Exception ignored) {}
    if (count <= 0 || count > 20) count = 1

    def positions = []
    int idx = locStart
    for (int i = 0; i < count; i++) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        idx = parseLocBlock(pos, v, idx, ctx)
        positions.add(pos)
    }
    def last = positions.last()
    if (power != null && power > 10) last.set(Position.KEY_POWER, power / 1000.0)

    // parse trailing fields after location block(s)
    int ti = idx  // trailing start
    int deviceTimeIdx = v.length - 2
    if (deviceTimeIdx > ti && v[deviceTimeIdx] && v[deviceTimeIdx].length() == 14) {
        last.setDeviceTime(parseDate14(v[deviceTimeIdx]))
    }
    // scan trailing for odometer, hours, battery, status
    for (int i = ti; i < deviceTimeIdx; i++) {
        String f = v[i]
        if (!f) continue
        if (f ==~ /\d{1,7}\.\d/) {
            last.set(Position.KEY_ODOMETER, Double.parseDouble(f) * 1000)
        } else if (f ==~ /\d{5}:\d{2}:\d{2}/) {
            last.set(Position.KEY_HOURS, parseHours(f))
        } else if (f ==~ /[0-9a-fA-F]{6}/) {
            try { decodeStatusBits(last, Long.parseLong(f, 16)) } catch (Exception ignored) {}
        } else if (f ==~ /\d{1,3}/) {
            try {
                int val = Integer.parseInt(f)
                if (val <= 100) last.set(Position.KEY_BATTERY_LEVEL, val)
            } catch (Exception ignored) {}
        }
    }
    positions
}

// ERI — extended regular interval report
def decodeEri = { String[] v, ctx ->
    String protocolVersion = v[1]
    def session = ctx.session(v[2])
    if (session == null) return null
    String model = getModel(protocolVersion)
    int idx = 3  // device name
    idx++
    long mask = 0L
    try { mask = Long.parseLong(v[idx], 16) } catch (Exception ignored) {}
    idx++
    Double power = v[idx].isEmpty() ? null : Integer.parseInt(v[idx]) / 1000.0
    idx++
    idx++  // report type
    int count = Integer.parseInt(v[idx++])
    def positions = []
    for (int i = 0; i < count; i++) {
        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        idx = decodeLocIdx(pos, model, v, idx)
        positions.add(pos)
    }
    def last = positions.last()
    last.set(Position.KEY_POWER, power)

    if (!model.startsWith("GL5")) {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_ODOMETER, Double.parseDouble(v[idx]) * 1000)
        idx++
    }
    if (!model.startsWith("GL5") && !model.equals("GL320M")) {
        if (idx < v.length && !v[idx].isEmpty()) last.set(Position.KEY_HOURS, parseHours(v[idx]))
        idx++
        if (idx < v.length && !v[idx].isEmpty()) decodeAnalog(last, 1, v[idx])
        idx++
    }
    if (model.startsWith("GV") && !model.startsWith("GV6") && !model.equals("GV350M")) {
        if (idx < v.length && !v[idx].isEmpty()) decodeAnalog(last, 2, v[idx])
        idx++
    }
    if (model.equals("GV200") || model.equals("GV310LAU")) {
        if (idx < v.length && !v[idx].isEmpty()) decodeAnalog(last, 3, v[idx])
        idx++
    }
    if ((model.startsWith("GV3") && model.endsWith("CEU")) || model.startsWith("GV600M")) idx++

    if (model.startsWith("GL5")) {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[idx]))
        idx++
        idx++  // mode
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_MOTION, Integer.parseInt(v[idx]) > 0)
        idx++
    } else if (model.equals("GV200")) {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_INPUT, Integer.parseInt(v[idx], 16))
        idx++
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_OUTPUT, Integer.parseInt(v[idx], 16))
        idx++
        idx++  // uart device type
    } else if (model.equals("GL320M")) {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[idx]))
        idx++
        if (BitUtil.check(mask, 7) && idx < v.length && !v[idx].isEmpty())
            last.set("externalBattery", Integer.parseInt(v[idx]))
        idx++
    } else {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[idx]))
        idx++
        if (idx < v.length && !v[idx].isEmpty()) {
            try { decodeStatusBits(last, Long.parseLong(v[idx], 16)) } catch (Exception ignored) {}
        }
        idx++
        idx++  // uart device type
    }

    Date deviceTime = parseDate14(v[v.length - 2])
    last.setDeviceTime(deviceTime)

    if (BitUtil.check(mask, 0) && !model.equals("GV350M")) {
        if (idx < v.length && !v[idx].isEmpty())
            last.set(Position.KEY_FUEL, Integer.parseInt(v[idx], 16))
        idx++
    }
    if (BitUtil.check(mask, 1) && idx < v.length) {
        int dc = Integer.parseInt(v[idx++])
        for (int i = 1; i <= dc && idx + 2 < v.length; i++) {
            idx++; idx++  // id, type
            if (!v[idx].isEmpty())
                last.set(Position.PREFIX_TEMP + i, (short)Integer.parseInt(v[idx], 16) * 0.0625)
            idx++
        }
    }
    if (BitUtil.check(mask, 2)) return positions  // CAN data not parsed

    if ((BitUtil.check(mask, 3) || BitUtil.check(mask, 4) ||
            (BitUtil.check(mask, 0) && model.equals("GV350M"))) && idx < v.length - 2) {
        int dc = Integer.parseInt(v[idx++])
        for (int i = 1; i <= dc && idx < v.length - 2; i++) {
            idx++  // type
            if (model.equals("GV350M")) {
                idx++  // uart id
                if (BitUtil.check(mask, 0) && !v[idx].isEmpty())
                    last.set(Position.KEY_FUEL, Integer.parseInt(v[idx], 16))
                idx++
            }
            if (BitUtil.check(mask, 3) && !v[idx].isEmpty())
                last.set(Position.KEY_FUEL, Double.parseDouble(v[idx]))
            idx++
            if (BitUtil.check(mask, 4)) idx++  // volume
        }
    }

    if (BitUtil.check(mask, 7) && !model.equals("GL320M") && idx < v.length - 2) {
        int dc = Integer.parseInt(v[idx++])
        for (int i = 1; i <= dc && idx + 2 < v.length; i++) {
            idx++  // serial number
            int dType = Integer.parseInt(v[idx++])
            idx++  // temperature
            if (dType == 2) idx++  // humidity
        }
    }

    if (BitUtil.check(mask, 8) && !model.equals("GL320M") && idx < v.length - 2) {
        int dc = Integer.parseInt(v[idx++])
        for (int i = 1; i <= dc && idx < v.length - 2; i++) {
            idx++; idx++; idx++  // index, type, model
            if (model.startsWith("GV600M")) idx++  // raw data length
            idx++  // raw data
            int dm = Integer.parseInt(v[idx++], 16)
            if (BitUtil.check(dm, 0)) idx++  // name
            if (BitUtil.check(dm, 1) && !v[idx].isEmpty())
                last.set("tag" + i + "Id", v[idx])
            idx++
            if (BitUtil.check(dm, 2)) idx++  // status
            if (BitUtil.check(dm, 3)) idx++  // battery level
            if (BitUtil.check(dm, 4) && !v[idx].isEmpty())
                last.set("tag" + i + "Temp", Double.parseDouble(v[idx]))
            idx++
            if (BitUtil.check(dm, 5) && !v[idx].isEmpty())
                last.set("tag" + i + "Humidity", Integer.parseInt(v[idx]))
            idx++
            if (BitUtil.check(dm, 7))  idx++  // in/out
            if (BitUtil.check(dm, 8))  idx++  // event notification
            if (BitUtil.check(dm, 9))  idx++  // tire pressure
            if (BitUtil.check(dm, 10)) idx++  // timestamp
            if (BitUtil.check(dm, 11)) idx++  // enhanced temp
            if (BitUtil.check(dm, 12)) idx++  // magnet
            if (BitUtil.check(dm, 13) && !v[idx].isEmpty())
                last.set("tag" + i + "Battery", Integer.parseInt(v[idx]))
            idx++
            if (BitUtil.check(dm, 14)) idx++  // relay
        }
    }

    positions
}

// IGN / IGF / VGN / VGF — ignition events
def decodeIgn = { String[] v, String type, ctx ->
    String protocolVersion = v[1]
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    String model = getModel(protocolVersion)
    int idx = 3  // device name
    idx++
    if (model.equals("CV200")) { idx++; idx++ }  // reserved, report type
    idx++  // duration
    idx = decodeLocIdx(pos, model, v, idx)
    pos.set(Position.KEY_IGNITION, type.contains("GN"))
    if (idx < v.length) {
        def hours = parseHours(v[idx]); idx++
        if (hours != null) pos.set(Position.KEY_HOURS, hours)
    }
    if (idx < v.length && !v[idx].isEmpty())
        pos.set(Position.KEY_ODOMETER, Double.parseDouble(v[idx]) * 1000)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// INF — device information
def decodeInf = { String[] v, ctx ->
    int idx = 1
    String protocolVersion = v[idx++]
    if (protocolVersion.length() > 10) return null  // GT300 protocol guard
    def session = ctx.session(v[idx++])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    String model = getModel(protocolVersion)
    idx++  // device name
    if (!v[idx].isEmpty()) {
        int state = Integer.parseInt(v[idx], 16)
        switch (state) {
            case 0x16: case 0x1A: case 0x12:
                pos.set(Position.KEY_IGNITION, false); pos.set(Position.KEY_MOTION, true); break
            case 0x11:
                pos.set(Position.KEY_IGNITION, false); pos.set(Position.KEY_MOTION, false); break
            case 0x21:
                pos.set(Position.KEY_IGNITION, true);  pos.set(Position.KEY_MOTION, false); break
            case 0x22:
                pos.set(Position.KEY_IGNITION, true);  pos.set(Position.KEY_MOTION, true); break
            case 0x41: pos.set(Position.KEY_MOTION, false); break
            case 0x42: pos.set(Position.KEY_MOTION, true);  break
        }
    }
    idx++
    pos.set(Position.KEY_ICCID, v[idx++])
    if (!v[idx].isEmpty()) pos.set(Position.KEY_RSSI, Integer.parseInt(v[idx]))
    idx++
    idx++  // signal quality
    idx++  // external power supply
    if (idx < v.length - 2 && v[idx + 1].length() >= 12) {
        idx++  // BLE sensor mac
        pos.set(Position.KEY_DEVICE_TEMP, Integer.parseInt(v[idx++]))
        pos.set(Position.KEY_HUMIDITY, Integer.parseInt(v[idx++]))
    }
    if (idx < v.length && !v[idx].isEmpty()) {
        String val = v[idx]
        if (val.contains(".")) pos.set(Position.KEY_ODOMETER, Double.parseDouble(val) * 1000)
        else pos.set(Position.KEY_POWER, Integer.parseInt(val) / 1000.0)
    }
    idx++
    if (!model.equals("GV500VC")) {
        if (model.equals("GV350M") || model.equals("GV310LAU")) {
            idx++  // expand mask or network type
        } else if (idx < v.length && !v[idx].isEmpty()) {
            pos.set("power2", Integer.parseInt(v[idx]) / 1000.0)
            idx++
        } else { idx++ }
    }
    if (idx < v.length && !v[idx].isEmpty()) pos.set(Position.KEY_BATTERY, Double.parseDouble(v[idx]))
    idx++
    if (idx < v.length && !v[idx].isEmpty()) {
        pos.set(Position.KEY_CHARGE, Integer.parseInt(v[idx]) == 1)
        idx++
    } else { idx++ }
    if (model.equals("GV310LAU")) {
        idx += 5  // led, power saving, antenna, last fix, pin mask
        if (idx < v.length) pos.set(Position.PREFIX_ADC + "1", Integer.parseInt(v[idx++]))
        if (idx < v.length) pos.set(Position.PREFIX_ADC + "2", Integer.parseInt(v[idx++]))
        if (idx < v.length) pos.set(Position.PREFIX_ADC + "3", Integer.parseInt(v[idx++]))
    }
    ctx.lastLocation(pos, parseDate14(v[v.length - 2]))
    pos
}

// LSW / TSW — switch events
def decodeLsw = { String sentence, String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    // v[4] = type (0/1), v[5] = state
    int state = Integer.parseInt(v[5])
    if (sentence.contains("LSW")) pos.set(Position.PREFIX_IN + "1", state == 1)
    else                          pos.set(Position.PREFIX_IN + "2", state == 1)
    // location at v[6..] - use findLocStart
    int ls = findLocStart(v, 6)
    if (ls >= 0) parseLocBlock(pos, v, ls, ctx)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// PNA / PFA — power on/off alarms
def decodePna = { String sentence, String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, parseDate14(v[v.length - 2]))
    pos.addAlarm(sentence.contains("PNA") ? Position.ALARM_POWER_ON : Position.ALARM_POWER_OFF)
    pos
}

// VER — version report
def decodeVer = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    if (v.length > 4) pos.set("deviceType", v[4])
    if (v.length > 5) try { pos.set(Position.KEY_VERSION_FW, Integer.parseInt(v[5], 16)) } catch (Exception ignored) {}
    if (v.length > 6) try { pos.set(Position.KEY_VERSION_HW, Integer.parseInt(v[6], 16)) } catch (Exception ignored) {}
    ctx.lastLocation(pos, parseDate14(v[v.length - 2]))
    pos
}

// IDA — driver ID
def decodeIda = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_DRIVER_UNIQUE_ID, v[4])
    int ls = findLocStart(v, 5)
    if (ls >= 0) {
        int ni = parseLocBlock(pos, v, ls, ctx)
        if (ni < v.length && !v[ni].isEmpty())
            pos.set(Position.KEY_ODOMETER, Double.parseDouble(v[ni]) * 1000)
    }
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// WIF — WiFi scan
def WIFI_PAT = Pattern.compile("([0-9a-fA-F]{12}),(-?\\d+),,,,")
def decodeWif = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    // join the wifi MAC block and parse
    if (v.length > 5) {
        Network net = new Network()
        def joined = v[4..-1].join(",")  // everything after count
        def m = WIFI_PAT.matcher(joined)
        while (m.find()) {
            String mac = m.group(1).replaceAll("(..)", "\$1:")
            net.addWifiAccessPoint(WifiAccessPoint.from(mac.substring(0, mac.length() - 1),
                Integer.parseInt(m.group(2))))
        }
        if (net.getWifiAccessPoints()) pos.setNetwork(net)
    }
    if (v.length > 3) try { pos.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[v.length - 3])) } catch (Exception ignored) {}
    pos
}

// GSM — cell scan
def decodeGsm = { String sentence, String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    Network net = new Network()
    for (int i = 4; i + 4 < v.length - 2; i += 6) {
        if (!v[i].isEmpty()) {
            try {
                net.addCellTower(CellTower.from(
                    Integer.parseInt(v[i]), Integer.parseInt(v[i + 1]),
                    Integer.parseInt(v[i + 2], 16), Integer.parseInt(v[i + 3], 16),
                    Integer.parseInt(v[i + 4])))
            } catch (Exception ignored) {}
        }
    }
    if (net.getCellTowers()) pos.setNetwork(net)
    pos
}

// DAR — driver alert
def decodeDar = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    if (v.length < 5) return null
    int warningType = Integer.parseInt(v[4])
    if (warningType == 1) {
        pos.addAlarm(Position.ALARM_FATIGUE_DRIVING)
        if (v.length > 5) try { pos.set("fatigueDegree", Integer.parseInt(v[5])) } catch (Exception ignored) {}
    } else {
        pos.set("warningType", warningType)
    }
    int ls = findLocStart(v, 6)
    if (ls >= 0) parseLocBlock(pos, v, ls, ctx)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// DTT — data transfer (sensor hex payload)
def decodeDtt = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    // v[6] = hex data
    if (v.length > 6 && !v[6].isEmpty()) {
        try {
            String data = new String(v[6].decodeHex(), StandardCharsets.US_ASCII)
            if (data.contains("COMB")) {
                pos.set(Position.KEY_FUEL, Double.parseDouble(data.split(",")[2]))
            } else {
                pos.set(Position.KEY_RESULT, data)
            }
        } catch (Exception ignored) {}
    }
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// BAA — BLE accessory alert
def decodeBaa = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    if (v.length < 7) return null
    int mask = 0
    try { mask = Integer.parseInt(v[6], 16) } catch (Exception ignored) {}
    // optional values start at v[7]
    int vi = 7
    if (BitUtil.check(mask, 0) && vi < v.length) { pos.set("accessoryName", v[vi++]) }
    if (BitUtil.check(mask, 1) && vi < v.length) { pos.set("accessoryMac", v[vi++]) }
    if (BitUtil.check(mask, 2) && vi < v.length) { try { pos.set("accessoryStatus", Integer.parseInt(v[vi])) } catch (Exception ignored) {}; vi++ }
    if (BitUtil.check(mask, 3) && vi < v.length) { try { pos.set("accessoryVoltage", Integer.parseInt(v[vi]) / 1000.0) } catch (Exception ignored) {}; vi++ }
    if (BitUtil.check(mask, 4) && vi < v.length) { try { pos.set("accessoryTemp", Integer.parseInt(v[vi])) } catch (Exception ignored) {}; vi++ }
    if (BitUtil.check(mask, 5) && vi < v.length) { try { pos.set("accessoryHumidity", Integer.parseInt(v[vi])) } catch (Exception ignored) {}; vi++ }
    int ls = findLocStart(v, vi)
    if (ls >= 0) parseLocBlock(pos, v, ls, ctx)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// BID — BLE beacon ID
def decodeBid = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    int mask = 0
    try { mask = Integer.parseInt(v[5], 16) } catch (Exception ignored) {}
    int vi = 6
    if (BitUtil.check(mask, 1) && vi < v.length) { pos.set("accessoryMac", v[vi++]) }
    if (BitUtil.check(mask, 3) && vi < v.length) { try { pos.set("accessoryVoltage", Integer.parseInt(v[vi]) / 1000.0) } catch (Exception ignored) {}; vi++ }
    int ls = findLocStart(v, vi)
    if (ls >= 0) parseLocBlock(pos, v, ls, ctx)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// LSA — light sensor alert
def decodeLsa = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    int ls = findLocStart(v, 4)
    int ni = ls >= 0 ? parseLocBlock(pos, v, ls, ctx) : 4
    if (ni < v.length) { try { ni++ } catch (Exception ignored) {} }  // bit error rate
    if (ni < v.length) { try { pos.set("lightLevel", Integer.parseInt(v[ni])) } catch (Exception ignored) {}; ni++ }
    if (ni < v.length) { try { pos.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[ni])) } catch (Exception ignored) {}; ni++ }
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// DAT — generic data report
def decodeDat = { String[] v, ctx ->
    String protocolVersion = v[1]
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    String model = getModel(protocolVersion)
    // v[3]=name, v[4]=reportType, v[5]=reserved, v[6]=reserved, v[7]=data
    if (v.length > 7) pos.set("data", v[7])
    int ls = findLocStart(v, 7)
    if (ls >= 0) decodeLocIdx(pos, model, v, ls)
    pos.setDeviceTime(parseDate14(v[v.length - 2]))
    pos
}

// CAN bus data report
def decodeCan = { String[] v, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    String model = getModel(v[1])
    def i = [6]  // array-boxed index: start at reportMask field (0=hdr,1=ver,2=imei,3=name,4=rtype,5=state,6=mask)
    long mask = 0L
    try { mask = Long.parseLong(v[i[0]], 16) } catch (Exception ignored) {}
    i[0]++

    // inline helpers: c() consumes + sets if non-empty; s() just skips
    def c = { boolean set, Closure action ->
        if (set && i[0] < v.length) {
            String f = v[i[0]++]
            if (!f.isEmpty()) try { action(f) } catch (Exception ignored2) {}
        }
    }
    def sk = { boolean cond -> if (cond && i[0] < v.length) i[0]++ }

    c(BitUtil.check(mask, 0))  { f -> pos.set(Position.KEY_VIN, f) }
    c(BitUtil.check(mask, 1))  { f -> pos.set(Position.KEY_IGNITION, Integer.parseInt(f) > 0) }
    c(BitUtil.check(mask, 2))  { f -> pos.set(Position.KEY_OBD_ODOMETER, Integer.parseInt(f.substring(1))) }
    c(BitUtil.check(mask, 3))  { f -> pos.set(Position.KEY_FUEL_USED, Double.parseDouble(f)) }
    c(BitUtil.check(mask, 5))  { f -> pos.set(Position.KEY_RPM, Integer.parseInt(f)) }
    c(BitUtil.check(mask, 4))  { f -> pos.set(Position.KEY_OBD_SPEED, Integer.parseInt(f)) }
    c(BitUtil.check(mask, 6))  { f -> pos.set(Position.KEY_COOLANT_TEMP, Integer.parseInt(f)) }
    c(BitUtil.check(mask, 7))  { f -> if (f.startsWith("L/H")) pos.set(Position.KEY_FUEL_CONSUMPTION, Double.parseDouble(f.substring(3))) }
    c(BitUtil.check(mask, 8))  { f -> pos.set(Position.KEY_FUEL, Double.parseDouble(f.substring(1))) }
    c(BitUtil.check(mask, 9))  { f -> pos.set("range", Long.parseLong(f) * 100) }
    c(BitUtil.check(mask, 10)) { f -> pos.set(Position.KEY_THROTTLE, Integer.parseInt(f)) }
    c(BitUtil.check(mask, 11)) { f -> pos.set(Position.KEY_HOURS, (long)(Double.parseDouble(f) * 3600000L)) }
    c(BitUtil.check(mask, 12)) { f -> pos.set(Position.KEY_DRIVING_TIME, Double.parseDouble(f)) }
    c(BitUtil.check(mask, 13)) { f -> pos.set("idleHours", Double.parseDouble(f)) }
    c(BitUtil.check(mask, 14)) { f -> pos.set("idleFuelConsumption", Double.parseDouble(f)) }
    c(BitUtil.check(mask, 15)) { f -> pos.set(Position.KEY_AXLE_WEIGHT, Integer.parseInt(f)) }
    c(BitUtil.check(mask, 16)) { f -> pos.set("tachographInfo", Integer.parseInt(f, 16)) }
    c(BitUtil.check(mask, 17)) { f -> pos.set("indicators", Integer.parseInt(f, 16)) }
    c(BitUtil.check(mask, 18)) { f -> pos.set("lights", Integer.parseInt(f, 16)) }
    c(BitUtil.check(mask, 19)) { f -> pos.set("doors", Integer.parseInt(f, 16)) }
    c(BitUtil.check(mask, 20)) { f -> pos.set("vehicleOverspeed", Double.parseDouble(f)) }
    c(BitUtil.check(mask, 21)) { f -> pos.set("engineOverspeed", Double.parseDouble(f)) }

    if (model == "GV350M") {
        sk(BitUtil.check(mask, 22)); sk(BitUtil.check(mask, 23)); sk(BitUtil.check(mask, 24))
    } else if (model == "GV355CEU") {
        sk(BitUtil.check(mask, 22)); sk(BitUtil.check(mask, 23)); sk(BitUtil.check(mask, 24))
        sk(BitUtil.check(mask, 25)); sk(BitUtil.check(mask, 26)); sk(BitUtil.check(mask, 27))
        sk(BitUtil.check(mask, 28))
    }

    long extMask = 0L
    if (BitUtil.check(mask, 29) && i[0] < v.length) {
        try { extMask = Long.parseLong(v[i[0]], 16) } catch (Exception ignored) {}
        i[0]++
    }

    c(BitUtil.check(extMask, 0))  { f -> pos.set("adBlueLevel", Double.parseDouble(f.substring(1))) }
    c(BitUtil.check(extMask, 1))  { f -> pos.set("axleWeight1", Integer.parseInt(f)) }
    c(BitUtil.check(extMask, 2))  { f -> pos.set("axleWeight3", Integer.parseInt(f)) }
    c(BitUtil.check(extMask, 3))  { f -> pos.set("axleWeight4", Integer.parseInt(f)) }
    sk(BitUtil.check(extMask, 4)); sk(BitUtil.check(extMask, 5)); sk(BitUtil.check(extMask, 6))
    c(BitUtil.check(extMask, 7))  { f -> pos.set(Position.PREFIX_ADC + 1, Integer.parseInt(f) / 1000.0) }
    sk(BitUtil.check(extMask, 8));  sk(BitUtil.check(extMask, 9));  sk(BitUtil.check(extMask, 10))
    sk(BitUtil.check(extMask, 11)); sk(BitUtil.check(extMask, 12)); sk(BitUtil.check(extMask, 13))
    sk(BitUtil.check(extMask, 14))
    c(BitUtil.check(extMask, 15)) { f -> pos.set("driver1Card", f) }
    c(BitUtil.check(extMask, 16)) { f -> pos.set("driver2Card", f) }
    c(BitUtil.check(extMask, 17)) { f -> pos.set("driver1Name", f) }
    c(BitUtil.check(extMask, 18)) { f -> pos.set("driver2Name", f) }
    c(BitUtil.check(extMask, 19)) { f -> pos.set("registration", f) }
    sk(BitUtil.check(extMask, 20)); sk(BitUtil.check(extMask, 21)); sk(BitUtil.check(extMask, 22))
    sk(BitUtil.check(extMask, 23)); sk(BitUtil.check(extMask, 24)); sk(BitUtil.check(extMask, 25))
    sk(BitUtil.check(extMask, 26)); sk(BitUtil.check(extMask, 27)); sk(BitUtil.check(extMask, 28))
    sk(BitUtil.check(extMask, 29)); sk(BitUtil.check(extMask, 30))

    if (BitUtil.check(extMask, 31) && i[0] < v.length) {
        long canMask = 0L
        try { canMask = Long.parseLong(v[i[0]], 16) } catch (Exception ignored) {}
        i[0]++
        sk(BitUtil.check(canMask, 0)); sk(BitUtil.check(canMask, 1)); sk(BitUtil.check(canMask, 2))
    }

    if (model != "GV355CEU" && BitUtil.check(mask, 30)) {
        while (i[0] < v.length && v[i[0]].isEmpty()) i[0]++
        if (i[0] < v.length) {
            boolean valid = Integer.parseInt(v[i[0]++]) > 0
            if (i[0] < v.length && !v[i[0]].isEmpty()) {
                pos.setValid(valid)
                pos.setSpeed(UnitsConverter.knotsFromKph(Double.parseDouble(v[i[0]++])))
                pos.setCourse(Integer.parseInt(v[i[0]++]))
                pos.setAltitude(Double.parseDouble(v[i[0]++]))
                pos.setLongitude(Double.parseDouble(v[i[0]++]))
                pos.setLatitude(Double.parseDouble(v[i[0]++]))
                pos.setTime(parseDate14(v[i[0]++]))
            } else {
                i[0] += 6
                ctx.lastLocation(pos, null)
            }
        }
    } else {
        ctx.lastLocation(pos, null)
    }

    if (BitUtil.check(mask, 31)) i[0] += 5  // cell (4) + reserved (1)

    if (v.length >= 2 && v[v.length - 2]?.length() == 14) {
        pos.setDeviceTime(parseDate14(v[v.length - 2]))
    }

    pos
}

// basic fallback for any unrecognised type
def decodeBasic = { String[] v, String type, ctx ->
    def session = ctx.session(v[2])
    if (session == null) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    // check for alarm at v[v.length-4] or thereabouts (report type field)
    if (v.length > 5 && v[5] ==~ /[0-9a-fA-F]{1,2}/) {
        int reportType
        try { reportType = Integer.parseInt(v[5], 16) } catch (Exception ignored) { reportType = -1 }
        if (reportType >= 0) {
            switch (type) {
                case "NMR": pos.set(Position.KEY_MOTION, reportType == 1); break
                case "DIS": if (reportType > 0) pos.set(Position.PREFIX_IN + (reportType.intdiv(0x10)), reportType % 0x10 == 1); break
                case "IGL": pos.set(Position.KEY_IGNITION, reportType % 0x10 == 1); break
                case "HBM":
                    switch (reportType % 0x10) {
                        case 0: case 3: pos.addAlarm(Position.ALARM_BRAKING); break
                        case 1: case 4: pos.addAlarm(Position.ALARM_ACCELERATION); break
                        case 2: pos.addAlarm(Position.ALARM_CORNERING); break
                    }
                    break
            }
        }
    }
    // find location
    int ls = findLocStart(v, 3)
    if (ls >= 0) parseLocBlock(pos, v, ls, ctx)
    else ctx.lastLocation(pos, null)
    // find cell tower
    for (int i = 3; i + 3 < v.length - 2; i++) {
        if (v[i] ==~ /\d{4}/ && v[i+1] ==~ /\d{4}/ &&
            v[i+2] ==~ /[0-9a-fA-F]{4}/ && v[i+3] ==~ /[0-9a-fA-F]{4,8}/) {
            try {
                pos.setNetwork(new Network(CellTower.from(
                    Integer.parseInt(v[i]), Integer.parseInt(v[i+1]),
                    Integer.parseInt(v[i+2], 16), Long.parseLong(v[i+3], 16))))
            } catch (Exception ignored) {}
            break
        }
    }
    // battery level before deviceTime
    int dti = v.length - 2
    if (dti > 3 && v[dti] && v[dti].length() == 14 && v[dti-1] ==~ /\d{1,3}/) {
        try { pos.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(v[dti-1])) } catch (Exception ignored) {}
    }
    if (v.length >= 2 && v[dti] && v[dti].length() == 14) {
        pos.setDeviceTime(parseDate14(v[dti]))
    }
    // alarm by type
    switch (type) {
        case "SOS": pos.addAlarm(Position.ALARM_SOS); break
        case "SPD": pos.addAlarm(Position.ALARM_OVERSPEED); break
        case "TOW": pos.addAlarm(Position.ALARM_TOW); break
        case "IDL": pos.addAlarm(Position.ALARM_IDLE); break
        case "PNA": pos.addAlarm(Position.ALARM_POWER_ON); break
        case "PFA": pos.addAlarm(Position.ALARM_POWER_OFF); break
        case "EPN": case "MPN": pos.addAlarm(Position.ALARM_POWER_RESTORED); break
        case "EPF": case "MPF": pos.addAlarm(Position.ALARM_POWER_CUT); break
        case "BPL": pos.addAlarm(Position.ALARM_LOW_BATTERY); break
        case "STT": pos.addAlarm(Position.ALARM_MOVEMENT); break
        case "SWG": pos.addAlarm(Position.ALARM_GEOFENCE); break
        case "TMP": case "TEM": pos.addAlarm(Position.ALARM_TEMPERATURE); break
        case "JDR": case "JDS": pos.addAlarm(Position.ALARM_JAMMING); break
    }
    (!pos.getAttributes().isEmpty() || pos.getNetwork() != null) ? pos : null
}

// ── BINARY / TEXT DISPATCH ─────────────────────────────────────────────────

def BINARY_HEADERS = ["+RSP", "+BSP", "+EVT", "+BVT", "+INF", "+BNF",
                      "+HBD", "+CRD", "+BRD", "+LGN"] as Set

def isBinaryFrame = { BufReader buf ->
    int b0 = buf.getUByte(0), b1 = buf.getUByte(1), b2 = buf.getUByte(2), b3 = buf.getUByte(3)
    char[] hc = [b0 as char, b1 as char, b2 as char, b3 as char]
    String hdr = new String(hc)
    if (hdr == "+ACK") return buf.getUByte(4) != (int)':'
    BINARY_HEADERS.contains(hdr)
}

def getHdrStr = { BufReader buf ->
    char[] hc = [buf.getUByte(0) as char, buf.getUByte(1) as char,
                 buf.getUByte(2) as char, buf.getUByte(3) as char]
    new String(hc)
}

// ── PROTOCOL DEFINITION ────────────────────────────────────────────────────

protocol("gl200") {
    port 5004
    commands(
        Command.TYPE_POSITION_SINGLE,
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME,
        Command.TYPE_IDENTIFICATION,
        Command.TYPE_REBOOT_DEVICE
    )

    variant("main") {

        scriptedFrame { fb ->
            if (fb.readableBytes() < 4) return null
            String hdr = getHdrStr(fb)

            if (hdr == "+ACK" && fb.getUByte(4) != (int)':') {
                // binary ACK: length at offset 6 (1 byte)
                if (fb.readableBytes() < 7) return null
                int len = fb.getUByte(6)
                return fb.readableBytes() >= len ? len : null
            }
            if (BINARY_HEADERS.contains(hdr)) {
                if (fb.readableBytes() < 11) return null
                int len
                switch (hdr) {
                    case "+INF": case "+BNF": len = fb.getUShort(7); break
                    case "+HBD":              len = fb.getUByte(5);  break
                    case "+CRD": case "+BRD": case "+LGN": len = fb.getUShort(6); break
                    default:                  len = fb.getUShort(9); break
                }
                return fb.readableBytes() >= len ? len : null
            }
            // text: find '$' or null byte
            int n = fb.readableBytes()
            for (int i = 0; i < n; i++) {
                int b = fb.getUByte(i)
                if (b == (int)'$' || b == 0) return i + 1
            }
            null
        }

        decode { msg, ctx ->
            def buf = msg as BufReader
            if (buf.readableBytes() < 4) return null

            if (isBinaryFrame(buf)) {
                String hdr = getHdrStr(buf)
                buf.readString(4)  // consume 4-byte header
                switch (hdr) {
                    case "+RSP": case "+BSP": return decodeBinLocation(buf, ctx)
                    case "+EVT": case "+BVT": return decodeBinEvent(buf, ctx)
                    case "+INF": case "+BNF": return decodeBinInformation(buf, ctx)
                    default: return null
                }
            }

            // text: read all bytes as ASCII, strip '$' delimiter
            byte[] bytes = buf.readBytes(buf.readableBytes())
            String sentence = new String(bytes, StandardCharsets.US_ASCII)
            int dollarIdx = sentence.indexOf((int)'$')
            if (dollarIdx >= 0) sentence = sentence.substring(0, dollarIdx)
            int nullIdx = sentence.indexOf('\0')
            if (nullIdx >= 0) sentence = sentence.substring(0, nullIdx)
            sentence = sentence.trim()

            if (sentence.isEmpty()) return null
            String[] v = sentence.split(",", -1)
            if (v.length < 3) return null

            int typeIdx = sentence.indexOf(":GT")
            String type = typeIdx >= 0 ? sentence.substring(typeIdx + 3, Math.min(typeIdx + 6, sentence.length())) : ""

            Object result
            if (sentence.startsWith("+ACK")) {
                result = decodeAck(v, ctx)
            } else {
                result = switch (type) {
                    case "INF" -> decodeInf(v, ctx)
                    case "ERI" -> decodeEri(v, ctx)
                    case "IGN", "IGF", "VGN", "VGF" -> decodeIgn(v, type, ctx)
                    case "CTN", "FRI", "GEO", "RTL", "DOG", "STR" -> decodeFri(v, ctx)
                    case "LSW", "TSW" -> decodeLsw(sentence, v, ctx)
                    case "PNA", "PFA" -> decodePna(sentence, v, ctx)
                    case "VER" -> decodeVer(v, ctx)
                    case "IDA" -> decodeIda(v, ctx)
                    case "WIF" -> decodeWif(v, ctx)
                    case "GSM" -> decodeGsm(sentence, v, ctx)
                    case "DAR" -> decodeDar(v, ctx)
                    case "DTT" -> decodeDtt(v, ctx)
                    case "BAA" -> decodeBaa(v, ctx)
                    case "BID" -> decodeBid(v, ctx)
                    case "LSA" -> decodeLsa(v, ctx)
                    case "DAT" -> decodeDat(v, ctx)
                    case "CAN" -> decodeCan(v, ctx)
                    default -> null
                }
                if (result == null) result = decodeBasic(v, type, ctx)
                if (result != null) {
                    def setType = { pos -> pos.set(Position.KEY_TYPE, type) }
                    if (result instanceof List) result.each { setType(it) }
                    else setType(result)
                }
            }
            result
        }

        encode { cmd, ctx ->
            String pw = ctx.store().getOrDefault("password", "gl200") as String
            switch (cmd.type) {
                case Command.TYPE_POSITION_SINGLE: return "AT+GTRTO=${pw},1,,,,,,FFFF\$"
                case Command.TYPE_ENGINE_STOP:     return "AT+GTOUT=${pw},1,,,0,0,0,0,0,0,0,,,,,,,FFFF\$"
                case Command.TYPE_ENGINE_RESUME:   return "AT+GTOUT=${pw},0,,,0,0,0,0,0,0,0,,,,,,,FFFF\$"
                case Command.TYPE_IDENTIFICATION:  return "AT+GTRTO=${pw},8,,,,,,FFFF\$"
                case Command.TYPE_REBOOT_DEVICE:   return "AT+GTRTO=${pw},3,,,,,,FFFF\$"
                default: return null
            }
        }
    }
}
