// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Minifinder2 binary tracker driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/minifinder2/
 *
 * Frame: LengthFieldBasedFrameDecoder(LE, MAX_FRAME_LENGTH_LARGE, 2, 2, 4, 0, true)
 *   → 0xAB header(1) + flags(1) + lengthLE(2) + checksumLE(2) + indexLE(2) + type(1) + content
 *   → total frame = 8 + length_value bytes
 *
 * ACK when flags bit 4 set: 0xAB 0x00 + contentLen(LE) + CRC16-XMODEM(LE) + index(LE) + content
 * Default ACK content: MSG_RESPONSE + 0x01 (key length) + 0x00 (success)
 *
 * TLV format (MSG_DATA / MSG_SERVICES): length_byte(1) + key(1) + data(length-1 bytes)
 * Duplicate non-beacon keys trigger a new position; all positions emitted via ctx.emit().
 *
 * Key 0x01: IMEI (15 ASCII chars)
 * Key 0x02: alarms 4-byte LE bitmask (bits: 0=low-battery, 1=overspeed, 2=fall, 4-7=geofence,
 *           8=power-off, 9=power-on, 10=movement, 12=SOS, 13=btn1, 14=btn2, 31=bark) +
 *           optional 4-byte LE device-time
 * Key 0x14: battery-level(1) + battery-voltage(2LE)
 * Key 0x20: GPS — lat(4LE)/lon(4LE)/speed(2LE kph)/course(2LE)/alt(2LE)/hdop(2LE)/odometer(4LE)/sats(1)
 * Key 0x21/0x29: cell towers — mcc(2LE)/mnc(1), loop rssi(1)/lac(2LE)/cid(2LE or 4LE)
 * Key 0x19/0x22: WiFi APs — loop rssi(1)/mac(6), key 0x19 also reads 1-byte name-length + name
 * Key 0x24: timestamp(4LE) + status(4LE): bits 4=charge, 7=archive, 9=motion, 19-23=RSSI, 24-31=batt
 * Key 0x25: call event — timestamp(4LE)/status(1)/duration(2LE)/result(1)/phone(string)
 * Key 0x27: BLE location — lat(4LE)/lon(4LE)/hdop(2LE)/alt(2LE)
 * Key 0x28/0x2c: beacon tags (repeatable, not counted as duplicates)
 * Key 0x2A: BLE anchor — skip flags+mac+rssi(8), lat(4LE)/lon(4LE)/description(rest)
 * Key 0x30: step counter — skip timestamp(4), steps(4LE)
 * Key 0x31: activity pairs — loop activityTime(4LE)/activityValue(4LE)
 * Key 0x37: bark — skip timestamp(4), barkFlags(4LE): bit31=stop, bits0-30=count
 * Key 0x40/0x41: heart-rate / SpO2 — skip timestamp(4), value(1)
 *
 * MSG_CONFIGURATION TLVs: same length-byte format, 35+ config key attributes
 * MSG_RESPONSE: last location + KEY_RESULT from 1-byte success code
 *
 * Commands: TYPE_CONFIGURATION (optional custom hex data or default 0xF0 query),
 *           TYPE_FIRMWARE_UPDATE (Nano model only, MSG_SYSTEM_CONTROL with OTA URL)
 */

import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.nio.ByteBuffer

def MSG_DATA           = 0x01
def MSG_CONFIGURATION  = 0x02
def MSG_SERVICES       = 0x03
def MSG_SYSTEM_CONTROL = 0x04
def MSG_RESPONSE       = 0x7F

// Build a complete response frame: 0xAB 0x00 + contentLen(LE) + CRC16-XMODEM(LE) + index(LE) + content
def buildAck = { int ackIndex, byte[] contentBytes ->
    int crc = Checksum.crc16(Checksum.CRC16_XMODEM, ByteBuffer.wrap(contentBytes))
    return bytes {
        writeByte 0xAB
        writeByte 0x00
        writeShortLE contentBytes.length
        writeShortLE crc
        writeShortLE ackIndex
        writeBytes contentBytes
    }
}

// Build an encoder command frame (index always 1)
def buildPacket = { byte[] content ->
    int crc = Checksum.crc16(Checksum.CRC16_XMODEM, ByteBuffer.wrap(content))
    return bytes {
        writeByte 0xAB
        writeByte 0x00
        writeShortLE content.length
        writeShortLE crc
        writeShortLE 1
        writeBytes content
    }
}

// Read 6 bytes in reverse order as hex (tag/beacon ID)
def readTagId = { tlv ->
    def sb = new StringBuilder()
    for (int i = 0; i < 6; i++) {
        sb.insert(0, String.format('%02x', tlv.readUByte()))
    }
    return sb.toString()
}

// Read ASCII string up to len bytes, stripping null terminator if present
def readNullTermStr = { tlv, int len ->
    if (len <= 0) return ''
    String s = tlv.readString(len)
    int n = s.indexOf('\0')
    return n >= 0 ? s.substring(0, n) : s
}

protocol("minifinder2") {

    port 5187
    commands TYPE_CONFIGURATION, TYPE_FIRMWARE_UPDATE

    variant("main") {

        // total frame = 8 (fixed header) + LE length field value; adjustment = 4 (checksum + index)
        frame readLengthFieldLE(2, 2, 4)

        decode { buf, ctx ->

            buf.skip(1)           // 0xAB header
            int flags  = buf.readUByte()
            buf.skip(2)           // length (already consumed by frame decoder)
            buf.skip(2)           // checksum
            int index  = buf.readUShortLE()
            int type   = buf.readUByte()

            // Device requests ACK when flags bit 4 is set
            if (checkBit(flags, 4)) {
                byte[] ackContent = bytes {
                    writeByte MSG_RESPONSE
                    writeByte 1   // key length
                    writeByte 0   // success
                }
                ctx.ack(buildAck(index, ackContent))
            }

            // ─── MSG_DATA / MSG_SERVICES ──────────────────────────────────────
            if (type == MSG_DATA || type == MSG_SERVICES) {

                def positions    = []
                def keys         = new HashSet()
                def pos          = ctx.newPosition()
                def deviceSession = null

                while (buf.readableBytes() > 0) {
                    int length = buf.readUByte()
                    def tlv    = buf.slice(length)   // exactly `length` bytes; auto-advances buf
                    int key    = tlv.readUByte()

                    // Duplicate non-beacon key → flush current position, start new one
                    if (key != 0x28 && key != 0x2c && keys.contains((Integer) key)) {
                        positions << pos
                        keys.clear()
                        pos = ctx.newPosition()
                    }
                    keys.add((Integer) key)

                    switch (key) {

                        case 0x01:
                            deviceSession = ctx.session(tlv.readString(15))
                            if (!deviceSession) return null
                            break

                        case 0x02:
                            long alarm = tlv.readUIntLE()
                            if (checkBit((int) alarm, 0))  pos.addAlarm(ALARM_LOW_BATTERY)
                            if (checkBit((int) alarm, 1))  pos.addAlarm(ALARM_OVERSPEED)
                            if (checkBit((int) alarm, 2))  pos.addAlarm(ALARM_FALL_DOWN)
                            for (int gi = 0; gi < 4; gi++) {
                                if (checkBit((int) alarm, gi + 4)) {
                                    pos.addAlarm(checkBit((int) alarm, gi + 26)
                                            ? ALARM_GEOFENCE_ENTER : ALARM_GEOFENCE_EXIT)
                                    pos.set(Position.KEY_GEOFENCE, gi + 1)
                                }
                            }
                            if (checkBit((int) alarm, 8))  pos.addAlarm(ALARM_POWER_OFF)
                            if (checkBit((int) alarm, 9))  pos.addAlarm(ALARM_POWER_ON)
                            if (checkBit((int) alarm, 10)) pos.addAlarm(ALARM_MOVEMENT)
                            if (checkBit((int) alarm, 12)) pos.addAlarm(ALARM_SOS)
                            if (checkBit((int) alarm, 13)) pos.set('button1', true)
                            if (checkBit((int) alarm, 14)) pos.set('button2', true)
                            if (checkBit((int) alarm, 31)) pos.set('bark', true)
                            if (tlv.readableBytes() >= 4) {
                                pos.deviceTime = new Date(tlv.readUIntLE() * 1000L)
                            }
                            pos.set(Position.KEY_EVENT, alarm)
                            break

                        case 0x14:
                            pos.set(Position.KEY_BATTERY_LEVEL, tlv.readUByte())
                            pos.set(Position.KEY_BATTERY, tlv.readUShortLE() / 1000.0)
                            break

                        case 0x20:
                            pos.latitude  = tlv.readIntLE() / 10000000.0
                            pos.longitude = tlv.readIntLE() / 10000000.0
                            pos.speed     = UnitsConverter.knotsFromKph(tlv.readUShortLE())
                            pos.course    = tlv.readUShortLE()
                            pos.altitude  = tlv.readShortLE()
                            int hdop = tlv.readUShortLE()
                            pos.valid     = hdop > 0
                            pos.set(Position.KEY_HDOP, hdop / 10.0)
                            pos.set(Position.KEY_ODOMETER, tlv.readUIntLE())
                            pos.set(Position.KEY_SATELLITES, tlv.readUByte())
                            break

                        case 0x21:
                        case 0x29:
                            int mcc = tlv.readUShortLE()
                            int mnc = tlv.readUByte()
                            if (!pos.network) pos.network = new Network()
                            while (tlv.readableBytes() > 0) {
                                int rssi = tlv.readByte()
                                int lac  = tlv.readUShortLE()
                                long cid = (key == 0x29) ? tlv.readUIntLE() : tlv.readUShortLE()
                                pos.network.addCellTower(CellTower.from(mcc, mnc, lac, cid, rssi))
                            }
                            break

                        case 0x19:
                        case 0x22:
                            if (!pos.network) pos.network = new Network()
                            while (tlv.readableBytes() > 0) {
                                int rssi = tlv.readByte()
                                String mac = tlv.readHex(6).replaceAll('(..)', '$1:')[0..-2]
                                pos.network.addWifiAccessPoint(WifiAccessPoint.from(mac, rssi))
                                if (key == 0x19) tlv.skip(tlv.readUByte())  // skip name
                            }
                            break

                        case 0x23:
                        case 0x26:
                            if (length >= 7) {
                                pos.set('tagId', readTagId(tlv))
                            }
                            if (length >= 15) {
                                pos.latitude  = tlv.readIntLE() / 10000000.0
                                pos.longitude = tlv.readIntLE() / 10000000.0
                                pos.valid     = true
                            }
                            if (key == 0x26) {
                                pos.set(Position.KEY_HDOP, tlv.readUShortLE() / 10.0)
                                pos.altitude = tlv.readShortLE()
                            } else if (tlv.readableBytes() > 0) {
                                pos.set('description', tlv.readString(tlv.readableBytes()))
                            }
                            break

                        case 0x24:
                            pos.time = new Date(tlv.readUIntLE() * 1000L)
                            long status = tlv.readUIntLE()
                            if (checkBit((int) status, 4)) pos.set(Position.KEY_CHARGE, true)
                            if (checkBit((int) status, 7)) pos.set(Position.KEY_ARCHIVE, true)
                            pos.set(Position.KEY_MOTION, checkBit((int) status, 9))
                            pos.set(Position.KEY_RSSI, (int)((status >> 19) & 0x1F))
                            pos.set(Position.KEY_BATTERY_LEVEL, (int)(status >> 24) & 0xFF)
                            pos.set(Position.KEY_STATUS, status)
                            break

                        case 0x25:
                            pos.time = new Date(tlv.readUIntLE() * 1000L)
                            pos.set('callStatus', tlv.readUByte())
                            pos.set('callDuration', tlv.readUShortLE())
                            pos.set('callResult', tlv.readUByte())
                            if (tlv.readableBytes() > 0) {
                                pos.set(Position.KEY_PHONE, tlv.readString(tlv.readableBytes()))
                            }
                            break

                        case 0x27:
                            pos.latitude  = tlv.readIntLE() / 10000000.0
                            pos.longitude = tlv.readIntLE() / 10000000.0
                            pos.valid     = true
                            pos.set(Position.KEY_HDOP, tlv.readUShortLE() / 10.0)
                            pos.altitude  = tlv.readShortLE()
                            break

                        case 0x28:
                        case 0x2c:
                            int beaconFlags = tlv.readUByte()
                            int beaconIndex = (beaconFlags & 0x0F) + 1
                            pos.set("tagId${beaconIndex}", readTagId(tlv))
                            pos.set("tagRssi${beaconIndex}", (int) tlv.readByte())
                            pos.set("tag1mRssi${beaconIndex}", (int) tlv.readByte())
                            if (key == 0x2c) pos.set("tagBattery${beaconIndex}", tlv.readUByte())
                            if (checkBit(beaconFlags, 7)) {
                                pos.latitude  = tlv.readIntLE() / 10000000.0
                                pos.longitude = tlv.readIntLE() / 10000000.0
                                pos.valid     = true
                            }
                            if (checkBit(beaconFlags, 6)) {
                                int descLen = (key == 0x2c) ? tlv.readUByte() : tlv.readableBytes()
                                pos.set("description${beaconIndex}", tlv.readString(descLen))
                            }
                            if (key == 0x2c && tlv.readableBytes() >= 2) {
                                pos.set("tagTemp${beaconIndex}", tlv.readShortLE() / 10.0)
                            }
                            break

                        case 0x2A:
                            tlv.skip(8)  // flags(1) + mac(6) + rssi(1)
                            pos.latitude  = tlv.readIntLE() / 10000000.0
                            pos.longitude = tlv.readIntLE() / 10000000.0
                            pos.valid     = true
                            if (tlv.readableBytes() > 0) {
                                pos.set('description', tlv.readString(tlv.readableBytes()))
                            }
                            break

                        case 0x30:
                            tlv.skip(4)  // timestamp
                            pos.set(Position.KEY_STEPS, tlv.readUIntLE())
                            break

                        case 0x31:
                            int actIdx = 1
                            while (tlv.readableBytes() >= 8) {
                                pos.set("activity${actIdx}Time", tlv.readUIntLE())
                                pos.set("activity${actIdx}", tlv.readUIntLE())
                                actIdx++
                            }
                            break

                        case 0x37:
                            tlv.skip(4)  // timestamp
                            long barking = tlv.readUIntLE()
                            if (checkBit((int) barking, 31)) pos.set('barkStop', true)
                            pos.set('barkCount', barking & 0x7FFFFFFFL)
                            break

                        case 0x40:
                            tlv.skip(4)
                            int heartRate = tlv.readUByte()
                            if (heartRate > 1) pos.set(Position.KEY_HEART_RATE, heartRate)
                            break

                        case 0x41:
                            tlv.skip(4)
                            int spO2 = tlv.readUByte()
                            if (spO2 > 1) pos.set('spO2', spO2)
                            break
                    }
                }

                positions << pos

                if (!deviceSession) return null

                for (Position p : positions) {
                    p.deviceId = deviceSession.deviceId
                    if (!p.valid && !p.hasAttribute(Position.KEY_HDOP)) {
                        ctx.lastLocation(p, null)
                    }
                    ctx.emit(p)
                }
                return null

            // ─── MSG_CONFIGURATION ────────────────────────────────────────────
            } else if (type == MSG_CONFIGURATION) {

                def session = ctx.session()
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos, null)

                while (buf.readableBytes() > 0) {
                    int rawLen = buf.readUByte()
                    if (rawLen == 0) break
                    def tlv    = buf.slice(rawLen)
                    int key    = tlv.readUByte()
                    int dataLen = rawLen - 1

                    switch (key) {
                        case 0x01: pos.set('moduleNumber', tlv.readUInt()); break
                        case 0x02: pos.set(Position.KEY_VERSION_FW, String.valueOf(tlv.readUInt())); break
                        case 0x03: pos.set('imei', readNullTermStr(tlv, dataLen)); break
                        case 0x04: pos.set(Position.KEY_ICCID, readNullTermStr(tlv, dataLen)); break
                        case 0x05: pos.set('bleMac', tlv.readHex(dataLen)); break
                        case 0x06: pos.set('settingTime', tlv.readUInt()); break
                        case 0x07: pos.set('runTimes', tlv.readUInt()); break
                        case 0x0A:
                            // 3-byte big-endian (readUnsignedMedium)
                            int med = (tlv.readUByte() << 16) | (tlv.readUByte() << 8) | tlv.readUByte()
                            pos.set('interval', med)
                            pos.set('petMode', tlv.readUByte())
                            break
                        case 0x0D: pos.set('passwordProtect', tlv.readUInt()); break
                        case 0x0E: pos.set('timeZone', tlv.readByte()); break
                        case 0x0F: pos.set('enableControl', tlv.readUInt()); break
                        case 0x13: pos.set('deviceName', readNullTermStr(tlv, dataLen)); break
                        case 0x14:
                            pos.set(Position.KEY_BATTERY_LEVEL, tlv.readUByte())
                            pos.set(Position.KEY_BATTERY, tlv.readUShort() / 1000.0)
                            break
                        case 0x15:
                            pos.set('bleLatitude',  tlv.readIntLE() / 10000000.0)
                            pos.set('bleLongitude', tlv.readIntLE() / 10000000.0)
                            pos.set('bleLocation', readNullTermStr(tlv, dataLen - 8))
                            break
                        case 0x17: pos.set('gpsUrl', readNullTermStr(tlv, dataLen)); break
                        case 0x18: pos.set('lbsUrl', readNullTermStr(tlv, dataLen)); break
                        case 0x1A: pos.set('firmware', readNullTermStr(tlv, dataLen)); break
                        case 0x1B: pos.set('gsmModule', readNullTermStr(tlv, dataLen)); break
                        case 0x1D:
                            pos.set('agpsUpdate', tlv.readUByte())
                            pos.set('agpsLatitude',  tlv.readIntLE() / 10000000.0)
                            pos.set('agpsLongitude', tlv.readIntLE() / 10000000.0)
                            break
                        case 0x30:
                            pos.set('numberFlag', tlv.readUByte())
                            pos.set('number', readNullTermStr(tlv, dataLen - 1))
                            break
                        case 0x31:
                            pos.set('prefixFlag', tlv.readUByte())
                            pos.set('prefix', readNullTermStr(tlv, dataLen - 1))
                            break
                        case 0x33: pos.set('phoneSwitches', tlv.readUByte()); break
                        case 0x40: pos.set('apn', readNullTermStr(tlv, dataLen)); break
                        case 0x41: pos.set('apnUser', readNullTermStr(tlv, dataLen)); break
                        case 0x42: pos.set('apnPassword', readNullTermStr(tlv, dataLen)); break
                        case 0x43:
                            tlv.skip(1)  // flag
                            pos.set('port', tlv.readUShort())
                            pos.set('server', readNullTermStr(tlv, dataLen - 3))
                            break
                        case 0x44:
                            pos.set('heartbeatInterval', tlv.readUInt())
                            pos.set('uploadInterval', tlv.readUInt())
                            pos.set('uploadLazyInterval', tlv.readUInt())
                            break
                        case 0x47: pos.set('deviceId', readNullTermStr(tlv, dataLen)); break
                        case 0x4E: pos.set('gsmBand', tlv.readUByte()); break
                        case 0x50: pos.set('powerAlert', tlv.readUInt()); break
                        case 0x51: pos.set('geoAlert', tlv.readUInt()); break
                        case 0x53: pos.set('motionAlert', tlv.readUInt()); break
                        case 0x5C:
                            pos.set('barkLevel', tlv.readUByte())
                            if (tlv.readableBytes() >= 4) pos.set('barkInterval', tlv.readUInt())
                            break
                        case 0x61: pos.set('msisdn', readNullTermStr(tlv, dataLen)); break
                        case 0x62:
                            pos.set('wifiWhitelist', tlv.readUByte())
                            pos.set('wifiWhitelistMac', tlv.readHex(6))
                            break
                        case 0x64:
                            pos.set(Position.KEY_RSSI, tlv.readUByte())
                            pos.set('networkBand', tlv.readUInt())
                            pos.set(Position.KEY_OPERATOR, readNullTermStr(tlv, dataLen - 5))
                            break
                        case 0x65:
                            pos.set(Position.KEY_RSSI, tlv.readUByte())
                            pos.set('networkStatus', tlv.readUByte())
                            pos.set('serverStatus', tlv.readUByte())
                            if (tlv.readableBytes() >= 6) pos.set('networkPlmn', tlv.readHex(6))
                            if (tlv.readableBytes() >= 6) pos.set('homePlmn', tlv.readHex(6))
                            break
                        case 0x66: pos.set('imsi', readNullTermStr(tlv, dataLen)); break
                        case 0x75: pos.set('extraEnableControl', tlv.readUInt()); break
                    }
                }

                return pos

            // ─── MSG_RESPONSE ─────────────────────────────────────────────────
            } else if (type == MSG_RESPONSE) {

                def session = ctx.session()
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                ctx.lastLocation(pos, null)

                buf.skip(1)  // key length byte
                pos.set(Position.KEY_RESULT, String.valueOf(buf.readUByte()))
                return pos
            }

            return null
        }

        encode { cmd, ctx ->
            if (cmd.type == TYPE_CONFIGURATION) {
                String cmdData = ctx.data()
                byte[] content
                if (cmdData) {
                    int hexLen = cmdData.length() / 2
                    byte[] rawHex = new byte[hexLen]
                    for (int i = 0; i < hexLen; i++) {
                        rawHex[i] = (byte) Integer.parseInt(cmdData.substring(i * 2, i * 2 + 2), 16)
                    }
                    content = bytes {
                        writeByte MSG_CONFIGURATION
                        writeBytes rawHex
                    }
                } else {
                    content = bytes {
                        writeByte MSG_CONFIGURATION
                        writeByte 1      // length
                        writeByte 0xF0   // query-all type
                    }
                }
                return buildPacket(content)
            }
            if (cmd.type == TYPE_FIRMWARE_UPDATE && 'Nano'.equalsIgnoreCase(ctx.deviceModel())) {
                String url = ctx.data()
                byte[] content = bytes {
                    writeByte MSG_SYSTEM_CONTROL
                    writeByte 1 + url.length()
                    writeByte 0x30
                    writeString url
                }
                return buildPacket(content)
            }
            return null
        }
    }
}
