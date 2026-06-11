/**
 * TLT2H GPS tracker driver.
 *
 * Wire format — each TCP frame ends with ##\r\n:
 *
 *   #<imei>#<user>#<pass>[#<door>#<adc>#<power>#<battery>#<temp>]#<status>#<count>\r\n
 *   #[<voltage>][#<mcc>,<mnc>,<lac>,<cid>]$GPRMC,<hhmmss>.<ms>,<AV>,<lat>,<NS>,<lon>,<EW>,<speed>,<course>,<ddmmyy>,...\r\n
 *   [... more position records ...]
 *   ##\r\n
 *
 * Multiple position records per frame are emitted individually.
 * Voltage field is 2 digits (tenths of a volt) or 4 digits (millivolts).
 */

import org.traccar.helper.DateBuilder
import org.traccar.helper.UnitsConverter
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.Position
import org.traccar.model.WifiAccessPoint

import java.util.regex.Pattern

def HEADER = Pattern.compile(
    /^#(\d+)#[^#]*#\d*(?:#([01])#(\d+)#(\d+)#(\d+)#(\d+))?#([^#]+)#\d+$/)

def GPRMC = Pattern.compile(
    /^#(?:(\d{2}|\d{4})|[0-9a-fA-F]*)(?:#(\d+),(\d+),([0-9a-fA-F]+),([0-9a-fA-F]+))?\$GPRMC,(?:(\d{2})(\d{2})(\d{2})\.\d+)?,([AVL]),(?:(\d+)(\d{2}\.\d+),([NS]),(\d+)(\d{2}\.\d+),([EW]),([\d.]*)?,([\d.]*)?(?:,(\d{2})(\d{2})(\d{2}))?)?/)

def WIFI = Pattern.compile(
    /^#(?:(\d{2}|\d{4})|[0-9a-fA-F]+)#?(?:(\d+),(\d+),([0-9a-fA-F]+),([0-9a-fA-F]+))?\$WIFI,(\d{2})(\d{2})(\d{2})\.\d+,[AVL],(.*),(\d{2})(\d{2})(\d{2})\*[0-9a-fA-F]{2}$/)

def voltage = { raw ->
    if (!raw) return null
    int v = raw.toInteger()
    v > 100 ? v / 1000.0 : v / 10.0
}

def nmea = { deg, min, hemi ->
    double v = deg.toInteger() + min.toDouble() / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

protocol("tlt2h") {

    port 5001

    variant("main") {

        // Batch-upload frame: one header line + N position records, each ~100 bytes.
        // 16 KB allows ~150 records, covering typical offline-buffering scenarios.
        maxFrameLength 16384
        // Frame ends with ##\r\n; the \r\n after ## is skipped by the frame decoder
        frame '#' as char, readUntil('##')

        matches { msg -> msg.startsWith('#') && msg =~ /#[A-Z]/ }

        decode { msg, ctx ->
            def lines = msg.split(/\r\n/)
            if (lines.length < 1) return null

            // --- Parse header line ---
            def hm = HEADER.matcher(lines[0])
            if (!hm.find()) return null

            def imei = hm.group(1)
            def session = ctx.session(imei)
            if (!session) return null

            // Optional sensor fields
            Boolean door    = hm.group(2) != null ? hm.group(2) == '1' : null
            Double  adc     = hm.group(3) != null ? hm.group(3).toInteger() / 10.0 : null
            Double  power   = hm.group(4) != null ? hm.group(4).toInteger() / 10.0 : null
            Double  battery = hm.group(5) != null ? hm.group(5).toInteger() / 10.0 : null
            Double  temp    = hm.group(6) != null ? hm.group(6).toInteger() / 10.0 : null
            String  status  = hm.group(7)

            // --- Parse each position record ---
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim()
                if (line.isEmpty()) continue

                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                if (line.contains('$GPRMC')) {
                    def m = GPRMC.matcher(line)
                    if (!m.find()) continue

                    def volt = voltage(m.group(1))
                    if (volt != null) pos.set(Position.KEY_BATTERY, volt)

                    if (m.group(2)) {
                        def net = new Network()
                        net.addCellTower(CellTower.from(
                            m.group(2).toInteger(), m.group(3).toInteger(),
                            Integer.parseInt(m.group(4), 16), Integer.parseInt(m.group(5), 16)))
                        pos.network = net
                    }

                    def db = new DateBuilder()
                    if (m.group(6)) db.setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())

                    pos.valid = m.group(9) == 'A'

                    if (m.group(10)) {
                        pos.latitude  = nmea(m.group(10), m.group(11), m.group(12))
                        pos.longitude = nmea(m.group(13), m.group(14), m.group(15))
                        pos.speed     = m.group(16) ? UnitsConverter.knotsFromKph(m.group(16).toDouble()) : 0
                        pos.course    = m.group(17) ? m.group(17).toDouble() : 0
                        if (m.group(18)) {
                            db.setDateReverse(m.group(18).toInteger(), m.group(19).toInteger(), m.group(20).toInteger())
                            pos.time = db.getDate()
                        }
                    } else {
                        ctx.lastLocation(pos)
                    }

                } else if (line.contains('$WIFI')) {
                    def m = WIFI.matcher(line)
                    if (!m.find()) continue

                    def volt = voltage(m.group(1))
                    if (volt != null) pos.set(Position.KEY_BATTERY, volt)

                    def net = new Network()
                    if (m.group(2)) {
                        net.addCellTower(CellTower.from(
                            m.group(2).toInteger(), m.group(3).toInteger(),
                            Integer.parseInt(m.group(4), 16), Integer.parseInt(m.group(5), 16)))
                    }

                    def db = new DateBuilder()
                            .setTime(m.group(6).toInteger(), m.group(7).toInteger(), m.group(8).toInteger())

                    // Wifi APs: alternating rssi,mac pairs
                    def values = m.group(9).split(',')
                    for (int j = 0; j + 1 < values.length; j += 2) {
                        try {
                            def mac = values[j + 1].replaceAll(/(..)(?!$)/, '$1:')
                            net.addWifiAccessPoint(WifiAccessPoint.from(mac, values[j].toInteger()))
                        } catch (ignored) {}
                    }
                    pos.network = net

                    db.setDateReverse(m.group(10).toInteger(), m.group(11).toInteger(), m.group(12).toInteger())
                    ctx.lastLocation(pos)
                    pos.fixTime = db.getDate()

                } else {
                    ctx.lastLocation(pos)
                }

                // Apply header-level fields to every position in the batch
                if (door    != null) pos.set(Position.KEY_DOOR,         door)
                if (adc     != null) pos.set(Position.PREFIX_ADC + '1', adc)
                if (power   != null) pos.set(Position.KEY_POWER,        power)
                if (battery != null) pos.set(Position.KEY_BATTERY,      battery)
                if (temp    != null) pos.set(Position.PREFIX_TEMP + '1', temp)

                // Status string → alarm / ignition
                switch (status) {
                    case 'AUTOSTART': case 'AUTO':     pos.set(Position.KEY_IGNITION, true);  break
                    case 'AUTOSTOP':  case 'AUTOLOW':  pos.set(Position.KEY_IGNITION, false); break
                    case 'TOWED':     pos.addAlarm(ALARM_TOW);           break
                    case 'SHAKE':     pos.addAlarm(ALARM_VIBRATION);     break
                    case 'SOS':       pos.addAlarm(ALARM_SOS);           break
                    case 'DEF':       pos.addAlarm(ALARM_POWER_CUT);     break
                    case 'BLP':       pos.addAlarm(ALARM_LOW_BATTERY);   break
                    case 'CLP':       pos.addAlarm(ALARM_LOW_POWER);     break
                    case 'OS':        pos.addAlarm(ALARM_GEOFENCE_EXIT); break
                    case 'RS':        pos.addAlarm(ALARM_GEOFENCE_ENTER);break
                    case 'OVERSPEED': pos.addAlarm(ALARM_OVERSPEED);     break
                }

                ctx.emit(pos)
            }

            return null  // all positions delivered via emit()
        }
    }
}
