// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * Totem GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/totem/ (Java reference)
 *
 * Custom `$$`-delimited frames: byte[2]=='0' → 4-digit decimal length (new
 * format); else 2-digit hex length (old format).
 *
 * Four message families:
 *   Format1 (old + $GPRMC), Format2 (old + two `|` pipes), Format3 (old compact),
 *   Format4 (new, 4-digit decimal length, 2-hex type byte; subtypes E2, E5, main)
 */

import org.traccar.driver.BufReader
import org.traccar.helper.BitUtil
import org.traccar.helper.Checksum
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Command
import org.traccar.model.Network
import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone

def coord = { String d, String m, String h ->
    double v = d.toInteger() + m.toDouble() / 60.0
    (h == "S" || h == "W") ? -v : v
}

def utcDate = { int year, int month, int day, int h, int min, int s ->
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    c.set(year < 100 ? 2000 + year : year, month - 1, day, h, min, s)
    c.set(Calendar.MILLISECOND, 0)
    c.getTime()
}

def decodeAlarm123 = { int v ->
    switch (v) {
        case 0x01: return ALARM_SOS
        case 0x10: return ALARM_LOW_BATTERY
        case 0x11: return ALARM_OVERSPEED
        case 0x30: return ALARM_PARKING
        case 0x42: return ALARM_GEOFENCE_EXIT
        case 0x43: return ALARM_GEOFENCE_ENTER
        default:   return null
    }
}

def decodeAlarm4 = { int v ->
    switch (v) {
        case 0x01: return ALARM_SOS
        case 0x02: return ALARM_OVERSPEED
        case 0x04: return ALARM_GEOFENCE_EXIT
        case 0x05: return ALARM_GEOFENCE_ENTER
        case 0x06: return ALARM_TOW
        case 0x07: return ALARM_GPS_ANTENNA_CUT
        case 0x10: return ALARM_POWER_CUT
        case 0x11: return ALARM_POWER_RESTORED
        case 0x12: return ALARM_LOW_POWER
        case 0x13: return ALARM_LOW_BATTERY
        case 0x40: return ALARM_VIBRATION
        case 0x41: return ALARM_IDLE
        case 0x42: return ALARM_ACCELERATION
        case 0x43: return ALARM_BRAKING
        default:   return null
    }
}

// Format 1 (old + $GPRMC):
// $$XX<IMEI>|<alarm>$GPRMC,<hhmmss.d>,<AV>,<lat>,<latHem>,<lon>,<lonHem>,<spd>,<crs>,<ddmmyy>,...*XX|pdop|hdop|vdop|io|batTime|<charged><bat3><pwr4>|[adc]|x*(lac4)(cid4)|temp|odo|serial|...|xxxx.*
// Groups: 1=imei, 2=alarm(hex), 3=hh,4=mm,5=ss, 6=validity,
//         7=latDeg,8=latMin,9=latHem, 10=lonDeg,11=lonMin,12=lonHem,
//         13=speed,14=course, 15=day,16=mon,17=year,
//         18=pdop,19=hdop,20=vdop, 21=io, 22=bat,23=pwr, 24=adc(opt),
//         25=lac,26=cid, 27=temp, 28=odo
def RE1 = ~/(?s)\$\$[0-9a-fA-F]{2}(\d+)\|(..)\$GPRMC,(\d{2})(\d{2})(\d{2})\.\d+,([AV]),(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),(\d+\.?\d*)?,(\d+\.?\d*)?,(\d{2})(\d{2})(\d{2})[^*]*\*[0-9a-fA-F]{2}\|(\d+\.\d+)\|(\d+\.\d+)\|(\d+\.\d+)\|(\d+)\|\d+\|\d(\d{3})(\d{4})\|(?:(\d+)\|)?[0-9a-fA-F]*([0-9a-fA-F]{4})([0-9a-fA-F]{4})\|(\d+)\|(\d+\.\d+)\|\d+\|.*[0-9a-fA-F]{4}.*/

// Format 2 (old, no $GPRMC, two pipe groups):
// $$XX<IMEI>|<alarm><ddmmyy><hhmmss>|<AV>|<latDeg><latMin>|<latHem>|<lonDeg><lonMin>|<lonHem>|<spd?>|<crs?>|hdop|io|<charged><bat2><pwr2>|adc|(lac4)(cid4)|temp|odo|serial|xxxx.*
// Groups: 1=imei, 2=alarm, 3=day,4=mon,5=year, 6=hh,7=mm,8=ss, 9=validity,
//         10=latDeg,11=latMin,12=latHem, 13=lonDeg,14=lonMin,15=lonHem,
//         16=speed(opt),17=course(opt), 18=hdop, 19=io, 20=bat,21=pwr, 22=adc,
//         23=lac,24=cid, 25=temp, 26=odo
def RE2 = ~/(?s)\$\$[0-9a-fA-F]{2}(\d+)\|(..)(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\|([AV])\|(\d+)(\d{2}\.\d+)\|([NS])\|(\d+)(\d{2}\.\d+)\|([EW])\|(\d+\.\d+)?\|(\d+)?\|(\d+\.\d+)\|(\d+)\|\d(\d{2})(\d{2})\|(\d+)\|([0-9a-fA-F]{4})([0-9a-fA-F]{4})\|(\d+)\|(\d+\.\d+)\|\d+\|[0-9a-fA-F]{4}.*/

// Format 3 (old compact, fixed-width, no $GPRMC, single pipe section):
// $$XX<IMEI>|<alarm><ddmmyy><hhmmss><io4hex>[01]<bat2><pwr2><adc14><adc24><t13><t23><lac4><cid4><AV><sats2><crs3><spd3><pdop><odo7><lat><lon><serial4><chk4>.*
// Groups: 1=imei,2=alarm, 3=day,4=mon,5=year, 6=hh,7=mm,8=ss,
//         9=io, 10=bat,11=pwr, 12=adc1,13=adc2, 14=t1,15=t2,
//         16=lac,17=cid, 18=validity, 19=sats, 20=course,21=speed,22=pdop,
//         23=odo, 24=latDeg,25=latMin,26=latHem, 27=lonDeg,28=lonMin,29=lonHem
def RE3 = ~/(?s)\$\$[0-9a-fA-F]{2}(\d+)\|(..)(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})([0-9a-fA-F]{4})[01](\d{2})(\d{2})(\d{4})(\d{4})(\d{3})(\d{3})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([AV])(\d{2})(\d{3})(\d{3})(\d{2}\.\d)(\d{7})(\d{2})(\d{2}\.\d{4})([NS])(\d{3})(\d{2}\.\d{4})([EW])\d{4}[0-9a-fA-F]{4}.*/

// Format E2 (type=0xE2): $$<len4><type2><IMEI>|<yymmdd><hhmmss>,<lon>,<lat>,<rfid>|<xx>.*
// Groups: 1=imei, 2=yr,3=mon,4=day, 5=hh,6=mm,7=ss, 8=lon,9=lat, 10=rfid
def RE_E2 = ~/(?s)\$\$\d{4}[0-9a-fA-F]{2}(\d+)\|(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(-?\d+\.\d+),(-?\d+\.\d+),(.+)\|[0-9a-fA-F]{2}.*/

// Format E5 (type=0xE5): OBD data
// Groups: 1=imei, 2=yr,3=mon,4=day, 5=hh,6=mm,7=ss, 8=lon,9=lat,
//         10=odo, 11=fuelUsed, 12=fuelCons, 13=power, 14=rpm, 15=speed,
//         16=intakeFlow(skip), 17=intakePres(skip), 18=coolant,
//         19=intakeTemp, 20=engineLoad, 21=throttle, 22=fuel
def RE_E5 = ~/(?s)\$\$\d{4}[0-9a-fA-F]{2}(\d+)\|(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2}),(-?\d+\.\d+),(-?\d+\.\d+),[^,]*,(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),\|[0-9a-fA-F]{2}.*/

// Format 4 main: $$<len4><type2><IMEI>|<status8hex><yymmdd><hhmmss>
//   <bat+pwr OR bat3><adc1>[<adc2-4><t1><t2?>]<lac><cid>[<mcc><mnc>]
//   <sats><rssi><crs><spd><hdop><odo><lat><lon>[<temp4>]<serial4><xx>.*
// Groups: 1=imei, 2=status, 3=yr,4=mon,5=day, 6=hh,7=mm,8=ss,
//         9=bat2(opt),10=pwr2(opt),11=bat3(opt), 12=adc1,
//         13=adc2(opt),14=adc3(opt),15=adc4(opt), 16=t1(opt),17=t2(opt),
//         18=lac,19=cid, 20=mcc(opt),21=mnc(opt),
//         22=sats,23=rssi, 24=course,25=speed,26=hdop,27=odo,
//         28=latDeg,29=latMin,30=latHem, 31=lonDeg,32=lonMin,33=lonHem
def RE4 = ~/(?s)\$\$\d{4}[0-9a-fA-F]{2}(\d+)\|([0-9a-fA-F]{8})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:(\d{2})(\d{2})|(\d{3}))(\d{4})(?:(?:(\d{4})(\d{4})(\d{4}))?(\d{4})(\d{4})?)?([0-9a-fA-F]{4})([0-9a-fA-F]{4})(?:(\d{2})(\d{3}))?(\d{2})(\d{2})(\d{3})(\d{3})(\d{2}\.\d)(\d{7})(\d{2})(\d{2}\.\d{4})([NS])(\d{3})(\d{2}\.\d{4})([EW])(?:\d{4})?\d{4}[0-9a-fA-F]{2}.*/

def decodeFormat1 = { String s, ctx ->
    def m = RE1.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm123(Integer.parseInt(m.group(2), 16)))
    int hh = m.group(3).toInteger(); int mm = m.group(4).toInteger(); int ss = m.group(5).toInteger()
    pos.valid = (m.group(6) == "A")
    pos.latitude  = coord(m.group(7),  m.group(8),  m.group(9))
    pos.longitude = coord(m.group(10), m.group(11), m.group(12))
    pos.speed  = m.group(13) ? m.group(13).toDouble() : 0.0
    pos.course = m.group(14) ? m.group(14).toDouble() : 0.0
    int day = m.group(15).toInteger(); int mon = m.group(16).toInteger(); int yr = m.group(17).toInteger()
    if (yr == 0) return null
    pos.time = utcDate(yr, mon, day, hh, mm, ss)
    pos.set(Position.KEY_PDOP, m.group(18).toDouble())
    pos.set(Position.KEY_HDOP, m.group(19).toDouble())
    pos.set(Position.KEY_VDOP, m.group(20).toDouble())
    int io = m.group(21).toInteger()
    pos.set(Position.KEY_STATUS, io)
    pos.addAlarm(BitUtil.check(io, 0) ? ALARM_SOS : null)
    pos.set(Position.PREFIX_IN + 3, BitUtil.check(io, 4))
    pos.set(Position.PREFIX_IN + 4, BitUtil.check(io, 5))
    pos.set(Position.PREFIX_IN + 1, BitUtil.check(io, 6))
    pos.set(Position.PREFIX_IN + 2, BitUtil.check(io, 7))
    pos.set(Position.PREFIX_OUT + 1, BitUtil.check(io, 8))
    pos.set(Position.PREFIX_OUT + 2, BitUtil.check(io, 9))
    pos.set(Position.KEY_BATTERY, m.group(22).toDouble() / 100.0)
    pos.set(Position.KEY_POWER,   m.group(23).toDouble())
    if (m.group(24)) pos.set(Position.PREFIX_ADC + 1, m.group(24))
    int lac = Integer.parseInt(m.group(25), 16)
    int cid = Integer.parseInt(m.group(26), 16)
    if (lac != 0 && cid != 0) pos.network = new Network(CellTower.from(0, 0, lac, cid))
    pos.set(Position.PREFIX_TEMP + 1, m.group(27))
    pos.set(Position.KEY_ODOMETER, m.group(28).toDouble() * 1000)
    pos
}

def decodeFormat2 = { String s, ctx ->
    def m = RE2.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm123(Integer.parseInt(m.group(2), 16)))
    int day = m.group(3).toInteger(); int mon = m.group(4).toInteger(); int yr = m.group(5).toInteger()
    int hh  = m.group(6).toInteger(); int mm  = m.group(7).toInteger(); int ss = m.group(8).toInteger()
    if (yr == 0) return null
    pos.time = utcDate(yr, mon, day, hh, mm, ss)
    pos.valid     = (m.group(9) == "A")
    pos.latitude  = coord(m.group(10), m.group(11), m.group(12))
    pos.longitude = coord(m.group(13), m.group(14), m.group(15))
    pos.speed  = m.group(16) ? m.group(16).toDouble() : 0.0
    pos.course = m.group(17) ? m.group(17).toDouble() : 0.0
    pos.set(Position.KEY_HDOP, m.group(18).toDouble())
    int io = m.group(19).toInteger()
    pos.set(Position.KEY_STATUS, io)
    pos.set(Position.KEY_ANTENNA, BitUtil.check(io, 0))
    pos.set(Position.KEY_CHARGE,  BitUtil.check(io, 1))
    (1..6).each { i -> pos.set(Position.PREFIX_IN  + i, BitUtil.check(io, 1 + i)) }
    (1..4).each { i -> pos.set(Position.PREFIX_OUT + i, BitUtil.check(io, 7 + i)) }
    pos.set(Position.KEY_BATTERY, m.group(20).toDouble() / 10.0)
    pos.set(Position.KEY_POWER,   m.group(21).toDouble())
    pos.set(Position.PREFIX_ADC + 1, m.group(22))
    int lac = Integer.parseInt(m.group(23), 16)
    int cid = Integer.parseInt(m.group(24), 16)
    if (lac != 0 && cid != 0) pos.network = new Network(CellTower.from(0, 0, lac, cid))
    pos.set(Position.PREFIX_TEMP + 1, m.group(25))
    pos.set(Position.KEY_ODOMETER, m.group(26).toDouble() * 1000)
    pos
}

def decodeFormat3 = { String s, ctx ->
    def m = RE3.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm123(Integer.parseInt(m.group(2), 16)))
    pos.time = utcDate(m.group(5).toInteger(), m.group(4).toInteger(), m.group(3).toInteger(),
            m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
    pos.set(Position.PREFIX_IO + 1, m.group(9))
    pos.set(Position.KEY_BATTERY, m.group(10).toDouble() / 10.0)
    pos.set(Position.KEY_POWER,   m.group(11).toDouble())
    pos.set(Position.PREFIX_ADC + 1, m.group(12))
    pos.set(Position.PREFIX_ADC + 2, m.group(13))
    pos.set(Position.PREFIX_TEMP + 1, m.group(14))
    pos.set(Position.PREFIX_TEMP + 2, m.group(15))
    pos.network = new Network(CellTower.from(
            0, 0, Integer.parseInt(m.group(16), 16), Integer.parseInt(m.group(17), 16)))
    pos.valid = (m.group(18) == "A")
    pos.set(Position.KEY_SATELLITES, m.group(19).toInteger())
    pos.course = m.group(20).toDouble()
    pos.speed  = m.group(21).toDouble()
    pos.set(Position.KEY_PDOP, m.group(22).toDouble())
    pos.set(Position.KEY_ODOMETER, m.group(23).toInteger() * 1000)
    pos.latitude  = coord(m.group(24), m.group(25), m.group(26))
    pos.longitude = coord(m.group(27), m.group(28), m.group(29))
    pos
}

def decodeFormatE2 = { String s, ctx ->
    def m = RE_E2.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.valid = true
    pos.time = utcDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger(),
            m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
    pos.longitude = m.group(8).toDouble()
    pos.latitude  = m.group(9).toDouble()
    pos.set(Position.KEY_DRIVER_UNIQUE_ID, m.group(10))
    pos
}

def decodeFormatE5 = { String s, ctx ->
    def m = RE_E5.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.valid = true
    pos.time = utcDate(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger(),
            m.group(5).toInteger(), m.group(6).toInteger(), m.group(7).toInteger())
    pos.longitude = m.group(8).toDouble()
    pos.latitude  = m.group(9).toDouble()
    pos.set(Position.KEY_ODOMETER, m.group(10).toLong())
    pos.set(Position.KEY_FUEL_USED, m.group(11).toInteger())
    pos.set(Position.KEY_FUEL_CONSUMPTION, m.group(12).toInteger())
    pos.set(Position.KEY_POWER, m.group(13).toInteger() / 1000.0)
    pos.set(Position.KEY_RPM, m.group(14).toInteger())
    pos.set(Position.KEY_OBD_SPEED, m.group(15).toInteger())
    // groups 16,17 = intakeFlow, intakePres (skipped)
    pos.set(Position.KEY_COOLANT_TEMP, m.group(18).toInteger())
    pos.set("intakeTemp", m.group(19).toInteger())
    pos.set(Position.KEY_ENGINE_LOAD, m.group(20).toInteger())
    pos.set(Position.KEY_THROTTLE, m.group(21).toInteger())
    pos.set(Position.KEY_FUEL, m.group(22).toInteger())
    pos
}

def decodeFormat4 = { String s, ctx ->
    int type = Integer.parseInt(s.substring(6, 8), 16)
    if (type == 0xE2) return decodeFormatE2(s, ctx)
    if (type == 0xE5) return decodeFormatE5(s, ctx)

    def m = RE4.matcher(s)
    if (!m.matches()) return null
    def session = ctx.session(m.group(1))
    if (!session) return null
    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.addAlarm(decodeAlarm4(type))

    long status = Long.parseLong(m.group(2), 16)
    pos.addAlarm(BitUtil.check(status, 31) ? ALARM_SOS             : null)
    pos.set(Position.KEY_IGNITION,         BitUtil.check(status, 30))
    pos.addAlarm(BitUtil.check(status, 29) ? ALARM_OVERSPEED       : null)
    pos.set(Position.KEY_CHARGE,           BitUtil.check(status, 28))
    pos.addAlarm(BitUtil.check(status, 27) ? ALARM_GEOFENCE_EXIT   : null)
    pos.addAlarm(BitUtil.check(status, 26) ? ALARM_GEOFENCE_ENTER  : null)
    pos.addAlarm(BitUtil.check(status, 25) ? ALARM_GPS_ANTENNA_CUT : null)
    pos.set(Position.PREFIX_OUT + 1, BitUtil.check(status, 23))
    pos.set(Position.PREFIX_OUT + 2, BitUtil.check(status, 22))
    pos.set(Position.PREFIX_OUT + 3, BitUtil.check(status, 21))
    pos.set(Position.KEY_STATUS, status)

    pos.time = utcDate(m.group(3).toInteger(), m.group(4).toInteger(), m.group(5).toInteger(),
            m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())

    if (m.group(9) != null) {
        pos.set(Position.KEY_BATTERY, m.group(9).toDouble() / 10.0)
        pos.set(Position.KEY_POWER,   m.group(10).toDouble())
    } else if (m.group(11) != null) {
        pos.set(Position.KEY_BATTERY, m.group(11).toDouble() / 100.0)
    }
    pos.set(Position.PREFIX_ADC + 1, m.group(12))
    if (m.group(13) != null) {
        pos.set(Position.PREFIX_ADC + 2, m.group(13))
        pos.set(Position.PREFIX_ADC + 3, m.group(14))
        pos.set(Position.PREFIX_ADC + 4, m.group(15))
    }
    if (m.group(16) != null) pos.set(Position.PREFIX_TEMP + 1, m.group(16))

    if (m.group(17) != null) {
        pos.set(Position.PREFIX_TEMP + 2, m.group(17))
        pos.valid = BitUtil.check(status, 12)  // bit 32-20
    } else {
        pos.valid = BitUtil.check(status, 14)  // bit 32-18
    }

    int lac = Integer.parseInt(m.group(18), 16)
    int cid = Integer.parseInt(m.group(19), 16)
    CellTower ct
    if (m.group(20) != null) {
        ct = CellTower.from(m.group(20).toInteger(), m.group(21).toInteger(), lac, cid)
    } else {
        ct = CellTower.from(0, 0, lac, cid)
    }
    pos.set(Position.KEY_SATELLITES, m.group(22).toInteger())
    ct.setSignalStrength(m.group(23).toInteger())
    pos.network = new Network(ct)

    pos.course = m.group(24).toDouble()
    pos.speed  = UnitsConverter.knotsFromKph(m.group(25).toDouble())
    pos.set(Position.KEY_HDOP, m.group(26).toDouble())
    pos.set(Position.KEY_ODOMETER, m.group(27).toInteger() * 1000)
    pos.latitude  = coord(m.group(28), m.group(29), m.group(30))
    pos.longitude = coord(m.group(31), m.group(32), m.group(33))
    pos
}

protocol("totem") {

    port 5007

    commands(
            TYPE_CUSTOM,
            TYPE_REBOOT_DEVICE,
            TYPE_FACTORY_RESET,
            TYPE_GET_VERSION,
            TYPE_POSITION_SINGLE,
            TYPE_ENGINE_STOP,
            TYPE_ENGINE_RESUME)

    variant("main") {

        frame scriptedFrame { fb ->
            if (fb.readableBytes() < 10) return null
            int start = fb.indexOf("$$")
            if (start < 0) return null
            int frameLen
            if (fb.getUByte(start + 2) == 0x30) {
                frameLen = Integer.parseInt(fb.ascii(start + 2, 4))
            } else {
                frameLen = Integer.parseInt(fb.ascii(start + 2, 2), 16)
            }
            if (start + frameLen > fb.readableBytes()) return null
            return frameResult(start + frameLen, fb.bytes(start, frameLen))
        }

        matches { msg -> true }

        decode { msg, ctx ->
            String sentence = (msg instanceof BufReader) ? msg.readString(msg.remaining()) : msg.toString()
            sentence = sentence.trim()

            def pos
            if (sentence.charAt(2) == '0') {
                pos = decodeFormat4(sentence, ctx)
            } else if (sentence.contains("\$GPRMC")) {
                pos = decodeFormat1(sentence, ctx)
            } else {
                int idx = sentence.indexOf('|')
                if (idx >= 0 && sentence.indexOf('|', idx + 1) >= 0) {
                    pos = decodeFormat2(sentence, ctx)
                } else {
                    pos = decodeFormat3(sentence, ctx)
                }
            }

            if (sentence.charAt(2) == '0') {
                String serial = sentence.substring(sentence.length() - 6, sentence.length() - 2)
                String base = "$$0014AA" + serial
                ctx.ack(base + String.format("%02X", Checksum.xor(base)))
            } else {
                ctx.ack("ACK OK\r\n")
            }

            pos
        }

        encode { command, ctx ->
            String pwd = command.getString(Command.KEY_DEVICE_PASSWORD) ?: "000000"
            String content = null
            switch (command.type) {
                case TYPE_CUSTOM:         content = "${pwd},${command.getString(Command.KEY_DATA)}"; break
                case TYPE_REBOOT_DEVICE:  content = "${pwd},006"; break
                case TYPE_FACTORY_RESET:  content = "${pwd},007"; break
                case TYPE_GET_VERSION:    content = "${pwd},056"; break
                case TYPE_POSITION_SINGLE: content = "${pwd},012"; break
                case TYPE_ENGINE_STOP:    content = "${pwd},025,C,1"; break
                case TYPE_ENGINE_RESUME:  content = "${pwd},025,C,0"; break
                default: return null
            }
            String pre = String.format('$$%04dCF%s', 10 + content.getBytes("US-ASCII").length, content)
            return pre + String.format('%02X', Checksum.xor(pre))
        }
    }
}
