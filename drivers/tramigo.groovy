// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Tramigo binary GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/tramigo/ (Java reference)
 *
 * Binary framing: first byte is the protocol version, which selects
 * the frame-length field layout.
 *   0x80 — length (LE) at offset 6; payload is binary header + ASCII text
 *   0x02 / 0x04 — length (LE) at offset 1; fully binary
 *   0x01 / other — length (BE) at offset 6; fully binary
 *
 * Supported message types:
 *   0x01 — type 0x0100 / 0x00FE: position data
 *   0x04 — TLV position/event packet (ACK with CRC16-CCITT-FALSE)
 *   0x80 — text-payload position report (ACK "gprs,ack,<index>")
 *
 * Device IDs:
 *   0x01 / 0x80 — 32-bit unsigned integer formatted as decimal string
 *   0x04       — two 32-bit LE integers formatted as "%08d%07d"
 */

import org.traccar.helper.DateUtil
import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

def DIRECTIONS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW']

def PAT_COORDS = Pattern.compile('(-?\\d+\\.\\d+), (-?\\d+\\.\\d+)')
def PAT_DIR    = Pattern.compile('([NSWE]{1,2}) with speed (\\d+) km/h')
def PAT_TIME   = Pattern.compile('(\\d{1,2}:\\d{2}(:\\d{2})? \\w{3} \\d{1,2})')

def FMT_HMS = DateTimeFormatter.ofPattern('HH:mm:ss MMM d yyyy', Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())
def FMT_HM  = DateTimeFormatter.ofPattern('HH:mm MMM d yyyy',    Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

def patchCrc16LE = { byte[] data, int offset ->
    int crc = crc16CcittFalse(data)
    data[offset]     = (byte)(crc & 0xff)
    data[offset + 1] = (byte)((crc >> 8) & 0xff)
    data
}

def decode01 = { buf, ctx ->
    buf.skip(1)                     // version id
    int index = buf.readUShortLE()
    int type  = buf.readUShortLE()

    if (type != 0x0100 && type != 0x00FE) return null

    buf.skip(6)                     // length, mask, checksum
    long id = buf.readUIntLE()
    buf.skip(4)                     // time

    def session = ctx.session(String.valueOf(id))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, index)

    buf.skip(4)                     // report trigger, state flag
    pos.valid     = true
    pos.latitude  = buf.readUIntLE()  / 10000000.0
    pos.longitude = buf.readUIntLE()  / 10000000.0
    pos.set(Position.KEY_RSSI,               buf.readUShortLE())
    pos.set(Position.KEY_SATELLITES,         buf.readUShortLE())
    pos.set(Position.KEY_SATELLITES_VISIBLE, buf.readUShortLE())
    pos.set('gpsAntennaStatus',              buf.readUShortLE())
    pos.speed  = buf.readUShortLE() * 0.194384
    pos.course = buf.readUShortLE()
    pos.set(Position.KEY_ODOMETER, buf.readUIntLE())
    pos.set(Position.KEY_BATTERY,  buf.readUShortLE())
    pos.set(Position.KEY_CHARGE,   buf.readUShortLE())
    pos.time = new Date(buf.readUIntLE() * 1000)

    return pos
}

def decode04 = { buf, ctx ->
    buf.skip(4)                      // length, checksum
    int index = buf.readUShortLE()
    long id1  = buf.readUIntLE()
    long id2  = buf.readUIntLE()
    long time = buf.readUIntLE()

    def session = ctx.session(String.format('%08d%07d', id1, id2))
    if (!session) return null

    def ack = bytes {
        writeByte(0x04)
        writeShortLE(24)
        writeShortLE(0)              // checksum placeholder
        writeShortLE(index)
        writeIntLE((int) id1)
        writeIntLE((int) id2)
        writeIntLE((int) time)
        writeByte(0xff)
        writeShortLE(index)
        writeShortLE(0)
    }
    patchCrc16LE(ack, 3)
    ctx.ack(ack)

    def pos = ctx.newPosition()
    pos.deviceId   = session.deviceId
    pos.deviceTime = new Date(time * 1000)
    pos.set(Position.KEY_INDEX, index)

    while (buf.isReadable()) {
        int t = buf.readUByte()
        switch (t) {
            case 0:
                pos.set(Position.KEY_EVENT, buf.readUShortLE())
                buf.skip(4)          // event data
                int status = buf.readUShortLE()
                pos.set(Position.KEY_IGNITION, checkBit(status, 5))
                pos.set(Position.KEY_STATUS,   status)
                pos.valid     = true
                pos.latitude  = buf.readIntLE()  / 100000.0
                pos.longitude = buf.readIntLE()  / 100000.0
                pos.speed  = UnitsConverter.knotsFromKph(buf.readUShortLE())
                pos.course = buf.readUShortLE()
                pos.set(Position.KEY_RSSI,          buf.readUByte())
                pos.set(Position.KEY_GPS,            buf.readUByte())
                pos.set(Position.KEY_BATTERY_LEVEL,  buf.readUByte())
                pos.set(Position.KEY_ODOMETER_TRIP,  buf.readUShortLE())
                pos.set('maxAcceleration', buf.readUShortLE() / 1000.0)
                pos.set('maxDeceleration', buf.readUShortLE() / 1000.0)
                buf.skip(6)          // bearing to landmark, distance to landmark
                pos.fixTime = new Date(buf.readUIntLE() * 1000)
                buf.skip(1)          // reserved
                break
            case 1:
                buf.skip(buf.readUShortLE() - 3)   // landmark
                break
            case 4:
                buf.skip(53)         // trip
                break
            case 20:
                buf.skip(32)         // extended
                break
            case 22:
                buf.skip(1)
                buf.skip(buf.readUShortLE())        // zone name
                break
            case 30:
                buf.skip(79)         // system status
                break
            case 40:
                buf.skip(40)         // analog
                break
            case 50:
                buf.skip(buf.readUShortLE() - 3)   // console
                break
            case 255:
                buf.skip(4)          // acknowledgement
                break
            default:
                return pos.valid ? pos : null
        }
    }

    return pos.valid ? pos : null
}

def decode80 = { buf, ctx ->
    buf.skip(1)                      // version id
    int index = buf.readUShort()
    buf.skip(2)                      // type
    buf.skip(2)                      // length
    buf.skip(2)                      // mask
    buf.skip(2)                      // checksum
    long id = buf.readUInt()
    buf.skip(4)                      // time

    def session = ctx.session(String.valueOf(id))
    if (!session) return null

    ctx.ack("gprs,ack,${index}")

    def sentence = buf.readString(buf.remaining())

    def m = PAT_COORDS.matcher(sentence)
    if (!m.find()) return null

    def pos = ctx.newPosition()
    pos.deviceId  = session.deviceId
    pos.set(Position.KEY_INDEX, index)
    pos.valid     = true
    pos.latitude  = m.group(1).toDouble()
    pos.longitude = m.group(2).toDouble()

    m = PAT_DIR.matcher(sentence)
    if (m.find()) {
        int dirIdx = DIRECTIONS.indexOf(m.group(1))
        if (dirIdx >= 0) pos.course = dirIdx * 45.0
        pos.speed = UnitsConverter.knotsFromKph(m.group(2).toDouble())
    }

    m = PAT_TIME.matcher(sentence)
    if (!m.find()) return null
    int year = Calendar.getInstance().get(Calendar.YEAR)
    DateTimeFormatter fmt = m.group(2) != null ? FMT_HMS : FMT_HM
    pos.time = DateUtil.correctYear(DateUtil.parse(fmt, m.group(1) + ' ' + year))

    if (sentence.contains('Ignition on detected')) {
        pos.set(Position.KEY_IGNITION, true)
    } else if (sentence.contains('Ignition off detected')) {
        pos.set(Position.KEY_IGNITION, false)
    }

    return pos
}

protocol("tramigo") {

    port 5073

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 20) return null
            int protocol = fb.getUByte(0)
            int length
            if (protocol == 0x80) {
                length = fb.getUShortLE(6)
            } else if (protocol == 0x02 || protocol == 0x04) {
                length = fb.getUShortLE(1)
            } else {
                length = fb.getUShort(6)
            }
            length <= fb.readableBytes() ? frameRaw(length) : null
        }

        decode { buf, ctx ->
            int protocol = buf.readUByte()
            switch (protocol) {
                case 0x01: return decode01(buf, ctx)
                case 0x04: return decode04(buf, ctx)
                case 0x80: return decode80(buf, ctx)
                default:   return null
            }
        }
    }
}
