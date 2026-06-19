// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

import org.traccar.driver.BufReader
import org.traccar.helper.ObdDecoder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position

import groovy.json.JsonOutput
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

// ── message type constants ─────────────────────────────────────────────────

def MSG_SC_LOGIN               = 0x1001
def MSG_SC_LOGOUT              = 0x1002
def MSG_SC_HEARTBEAT           = 0x1003
def MSG_SC_HEARTBEAT_RESPONSE  = 0x9003
def MSG_SC_GPS                 = 0x4001
def MSG_SC_PID_DATA            = 0x4002
def MSG_SC_G_SENSOR            = 0x4003
def MSG_SC_OBD_DATA            = 0x4005
def MSG_SC_DTCS_PASSENGER      = 0x4006
def MSG_SC_ALARM               = 0x4007
def MSG_SC_ALARM_RESPONSE      = 0xC007
def MSG_SC_CELL                = 0x4008
def MSG_SC_GPS_SLEEP           = 0x4009
def MSG_SC_DTCS_COMMERCIAL     = 0x400B
def MSG_SC_FUEL                = 0x400E
def MSG_SC_COMPREHENSIVE       = 0x401F
def MSG_SC_AGPS_REQUEST        = 0x5101
def MSG_SC_LOGIN_RESPONSE      = 0x9001
def MSG_SC_QUERY_RESPONSE      = 0xA002
def MSG_SC_CURRENT_LOCATION    = 0xB001

def MSG_CC_LOGIN               = 0x4001
def MSG_CC_LOGIN_RESPONSE      = 0x8001
def MSG_CC_HEARTBEAT           = 0x4206
def MSG_CC_PETROL_CONTROL      = 0x4583
def MSG_CC_HEARTBEAT_RESPONSE  = 0x8206

// ── CRC16-X25 ──────────────────────────────────────────────────────────────

def crc16X25 = { byte[] data ->
    int crc = 0xFFFF
    for (byte b : data) {
        crc ^= (b & 0xFF)
        for (int i = 0; i < 8; i++) {
            crc = (crc & 1) != 0 ? ((crc >>> 1) ^ 0x8408) : (crc >>> 1)
        }
    }
    (crc ^ 0xFFFF) & 0xFFFF
}

// ── frame builder ──────────────────────────────────────────────────────────

def buildFrame = { byte[] idBytes, int version, int type, byte[] content ->
    int contentLen = content ? content.length : 0
    int length = 31 + contentLen  // @@(2)+len(2)+ver(1)+id(20)+type(2)+crc(2)+\r\n(2) = 31
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    baos.write(0x40); baos.write(0x40)
    baos.write(length & 0xFF); baos.write((length >> 8) & 0xFF)
    baos.write(version)
    baos.write(idBytes, 0, 20)
    baos.write((type >> 8) & 0xFF); baos.write(type & 0xFF)
    if (content) baos.write(content)
    byte[] frame = baos.toByteArray()
    int crc = crc16X25(frame)
    baos.write(crc & 0xFF); baos.write((crc >> 8) & 0xFF)
    baos.write(0x0D); baos.write(0x0A)
    baos.toByteArray()
}

def buildMpipResponse = { byte[] idBytes, int type ->
    int length = 42  // @@(2)+len(2)+id(20)+type(2)+pad4(4)+pad8(8)+crc(2)+\r\n(2) = 42
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    baos.write(0x40); baos.write(0x40)
    baos.write(length & 0xFF); baos.write((length >> 8) & 0xFF)
    baos.write(idBytes, 0, 20)
    baos.write((type >> 8) & 0xFF); baos.write(type & 0xFF)
    baos.write(new byte[4])  // 4 bytes zero
    baos.write(new byte[8])  // 8 bytes 0xFF
    byte[] frame = baos.toByteArray()
    // Replace the 8 zero bytes (positions 28-35) with 0xFF
    for (int i = 28; i < 36; i++) frame[i] = (byte) 0xFF
    int crc = crc16X25(frame)
    baos.reset()
    baos.write(frame)
    baos.write(crc & 0xFF); baos.write((crc >> 8) & 0xFF)
    baos.write(0x0D); baos.write(0x0A)
    baos.toByteArray()
}

// ── position reader ────────────────────────────────────────────────────────

def readPos = { BufReader buf, ctx ->
    int yy = buf.readUByte(); int mo = buf.readUByte(); int dd = buf.readUByte()
    int hh = buf.readUByte(); int mi = buf.readUByte(); int ss = buf.readUByte()
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(2000 + yy, mo - 1, dd, hh, mi, ss)
    cal.set(Calendar.MILLISECOND, 0)
    long latRaw = buf.readUInt()
    long lonRaw = buf.readUInt()
    int speedRaw = buf.readUShort()
    int courseRaw = buf.readUShort()
    int flags = buf.readUByte()
    double lat = latRaw / 3600000.0
    double lon = lonRaw / 3600000.0
    if ((flags & 0x01) == 0) lon = -lon
    if ((flags & 0x02) == 0) lat = -lat
    def pos = ctx.newPosition()
    pos.setTime(cal.getTime())
    pos.setValid((flags & 0x0C) > 0)
    pos.set(Position.KEY_SATELLITES, (flags >> 4) & 0x0F)
    pos.setLatitude(lat)
    pos.setLongitude(lon)
    pos.setSpeed(UnitsConverter.knotsFromCps(speedRaw))
    pos.setCourse(courseRaw / 10.0)
    pos
}

// ── stat block reader (34 bytes) ───────────────────────────────────────────

def readStat = { BufReader buf ->
    buf.skip(4); buf.skip(4)  // ACC ON time, UTC time
    long odo = buf.readUInt()
    long tripOdo = buf.readUInt()
    long fuel = buf.readUInt()
    buf.skip(2)  // current fuel
    long state = buf.readUInt()
    buf.skip(8)
    [odo: odo, tripOdo: tripOdo, fuel: fuel, state: state]
}

def applyStat = { Map stat, List positions ->
    positions.each { pos ->
        pos.set(Position.KEY_ODOMETER, stat.odo * 1000L)
        pos.set(Position.KEY_ODOMETER_TRIP, stat.tripOdo * 1000L)
        pos.set(Position.KEY_FUEL_CONSUMPTION, stat.fuel)
        pos.set(Position.KEY_STATUS, stat.state)
    }
}

// ── alarm decoder ──────────────────────────────────────────────────────────

def decodeAlarm = { pos, int event ->
    switch (event) {
        case 0x01: pos.addAlarm(Position.ALARM_OVERSPEED); break
        case 0x02: pos.addAlarm(Position.ALARM_LOW_POWER); break
        case 0x03: pos.addAlarm(Position.ALARM_TEMPERATURE); break
        case 0x04: pos.addAlarm(Position.ALARM_ACCELERATION); break
        case 0x05: pos.addAlarm(Position.ALARM_BRAKING); break
        case 0x06: pos.addAlarm(Position.ALARM_IDLE); break
        case 0x07: pos.addAlarm(Position.ALARM_TOW); break
        case 0x08: pos.addAlarm(Position.ALARM_HIGH_RPM); break
        case 0x09: pos.addAlarm(Position.ALARM_POWER_ON); break
        case 0x0B: pos.addAlarm(Position.ALARM_LANE_CHANGE); break
        case 0x0C: pos.addAlarm(Position.ALARM_CORNERING); break
        case 0x0D: pos.addAlarm(Position.ALARM_FATIGUE_DRIVING); break
        case 0x0E: pos.addAlarm(Position.ALARM_POWER_OFF); break
        case 0x11: pos.addAlarm(Position.ALARM_ACCIDENT); break
        case 0x12: pos.addAlarm(Position.ALARM_TAMPERING); break
        case 0x16: pos.set(Position.KEY_IGNITION, true); break
        case 0x17: pos.set(Position.KEY_IGNITION, false); break
        case 0x1C: pos.addAlarm(Position.ALARM_VIBRATION); break
    }
}

// ── OBD PID length map ─────────────────────────────────────────────────────

def PID_LENGTHS = [:]
[0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0b, 0x0d, 0x0e, 0x0f,
 0x11, 0x12, 0x13, 0x1c, 0x1d, 0x1e, 0x2c, 0x2d, 0x2e, 0x2f,
 0x30, 0x33, 0x43, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b,
 0x4c, 0x51, 0x52, 0x5a].each { PID_LENGTHS[it] = 1 }
[0x02, 0x03, 0x0a, 0x0c, 0x10, 0x14, 0x15, 0x16, 0x17, 0x18,
 0x19, 0x1a, 0x1b, 0x1f, 0x21, 0x22, 0x23, 0x31, 0x32, 0x3c,
 0x3d, 0x3e, 0x3f, 0x42, 0x44, 0x4d, 0x4e, 0x50, 0x53, 0x54,
 0x55, 0x56, 0x57, 0x58, 0x59, 0x9d].each { PID_LENGTHS[it] = 2 }
[0x00, 0x01, 0x20, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a,
 0x2b, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x40,
 0x41, 0x4f, 0x67, 0xa6].each { PID_LENGTHS[it] = 4 }

def decodeObd = { BufReader buf, pos, boolean groups ->
    int pidCount = buf.readUByte()
    int[] pids = new int[pidCount]
    for (int i = 0; i < pidCount; i++) pids[i] = buf.readUShort() & 0xFF
    if (groups) { buf.skip(1); buf.skip(1) }
    for (int i = 0; i < pidCount; i++) {
        int pid = pids[i]
        int len = PID_LENGTHS.getOrDefault(pid, 0)
        if (len == 0) break
        long val = (len == 1) ? buf.readUByte() : (len == 2) ? buf.readUShort() : buf.readUInt()
        ObdDecoder.decodeData(pos, pid, (int) val, false)
    }
}

// ── SC protocol handler ────────────────────────────────────────────────────

def decodeSc = { int type, BufReader buf, byte[] idBytes, int version, ctx ->
    // heartbeat
    if (type == 0x1003) {
        ctx.ack(buildFrame(idBytes, version, 0x9003, null))
        return null
    }

    // login response
    if (type == 0x1001) {
        ByteArrayOutputStream rc = new ByteArrayOutputStream()
        rc.write([0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00] as byte[])
        long ts = System.currentTimeMillis() / 1000
        rc.write((int)(ts & 0xFF)); rc.write((int)((ts >> 8) & 0xFF))
        rc.write((int)((ts >> 16) & 0xFF)); rc.write((int)((ts >> 24) & 0xFF))
        ctx.ack(buildFrame(idBytes, version, 0x9001, rc.toByteArray()))
    }

    // position types
    if (type == 0x1001 || type == 0x1002 || type == 0x4001 || type == 0xB001) {
        if (type == 0xB001) buf.skip(2)
        else buf.skip(1)  // historical flag
        Map stat = readStat(buf)
        int count = buf.readUByte()
        List positions = []
        for (int i = 0; i < count; i++) positions.add(readPos(buf, ctx))
        applyStat(stat, positions)
        if (positions.isEmpty()) return null
        return positions.size() == 1 ? positions[0] : positions
    }

    if (type == 0x4007) {
        buf.skip(1)
        long alarmIdx = buf.readUInt()
        ByteArrayOutputStream rc = new ByteArrayOutputStream()
        rc.write((int)(alarmIdx & 0xFF)); rc.write((int)((alarmIdx >> 8) & 0xFF))
        rc.write((int)((alarmIdx >> 16) & 0xFF)); rc.write((int)((alarmIdx >> 24) & 0xFF))
        ctx.ack(buildFrame(idBytes, version, 0xC007, rc.toByteArray()))
        Map stat = readStat(buf)
        int count = buf.readUByte()
        List positions = []
        for (int i = 0; i < count; i++) positions.add(readPos(buf, ctx))
        applyStat(stat, positions)
        if (buf.isReadable()) {
            int alarmCount = buf.readUByte()
            for (int a = 0; a < alarmCount; a++) {
                int flag = buf.readUByte()
                int event = buf.readUByte()
                if (flag > 0) positions.each { decodeAlarm(it, event) }
                buf.skip(4)  // description(2) + threshold(2)
            }
        }
        if (positions.isEmpty()) return null
        return positions.size() == 1 ? positions[0] : positions
    }

    if (type == 0x400E) {
        buf.skip(1)
        Map stat = readStat(buf)
        int count = buf.readUByte()
        List positions = []
        for (int i = 0; i < count; i++) {
            def pos = readPos(buf, ctx)
            pos.set(Position.PREFIX_ADC + 1, buf.readUShort())
            positions.add(pos)
        }
        applyStat(stat, positions)
        if (positions.isEmpty()) return null
        return positions.size() == 1 ? positions[0] : positions
    }

    if (type == 0x4002) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        applyStat(readStat(buf), [pos])
        buf.skip(2)  // sample rate
        decodeObd(buf, pos, true)
        return pos
    }

    if (type == 0x4003) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        applyStat(readStat(buf), [pos])
        buf.skip(2)  // sample rate
        int count = buf.readUByte()
        def readings = []
        for (int i = 0; i < count; i++) {
            readings.add([
                x: buf.readShort() * 0.015625,
                y: buf.readShort() * 0.015625,
                z: buf.readShort() * 0.015625
            ])
        }
        pos.set(Position.KEY_G_SENSOR, JsonOutput.toJson(readings))
        return pos
    }

    if (type == 0x4006 || type == 0x400B) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        applyStat(readStat(buf), [pos])
        buf.skip(1)  // flag
        int count = buf.readUByte()
        List<String> codes = []
        for (int i = 0; i < count; i++) {
            int code
            if (type == 0x400B) {
                code = buf.readUShort()
                buf.skip(1); buf.skip(1)  // attribute, occurrence
            } else {
                code = buf.readUShort()
            }
            String decoded = ObdDecoder.decodeCode(code)
            if (decoded) codes.add(decoded)
        }
        if (type == 0x400B && buf.isReadable()) buf.skip(2)  // MIL status
        pos.set(Position.KEY_DTCS, codes.join(" "))
        return pos
    }

    if (type == 0x4005) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        applyStat(readStat(buf), [pos])
        buf.skip(1)
        decodeObd(buf, pos, false)
        return pos
    }

    if (type == 0x4008) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        applyStat(readStat(buf), [pos])
        int lac = buf.readUShort()
        int cid = buf.readUShort()
        pos.setNetwork(new Network(CellTower.from(0, 0, lac, cid)))
        return pos
    }

    if (type == 0x4009) {
        buf.skip(4)
        def pos = readPos(buf, ctx)
        return pos
    }

    if (type == 0x5101) {
        def pos = readPos(buf, ctx)
        return pos
    }

    if (type == 0xA002) {
        def pos = ctx.newPosition()
        ctx.lastLocation(pos, null)
        buf.skip(2)  // index
        int respCount = buf.readUByte()
        int respIdx = buf.readUByte()
        int failCount = buf.readUByte()
        for (int i = 0; i < failCount; i++) buf.skip(2)
        int successCount = buf.readUByte()
        for (int i = 0; i < successCount; i++) {
            buf.skip(2)  // tag
            int rlen = buf.readUShort()
            String result = buf.readString(rlen)
            pos.set(Position.KEY_RESULT, result)
        }
        return pos
    }

    if (type == 0x401F) {
        buf.skip(1)  // historical flag
        buf.skip(4)  // index
        Map stat = readStat(buf)
        int count = buf.readUByte()
        List positions = []
        for (int i = 0; i < count; i++) positions.add(readPos(buf, ctx))
        applyStat(stat, positions)
        def pos = positions.isEmpty() ? ctx.newPosition() : positions[0]
        if (positions.isEmpty()) ctx.lastLocation(pos, null)
        while (buf.readableBytes() > 4) {
            int tag = buf.readUShort()
            int tagLen = buf.readUShort()
            switch (tag) {
                case 0x0002:
                    int pidCount = buf.readUByte()
                    for (int i = 0; i < pidCount; i++) {
                        int pidTag = buf.readUShort()
                        int pidLen = buf.readUShort()
                        pos.set("pid" + pidTag, buf.readHex(pidLen))
                    }
                    break
                case 0x0006:
                    buf.skip(1)
                    int fc = buf.readUByte()
                    for (int i = 1; i <= fc; i++) pos.set("fault$i", buf.readUShort())
                    break
                case 0x000B:
                    buf.skip(1)
                    int fc2 = buf.readUByte()
                    for (int i = 1; i <= fc2; i++) pos.set("fault$i", buf.readUInt())
                    buf.skip(2)
                    break
                case 0x0007:
                    buf.skip(4)
                    int ac = buf.readUByte()
                    for (int a = 0; a < ac; a++) {
                        int flag = buf.readUByte()
                        int event = buf.readUByte()
                        if (flag > 0) decodeAlarm(pos, event)
                        buf.skip(4)
                    }
                    break
                case 0x0010:
                    pos.set(Position.KEY_DEVICE_TEMP, buf.readShort() / 10.0)
                    break
                case 0x0011: case 0x0012: case 0x0013: case 0x0014:
                    pos.set(Position.PREFIX_TEMP + (tag - 0x0010), buf.readShort() / 10.0)
                    break
                case 0x0020:
                    pos.set(Position.KEY_POWER, buf.readUShort() / 100.0)
                    break
                case 0x0021:
                    pos.set(Position.KEY_BATTERY, buf.readUShort() / 100.0)
                    break
                default:
                    buf.skip(tagLen)
                    break
            }
        }
        if (positions.isEmpty()) return pos
        return positions.size() == 1 ? positions[0] : positions
    }

    null
}

// ── CC protocol handler ────────────────────────────────────────────────────

def decodeCc = { int type, BufReader buf, byte[] idBytes, int version, ctx ->
    if (type == 0x4206) {
        ctx.ack(buildFrame(idBytes, version, 0x8206, null))
        buf.skip(1)  // historical flag
        int count = buf.readUByte()
        List positions = []
        for (int i = 0; i < count; i++) {
            def pos = readPos(buf, ctx)
            pos.set(Position.KEY_STATUS, buf.readUInt())
            pos.set(Position.KEY_BATTERY, buf.readUByte())
            pos.set(Position.KEY_ODOMETER, buf.readUInt() * 1000L)
            buf.skip(3)  // geofencing id + flags + additional flags
            int lac = buf.readUShort()
            int cid = buf.readUShort()
            pos.setNetwork(new Network(CellTower.from(0, 0, lac, cid)))
            positions.add(pos)
        }
        if (positions.isEmpty()) return null
        return positions.size() == 1 ? positions[0] : positions
    }

    if (type == 0x4001) {
        ctx.ack(buildFrame(idBytes, version, 0x8001, null))
        def pos = readPos(buf, ctx)
        pos.set(Position.KEY_STATUS, buf.readUInt())
        pos.set(Position.KEY_BATTERY, buf.readUByte())
        pos.set(Position.KEY_ODOMETER, buf.readUInt() * 1000L)
        buf.skip(3)
        return pos
    }

    null
}

// ── MPIP protocol handler ──────────────────────────────────────────────────

def decodeMpip = { int type, BufReader buf, byte[] idBytes, ctx ->
    if (type == 0x4001) {
        ctx.ack(buildMpipResponse(idBytes, 0x4001))
        return readPos(buf, ctx)
    }

    if (type == 0x2001) {
        ctx.ack(buildMpipResponse(idBytes, 0x1001))
        buf.skip(4); buf.skip(4); buf.skip(1)
        return readPos(buf, ctx)
    }

    if (type == 0x4201 || type == 0x4202 || type == 0x4206) {
        return readPos(buf, ctx)
    }

    if (type == 0x4204) {
        List positions = []
        for (int i = 0; i < 8; i++) {
            positions.add(readPos(buf, ctx))
            buf.skip(31)
        }
        return positions
    }

    null
}

// ── protocol ───────────────────────────────────────────────────────────────

protocol("castel") {
    port 5086

    commands(
        Command.TYPE_ENGINE_STOP,
        Command.TYPE_ENGINE_RESUME
    )

    variant("main") {

        scriptedFrame { fb ->
            if (fb.readableBytes() < 4) return null
            int len = (fb.getUByte(2) & 0xFF) | ((fb.getUByte(3) & 0xFF) << 8)
            fb.readableBytes() >= len ? len : null
        }

        decode { msg, ctx ->
            def buf = msg as BufReader
            if (buf.readableBytes() < 4) return null

            int h1 = buf.readUByte()
            int h2 = buf.readUByte()
            boolean isMpip = (h1 == 0x24 && h2 == 0x24)

            buf.skip(2)  // length already used for framing

            int version = -1
            if (!isMpip) version = buf.readByte()

            byte[] idBytes = buf.readBytes(20)
            String id = new String(idBytes, StandardCharsets.US_ASCII).trim()
            ctx.session(id)

            int type = buf.readUShort()

            // payload excludes last 4 bytes (CRC2 + trailer2)
            int payloadLen = buf.readableBytes() - 4
            if (payloadLen < 0) return null
            def payload = buf.slice(payloadLen)

            if (isMpip) {
                return decodeMpip(type, payload, idBytes, ctx)
            } else if (version == 3 || version == 4) {
                return decodeSc(type, payload, idBytes, version, ctx)
            } else {
                return decodeCc(type, payload, idBytes, version, ctx)
            }
        }

        encode { cmd, ctx ->
            String uid = ctx.uniqueId()
            byte[] idBytes = (uid + "\0" * 20).substring(0, 20).getBytes(StandardCharsets.US_ASCII)
            byte version = 0x01
            switch (cmd.type) {
                case Command.TYPE_ENGINE_STOP:
                    return buildFrame(idBytes, version, MSG_CC_PETROL_CONTROL, [0x01] as byte[])
                case Command.TYPE_ENGINE_RESUME:
                    return buildFrame(idBytes, version, MSG_CC_PETROL_CONTROL, [0x00] as byte[])
                default:
                    return null
            }
        }
    }
}
