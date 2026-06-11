# Driver Development Guide

Drivers are Groovy scripts that teach the server how to speak a specific GPS tracker
protocol. Dropping a `.groovy` file into the `drivers/` directory is all that is
needed — no Java, no recompile, no restart.

## Quick-start

```
traccar/
  drivers/
    mydevice.groovy   ← create this file
```

The server loads every `.groovy` file in the `drivers/` directory at startup and
watches for changes. Saving a file hot-reloads that driver without disrupting any
other connections.

---

## Script structure

Every driver script calls `protocol()` exactly once at the top level.

```groovy
protocol("mydevice") {

    port 5200           // default port (user may override in traccar.xml)

    variant("main") {
        frame '*' as char, readUntil('#')   // framing
        matches { msg -> msg.startsWith("*") }

        alarms {
            "SOS"    >> ALARM_SOS
            "LOW_V"  >> ALARM_LOW_BATTERY
        }

        decode { msg, ctx ->
            // parse msg, return a Position or null
        }

        encode { cmd, ctx ->
            // return a String (or byte[]) to send, or null
        }
    }
}
```

A protocol can contain multiple variants (one per wire format). The first variant
whose `matches` closure returns `true` handles the message.

---

## Framing

Framing tells the server how to extract one complete message from the TCP byte stream.
UDP is already packetized and ignores the frame declaration.

### Text framing

The `decode` closure receives a `String`.

```groovy
// Read until a single-byte terminator; strip the terminator from the output.
frame readUntil('#')

// Read until a multi-byte terminator.
frame readUntil('##')

// Read until newline (\n); strip trailing \r if present.
frame readLine()

// With an ASCII first-byte hint: the frame decoder picks this variant's framing
// when the first byte of the buffer matches the hint.
frame '*' as char, readUntil('#')   // first byte '*' → read until '#'
frame '#' as char, readUntil('##')  // first byte '#' → read until '##'
```

### Binary framing

The `decode` closure receives a [`BufReader`](#bufreader-api) instead of a `String`.
Binary variants **must** have a first-byte hint so the frame decoder can identify
them before it reads the full frame.

```groovy
// Read exactly N bytes per frame (fixed-size protocol).
frame 0x24 as byte, readFixed(32)

// Read a length field embedded in the header, then that many more bytes.
//   readLengthField(offset, fieldWidth)
//   Total frame = offset + fieldWidth + fieldValue
frame 0x78 as byte, readLengthField(2, 2)

// Add an adjustment for bytes after the body (e.g. a checksum not counted
// in the length field).
//   readLengthField(offset, fieldWidth, adjustment)
//   Total frame = offset + fieldWidth + fieldValue + adjustment
frame 0x78 as byte, readLengthField(2, 2, 1)   // 2-byte header, 2-byte length, body, 1-byte checksum
```

`fieldWidth` must be 1, 2, or 4 bytes.

### Byte hints and fallback

The `frameByteHint` allows multiple variants on the same port — the decoder checks
the first byte before it has the full message. A variant without a hint is the
fallback for any byte that has no explicit match.

```groovy
variant("binary") {
    frame 0x78 as byte, readLengthField(2, 2, 1)   // binary frames start 0x78
    ...
}
variant("text") {
    frame readLine()                                 // fallback for everything else
    matches { msg -> msg.startsWith("$TRACK,") }
    ...
}
```

---

## `matches` closure

Only used for **text** variants. Receives the fully-extracted message string.
Return `true` if this variant handles it. Binary variants are already selected
by their `frameByteHint` and do not need a `matches` closure.

```groovy
matches { msg -> msg.startsWith("*HQ,") }
matches { msg -> msg.startsWith("#") && msg.contains("#MT") }
matches { msg -> msg =~ /^\$TRACK,/ }   // Groovy regex match
```

---

## `model` closure

Optional. Extracts a device-model string from the message. The result is passed to
model-aware alarm closures and is available in the decode closure via `ctx.model(msg)`.

```groovy
model { msg -> msg.split(",")[3] }          // e.g. "GT06", "GT300"
model { msg -> msg.split("#")[2] }          // e.g. "MT700", "MT600"
```

---

## `alarms` block

Maps device event codes to Traccar alarm constants. The `>>` operator adds a mapping.

```groovy
alarms {
    // Simple: event string -> alarm constant
    "SOS"       >> ALARM_SOS
    "TOWED"     >> ALARM_TOW
    "VIBRATION" >> ALARM_VIBRATION
    "LOW_BAT"   >> ALARM_LOW_BATTERY

    // Model-aware: closure receives the model string, returns the alarm constant (or null)
    "DEF" >> { model -> model?.startsWith("MT700") ? ALARM_REMOVING : ALARM_POWER_CUT }
    "HT"  >> { model -> model?.startsWith("MT700") ? null            : ALARM_TEMPERATURE }
}
```

Resolve an alarm inside the `decode` closure:

```groovy
pos.addAlarm(ctx.alarm(eventCode))              // no model
pos.addAlarm(ctx.alarm(eventCode, ctx.model(msg)))  // model-aware
```

---

## `decode` closure

For **text** variants: receives `(String msg, DecodeContext ctx)`.
For **binary** variants: receives `(BufReader buf, DecodeContext ctx)`.

Must return a `Position` object or `null`.

### DecodeContext API

| Method | Description |
|---|---|
| `ctx.session(uniqueId)` | Gets/creates a device session for the given IMEI. Returns `null` if the device is unknown — always guard against this. |
| `ctx.newPosition()` | Creates a new `Position` pre-tagged with the protocol name. |
| `ctx.lastLocation(pos)` | Fills in the last known GPS fix when the current message has no coordinates. |
| `ctx.ack(string)` | Sends a raw **text** response back to the device on the current channel. |
| `ctx.ack(byte[])` | Sends a raw **binary** response back to the device. |
| `ctx.emit(pos)` | Accumulates a position for **batch-upload** protocols where one frame contains multiple fixes. All emitted positions are returned as a list. Call instead of `return pos` for each record, then `return null` at the end. |
| `ctx.alarm(event)` | Resolves an event code using this variant's alarm map. |
| `ctx.alarm(event, model)` | Resolves an event code with a model string for model-aware mappings. |
| `ctx.model(msg)` | Runs this variant's `model` closure against `msg`. |
| `ctx.deviceAttrs(session)` | Returns a [`DeviceAttrs`](#per-device-lookups) for the session's device. |

### Setting Position fields

```groovy
decode { msg, ctx ->
    def session = ctx.session(imei)
    if (!session) return null              // unknown device

    def pos = ctx.newPosition()
    pos.deviceId  = session.deviceId      // required

    // Time
    pos.time      = new Date()            // or use DateBuilder (see below)

    // Coordinates
    pos.valid     = true                  // false = no GPS fix
    pos.latitude  = 36.5000              // decimal degrees, negative = S
    pos.longitude = -97.0000             // decimal degrees, negative = W
    pos.speed     = 0.0                  // knots
    pos.course    = 180.0                // degrees 0-360

    // Optional fields
    pos.altitude  = 350.0               // metres
    pos.set(Position.KEY_BATTERY,  3.7) // volts
    pos.set(Position.KEY_IGNITION, true)
    pos.set(Position.KEY_SATELLITES, 8)
    pos.addAlarm(ALARM_SOS)             // add an active alarm

    // No GPS fix — copy last known position
    ctx.lastLocation(pos)               // sets lat/lon from history

    return pos
}
```

### DateBuilder

`DateBuilder` from `org.traccar.helper` assembles a `Date` from parts and handles
century roll-overs correctly.

```groovy
import org.traccar.helper.DateBuilder

def db = new DateBuilder()
db.setTime(14, 30, 22)          // HH mm ss
db.setDateReverse(11, 6, 24)    // dd mm yy (reversed because GPRMC is ddmmyy)
pos.time = db.getDate()
```

### Cell towers and Wi-Fi

```groovy
import org.traccar.model.CellTower
import org.traccar.model.Network
import org.traccar.model.WifiAccessPoint

def network = new Network()
network.addCellTower(CellTower.from(mcc, mnc, lac, cellId))
network.addWifiAccessPoint(WifiAccessPoint.from("AA:BB:CC:DD:EE:FF", -72))
pos.network = network
```

### Unit conversions

```groovy
import org.traccar.helper.UnitsConverter

pos.speed = UnitsConverter.knotsFromKph(speed_kph)
pos.speed = UnitsConverter.knotsFromMph(speed_mph)
```

---

## `encode` closure

Receives `(Command cmd, EncodeContext ctx)`. Return a `String` or `byte[]` to send
to the device, or `null` if the command type is not supported.

### EncodeContext API

| Method | Description |
|---|---|
| `ctx.deviceId()` | The device's unique ID (IMEI) looked up from the command. |
| `ctx.utcTime()` | Current UTC time as `HHmmss`. |
| `ctx.freq()` | `frequency` attribute from the command (int, seconds). |
| `ctx.server()` | `server` attribute from the command (String). |
| `ctx.port()` | `port` attribute from the command (String). |
| `ctx.data()` | `data` attribute from the command (String). Used for `TYPE_CUSTOM`. |
| `ctx.clamp(value, min, max)` | Clamps a long value to `[min, max]`. |
| `ctx.devicePassword(default)` | Device password from the Traccar `password` attribute, walking the device → group → server → config hierarchy. Falls back to `default`. |
| `ctx.deviceModel()` | Device model string from the Traccar device record (e.g. `"MT700"`), or `null`. |
| `ctx.deviceAttrs()` | Returns a [`DeviceAttrs`](#per-device-lookups) for arbitrary attribute access. |

### Command types

All `TYPE_*` constants are available without imports:

| Constant | Traccar value |
|---|---|
| `TYPE_CUSTOM` | `"custom"` — raw command string from `ctx.data()` |
| `TYPE_POSITION_SINGLE` | `"positionSingle"` — request one position report |
| `TYPE_POSITION_PERIODIC` | `"positionPeriodic"` |
| `TYPE_ENGINE_STOP` | `"engineStop"` |
| `TYPE_ENGINE_RESUME` | `"engineResume"` |
| `TYPE_ALARM_ARM` | `"alarmArm"` |
| `TYPE_ALARM_DISARM` | `"alarmDisarm"` |
| `TYPE_REBOOT_DEVICE` | `"rebootDevice"` |
| `TYPE_MODE_DEEP_SLEEP` | `"modeDeepSleep"` |
| `TYPE_SET_CONNECTION` | `"setConnection"` |
| `TYPE_GET_DEVICE_STATUS` | `"getDeviceStatus"` |
| `TYPE_POWER_OFF` | `"powerOff"` |
| `TYPE_OUTPUT_CONTROL` | `"outputControl"` |
| `TYPE_IDENTIFICATION` | `"identification"` |

---

## Binary protocols

When a tracker sends raw binary frames (non-ASCII start byte), use `readFixed` or
`readLengthField` framing. The `decode` closure then receives a `BufReader` instead
of a `String`.

### BufReader API

| Method | Returns | Description |
|---|---|---|
| `buf.readUByte()` | `int` | One byte, unsigned (0–255) |
| `buf.readByte()` | `int` | One byte, signed |
| `buf.readUShort()` | `int` | Two bytes, big-endian, unsigned |
| `buf.readShort()` | `int` | Two bytes, big-endian, signed |
| `buf.readUShortLE()` | `int` | Two bytes, little-endian, unsigned |
| `buf.readUInt()` | `long` | Four bytes, big-endian, unsigned |
| `buf.readInt()` | `int` | Four bytes, big-endian, signed |
| `buf.readIntLE()` | `int` | Four bytes, little-endian, signed |
| `buf.readBcd(digits)` | `String` | BCD-encoded decimal digits (IMEI pattern: `readBcd(15)` → 8 bytes) |
| `buf.readHex(n)` | `String` | `n` bytes as lowercase hex string (e.g. `"0a1b2c"`) |
| `buf.readBytes(n)` | `byte[]` | Raw bytes |
| `buf.readString(n)` | `String` | `n` ASCII bytes |
| `buf.readString(n, charset)` | `String` | `n` bytes decoded with the named charset |
| `buf.skip(n)` | — | Advance read position by `n` bytes |
| `buf.slice(n)` | `BufReader` | New `BufReader` over the next `n` bytes (independent pointer) |
| `buf.getUByte(index)` | `int` | Peek at `index` bytes ahead, without advancing |
| `buf.readableBytes()` | `int` | Bytes remaining |
| `buf.isReadable()` | `boolean` | `true` if any bytes remain |
| `BufReader.checkBit(v, bit)` | `boolean` | `true` if bit `bit` is set in `v` (bit 0 = LSB) |

### Binary decode skeleton

```groovy
import org.traccar.helper.DateBuilder
import org.traccar.model.Position

protocol("mybin") {

    port 5099

    variant("main") {

        // 0x78 start byte; length field at offset 2, 2 bytes wide;
        // +1 for the checksum byte not counted in the length field.
        frame 0x78 as byte, readLengthField(2, 2, 1)

        decode { buf, ctx ->
            buf.skip(2)                         // start bytes
            int msgType = buf.readUShort()
            int bodyLen = buf.readUShort()
            String imei = buf.readBcd(15)
            buf.skip(2)                         // sequence number

            def session = ctx.session(imei)
            if (!session) return null

            if (msgType == 0x0200) {            // position report
                def pos = ctx.newPosition()
                pos.deviceId = session.deviceId

                long gpsInfo  = buf.readUInt()
                pos.valid     = (gpsInfo & 0x01) != 0
                int sats      = (int) ((gpsInfo >> 1) & 0x0F)
                pos.set(Position.KEY_SATELLITES, sats)

                pos.latitude  = buf.readInt()  / 1000000.0
                pos.longitude = buf.readInt()  / 1000000.0
                pos.speed     = buf.readUShort() / 10.0
                pos.course    = buf.readUShort() / 10.0

                // ... timestamp etc ...

                // Binary ACK
                ctx.ack([0x78, 0x78, 0x00, 0x05, 0x00, 0x01,
                         (msgType >> 8) & 0xFF, msgType & 0xFF,
                         0x0D, 0x0A] as byte[])

                return pos
            }

            return null
        }

        encode { cmd, ctx ->
            // Build byte[] response for commands
            // ctx.devicePassword('000000') for per-device password
            return null
        }
    }
}
```

### Binary ACK

```groovy
ctx.ack([0x78, 0x78, 0x00, 0x05] as byte[])
```

The `byte[]` is copied into a Netty buffer and sent directly. `StringEncoder` in
the pipeline passes `ByteBuf` through unchanged, so binary ACKs work alongside
text ACKs with no pipeline changes.

---

## Per-device lookups

Driver scripts can read per-device attributes from the Traccar device record,
rather than hardcoding values like passwords.

### In a `decode` closure

```groovy
decode { msg, ctx ->
    def session = ctx.session(imei)
    if (!session) return null

    def attrs = ctx.deviceAttrs(session)
    def pwd   = attrs.password('00000000')  // 'password' attribute, walks hierarchy
    def model = attrs.model()               // device model string
    def fmt   = attrs.get('myProtocol.format', 'v2')  // arbitrary attribute with default
    // ...
}
```

### In an `encode` closure

```groovy
encode { cmd, ctx ->
    def pwd   = ctx.devicePassword('00000000')  // common shortcut
    def model = ctx.deviceModel()
    def attrs = ctx.deviceAttrs()               // full DeviceAttrs for arbitrary keys
    def fmt   = attrs.get('myProtocol.format', 'v2')
    // ...
}
```

### `DeviceAttrs` methods

| Method | Description |
|---|---|
| `attrs.password(default)` | Device password from the Traccar `password` attribute, walking device → group → server → config. Returns `default` if not set. |
| `attrs.model()` | Device model string, or `null`. |
| `attrs.get(key)` | Raw device attribute, or `null`. |
| `attrs.get(key, default)` | Raw device attribute with fallback. |

Set per-device values in the Traccar web interface: **Device → Edit → Attributes**.

---

## Alarm constants

All `ALARM_*` constants are available without imports:

| Constant | String value |
|---|---|
| `ALARM_SOS` | `"sos"` |
| `ALARM_TOW` | `"tow"` |
| `ALARM_VIBRATION` | `"vibration"` |
| `ALARM_MOVEMENT` | `"movement"` |
| `ALARM_OVERSPEED` | `"overspeed"` |
| `ALARM_LOW_BATTERY` | `"lowBattery"` |
| `ALARM_LOW_POWER` | `"lowPower"` |
| `ALARM_POWER_CUT` | `"powerCut"` |
| `ALARM_POWER_ON` | `"powerOn"` |
| `ALARM_POWER_OFF` | `"powerOff"` |
| `ALARM_POWER_RESTORED` | `"powerRestored"` |
| `ALARM_REMOVING` | `"removing"` |
| `ALARM_TAMPERING` | `"tampering"` |
| `ALARM_TEMPERATURE` | `"temperature"` |
| `ALARM_GEOFENCE` | `"geofence"` |
| `ALARM_GEOFENCE_ENTER` | `"geofenceEnter"` |
| `ALARM_GEOFENCE_EXIT` | `"geofenceExit"` |
| `ALARM_ACCIDENT` | `"accident"` |
| `ALARM_FALL_DOWN` | `"fallDown"` |
| `ALARM_IDLE` | `"idle"` |
| `ALARM_PARKING` | `"parking"` |
| `ALARM_JAMMING` | `"jamming"` |
| `ALARM_GPS_ANTENNA_CUT` | `"gpsAntennaCut"` |
| `ALARM_ACCELERATION` | `"hardAcceleration"` |
| `ALARM_BRAKING` | `"hardBraking"` |
| `ALARM_CORNERING` | `"hardCornering"` |
| `ALARM_FUEL_LEAK` | `"fuelLeak"` |

---

## Position keys

Common keys for `pos.set(key, value)` and direct fields:

### Direct fields (set as properties)

```groovy
pos.time      = new Date()     // message timestamp (required)
pos.fixTime   = new Date()     // GPS fix time when different from message time
pos.valid     = true           // GPS validity — false = no fix
pos.latitude  = 36.5000        // decimal degrees, negative = S
pos.longitude = -97.0000       // decimal degrees, negative = W
pos.speed     = 0.0            // knots
pos.course    = 180.0          // degrees 0–360
pos.altitude  = 350.0          // metres
pos.deviceId  = session.deviceId
pos.network   = network        // Network object with cell towers / Wi-Fi APs
```

Use `pos.fixTime` when a WiFi or LBS message carries a GPS timestamp that differs
from the server-receive time. `ctx.lastLocation(pos)` sets coordinates from the
previous fix and leaves `time` for you to assign.

### `pos.set(key, value)` keys

| Constant | Type | Description |
|---|---|---|
| `Position.KEY_BATTERY` | double | Battery voltage (V) |
| `Position.KEY_BATTERY_LEVEL` | int | Battery charge percentage (0–100) |
| `Position.KEY_POWER` | double | External power voltage (V) |
| `Position.KEY_IGNITION` | boolean | Ignition state |
| `Position.KEY_SATELLITES` | int | GPS satellite count |
| `Position.KEY_HDOP` | double | Horizontal dilution of precision |
| `Position.KEY_ODOMETER` | long | Odometer (cm) |
| `Position.KEY_ICCID` | String | SIM card ICCID |
| `Position.KEY_DOOR` | boolean | Door open state |
| `Position.KEY_RSSI` | int | Cellular signal strength |
| `Position.KEY_STEPS` | int | Pedometer step count |
| `Position.KEY_FUEL` | double | Fuel level |
| `Position.KEY_STATUS` | long | Raw device status bitmask |
| `Position.KEY_RESULT` | String | Command response text |
| `Position.KEY_EVENT` | int | Raw event code from device |

### Indexed keys (append index as a String)

```groovy
pos.set(Position.PREFIX_ADC  + '1', 3.7)    // ADC channel 1 voltage
pos.set(Position.PREFIX_TEMP + '1', 23.5)   // temperature sensor 1 (°C)
pos.set(Position.PREFIX_IO   + '1', "HIGH") // I/O port 1 value
pos.set(Position.PREFIX_IN   + '1', true)   // digital input 1 state
```

---

## Hot-reload

The server watches the `drivers/` directory. Saving a `.groovy` file:
- **creates** a new driver or replaces an existing one (connections on that port
  will use the new definition from the next message)
- **deletes** a `.groovy` file to unload that driver

No restart required. Syntax errors in the updated file are logged and the old
definition continues to serve until the file is fixed.

---

## Multiple variants on one port

A single port can handle devices with completely different wire formats. The
`frameByteHint` lets the frame decoder distinguish them before a full message
is available:

```groovy
protocol("myfirmware") {
    port 5100

    variant("binary") {
        frame 0x78 as byte, readLengthField(2, 2, 1)   // binary frames start 0x78
        decode { buf, ctx -> ... }
    }

    variant("v2") {
        frame '*' as char, readUntil('#')   // '*' first byte → #-terminated text
        matches { msg -> msg.startsWith("*V2,") }
        decode { msg, ctx -> ... }
    }

    variant("legacy") {
        frame readLine()                    // no hint → fallback for everything else
        matches { msg -> msg =~ /^\d{15},/ }
        decode { msg, ctx -> ... }
    }
}
```

---

## Real-world examples

### [drivers/example.groovy](../drivers/example.groovy)

Pedagogical driver for the fictional "SimpleTrack" protocol — documents every DSL
feature for text protocols including framing, alarms, DateBuilder, encoding, and
per-device password lookup.

### [drivers/mictrack.groovy](../drivers/mictrack.groovy)

Two variants on one port (`*HQ,...#` and `#IMEI#...##` framing):
- `frameByteHint` distinguishing HQ and MT700 frames on the same TCP port
- V4 heartbeat with R12 ACK
- 4-byte active-low vehicle status bitmask parsed with bit operations
- Model-aware alarm mapping (MT700 vs MT600 `DEF`/`HT` semantics)
- GPRMC and WiFi location parsing
- Cell tower network attachment
- Full command encoding for both variants

### [drivers/tlt2h.groovy](../drivers/tlt2h.groovy)

Batch-upload protocol (`##`-terminated frames, multiple records per frame):
- `ctx.emit(pos)` returning a list of positions from one TCP frame
- Header-level sensor fields (door, ADC, power, battery, temperature) applied to
  every position in the batch
- GPRMC position records + WiFi AP records in the same frame
- `pos.fixTime` for WiFi frames that carry a GPS timestamp

### [drivers/h02.groovy](../drivers/h02.groovy)

Protocol with many message subtypes dispatched by type field:
- `*XX,...#` text framing with type-based dispatch inside the decode closure
- Standard position (V1/V5/V6), LBS cell-tower (NBR, V3), activity tracker (LINK),
  VP1 (cell or GPS), heartbeats (HTBT/V4), SMS command results
- Active-low status bitmask decoded into alarms and ignition
- Full encoder (ARM/DISARM/ENGINE STOP/RESUME/PERIODIC)

### [drivers/laipac.groovy](../drivers/laipac.groovy)

Three message types on one port; demonstrates per-device password lookup:
- `$ECHK` heartbeat (echo), `$EAVSYS` device info, `$AVRMC` position
- NMEA `ddmm.mmmm` coordinate conversion
- Event-driven alarm acknowledgement with `ctx.ack()`
- `ctx.deviceAttrs(session).password('00000000')` — per-device password in decode
- `ctx.devicePassword('00000000')` — per-device password in encode

---

## Common patterns

### Checksum validation

```groovy
def validChecksum = { msg ->
    int star = msg.lastIndexOf('*')
    if (star < 0) return false
    int expected = Integer.parseInt(msg[(star + 1)..(star + 2)], 16)
    int actual   = msg[1..(star - 1)].bytes.inject(0) { acc, b -> acc ^ b }
    actual == expected
}
```

### NMEA coordinate conversion (ddmm.mmmm → decimal)

```groovy
def nmea = { raw, hemi ->
    if (!raw) return 0.0
    int dot  = raw.indexOf('.')
    double d = raw[0..(dot - 3)].toDouble()
    double m = raw[(dot - 2)..-1].toDouble()
    double v = d + m / 60.0
    (hemi == 'S' || hemi == 'W') ? -v : v
}

pos.latitude  = nmea(m.group(1), m.group(2))   // "3606.1589", "N"
pos.longitude = nmea(m.group(3), m.group(4))   // "09617.0459", "W"
```

### Sending an acknowledgement

```groovy
// Text ACK
ctx.ack("OK\r\n")

// Binary ACK
ctx.ack([0x78, 0x78, 0x00, 0x01, 0x0D, 0x0A] as byte[])
```

### Ignoring non-position messages

```groovy
decode { msg, ctx ->
    if (msg.startsWith("HEARTBEAT")) {
        ctx.ack("ACK\r\n")
        return null           // null = no position stored, connection kept alive
    }
    // ... parse position ...
}
```

### Returning multiple positions from one frame

Some devices batch-upload several fixes in a single TCP frame (e.g. TLT2H). Call
`ctx.emit(pos)` for each record and `return null` at the end — the decoder
collects all emitted positions and forwards them as a list.

```groovy
frame '#' as char, readUntil('##')

decode { msg, ctx ->
    def lines = msg.split(/\r\n/)
    if (lines.length < 2) return null

    def session = ctx.session(parseImei(lines[0]))
    if (!session) return null

    for (int i = 1; i < lines.length; i++) {
        def line = lines[i].trim()
        if (line.isEmpty()) continue

        def pos = ctx.newPosition()
        pos.deviceId = session.deviceId
        // ... parse line into pos ...
        ctx.emit(pos)            // accumulate instead of returning
    }

    return null   // all positions were already delivered via emit()
}
```

`ctx.emit()` and `return pos` can be mixed: if the closure both emits positions
*and* returns a `Position`, the returned position is appended to the emitted list.

### Per-device password

Set the `password` attribute on the device record in the Traccar web interface.
Read it in the decode or encode closure:

```groovy
// decode:
def pwd = ctx.deviceAttrs(session).password('00000000')

// encode:
def pwd = ctx.devicePassword('00000000')
```

Both methods walk the device → group → server → config hierarchy, so you can set
a fleet-wide default at the server level and override per device.

### Binary checksum verification

```groovy
decode { buf, ctx ->
    // Peek at the full frame to verify checksum before consuming
    byte[] frame = buf.readBytes(buf.readableBytes())
    int checksum = 0
    for (int i = 2; i < frame.length - 3; i++) {
        checksum ^= (frame[i] & 0xFF)
    }
    if (checksum != (frame[frame.length - 3] & 0xFF)) return null

    // Now re-parse from a BufReader wrapping the verified bytes
    // (or re-enter from buf.slice() before consuming)
}
```

For cleaner code, verify by index before consuming: use `buf.getUByte(index)` to
peek at individual bytes without advancing the read pointer.
