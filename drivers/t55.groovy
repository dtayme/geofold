// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * T55 GPS tracker driver.
 *
 * Source documentation:
 *   archived-protocols/t55/ (Java reference)
 *
 * Text line-framed protocol. Dispatches on message type after stripping
 * any device-ID prefix that precedes the first '$'.
 */

import org.traccar.helper.UnitsConverter
import org.traccar.model.Position

import java.util.Calendar
import java.util.TimeZone

// Parse DDDMM.MMMM coordinate (deg string, min.decimal string, hemisphere)
def coord = { String d, String m, String h ->
    double v = d.toInteger() + m.toDouble() / 60.0
    (h == "S" || h == "W") ? -v : v
}

// Build UTC Date from components (year may be 2- or 4-digit)
def utcDate = { int year, int month, int day, int h, int min, int s ->
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    c.set(year < 100 ? 2000 + year : year, month - 1, day, h, min, s)
    c.set(Calendar.MILLISECOND, 0)
    c.getTime()
}

// Build UTC Date from HMS only, using today's date
def utcTime = { int h, int min, int s ->
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    c.set(Calendar.HOUR_OF_DAY, h)
    c.set(Calendar.MINUTE, min)
    c.set(Calendar.SECOND, s)
    c.set(Calendar.MILLISECOND, 0)
    c.getTime()
}

// Patterns (compiled once)
// GPRMC: standard + optional extended fields (sats, IMEI, ignition, fuel, battery, params)
def GPRMC_RE = ~/\$G[PLN]RMC,(\d{2})(\d{2})(\d{2})\.?\d*,([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{2,3})(\d{2}\.\d+),([EW]),(\d+\.?\d*)?,(\d+\.?\d*)?,(\d{2})(\d{2})(\d{2}),[^*]*(?:\*[^\s,]+,(\d+),(\d+),([01]),(\d+)(?:,(\d+))?)?((?:,\d+)+)?.*/

def GPGGA_RE = ~/\$G[PLN]GGA,(\d{2})(\d{2})(\d{2})\.?\d*,(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),(\d+),(\d+),(\d+\.?\d*),(-?\d+\.?\d*).*/

def GPGLL_RE = ~/\$G[PLN]GLL,(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),(\d{2})(\d{2})(\d{2})\.?\d*,([AV]).*/

def GPRMA_RE = ~/\$GPRMA,([AV]),(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW]),(\d+\.?\d*)?,(\d+\.?\d*)?.*/

def TRCCR_RE = ~/\$TRCCR,(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\.?\d*,([AV]),(-?\d+\.\d+),(-?\d+\.\d+),(\d+\.\d+),(\d+\.\d+),(-?\d+\.\d+),(\d+\.?\d*).*/

def GPIOP_RE = ~/\$GPIOP,[01]{8},[01]{8},\d+\.\d+,\d+\.\d+,\d+\.\d+,\d+\.\d+,(\d+\.\d+),(\d+\.\d+).*/

def QZE_RE = ~/QZE,(\d{15}),(\d+),(\d{2})(\d{2})(\d{4}),(\d{2})(\d{2})(\d{2}),(-?\d+\.\d+),(-?\d+\.\d+),(\d+),(\d+),([AV]),([01]).*/

// PUBX: index, time, lat, lon, alt, status, hAcc, vAcc, speed, course, vVel, cAge, hdop, vdop, tdop, sats, deviceId, num, checksum
def PUBX_RE = ~/\$PUBX,(\d+),(\d{2})(\d{2})(\d{2})\.\d+,(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),(-?\d+\.\d+),(\S{2}),(\d+\.\d+),\S+,(\d+\.\d+),(\d+\.\d+),\S+,[^,]*,(\d+\.\d+),(\d+\.\d+),\d+\.\d+,(\d+),(\d+),\d+\*\S+.*/

def GPTXT_RE = ~/\$GPTXT,NET,(\d+),([^,]+),(-\d+),(\d+) (\d+)\*\S+.*/

// WMCS: imei, optional alarm, validity, optional date (ddmmyy), optional time, optional sats, lat, lon, heading, speed, distance
def WMCS_RE = ~/(?s)\$ID,(\d+),.*?(?:ALARM,(0x[0-9a-fA-F]+),)?.*?GPSE[XHTD],([AV])(?:,D,(\d{2})(\d{2})(\d{2}))?(?:,T,(\d{2})(\d{2})(\d{2}))?(?:,S,(\d+):(\d+))?(?:,La,(-?\d+\.\d+),([NS]))?(?:,Lo,(-?\d+\.\d+),([EW]))?(?:,H,(\d+\.\d+))?(?:,V,(\d+\.\d+))?(?:,DD,(\d+))?.*/

def decodeGprmc = { String sentence, session, ctx ->
    def m = GPRMC_RE.matcher(sentence)
    if (!m.matches()) return null

    int hh = m.group(1).toInteger()
    int mm = m.group(2).toInteger()
    int ss = m.group(3).toInteger()
    boolean valid = (m.group(4) == "A")
    double lat = coord(m.group(5), m.group(6), m.group(7))
    double lon = coord(m.group(8), m.group(9), m.group(10))
    double speed = m.group(11) ? m.group(11).toDouble() : 0.0
    double course = m.group(12) ? m.group(12).toDouble() : 0.0
    int dd = m.group(13).toInteger()
    int mo = m.group(14).toInteger()
    int yy = m.group(15).toInteger()
    def date = utcDate(yy, mo, dd, hh, mm, ss)

    // Extended fields: satellites, IMEI, ignition, fuel, [battery]
    if (m.group(16)) {
        def newSession = ctx.session(m.group(17))
        if (!newSession) return null
        session = newSession
    }

    if (!session) {
        // No session yet — save position for $GPFID
        def pos = ctx.newPosition()
        pos.valid = valid
        pos.latitude = lat
        pos.longitude = lon
        pos.speed = speed
        pos.course = course
        pos.time = date
        ctx.store()["savedPos"] = pos
        return null
    }

    // ACK for TCP only (configurable)
    if (!ctx.isUdp() && ctx.configBoolean("ack", false)) {
        ctx.ack("OK1\r\n")
    }

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.valid = valid
    pos.latitude = lat
    pos.longitude = lon
    pos.speed = speed
    pos.course = course
    pos.time = date

    if (m.group(16)) {
        pos.set(Position.KEY_SATELLITES, m.group(16).toInteger())
        pos.set(Position.KEY_IGNITION, m.group(18) == "1")
        pos.set(Position.KEY_FUEL, m.group(19).toInteger())
        if (m.group(20)) pos.set(Position.KEY_BATTERY, m.group(20).toInteger())
        if (m.group(21)) {
            String[] params = m.group(21).split(",")
            for (int i = 1; i < params.length; i++) {
                pos.set(Position.PREFIX_IO + i, params[i])
            }
        }
    }

    pos
}

def decodeGpgga = { String sentence, session, ctx ->
    def m = GPGGA_RE.matcher(sentence)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.time = utcTime(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
    pos.latitude = coord(m.group(4), m.group(5), m.group(6))
    pos.longitude = coord(m.group(7), m.group(8), m.group(9))
    pos.valid = m.group(10).toInteger() > 0
    pos.set(Position.KEY_SATELLITES, m.group(11).toInteger())
    pos.set(Position.KEY_HDOP, m.group(12).toDouble())
    pos.altitude = m.group(13).toDouble()
    pos
}

def decodeGpgll = { String sentence, session, ctx ->
    def m = GPGLL_RE.matcher(sentence)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.latitude = coord(m.group(1), m.group(2), m.group(3))
    pos.longitude = coord(m.group(4), m.group(5), m.group(6))
    pos.time = utcTime(m.group(7).toInteger(), m.group(8).toInteger(), m.group(9).toInteger())
    pos.valid = (m.group(10) == "A")
    pos
}

def decodeGprma = { String sentence, session, ctx ->
    def m = GPRMA_RE.matcher(sentence)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.time = new Date()
    pos.valid = (m.group(1) == "A")
    pos.latitude = coord(m.group(2), m.group(3), m.group(4))
    pos.longitude = coord(m.group(5), m.group(6), m.group(7))
    pos.speed = m.group(8) ? m.group(8).toDouble() : 0.0
    pos.course = m.group(9) ? m.group(9).toDouble() : 0.0
    pos
}

def decodeTrccr = { String sentence, session, ctx ->
    def m = TRCCR_RE.matcher(sentence)
    if (!m.matches()) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.time = utcDate(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger(),
            m.group(4).toInteger(), m.group(5).toInteger(), m.group(6).toInteger())
    pos.valid = (m.group(7) == "A")
    pos.latitude = m.group(8).toDouble()
    pos.longitude = m.group(9).toDouble()
    pos.speed = m.group(10).toDouble()
    pos.course = m.group(11).toDouble()
    pos.altitude = m.group(12).toDouble()
    pos.set(Position.KEY_BATTERY, m.group(13).toDouble())
    pos
}

def decodeGpiop = { String sentence, session, ctx ->
    def m = GPIOP_RE.matcher(sentence)
    if (!m.matches()) return null
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    pos.set(Position.KEY_POWER, m.group(1).toDouble())
    pos.set(Position.KEY_BATTERY, m.group(2).toDouble())
    pos
}

def decodeQze = { String sentence, ctx ->
    def m = QZE_RE.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_EVENT, m.group(2).toInteger())
    // Date is ddmmyyyy (despite Java comment saying mmddyyyy)
    pos.time = utcDate(m.group(5).toInteger(), m.group(4).toInteger(), m.group(3).toInteger(),
            m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())
    pos.latitude = m.group(9).toDouble()
    pos.longitude = m.group(10).toDouble()
    pos.speed = UnitsConverter.knotsFromKph(m.group(11).toInteger())
    pos.course = m.group(12).toInteger()
    pos.valid = (m.group(13) == "A")
    pos.set(Position.KEY_IGNITION, m.group(14).toInteger() > 0)
    pos
}

def decodePubx = { String sentence, ctx ->
    def m = PUBX_RE.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(20))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    pos.set(Position.KEY_INDEX, m.group(1).toInteger())
    pos.time = utcTime(m.group(2).toInteger(), m.group(3).toInteger(), m.group(4).toInteger())
    pos.latitude = coord(m.group(5), m.group(6), m.group(7))
    pos.longitude = coord(m.group(8), m.group(9), m.group(10))
    pos.altitude = m.group(11).toDouble()
    pos.valid = !(m.group(12) == "NF")
    pos.accuracy = m.group(13).toDouble()
    pos.speed = UnitsConverter.knotsFromKph(m.group(14).toDouble())
    pos.course = m.group(15).toDouble()
    pos.set(Position.KEY_HDOP, m.group(16).toDouble())
    pos.set(Position.KEY_VDOP, m.group(17).toDouble())
    pos.set(Position.KEY_SATELLITES, m.group(18).toInteger())
    pos
}

def decodeGptxt = { String sentence, ctx ->
    def m = GPTXT_RE.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId
    ctx.lastLocation(pos, null)
    pos.set(Position.KEY_OPERATOR, m.group(2))
    pos.set(Position.KEY_RSSI, m.group(3).toInteger())
    pos.set("mcc", m.group(4).toInteger())
    pos.set("mnc", m.group(5).toInteger())
    pos
}

def decodeWmcs = { String sentence, ctx ->
    def m = WMCS_RE.matcher(sentence)
    if (!m.matches()) return null

    def session = ctx.session(m.group(1))
    if (!session) return null

    def pos = ctx.newPosition()
    pos.deviceId = session.deviceId

    if (m.group(2)) {
        pos.set(Position.KEY_ALARM, m.group(2))
    }

    pos.valid = (m.group(3) == "A")

    if (m.group(4)) {
        pos.time = utcDate(2000 + m.group(6).toInteger(), m.group(5).toInteger(), m.group(4).toInteger(),
                m.group(7).toInteger(), m.group(8).toInteger(), m.group(9).toInteger())
    } else {
        ctx.lastLocation(pos, null)
    }

    if (m.group(10)) {
        pos.set(Position.KEY_SATELLITES_VISIBLE, m.group(10).toInteger())
        pos.set(Position.KEY_SATELLITES, m.group(11).toInteger())
    }
    if (m.group(12)) {
        pos.latitude = m.group(12).toDouble() * (m.group(13) == "S" ? -1.0 : 1.0)
    }
    if (m.group(14)) {
        pos.longitude = m.group(14).toDouble() * (m.group(15) == "W" ? -1.0 : 1.0)
    }
    if (m.group(16)) pos.course = m.group(16).toDouble()
    if (m.group(17)) pos.speed = UnitsConverter.knotsFromKph(m.group(17).toDouble())
    if (m.group(18)) pos.set(Position.KEY_TOTAL_DISTANCE, m.group(18).toDouble())

    pos
}

protocol("t55") {

    port 5005

    variant("main") {

        frame readLine()

        decode { msg, ctx ->
            String sentence = msg.trim()
            def session = null

            // Strip device-ID prefix that precedes first '$'
            if (!sentence.startsWith("\$") && sentence.contains("\$")) {
                int idx = sentence.indexOf("\$")
                String prefix = sentence.substring(0, idx)
                if (prefix.endsWith(",")) {
                    prefix = prefix.substring(0, prefix.length() - 1)
                } else if (prefix.contains("/")) {
                    prefix = prefix.substring(prefix.indexOf('/') + 1, prefix.length() - 1)
                }
                session = ctx.session(prefix.trim())
                sentence = sentence.substring(idx)
            } else {
                session = ctx.session()
            }

            // ID registration messages
            if (sentence.startsWith("\$PGID,")) {
                ctx.session(sentence.substring(6, sentence.length() - 3))
                return null
            } else if (sentence.startsWith("\$DEVID,")) {
                ctx.session(sentence.substring(7, sentence.lastIndexOf("*")))
                return null
            } else if (sentence.startsWith("\$PCPTI,")) {
                ctx.session(sentence.substring(7, sentence.indexOf(",", 7)))
                return null
            } else if (sentence.startsWith("IMEI ")) {
                ctx.session(sentence.substring(5))
                return null
            } else if (sentence.startsWith("\$IMEI")) {
                ctx.session(sentence.substring(6))
                return null
            } else if (sentence.startsWith("\$PSIWMDID,")) {
                ctx.session(sentence.substring(10, sentence.lastIndexOf("*")))
                return null
            } else if (sentence.startsWith("\$CONNECT,")) {
                ctx.session(sentence.substring(9, sentence.indexOf(",", 9)))
                return null
            } else if (sentence.startsWith("\$GPFID,")) {
                def newSession = ctx.session(sentence.substring(7))
                def savedPos = ctx.store()["savedPos"]
                if (newSession && savedPos != null) {
                    savedPos.deviceId = newSession.deviceId
                    ctx.store()["savedPos"] = null
                    return savedPos
                }
                return null
            } else if (sentence ==~ /[0-9A-F]+/) {
                ctx.session(sentence)
                return null
            }

            // Dispatch by message type
            if (sentence.length() >= 6 && sentence.substring(3, 6) == "RMC") {
                return decodeGprmc(sentence, session, ctx)
            } else if (sentence.length() >= 6 && sentence.substring(3, 6) == "GGA" && session) {
                return decodeGpgga(sentence, session, ctx)
            } else if (sentence.length() >= 6 && sentence.substring(3, 6) == "GLL" && session) {
                return decodeGpgll(sentence, session, ctx)
            } else if (sentence.startsWith("\$GPRMA") && session) {
                return decodeGprma(sentence, session, ctx)
            } else if (sentence.startsWith("\$TRCCR") && session) {
                return decodeTrccr(sentence, session, ctx)
            } else if (sentence.startsWith("\$GPIOP")) {
                return decodeGpiop(sentence, session, ctx)
            } else if (sentence.startsWith("QZE,")) {
                return decodeQze(sentence, ctx)
            } else if (sentence.startsWith("\$PUBX,")) {
                return decodePubx(sentence, ctx)
            } else if (sentence.startsWith("\$GPTXT,")) {
                return decodeGptxt(sentence, ctx)
            } else if (sentence.startsWith("\$ID,")) {
                return decodeWmcs(sentence, ctx)
            }

            null
        }
    }
}
