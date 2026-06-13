// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/**
 * GpsGate Server Protocol driver.
 *
 * Source documentation:
 *   docs/driver-source-docs/traccar-protocols/gpsgate/
 *
 * Supports FRLIN authentication, FRVER version requests, GPRMC position
 * reports, and FRCMD position reports. TCP frames are terminated by NUL, LF,
 * or CRLF as in the archived Java frame decoder.
 */

import org.traccar.helper.DateBuilder
import org.traccar.model.Position

import java.util.regex.Pattern

def gprmcPattern = Pattern.compile(
        '^\\$GPRMC,(\\d{2})(\\d{2})(\\d{2})\\.?\\d*,([AV]),(\\d{2})(\\d{2}\\.\\d+),([NS]),'
                + '(\\d{3})(\\d{2}\\.\\d+),([EW]),(\\d+\\.\\d+)?,(\\d+\\.\\d+)?,'
                + '(\\d{2})(\\d{2})(\\d{2}).*')

def frcmdPattern = Pattern.compile(
        '^\\$FRCMD,(\\d+),[^,]*,[^,]*,(\\d+)(\\d{2}\\.\\d+),([NS]),(\\d+)(\\d{2}\\.\\d+),'
                + '([EW]),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*)?,'
                + '(\\d{2})(\\d{2})(\\d{2}),(\\d{2})(\\d{2})(\\d{2})\\.?\\d*,([01]).*')

def coordinate = { String deg, String min, String hemi ->
    double value = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -value : value
}

def withNmea = { String body -> "\$${body}${nmea(body)}\r\n" }

def decodeSentence
decodeSentence = { Object msg, ctx ->
    String sentence = msg instanceof String ? msg : msg.readString(msg.remaining())
    sentence = sentence.trim()

    if (sentence.startsWith('$FRLIN,')) {
        def parts = sentence.split(',', -1)
        if (parts.length >= 4 && parts[2] && ctx.session(parts[2])) {
            ctx.ack(withNmea('FRSES,driver'))
        } else {
            ctx.ack(withNmea('FRERR,AuthError,Unknown device'))
        }
        return null
    }

    if (sentence.startsWith('$FRVER,')) {
        ctx.ack(withNmea('FRVER,1,0,GpsGate Server 1.0'))
        return null
    }

    if (sentence.startsWith('$GPRMC,')) {
        def session = ctx.session()
        if (!session) return null

        def m = gprmcPattern.matcher(sentence)
        if (!m.matches()) return null

        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        pos.valid = m.group(4) == 'A'
        pos.latitude = coordinate(m.group(5), m.group(6), m.group(7))
        pos.longitude = coordinate(m.group(8), m.group(9), m.group(10))
        pos.speed = m.group(11) ? m.group(11).toDouble() : 0
        pos.course = m.group(12) ? m.group(12).toDouble() : 0
        pos.time = new DateBuilder()
                .setTime(m.group(1).toInteger(), m.group(2).toInteger(), m.group(3).toInteger())
                .setDateReverse(m.group(13).toInteger(), m.group(14).toInteger(), m.group(15).toInteger())
                .getDate()
        return pos
    }

    if (sentence.startsWith('$FRCMD,')) {
        def m = frcmdPattern.matcher(sentence)
        if (!m.matches()) return null

        def session = ctx.session(m.group(1))
        if (!session) return null

        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        pos.latitude = coordinate(m.group(2), m.group(3), m.group(4))
        pos.longitude = coordinate(m.group(5), m.group(6), m.group(7))
        pos.altitude = m.group(8).toDouble()
        pos.speed = m.group(9).toDouble()
        pos.course = m.group(10) ? m.group(10).toDouble() : 0
        pos.time = new DateBuilder()
                .setDateReverse(m.group(11).toInteger(), m.group(12).toInteger(), m.group(13).toInteger())
                .setTime(m.group(14).toInteger(), m.group(15).toInteger(), m.group(16).toInteger())
                .getDate()
        pos.valid = m.group(17) == '1'
        return pos
    }

    null
}

protocol("gpsgate") {

    port 5026

    variant("main") {

        maxFrameLength 2048
        frame '$' as char, { fb ->
            int nul = fb.indexOf(0)
            int lf = fb.indexOf(10)
            int end = [nul, lf].findAll { it >= 0 }.min()
            if (end == null) return null
            int payloadEnd = end > 0 && fb.getUByte(end - 1) == 13 ? end - 1 : end
            return frameResult(end + 1, fb.bytes(0, payloadEnd))
        }

        matches { msg -> msg instanceof String ? msg.startsWith('$') : true }

        decode decodeSentence
    }
}
