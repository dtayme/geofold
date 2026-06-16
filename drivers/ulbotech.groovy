// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Ulbotech GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/ulbotech/ (Java reference)
 *
 * Dual binary/text protocol on port 5072.
 *
 * Binary frames: 0xF8-delimited with escape sequences:
 *   0xF7 0x00 → 0xF7 (literal escape byte)
 *   0xF7 0x0F → 0xF8 (literal delimiter byte)
 * Frame layout: F8 version(1) type(1) IMEI(8) timestamp(4) [TLV…] CRC(2) F8
 * Timestamp is seconds from 2000-01-01 UTC, MSB masked off.
 *
 * Text frames: ASCII *TSdd,IMEI,HHMMSS,DDMMYY,COMMAND# terminated by '#'.
 *
 * ACK for binary: F8 01 FE [byte0 byte1 of incoming] [CRC16-XMODEM of 01 FE byte0 byte1] F8
 * ACK for text: *TS01,ACK:XXXX# where XXXX is CRC16-XMODEM of bytes 1..end-2 of incoming
 *
 * TLV types (binary):
 *   01 GPS  02 LBS  03 STATUS  04 ODOMETER  05 ADC  06 GEOFENCE
 *   07 OBD2  08 FUEL  09 OBD2_ALARM  0A HARSH_DRIVER  0B CANBUS (2-byte length)
 *   0C J1708  0D VIN  0E RFID  10 EVENT
 */

import org.traccar.helper.Checksum
import org.traccar.helper.ObdDecoder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

def DATA_GPS          = 0x01
def DATA_LBS          = 0x02
def DATA_STATUS       = 0x03
def DATA_ODOMETER     = 0x04
def DATA_ADC          = 0x05
def DATA_GEOFENCE     = 0x06
def DATA_OBD2         = 0x07
def DATA_FUEL         = 0x08
def DATA_OBD2_ALARM   = 0x09
def DATA_HARSH_DRIVER = 0x0A
def DATA_CANBUS       = 0x0B
def DATA_J1708        = 0x0C
def DATA_VIN          = 0x0D
def DATA_RFID         = 0x0E
def DATA_EVENT        = 0x10

def decodeAlarm = { int alarm ->
    if (checkBit(alarm, 0))  return ALARM_POWER_OFF
    if (checkBit(alarm, 1))  return ALARM_MOVEMENT
    if (checkBit(alarm, 2))  return ALARM_OVERSPEED
    if (checkBit(alarm, 4))  return ALARM_GEOFENCE
    if (checkBit(alarm, 10)) return ALARM_SOS
    return null
}

def decodeObd = { pos, tlv ->
    while (tlv.readableBytes() > 0) {
        int firstByte = tlv.readUByte()
        int paramLen = (firstByte >> 4) & 0x0F
        int mode = firstByte & 0x0F
        if (paramLen <= 1 || tlv.readableBytes() < paramLen - 1) break
        String hex = tlv.readHex(paramLen - 1)
        def entry = ObdDecoder.decode(mode, hex)
        if (entry != null) pos.set(entry.key, entry.value)
    }
}

def decodeJ1708 = { pos, tlv ->
    while (tlv.readableBytes() > 0) {
        int mark = tlv.readUByte()
        int len  = mark & 0x3F
        int type = (mark >> 6) & 0x03
        if (tlv.readableBytes() < 1) break
        int id = tlv.readUByte()
        if (type == 3) id += 256
        if (len <= 1 || tlv.readableBytes() < len - 1) break
        String value = tlv.readHex(len - 1)
        if (type == 2 || type == 3) pos.set('pid' + id, value)
    }
}

def decodeDriverBehavior = { pos, tlv ->
    int value = tlv.readUByte()
    if (checkBit(value, 0)) pos.set('rapidAcceleration', true)
    if (checkBit(value, 1)) pos.set('roughBraking',      true)
    if (checkBit(value, 2)) pos.set('harshCourse',       true)
    if (checkBit(value, 3)) pos.set('noWarmUp',          true)
    if (checkBit(value, 4)) pos.set('longIdle',          true)
    if (checkBit(value, 5)) pos.set('fatigueDriving',    true)
    if (checkBit(value, 6)) pos.set('roughTerrain',      true)
    if (checkBit(value, 7)) pos.set('highRpm',           true)
}

def decodeAdc = { pos, tlv ->
    while (tlv.readableBytes() >= 2) {
        int raw = tlv.readUShort()
        int id  = (raw >> 12) & 0x0F
        int val = raw & 0x0FFF
        switch (id) {
            case 0:           pos.set(Position.KEY_POWER,        val * 110 / 4096.0 - 10); break
            case 1:           pos.set(Position.PREFIX_TEMP + 1,  val * 180 / 4096.0 - 55); break
            case 2:           pos.set(Position.KEY_BATTERY,      val * 110 / 4096.0 - 10); break
            case 3:           pos.set(Position.PREFIX_ADC + 1,   val * 110 / 4096.0 - 10); break
            case 5: case 6: case 7: case 8: case 9:
                              pos.set('fuel' + (id - 4),         val * 2000 / 4096.0);     break
            default:          pos.set(Position.PREFIX_IO + id,   val);                      break
        }
    }
}

protocol("ulbotech") {

    port 5072
    commands TYPE_CUSTOM

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 2) return null

            if (fb.getUByte(0) == 0xF8) {
                // Binary: find closing 0xF8 and unescape content
                int endIdx = fb.indexOf(0xF8, 1)
                if (endIdx < 0) return null

                int rawLen = endIdx + 1
                byte[] raw = fb.bytes(0, rawLen)

                def out = new java.io.ByteArrayOutputStream(rawLen)
                int i = 0
                while (i < rawLen) {
                    int b = raw[i] & 0xFF
                    if (b == 0xF7 && i + 1 < rawLen) {
                        int ext = raw[i + 1] & 0xFF
                        if (ext == 0x00) { out.write(0xF7); i += 2 }
                        else if (ext == 0x0F) { out.write(0xF8); i += 2 }
                        else { out.write(b); i++ }
                    } else {
                        out.write(b); i++
                    }
                }
                return frameResult(rawLen, out.toByteArray())
            } else {
                // Text: scan for '#'
                int endIdx = fb.indexOf('#')
                if (endIdx < 0) return null
                return endIdx + 1
            }
        }

        decode { msg, ctx ->
            def buf = msg as org.traccar.driver.BufReader

            if (buf.getUByte(0) == 0xF8) {

                // Build binary ACK: F8 01 FE [byte0 byte1] [CRC16-XMODEM of 01 FE byte0 byte1] F8
                byte hdr0 = (byte)(buf.getUByte(0) & 0xFF)
                byte hdr1 = (byte)(buf.getUByte(1) & 0xFF)
                byte[] crcInput = [(byte)0x01, (byte)0xFE, hdr0, hdr1] as byte[]
                int crc = Checksum.crc16(Checksum.CRC16_XMODEM, ByteBuffer.wrap(crcInput))
                ctx.ack([(byte)0xF8, (byte)0x01, (byte)0xFE, hdr0, hdr1,
                         (byte)((crc >> 8) & 0xFF), (byte)(crc & 0xFF), (byte)0xF8] as byte[])

                buf.skip(1)  // header 0xF8
                buf.skip(1)  // version
                buf.skip(1)  // type

                String imei = buf.readHex(8).substring(1)
                def session = ctx.session(imei)
                if (!session) return null

                long seconds = (buf.readUInt() & 0x7FFFFFFFL) + 946684800L
                Date time = new Date(seconds * 1000L)

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId
                boolean hasLocation = false

                while (buf.readableBytes() > 3) {
                    int type   = buf.readUByte()
                    int length = (type == DATA_CANBUS) ? buf.readUShort() : buf.readUByte()
                    def tlv    = buf.slice(length)

                    switch (type) {
                        case DATA_GPS:
                            hasLocation = true
                            pos.latitude  = tlv.readInt() / 1000000.0
                            pos.longitude = tlv.readInt() / 1000000.0
                            pos.speed  = UnitsConverter.knotsFromKph(tlv.readUShort())
                            pos.course = tlv.readUShort()
                            int hdop = tlv.readUShort()
                            pos.valid = hdop < 9999
                            pos.set(Position.KEY_HDOP, hdop / 100.0)
                            break

                        case DATA_LBS:
                            int mcc = tlv.readUShort()
                            int mnc = tlv.readUShort()
                            int lac = tlv.readUShort()
                            long cid
                            int rssi
                            if (length == 11) {
                                cid  = tlv.readUInt()
                                rssi = -tlv.readUByte()
                            } else {
                                cid  = tlv.readUShort()
                                rssi = -tlv.readUByte()
                            }
                            pos.network = new Network(CellTower.from(mcc, mnc, lac, cid, rssi))
                            break

                        case DATA_STATUS:
                            int status = tlv.readUShort()
                            pos.set(Position.KEY_IGNITION, checkBit(status, 9))
                            pos.set(Position.KEY_STATUS, status)
                            pos.addAlarm(decodeAlarm(tlv.readUShort()))
                            break

                        case DATA_ODOMETER:
                            pos.set(Position.KEY_ODOMETER, tlv.readUInt())
                            break

                        case DATA_ADC:
                            decodeAdc(pos, tlv)
                            break

                        case DATA_GEOFENCE:
                            pos.set('geofenceIn',    tlv.readUInt())
                            pos.set('geofenceAlarm', tlv.readUInt())
                            break

                        case DATA_OBD2:
                        case DATA_OBD2_ALARM:
                            decodeObd(pos, tlv)
                            break

                        case DATA_FUEL:
                            pos.set(Position.KEY_FUEL_CONSUMPTION, tlv.readUInt() / 10000.0)
                            break

                        case DATA_HARSH_DRIVER:
                            decodeDriverBehavior(pos, tlv)
                            break

                        case DATA_CANBUS:
                            pos.set('can', tlv.readHex(length))
                            break

                        case DATA_J1708:
                            decodeJ1708(pos, tlv)
                            break

                        case DATA_VIN:
                            pos.set(Position.KEY_VIN, tlv.readString(length))
                            break

                        case DATA_RFID:
                            pos.set(Position.KEY_DRIVER_UNIQUE_ID, tlv.readString(length - 1))
                            pos.set('authorized', tlv.readUByte() != 0)
                            break

                        case DATA_EVENT:
                            pos.set(Position.KEY_EVENT, tlv.readUByte())
                            if (length > 1) pos.set('eventMask', tlv.readUInt())
                            break
                    }
                }

                if (!hasLocation) {
                    ctx.lastLocation(pos, time)
                } else {
                    pos.time = time
                }

                return pos

            } else {

                // Text frame: *TSdd,IMEI,HHMMSS,DDMMYY,COMMAND#
                int totalLen = buf.readableBytes()
                byte[] raw = buf.readBytes(totalLen)

                // CRC over bytes 1..end-2 (skip '*' and '#')
                int crc = Checksum.crc16(Checksum.CRC16_XMODEM, ByteBuffer.wrap(raw, 1, raw.length - 2))
                ctx.ack(("*TS01,ACK:" + String.format("%04X", crc) + "#").getBytes(StandardCharsets.US_ASCII))

                String sentence = new String(raw, StandardCharsets.US_ASCII)
                def m = sentence =~ /\*TS\d{2},(\d{15}),(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),([^#]+)#/
                if (!m.matches()) return null

                String imei = m.group(1)
                def session = ctx.session(imei)
                if (!session) return null

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                int hh  = m.group(2).toInteger()
                int min = m.group(3).toInteger()
                int ss  = m.group(4).toInteger()
                int dd  = m.group(5).toInteger()
                int mo  = m.group(6).toInteger()
                int yy  = m.group(7).toInteger()

                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.set(2000 + yy, mo - 1, dd, hh, min, ss)
                cal.set(Calendar.MILLISECOND, 0)
                ctx.lastLocation(pos, cal.getTime())

                pos.set(Position.KEY_RESULT, m.group(8))

                return pos
            }
        }
    }
}
