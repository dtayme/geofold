// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Xexun2 binary GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/xexun2/ (Java reference)
 *
 * Binary TCP protocol on port 5233.
 * Frame: escape-delimited, start + end FLAG = 0xfa 0xaf.
 *   Escape sequences inside the frame:
 *     0xfb 0xbf 0x01 → 0xfa 0xaf
 *     0xfb 0xbf 0x02 → 0xfb 0xbf
 *
 * After unescaping, frame layout:
 *   FLAG(2) | type(2 BE) | index(2 BE) | IMEI(8 bytes, 15 BCD digits + pad nibble) |
 *   payloadSize(2 BE, lower 10 bits used) | checksum(2 BE, ~byteSum & 0xffff) |
 *   payload(payloadSize bytes) | FLAG(2)
 *
 * Message types:
 *   0x07 MSG_COMMAND — command acknowledgement from device (no response sent)
 *   0x14 MSG_POSITION — batch position report (server responds with ACK)
 *
 * Batch position payload:
 *   count(1) | len[0..count-1](2 each) | record[0..count-1]
 *   Record: index(1) | time(4 BE uint32 epoch s) | rssi(1) |
 *           battery(2 BE, bit15=charge, bits0-14=level%) | mask(1) |
 *           [mask bit 0: alarm(4 BE)] [mask bit 1: position block] |
 *           [mask bit 3: skip 4 fingerprint] [mask bit 4: skip 38 version/IMSI/ICCID] |
 *           [mask bit 5: skip 12 device params]
 *
 * Commands (server → device): MSG_COMMAND frame with ASCII payload.
 * Supported: TYPE_CUSTOM, TYPE_POSITION_PERIODIC, TYPE_POWER_OFF, TYPE_REBOOT_DEVICE
 */

import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

def MSG_COMMAND  = 0x07
def MSG_POSITION = 0x14

def decodeAlarm = { long value ->
    if (BitUtil.check(value, 0))  return ALARM_SOS
    if (BitUtil.check(value, 1))  return ALARM_REMOVING
    if (BitUtil.check(value, 15)) return ALARM_FALL_DOWN
    return null
}

// Converts ddmm.mmmm float value to decimal degrees.
def convertCoordinate = { double value ->
    double degrees = (int) (value / 100)
    double minutes = value - degrees * 100
    return degrees + minutes / 60
}

// Applies Xexun2 escape encoding to inner (between FLAG delimiters) bytes.
def escapeInner = { byte[] input ->
    def out = new ByteArrayOutputStream()
    int i = 0
    while (i < input.length) {
        int b = input[i] & 0xff
        if (b == 0xfa && i + 1 < input.length && (input[i + 1] & 0xff) == 0xaf) {
            out.write(0xfb); out.write(0xbf); out.write(0x01)
            i += 2
        } else if (b == 0xfb && i + 1 < input.length && (input[i + 1] & 0xff) == 0xbf) {
            out.write(0xfb); out.write(0xbf); out.write(0x02)
            i += 2
        } else {
            out.write(b)
            i++
        }
    }
    return out.toByteArray()
}

// Wraps escaped inner bytes with start and end FLAG to form a wire frame.
def buildWireFrame = { byte[] inner ->
    byte[] escaped = escapeInner(inner)
    def out = new ByteArrayOutputStream()
    out.write(0xfa); out.write(0xaf)
    out.write(escaped)
    out.write(0xfa); out.write(0xaf)
    return out.toByteArray()
}

protocol("xexun2") {

    port 5233

    variant("main") {

        frame (0xfa as byte) { fb ->
            if (fb.readableBytes() < 5) return null
            // Find the trailing 0xfa 0xaf — guaranteed unique due to escape encoding.
            int scanFrom = 2
            int endOffset = -1
            while (true) {
                int faIdx = fb.indexOf(0xfa, scanFrom)
                if (faIdx < 0 || faIdx + 1 >= fb.readableBytes()) return null
                if (fb.getUByte(faIdx + 1) == 0xaf) {
                    endOffset = faIdx + 2
                    break
                }
                scanFrom = faIdx + 1
            }
            // Unescape the raw frame bytes.
            byte[] raw = fb.bytes(0, endOffset)
            def out = new ByteArrayOutputStream()
            int i = 0
            while (i < raw.length) {
                int b = raw[i] & 0xff
                if (b == 0xfb && i + 2 < raw.length && (raw[i + 1] & 0xff) == 0xbf) {
                    int ext = raw[i + 2] & 0xff
                    i += 3
                    if (ext == 0x01) {
                        out.write(0xfa); out.write(0xaf)
                    } else if (ext == 0x02) {
                        out.write(0xfb); out.write(0xbf)
                    }
                } else {
                    out.write(b)
                    i++
                }
            }
            return frameResult(endOffset, out.toByteArray())
        }

        commands TYPE_CUSTOM, TYPE_POSITION_PERIODIC, TYPE_POWER_OFF, TYPE_REBOOT_DEVICE

        encode { cmd, ctx ->
            String imei = ctx.deviceId()

            def buildCommand = { String content ->
                byte[] payload = content.getBytes(StandardCharsets.US_ASCII)
                int cksum = Checksum.ip(ByteBuffer.wrap(payload))
                byte[] inner = bytes {
                    writeShort 0x0007      // MSG_COMMAND
                    writeShort 1           // index
                    writeHex(imei + '0')   // IMEI: 15 BCD digits + padding nibble = 8 bytes
                    writeShort payload.length
                    writeShort cksum
                    writeBytes payload
                }
                return buildWireFrame(inner)
            }

            switch (cmd.type) {
                case TYPE_CUSTOM:
                    return buildCommand(ctx.data())
                case TYPE_POSITION_PERIODIC:
                    int freq = cmd.getInteger('frequency')
                    return buildCommand("tracking_send=${freq},${freq}")
                case TYPE_POWER_OFF:
                    return buildCommand('of=1')
                case TYPE_REBOOT_DEVICE:
                    return buildCommand('reset')
                default:
                    return null
            }
        }

        decode { msg, ctx ->
            msg.skip(2)  // start FLAG
            int type     = msg.readUShort()
            int index    = msg.readUShort()
            String imeiHex16 = msg.readHex(8)         // 16 lowercase hex chars (15-digit IMEI + pad)
            String imei  = imeiHex16.substring(0, 15)

            def session = ctx.session(imei)
            if (!session) return null

            int payloadSize = msg.readUShort() & 0x03ff
            int checksum    = msg.readUShort()

            // Verify IP-style byte-sum checksum (~byteSum & 0xffff).
            byte[] payloadCheck = msg.getBytes(0, payloadSize)
            if (checksum != Checksum.ip(ByteBuffer.wrap(payloadCheck))) return null

            // Acknowledge position messages.
            if (type != MSG_COMMAND) {
                byte[] inner = bytes {
                    writeShort type
                    writeShort index
                    writeHex imeiHex16
                    writeShort 1       // attributes: 1 byte payload
                    writeShort 0xfffe  // checksum placeholder
                    writeByte  1       // response byte
                }
                ctx.ack(buildWireFrame(inner))
            }

            if (type != MSG_POSITION) return null

            def payload = msg.slice(payloadSize)
            int count = payload.readUByte()
            int[] lengths = new int[count]
            for (int k = 0; k < count; k++) {
                lengths[k] = payload.readUShort()
            }

            for (int k = 0; k < count; k++) {
                def rec = payload.slice(lengths[k])

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                pos.set(Position.KEY_INDEX, rec.readUByte())
                pos.deviceTime = new Date(rec.readUInt() * 1000L)
                pos.set(Position.KEY_RSSI, rec.readUByte())

                int battery = rec.readUShort()
                pos.set(Position.KEY_CHARGE,        checkBit(battery, 15))
                pos.set(Position.KEY_BATTERY_LEVEL, BitUtil.to(battery, 15))

                int mask = rec.readUByte()

                if (checkBit(mask, 0)) {
                    String alarm = decodeAlarm(rec.readUInt())
                    if (alarm) pos.set(Position.KEY_ALARM, alarm)
                }

                if (checkBit(mask, 1)) {
                    int posMask = rec.readUByte()
                    def network = new Network()

                    if (checkBit(posMask, 0)) {
                        pos.valid    = true
                        pos.fixTime  = pos.deviceTime
                        pos.set(Position.KEY_SATELLITES, rec.readUByte())
                        pos.longitude = convertCoordinate(rec.readFloat())
                        pos.latitude  = convertCoordinate(rec.readFloat())
                    }

                    if (checkBit(posMask, 1)) {
                        int wifiCount = rec.readUByte()
                        wifiCount.times {
                            String mac = rec.readHex(6).replaceAll('(..)', '$1:')
                            network.addWifiAccessPoint(
                                    WifiAccessPoint.from(mac.substring(0, mac.length() - 1), rec.readByte()))
                        }
                    }

                    if (checkBit(posMask, 2)) {
                        int cellCount = rec.readUByte()
                        cellCount.times {
                            network.addCellTower(CellTower.from(
                                    rec.readUShort(), rec.readUShort(),
                                    rec.readInt(), rec.readUInt(), rec.readByte()))
                        }
                    }

                    if (checkBit(posMask, 3)) {
                        rec.skip(12 * rec.readUByte())  // ToF data
                    }

                    if (checkBit(posMask, 5)) {
                        pos.speed  = UnitsConverter.knotsFromKph(rec.readUShort() / 10.0)
                        pos.course = rec.readUShort() / 10.0
                    }

                    if (checkBit(posMask, 6)) {
                        pos.valid    = true
                        pos.fixTime  = pos.deviceTime
                        pos.set(Position.KEY_SATELLITES, rec.readUByte())
                        pos.longitude = convertCoordinate(rec.readDouble())
                        pos.latitude  = convertCoordinate(rec.readDouble())
                    }

                    if (checkBit(posMask, 7)) {
                        int dataLength = rec.readUShort()
                        if (dataLength > 0) {
                            int dataType = rec.readUByte()
                            def dataRec  = rec.slice(rec.readUShort())
                            if (dataType == 0x47) {  // 'G' — full GPS block
                                pos.fixTime   = pos.deviceTime
                                pos.longitude = convertCoordinate(dataRec.readDouble())
                                pos.latitude  = convertCoordinate(dataRec.readDouble())
                                pos.valid     = dataRec.readUByte() > 0
                                pos.set(Position.KEY_SATELLITES, dataRec.readUByte())
                                dataRec.skip(1)  // satellite SNR
                                pos.speed    = UnitsConverter.knotsFromKph(dataRec.readUShort() / 10.0)
                                pos.course   = dataRec.readUShort() / 10.0
                                pos.altitude = dataRec.readFloat()
                            }
                        }
                    }

                    if (network.wifiAccessPoints != null || network.cellTowers != null) {
                        pos.network = network
                    }
                }

                if (checkBit(mask, 3)) rec.skip(4)   // fingerprint
                if (checkBit(mask, 4)) rec.skip(38)  // version (20) + IMSI (8) + ICCID (10)
                if (checkBit(mask, 5)) rec.skip(12)  // device parameters

                if (!pos.valid) ctx.lastLocation(pos, pos.deviceTime)
                ctx.emit(pos)
            }

            return null
        }
    }
}
