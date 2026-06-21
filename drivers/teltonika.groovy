/*
 * Copyright 2013 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by FOGNETX <Drew.Taylor@fognetx.com>, 2026. Modifications licensed under
// AGPL-3.0-or-later (SPDX-License-Identifier: AGPL-3.0-or-later).

import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position

protocol("teltonika") {

    port 5027

    variant("main") {

        scriptedFrame { fb ->
            int n = fb.readableBytes()
            if (n < 1) return null
            if ((fb.getUByte(0) & 0xFF) == 0xFF) return 1
            if (n < 2) return null
            int header = ((fb.getUByte(0) & 0xFF) << 8) | (fb.getUByte(1) & 0xFF)
            if (header > 0) {
                int total = header + 2
                return n >= total ? total : null
            }
            if (n < 8) return null
            long dataLen = ((fb.getUByte(4) & 0xFFL) << 24) |
                           ((fb.getUByte(5) & 0xFFL) << 16) |
                           ((fb.getUByte(6) & 0xFFL) << 8) |
                           (fb.getUByte(7) & 0xFFL)
            int total = (int)(dataLen + 12)
            return n >= total ? total : null
        }

        decode { msg, ctx ->

            int CODEC_GH3000 = 0x07
            int CODEC_8 = 0x08
            int CODEC_8_EXT = 0x8E
            int CODEC_12 = 0x0C
            int CODEC_13 = 0x0D
            int CODEC_16 = 0x10

            // Ping
            if (msg.readableBytes() == 1) {
                msg.readUByte()
                return null
            }

            // IMEI identification frame (UShort header > 0)
            int header = ((msg.getUByte(0) & 0xFF) << 8) | (msg.getUByte(1) & 0xFF)
            if (header > 0) {
                msg.readUShort()
                String imei = msg.readString(header)
                ctx.session(imei)
                ctx.ack([0x01] as byte[])
                return null
            }

            // Data frame: skip 4 preamble bytes
            msg.skip(4)
            def buf = msg

            def readExtByte = { b, codec, codecs ->
                codecs.contains(codec) ? (int) b.readUShort() : (int) b.readUByte()
            }

            def readValue = { b, int length ->
                switch (length) {
                    case 1: return (long) b.readUByte()
                    case 2: return (long) b.readUShort()
                    case 4: return b.readUInt()
                    default: return b.readLong()
                }
            }

            def isPrintable = { b, int len ->
                for (int k = 0; k < len; k++) {
                    int c = b.getUByte(k) & 0xFF
                    if (c > 0x7E) return false
                    if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) return false
                }
                return true
            }

            def decodeGh3000Param = { pos, int id, b, int length ->
                long val = readValue(b, length)
                switch (id) {
                    case 1:   pos.set(Position.KEY_BATTERY_LEVEL, val); break
                    case 2:   pos.set("usbConnected", val == 1); break
                    case 5:   pos.set("uptime", val); break
                    case 20:  pos.set(Position.KEY_HDOP, val / 10.0); break
                    case 21:  pos.set(Position.KEY_VDOP, val / 10.0); break
                    case 22:  pos.set(Position.KEY_PDOP, val / 10.0); break
                    case 67:  pos.set(Position.KEY_BATTERY, val / 1000.0); break
                    case 221: pos.set("button", val); break
                    case 222: if (val == 1) pos.addAlarm(Position.ALARM_SOS); break
                    case 240: pos.set(Position.KEY_MOTION, val == 1); break
                    case 244: pos.set(Position.KEY_ROAMING, val == 1); break
                    default:  pos.set(Position.PREFIX_IO + id, val); break
                }
            }

            def decodeParam = { pos, int id, b, int length, int codec ->
                if (codec == CODEC_GH3000) {
                    decodeGh3000Param(pos, id, b, length)
                    return
                }
                switch (id) {
                    case 1:   pos.set(Position.PREFIX_IN + 1, b.readUByte() > 0); break
                    case 2:   pos.set(Position.PREFIX_IN + 2, b.readUByte() > 0); break
                    case 3:   pos.set(Position.PREFIX_IN + 3, b.readUByte() > 0); break
                    case 4:   pos.set(Position.PREFIX_IN + 4, b.readUByte() > 0); break
                    case 9:   pos.set(Position.PREFIX_ADC + 1, b.readUShort() / 1000.0); break
                    case 10:  pos.set(Position.PREFIX_ADC + 2, b.readUShort() / 1000.0); break
                    case 11:  pos.set(Position.KEY_ICCID, String.valueOf(b.readLong())); break
                    case 12:  pos.set(Position.KEY_FUEL_USED, b.readUInt() / 1000.0); break
                    case 13:  pos.set(Position.KEY_FUEL_CONSUMPTION, b.readUShort() / 100.0); break
                    case 16:  pos.set(Position.KEY_ODOMETER, b.readUInt()); break
                    case 17:  pos.set("axisX", (int) b.readShort()); break
                    case 18:  pos.set("axisY", (int) b.readShort()); break
                    case 19:  pos.set("axisZ", (int) b.readShort()); break
                    case 21:  pos.set(Position.KEY_RSSI, b.readUByte()); break
                    case 24:  pos.setSpeed(UnitsConverter.knotsFromKph(b.readUShort())); break
                    case 25:  pos.set("bleTemp1", b.readShort() / 100.0); break
                    case 26:  pos.set("bleTemp2", b.readShort() / 100.0); break
                    case 27:  pos.set("bleTemp3", b.readShort() / 100.0); break
                    case 28:  pos.set("bleTemp4", b.readShort() / 100.0); break
                    case 30:  pos.set("faultCount", b.readUByte()); break
                    case 31:  pos.set(Position.KEY_ENGINE_LOAD, b.readUByte()); break
                    case 32:  pos.set(Position.KEY_COOLANT_TEMP, (int) b.readByte()); break
                    case 36:  pos.set(Position.KEY_RPM, b.readUShort()); break
                    case 43:  pos.set("milDistance", b.readUShort()); break
                    case 57:  pos.set("hybridBatteryLevel", (int) b.readByte()); break
                    case 66:  pos.set(Position.KEY_POWER, b.readUShort() / 1000.0); break
                    case 67:  pos.set(Position.KEY_BATTERY, b.readUShort() / 1000.0); break
                    case 68:  pos.set("batteryCurrent", b.readUShort() / 1000.0); break
                    case 72:  pos.set(Position.PREFIX_TEMP + 1, b.readInt() / 10.0); break
                    case 73:  pos.set(Position.PREFIX_TEMP + 2, b.readInt() / 10.0); break
                    case 74:  pos.set(Position.PREFIX_TEMP + 3, b.readInt() / 10.0); break
                    case 75:  pos.set(Position.PREFIX_TEMP + 4, b.readInt() / 10.0); break
                    case 78:
                        long duid = b.readLongLE()
                        if (duid != 0) pos.set(Position.KEY_DRIVER_UNIQUE_ID, String.format("%016X", duid))
                        break
                    case 80:  pos.set("dataMode", b.readUByte()); break
                    case 81:  pos.set(Position.KEY_OBD_SPEED, b.readUByte()); break
                    case 82:  pos.set(Position.KEY_THROTTLE, b.readUByte()); break
                    case 83:  pos.set(Position.KEY_FUEL_USED, b.readUInt() / 10.0); break
                    case 84:  pos.set(Position.KEY_FUEL, b.readUShort() / 10.0); break
                    case 85:  pos.set(Position.KEY_RPM, b.readUShort()); break
                    case 87:  pos.set(Position.KEY_OBD_ODOMETER, b.readUInt()); break
                    case 89:  pos.set(Position.KEY_FUEL_LEVEL, b.readUByte()); break
                    case 107: pos.set(Position.KEY_FUEL_USED, b.readUInt() / 10.0); break
                    case 110: pos.set(Position.KEY_FUEL_CONSUMPTION, b.readUShort() / 10.0); break
                    case 113: pos.set(Position.KEY_BATTERY_LEVEL, b.readUByte()); break
                    case 115: pos.set(Position.KEY_ENGINE_TEMP, b.readShort() / 10.0); break
                    case 175:
                        pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_GEOFENCE_ENTER : Position.ALARM_GEOFENCE_EXIT)
                        break
                    case 179: pos.set(Position.PREFIX_OUT + 1, b.readUByte() > 0); break
                    case 180: pos.set(Position.PREFIX_OUT + 2, b.readUByte() > 0); break
                    case 181: pos.set(Position.KEY_PDOP, b.readUShort() / 10.0); break
                    case 182: pos.set(Position.KEY_HDOP, b.readUShort() / 10.0); break
                    case 199: pos.set(Position.KEY_ODOMETER_TRIP, b.readUInt()); break
                    case 200: pos.set("sleepMode", b.readUByte()); break
                    case 205: pos.set("cid2g", (int) b.readUShort()); break
                    case 206: pos.set("lac", (int) b.readUShort()); break
                    case 236: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_GENERAL : null); break
                    case 239: pos.set(Position.KEY_IGNITION, b.readUByte() > 0); break
                    case 240: pos.set(Position.KEY_MOTION, b.readUByte() > 0); break
                    case 241: pos.set(Position.KEY_OPERATOR, b.readUInt()); break
                    case 246: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_TOW : null); break
                    case 247: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_ACCIDENT : null); break
                    case 249: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_JAMMING : null); break
                    case 251: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_IDLE : null); break
                    case 252: pos.addAlarm(b.readUByte() > 0 ? Position.ALARM_POWER_CUT : null); break
                    case 253:
                        int dv = b.readUByte()
                        if (dv == 1) pos.addAlarm(Position.ALARM_ACCELERATION)
                        else if (dv == 2) pos.addAlarm(Position.ALARM_BRAKING)
                        else if (dv == 3) pos.addAlarm(Position.ALARM_CORNERING)
                        break
                    case 389: pos.set(Position.KEY_OBD_ODOMETER, b.readUInt() * 1000); break
                    case 636: pos.set("cid4g", b.readUInt()); break
                    case 662: pos.set(Position.KEY_DOOR, b.readUByte() > 0); break
                    case 701: pos.set("bleTemp1", b.readShort() / 10.0); break
                    case 702: pos.set("bleTemp2", b.readShort() / 10.0); break
                    case 703: pos.set("bleTemp3", b.readShort() / 10.0); break
                    case 704: pos.set("bleTemp4", b.readShort() / 10.0); break
                    case 10644: pos.set("tempProbe1", b.readShort() / 100.0); break
                    case 10645: pos.set("tempProbe2", b.readShort() / 100.0); break
                    case 10646: pos.set("tempProbe3", b.readShort() / 100.0); break
                    case 10647: pos.set("tempProbe4", b.readShort() / 100.0); break
                    case 10648: pos.set("tempProbe5", b.readShort() / 100.0); break
                    case 10649: pos.set("tempProbe6", b.readShort() / 100.0); break
                    case 10800: pos.set("eyeTemp1", b.readShort() / 100.0); break
                    case 10801: pos.set("eyeTemp2", b.readShort() / 100.0); break
                    case 10802: pos.set("eyeTemp3", b.readShort() / 100.0); break
                    case 10803: pos.set("eyeTemp4", b.readShort() / 100.0); break
                    case 10832: pos.set("eyeRoll1", (int) b.readShort()); break
                    case 10833: pos.set("eyeRoll2", (int) b.readShort()); break
                    case 10834: pos.set("eyeRoll3", (int) b.readShort()); break
                    case 10835: pos.set("eyeRoll4", (int) b.readShort()); break
                    default:
                        pos.set(Position.PREFIX_IO + id, readValue(b, length))
                        break
                }
            }

            def decodeXByteIos = { pos, b ->
                int cnt = b.readUShort()
                for (int j = 0; j < cnt; j++) {
                    int id = b.readUShort()
                    int length = b.readUShort()
                    if (id == 256 || id == 325) {
                        pos.set(Position.KEY_VIN, b.readString(length))
                    } else if (id == 281) {
                        pos.set(Position.KEY_DTCS, b.readString(length).replace(',', ' '))
                    } else if (id == 385) {
                        def data = b.slice(length)
                        data.readUByte() // data part
                        int bi = 1
                        while (data.isReadable()) {
                            int flags = data.readUByte()
                            if ((flags >> 4) > 0) {
                                pos.set("beacon${bi}Uuid", data.readHex(16))
                                pos.set("beacon${bi}Major", data.readUShort())
                                pos.set("beacon${bi}Minor", data.readUShort())
                            } else {
                                pos.set("beacon${bi}Namespace", data.readHex(10))
                                pos.set("beacon${bi}Instance", data.readHex(6))
                            }
                            pos.set("beacon${bi}Rssi", (int) data.readByte())
                            if ((flags & 2) != 0) pos.set("beacon${bi}Battery", data.readUShort() / 100.0)
                            if ((flags & 4) != 0) pos.set("beacon${bi}Temp", data.readUShort())
                            bi++
                        }
                    } else if (id == 548 || id == 10828 || id == 10829 || id == 10831 || id == 11317) {
                        def data = b.slice(length)
                        data.readUByte() // header
                        for (int ti = 1; data.isReadable(); ti++) {
                            def beacon = data.slice(data.readUByte())
                            while (beacon.isReadable()) {
                                int pid = beacon.readUByte()
                                int plen = beacon.readUByte()
                                switch (pid) {
                                    case 0:  pos.set("tag${ti}Rssi", (int) beacon.readByte()); break
                                    case 1:  pos.set("tag${ti}Id", beacon.readHex(plen)); break
                                    case 2:
                                        def bd = beacon.slice(plen)
                                        int flag = bd.readUByte()
                                        if ((flag & 64) != 0) pos.set("tag${ti}LowBattery", true)
                                        if ((flag & 128) != 0) pos.set("tag${ti}Voltage", bd.readUByte() * 10 + 2000)
                                        break
                                    case 5:  pos.set("tag${ti}Name", beacon.readString(plen)); break
                                    case 6:  pos.set("tag${ti}Temp", (int) beacon.readShort()); break
                                    case 7:  pos.set("tag${ti}Humidity", beacon.readUByte()); break
                                    case 8:  pos.set("tag${ti}Magnet", beacon.readUByte() > 0); break
                                    case 9:  pos.set("tag${ti}Motion", beacon.readUByte() > 0); break
                                    case 10: pos.set("tag${ti}MotionCount", beacon.readUShort()); break
                                    case 11: pos.set("tag${ti}Pitch", (int) beacon.readByte()); break
                                    case 12: pos.set("tag${ti}AngleRoll", (int) beacon.readShort()); break
                                    case 13: pos.set("tag${ti}LowBattery", beacon.readUByte()); break
                                    case 14: pos.set("tag${ti}Battery", beacon.readUShort()); break
                                    case 15: pos.set("tag${ti}Mac", beacon.readHex(6)); break
                                    default: beacon.skip(plen); break
                                }
                            }
                        }
                    } else {
                        pos.set(Position.PREFIX_IO + id, b.readHex(length))
                    }
                }
            }

            def decodeLocation = { pos, b, int codec ->
                int globalMask = 0x0F

                if (codec == CODEC_GH3000) {
                    long time = b.readUInt() & 0x3FFFFFFFL
                    time += 1167609600L
                    globalMask = b.readUByte()
                    if ((globalMask & 1) != 0) {
                        pos.setTime(new Date(time * 1000L))
                        int locationMask = b.readUByte()
                        if ((locationMask & 1) != 0) {
                            pos.setLatitude(b.readFloat())
                            pos.setLongitude(b.readFloat())
                        }
                        if ((locationMask & 2) != 0) pos.setAltitude(b.readUShort())
                        if ((locationMask & 4) != 0) pos.setCourse(b.readUByte() * 360.0 / 256)
                        if ((locationMask & 8) != 0) pos.setSpeed(UnitsConverter.knotsFromKph(b.readUByte()))
                        if ((locationMask & 16) != 0) pos.set(Position.KEY_SATELLITES, b.readUByte())
                        if ((locationMask & 32) != 0) {
                            int lac = b.readUShort()
                            int cid = b.readUShort()
                            def ct = CellTower.from(0, 0, lac, cid)
                            if ((locationMask & 64) != 0) ct.setSignalStrength(b.readUByte())
                            if ((locationMask & 128) != 0) ct.setOperator(b.readUInt())
                            pos.setNetwork(new Network(ct))
                        } else {
                            if ((locationMask & 64) != 0) pos.set(Position.KEY_RSSI, b.readUByte())
                            if ((locationMask & 128) != 0) pos.set(Position.KEY_OPERATOR, b.readUInt())
                        }
                    } else {
                        ctx.lastLocation(pos, new Date(time * 1000L))
                    }
                } else {
                    pos.setTime(new Date(b.readLong()))
                    pos.set("priority", b.readUByte())
                    pos.setLongitude(b.readInt() / 10000000.0)
                    pos.setLatitude(b.readInt() / 10000000.0)
                    pos.setAltitude((double) b.readShort())
                    pos.setCourse(b.readUShort())
                    int satellites = b.readUByte()
                    pos.set(Position.KEY_SATELLITES, satellites)
                    pos.setValid(satellites != 0)
                    pos.setSpeed(UnitsConverter.knotsFromKph(b.readUShort()))
                    pos.set(Position.KEY_EVENT, readExtByte(b, codec, [CODEC_8_EXT, CODEC_16]))
                    if (codec == CODEC_16) b.readUByte() // generation type
                    readExtByte(b, codec, [CODEC_8_EXT]) // total IO record count
                }

                // 1-byte IOs
                if ((globalMask & 2) != 0) {
                    int cnt = readExtByte(b, codec, [CODEC_8_EXT])
                    for (int j = 0; j < cnt; j++) {
                        int id = readExtByte(b, codec, [CODEC_8_EXT, CODEC_16])
                        decodeParam(pos, id, b, 1, codec)
                    }
                }

                // 2-byte IOs
                if ((globalMask & 4) != 0) {
                    int cnt = readExtByte(b, codec, [CODEC_8_EXT])
                    for (int j = 0; j < cnt; j++) {
                        int id = readExtByte(b, codec, [CODEC_8_EXT, CODEC_16])
                        decodeParam(pos, id, b, 2, codec)
                    }
                }

                // 4-byte IOs
                if ((globalMask & 8) != 0) {
                    int cnt = readExtByte(b, codec, [CODEC_8_EXT])
                    for (int j = 0; j < cnt; j++) {
                        int id = readExtByte(b, codec, [CODEC_8_EXT, CODEC_16])
                        decodeParam(pos, id, b, 4, codec)
                    }
                }

                // 8-byte IOs (CODEC_8, CODEC_8_EXT, CODEC_16 only)
                if (codec == CODEC_8 || codec == CODEC_8_EXT || codec == CODEC_16) {
                    int cnt = readExtByte(b, codec, [CODEC_8_EXT])
                    for (int j = 0; j < cnt; j++) {
                        int id = readExtByte(b, codec, [CODEC_8_EXT, CODEC_16])
                        decodeParam(pos, id, b, 8, codec)
                    }
                }

                // X-byte IOs (CODEC_8_EXT only)
                if (codec == CODEC_8_EXT) {
                    decodeXByteIos(pos, b)
                }

                // Cell tower extraction from stored attributes
                def attrs = pos.getAttributes()
                Object cid2gObj = attrs.remove("cid2g")
                Object cid4gObj = attrs.remove("cid4g")
                Object lacObj = attrs.remove("lac")
                if (lacObj != null && (cid2gObj != null || cid4gObj != null)) {
                    int lacVal = ((Number) lacObj).intValue()
                    def ct
                    def network = new Network()
                    if (cid2gObj != null) {
                        ct = CellTower.from(0, 0, lacVal, ((Number) cid2gObj).intValue())
                    } else {
                        ct = CellTower.from(0, 0, lacVal, ((Number) cid4gObj).longValue())
                        network.setRadioType("lte")
                    }
                    Object opObj = attrs.get(Position.KEY_OPERATOR)
                    if (opObj != null && ((Number) opObj).longValue() >= 1000L) {
                        ct.setOperator(((Number) opObj).longValue())
                    }
                    network.addCellTower(ct)
                    pos.setNetwork(network)
                }
            }

            // Parse data frame
            buf.readUInt() // data length field

            int codec = buf.readUByte()
            int count = buf.readUByte()

            List positions = []

            for (int i = 0; i < count; i++) {
                def pos = ctx.newPosition()
                pos.setValid(true)
                boolean addPosition = true

                if (codec == CODEC_13) {
                    buf.readUByte() // type
                    int length = buf.readInt() - 4
                    ctx.lastLocation(pos, new Date(buf.readUInt() * 1000L))
                    if (isPrintable(buf, length)) {
                        String data = buf.readString(length).trim()
                        if (data.startsWith("GTSL")) {
                            pos.set(Position.KEY_DRIVER_UNIQUE_ID, data.split("\\|")[4])
                        } else {
                            pos.set(Position.KEY_RESULT, data)
                        }
                    } else {
                        pos.set(Position.KEY_RESULT, buf.readHex(length))
                    }
                } else if (codec == CODEC_12) {
                    ctx.lastLocation(pos, null)
                    int type = buf.readUByte()
                    if (type == 0x0D) {
                        int photoLen = buf.readInt()
                        buf.skip(photoLen)
                        addPosition = false
                    } else {
                        pos.set(Position.KEY_TYPE, type)
                        int length = buf.readInt()
                        if (isPrintable(buf, length)) {
                            String data = buf.readString(length).trim()
                            if (data.startsWith("UUUUww") && data.endsWith("SSS")) {
                                String[] values = data.substring(6, data.length() - 4).split(";")
                                for (int a = 0; a < 8; a++) {
                                    pos.set("axle" + (a + 1), Double.parseDouble(values[a]))
                                }
                                pos.set("loadTruck", Double.parseDouble(values[8]))
                                pos.set("loadTrailer", Double.parseDouble(values[9]))
                                pos.set("totalTruck", Double.parseDouble(values[10]))
                                pos.set("totalTrailer", Double.parseDouble(values[11]))
                            } else {
                                pos.set(Position.KEY_RESULT, data)
                            }
                        } else {
                            pos.set(Position.KEY_RESULT, buf.readHex(length))
                        }
                    }
                } else {
                    decodeLocation(pos, buf, codec)
                }

                if (addPosition) positions.add(pos)
            }

            // Send ACK for data codecs
            if (codec != CODEC_12 && codec != CODEC_13) {
                ctx.ack([(byte)((count >> 24) & 0xFF),
                         (byte)((count >> 16) & 0xFF),
                         (byte)((count >> 8) & 0xFF),
                         (byte)(count & 0xFF)] as byte[])
            }

            if (positions.isEmpty()) return null
            return positions.size() == 1 ? positions[0] : positions
        }
    }
}
