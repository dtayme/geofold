<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Driver Development Guide

Drivers are Groovy scripts that teach the server how to speak a specific GPS tracker
protocol. Dropping a `.groovy` file into the `drivers/` directory registers the
script for review. The script is not compiled or executed until an administrator
enables that exact file hash.

## Quick-start

```
traccar/
  drivers/
    mydevice.groovy   ← create this file
```

The server watches every `.groovy` file in the `drivers/` directory. New and
changed files are registered in `/api/driver-scripts` with a SHA-256 hash and
remain disabled by default. Once an administrator enables a registered hash, the
server loads that script without a restart. If the enabled script introduces a
new listener port or transport, restart the server so that socket can be opened.

---

## Script structure

Every driver script calls `protocol()` exactly once at the top level.

```groovy
protocol("mydevice") {

    port 5200           // listener port
    transport 'tcp'     // optional; tcp is the default

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

## Transports and listeners

Driver scripts declare the listener port and transport at protocol scope. The
server creates one listener for each unique `(transport, port)` pair used by
enabled driver scripts at startup.

```groovy
protocol("rawtcp") {
    port 5200           // TCP listener on 5200; transport 'tcp' is implicit
}

protocol("datagram") {
    port 5201
    transport 'udp'
}

protocol("both") {
    port 5202
    transport 'tcp', 'udp'
}

protocol("webhook") {
    port 5203
    transport 'http'
}
```

Hot reload updates drivers served by already-open listeners. Adding a new port
or changing a driver's transport requires a server restart so the OS socket can
be opened or closed.

Raw TCP and HTTP are both stream transports and cannot bind to the same port as
separate listeners. Use distinct ports for raw TCP and HTTP drivers.

---

## Framing

Framing tells the server how to extract one complete message from a raw TCP byte
stream. UDP is already packetized and ignores the frame declaration. HTTP drivers
use HTTP request routing instead of `frame`.

### Text framing

The `decode` closure receives a `String`.

```groovy
// Read until a single-byte terminator; strip the terminator from the output.
frame readUntil('#')

// Read until a multi-byte terminator.
frame readUntil('##')

// Keep the terminator in the string passed to decode.
frame readUntilKeep('##')

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

// Read one of several fixed sizes for protocols with same-marker binary variants.
// If the buffered byte count exactly matches one configured size, that size is used;
// otherwise the smallest complete size is used to avoid consuming the next frame.
frame 0x24 as byte, readFixedAny(32, 45)

// Read a length field embedded in the header, then that many more bytes.
//   readLengthField(offset, fieldWidth)
//   Total frame = offset + fieldWidth + fieldValue
frame 0x78 as byte, readLengthField(2, 2)

// Add an adjustment for bytes after the body (e.g. a checksum not counted
// in the length field).
//   readLengthField(offset, fieldWidth, adjustment)
//   Total frame = offset + fieldWidth + fieldValue + adjustment
frame 0x78 as byte, readLengthField(2, 2, 1)   // 2-byte header, 2-byte length, body, 1-byte checksum

// Little-endian length fields use the same formula.
frame 0x68 as byte, readLengthFieldLE(1, 2, 1)

// Delimiter-framed binary protocols with byte escaping. The raw frame from
// delimiter to delimiter is consumed; decode receives the unescaped bytes
// between delimiters.
frame 0x7e as byte, readEscaped(0x7e as byte, 0x7d as byte, [
    (0x02): 0x7e,
    (0x01): 0x7d,
])

// Custom binary framing for formats that cannot be represented declaratively.
// Return null for incomplete data, a positive length to keep raw bytes, or
// frameResult(rawLength, payloadBytes) to replace the downstream payload.
frame 0x55 as byte, { fb ->
    if (fb.readableBytes() < 4) return null
    return frameRaw(4)
}
```

`fieldWidth` must be 1, 2, or 4 bytes.

### Maximum frame length

All driver frames are bounded to prevent unauthenticated clients from growing
decoder buffers indefinitely. The default maximum is configured with:

```xml
<entry key='driver.frameMaxLength'>8192</entry>
```

The value must be a positive byte count.

Each variant can override the default when a protocol legitimately needs larger
or smaller frames:

```groovy
variant("main") {
    maxFrameLength 2048
    frame '*' as char, readUntil('#')
}

variant("batch") {
    maxFrameLength 65536
    frame 0x78 as byte, readLengthField(2, 2, 1)
}
```

The limit is enforced for newline-delimited frames, arbitrary terminators, fixed
binary frames, length-field frames, escaped-delimiter frames, and scripted
frames. If a text frame grows past the limit before its delimiter arrives, or if
a binary frame declares a size above the limit, the frame decoder rejects it.

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

When a driver script declares `port N`, TCP frame selection first considers
drivers whose declared port matches the socket's local port. If no script matches
that local port, selection falls back to all loaded drivers. This lets converted
protocols reuse common markers such as `*` or `$` without accidentally selecting
another driver's fallback framing on their own port.

---

## `matches` closure

For raw text variants, receives the fully-extracted message string. For HTTP
variants, receives a [`DriverHttpRequest`](#http-drivers). Return `true` if this
variant handles it. Binary variants are already selected by their
`frameByteHint` and do not need a `matches` closure.

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
| `ctx.session()` | Returns the existing session for the current channel without registering a new device. Use when the device sends follow-up messages (e.g. command responses) that contain no identifier. Returns `null` if no session exists yet. |
| `ctx.newPosition()` | Creates a new `Position` pre-tagged with the protocol name. |
| `ctx.lastLocation(pos)` | Fills in the last known GPS fix when the current message has no coordinates. |
| `ctx.lastLocation(pos, date)` | Same as above, but supplies the device timestamp for timestamp-sensitive fallback logic. |
| `ctx.remoteAddress()` | Returns the message's remote network address, when available. |
| `ctx.localAddress()` | Returns the local channel address, when available. |
| `ctx.localPort()` | Returns the local listener port, or `null` when unavailable. |
| `ctx.isUdp()` / `ctx.isTcp()` | Returns whether the current channel is UDP/datagram or TCP/socket based. |
| `ctx.ack(string)` | Sends a raw **text** response back to the device on the current channel. |
| `ctx.ack(byte[])` | Sends a raw **binary** response back to the device. |
| `ctx.emit(pos)` | Accumulates a position for **batch-upload** protocols where one frame contains multiple fixes. All emitted positions are returned as a list. Call instead of `return pos` for each record, then `return null` at the end. |
| `ctx.alarm(event)` | Resolves an event code using this variant's alarm map. |
| `ctx.alarm(event, model)` | Resolves an event code with a model string for model-aware mappings. |
| `ctx.model(msg)` | Runs this variant's `model` closure against `msg`. |
| `ctx.configInt(suffix, default)` | Reads an integer protocol config key for this driver, such as `ctx.configInt('mask', 0)` reading `skypatrol.mask`. |
| `ctx.configBoolean(suffix, default)` | Reads a boolean protocol config key for this driver. |
| `ctx.configString(suffix, default)` | Reads a string protocol config key for this driver. |
| `ctx.deviceAttrs(session)` | Returns a [`DeviceAttrs`](#per-device-lookups) for the session's device. |

The config helper suffix may include or omit the leading dot. For a driver named
`example`, `ctx.configInt('mask', 0)` and `ctx.configInt('.mask', 0)` both read
the Traccar config key `example.mask`.

### HTTP drivers

HTTP variants use `transport 'http'` and do not declare `frame`. Their `matches`
and `decode` closures receive `(DriverHttpRequest req, DriverHttpContext ctx)`.

```groovy
protocol("webhook") {
    port 5203
    transport 'http'

    variant("json") {
        matches { req -> req.method() == 'POST' && req.path() == '/uplink' }

        decode { req, ctx ->
            def json = req.jsonObject()
            def session = ctx.session(json.getString('device_id'))
            if (!session) {
                ctx.notFound()
                return null
            }

            def pos = ctx.newPosition()
            pos.deviceId = session.deviceId
            pos.valid = true
            pos.latitude = json.getJsonNumber('lat').doubleValue()
            pos.longitude = json.getJsonNumber('lon').doubleValue()

            ctx.ok()
            return pos
        }
    }
}
```

`DriverHttpRequest` API:

| Method | Description |
|---|---|
| `req.method()` | HTTP method name, e.g. `GET` or `POST`. |
| `req.uri()` / `req.path()` | Full URI or decoded path without query string. |
| `req.header(name)` / `req.contentType()` | Header lookup helpers. |
| `req.params()` / `req.params(name)` / `req.param(name)` | Query-string parameters. |
| `req.content()` / `req.content(charset)` | Request body as text. |
| `req.bytes()` | Request body as `byte[]`. |
| `req.jsonObject()` / `req.jsonArray()` | Parse request body as JSON. |

`DriverHttpContext` mirrors the session, position, `lastLocation`, `emit`,
alarm, `configInt`/`configBoolean`/`configString`, and `deviceAttrs` helpers
from `DecodeContext`, and adds HTTP responses:

| Method | Description |
|---|---|
| `ctx.ok()` / `ctx.ok(body)` / `ctx.ok(byte[])` | Send HTTP 200. |
| `ctx.badRequest()` | Send HTTP 400. |
| `ctx.notFound()` | Send HTTP 404. |
| `ctx.status(code)` | Send an empty response with an arbitrary status. |
| `ctx.text(code, body)` | Send a text response. |
| `ctx.json(code, body)` | Send an `application/json` response body. |
| `ctx.binary(code, bytes, contentType)` | Send a binary response. |
| `ctx.nextQueuedCommand(deviceId)` | Read one queued command so polling devices can receive it in the HTTP response body. |

If a decode closure returns a position but does not explicitly respond, the
driver runtime sends `200 OK`. If it returns `null` and does not explicitly
respond, the runtime sends `400 Bad Request`.

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
| `ctx.alternative()` | `true` when `protocol.<name>.alternative` is enabled for the current device. |
| `ctx.clamp(value, min, max)` | Clamps a long value to `[min, max]`. |
| `ctx.devicePassword(default)` | Device password from the Traccar `password` attribute, walking the device → group → server → config hierarchy. Falls back to `default`. |
| `ctx.deviceModel()` | Device model string from the Traccar device record (e.g. `"MT700"`), or `null`. |
| `ctx.deviceAttrs()` | Returns a [`DeviceAttrs`](#per-device-lookups) for arbitrary attribute access. |

### Command types

All `TYPE_*` constants are available without imports:

| Constant | Traccar value |
|---|---|
| `TYPE_CUSTOM` | `"custom"` — raw command string from `ctx.data()` |
| `TYPE_IDENTIFICATION` | `"deviceIdentification"` |
| `TYPE_POSITION_SINGLE` | `"positionSingle"` — request one position report |
| `TYPE_POSITION_PERIODIC` | `"positionPeriodic"` |
| `TYPE_POSITION_STOP` | `"positionStop"` |
| `TYPE_ENGINE_STOP` | `"engineStop"` |
| `TYPE_ENGINE_RESUME` | `"engineResume"` |
| `TYPE_ALARM_ARM` | `"alarmArm"` |
| `TYPE_ALARM_DISARM` | `"alarmDisarm"` |
| `TYPE_ALARM_DISMISS` | `"alarmDismiss"` |
| `TYPE_SET_TIMEZONE` | `"setTimezone"` |
| `TYPE_REQUEST_PHOTO` | `"requestPhoto"` |
| `TYPE_POWER_OFF` | `"powerOff"` |
| `TYPE_REBOOT_DEVICE` | `"rebootDevice"` |
| `TYPE_FACTORY_RESET` | `"factoryReset"` |
| `TYPE_SEND_SMS` | `"sendSms"` |
| `TYPE_SEND_USSD` | `"sendUssd"` |
| `TYPE_SOS_NUMBER` | `"sosNumber"` |
| `TYPE_SILENCE_TIME` | `"silenceTime"` |
| `TYPE_SET_PHONEBOOK` | `"setPhonebook"` |
| `TYPE_MESSAGE` | `"message"` |
| `TYPE_VOICE_MESSAGE` | `"voiceMessage"` |
| `TYPE_OUTPUT_CONTROL` | `"outputControl"` |
| `TYPE_VOICE_MONITORING` | `"voiceMonitoring"` |
| `TYPE_SET_AGPS` | `"setAgps"` |
| `TYPE_SET_INDICATOR` | `"setIndicator"` |
| `TYPE_CONFIGURATION` | `"configuration"` |
| `TYPE_GET_VERSION` | `"getVersion"` |
| `TYPE_FIRMWARE_UPDATE` | `"firmwareUpdate"` |
| `TYPE_SET_CONNECTION` | `"setConnection"` |
| `TYPE_SET_ODOMETER` | `"setOdometer"` |
| `TYPE_GET_MODEM_STATUS` | `"getModemStatus"` |
| `TYPE_GET_DEVICE_STATUS` | `"getDeviceStatus"` |
| `TYPE_SET_SPEED_LIMIT` | `"setSpeedLimit"` |
| `TYPE_MODE_POWER_SAVING` | `"modePowerSaving"` |
| `TYPE_MODE_DEEP_SLEEP` | `"modeDeepSleep"` |
| `TYPE_VIDEO_START` | `"videoStart"` |
| `TYPE_VIDEO_STOP` | `"videoStop"` |
| `TYPE_ALARM_GEOFENCE` | `"alarmGeofence"` |
| `TYPE_ALARM_BATTERY` | `"alarmBattery"` |
| `TYPE_ALARM_SOS` | `"alarmSos"` |
| `TYPE_ALARM_REMOVE` | `"alarmRemove"` |
| `TYPE_ALARM_CLOCK` | `"alarmClock"` |
| `TYPE_ALARM_SPEED` | `"alarmSpeed"` |
| `TYPE_ALARM_FALL` | `"alarmFall"` |
| `TYPE_ALARM_VIBRATION` | `"alarmVibration"` |

Declare the first-class commands a driver implements at protocol scope:

```groovy
protocol("simpletrack") {
    port 5099
    commands TYPE_POSITION_SINGLE, TYPE_POSITION_PERIODIC, TYPE_REBOOT_DEVICE
    ...
}
```

The shared `driver` protocol advertises the union of declared Groovy-driver
commands plus `TYPE_CUSTOM`.

---

## Binary protocols

When a tracker sends raw binary frames (non-ASCII start byte), use `readFixed`,
`readFixedAny`, `readLengthField`, `readLengthFieldLE`, `readEscaped`, or a
scripted frame closure. The `decode` closure then receives a `BufReader` instead
of a `String`.

### BufReader API

| Method | Returns | Description |
|---|---|---|
| `buf.readUByte()` | `int` | One byte, unsigned (0–255) |
| `buf.readByte()` | `int` | One byte, signed |
| `buf.readUShort()` | `int` | Two bytes, big-endian, unsigned |
| `buf.readShort()` | `int` | Two bytes, big-endian, signed |
| `buf.readUShortLE()` | `int` | Two bytes, little-endian, unsigned |
| `buf.readShortLE()` | `int` | Two bytes, little-endian, signed |
| `buf.readUInt()` | `long` | Four bytes, big-endian, unsigned |
| `buf.readInt()` | `int` | Four bytes, big-endian, signed |
| `buf.readUIntLE()` | `long` | Four bytes, little-endian, unsigned |
| `buf.readIntLE()` | `int` | Four bytes, little-endian, signed |
| `buf.readLong()` | `long` | Eight bytes, big-endian, signed |
| `buf.readLongLE()` | `long` | Eight bytes, little-endian, signed |
| `buf.readFloat()` | `float` | Four bytes, big-endian IEEE-754 |
| `buf.readDouble()` | `double` | Eight bytes, big-endian IEEE-754 |
| `buf.readBcd(digits)` | `String` | BCD-encoded decimal digits (IMEI pattern: `readBcd(15)` → 8 bytes) |
| `buf.readHex(n)` | `String` | `n` bytes as lowercase hex string (e.g. `"0a1b2c"`) |
| `buf.readBytes(n)` | `byte[]` | Raw bytes |
| `buf.readString(n)` | `String` | `n` ASCII bytes |
| `buf.readString(n, charset)` | `String` | `n` bytes decoded with the named charset |
| `buf.skip(n)` | — | Advance read position by `n` bytes |
| `buf.slice(n)` | `BufReader` | New `BufReader` over the next `n` bytes (independent pointer) |
| `buf.getUByte(index)` | `int` | Peek at `index` bytes ahead, without advancing |
| `buf.getUShort(index)` / `buf.getUShortLE(index)` | `int` | Peek two bytes without advancing |
| `buf.getUInt(index)` / `buf.getUIntLE(index)` | `long` | Peek four bytes without advancing |
| `buf.getBytes(index, n)` | `byte[]` | Peek raw bytes without advancing |
| `buf.readableBytes()` | `int` | Bytes remaining |
| `buf.isReadable()` | `boolean` | `true` if any bytes remain |
| `BufReader.checkBit(v, bit)` | `boolean` | `true` if bit `bit` is set in `v` (bit 0 = LSB) |

### FrameBuffer API

Scripted frame closures receive a `FrameBuffer` named however you choose. It is a
read-only view over the current TCP bytes; all indexes are relative to the
current frame candidate and do not advance the stream.

| Method | Returns | Description |
|---|---|---|
| `fb.readableBytes()` | `int` | Buffered bytes available |
| `fb.getUByte(index)` / `fb.getByte(index)` | `int` | Peek one byte |
| `fb.getUShort(index)` / `fb.getUShortLE(index)` | `int` | Peek two bytes |
| `fb.getUInt(index)` / `fb.getUIntLE(index)` | `long` | Peek four bytes |
| `fb.indexOf(value)` / `fb.indexOf(value, from)` | `int` | Relative offset of a byte value, or `-1` |
| `fb.bytes(offset, n)` | `byte[]` | Copy raw bytes |
| `fb.ascii(offset, n)` | `String` | Copy bytes as US-ASCII |

Return values:

```groovy
return null                         // need more bytes
return frameRaw(12)                 // consume 12 bytes and pass those raw bytes to decode
return frameResult(rawLen, payload) // consume rawLen bytes and pass payload to decode
```

### BufWriter and checksums

Use `bytes { ... }` in encode closures or binary acknowledgements to build
packets without hand-maintaining byte arrays.

```groovy
byte[] packet = bytes {
    writeByte 0x78
    writeShort 0x0005
    writeBcd imei
    writeByte xor(toByteArray())
}
```

`BufWriter` methods: `writeByte`, `writeShort`, `writeShortLE`, `writeInt`,
`writeIntLE`, `writeBytes`, `writeHex`, `writeBcd`, `writeZero`,
`writeString`, `setByte`, `setShort`, `setShortLE`, `setInt`, `setIntLE`,
`size`, and `toByteArray`.

Checksum helpers available in every driver script:

| Helper | Description |
|---|---|
| `xor(byte[])` / `xor(string)` | 8-bit XOR checksum |
| `sum(byte[])` / `sum(string)` | Modulo-256 additive checksum |
| `nmea(string)` | NMEA-style `*HH` XOR suffix |
| `crc16X25(byte[])` | CRC-16/X-25 |
| `crc16Modbus(byte[])` | CRC-16/MODBUS |
| `crc16CcittFalse(byte[])` | CRC-16/CCITT-FALSE |
| `crc32(byte[])` | Standard CRC-32 as unsigned `long` |

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

## Approval and hot-reload

The server watches the `drivers/` directory. Saving a `.groovy` file:
- **registers** a new `filename + SHA-256 hash` row in `tc_driver_scripts`
- leaves that row disabled by default
- unloads any previously loaded driver for that file when the file changes
- requires the new hash to be enabled before the updated script is compiled

Deleting a `.groovy` file unloads that driver. The registration history remains
in the database.

Administrators can review and enable registered driver script hashes through:

```text
GET  /api/driver-scripts
POST /api/driver-scripts/{id}/enable
POST /api/driver-scripts/{id}/disable
```

Approval is hash-bound. Enabling `mydevice.groovy` only enables the exact file
contents that produced the registered hash. If the file is edited later, the
hash changes and the new version must be enabled separately.

Syntax errors in an enabled file are logged and stored on the registered
`DriverScript` record. The broken hash is not loaded.

This approval gate prevents a newly dropped or modified script from executing
silently. It is not a sandbox: enabled Groovy code still runs inside the Traccar
JVM with the Traccar service account privileges.

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
    byte[] covered = frame[2..<(frame.length - 3)] as byte[]
    if (xor(covered) != (frame[frame.length - 3] & 0xFF)) return null

    // Now re-parse from a BufReader wrapping the verified bytes
    // (or re-enter from buf.slice() before consuming)
}
```

For cleaner code, verify by index before consuming: use `buf.getUByte(index)`,
`buf.getUShort(index)`, or `buf.getBytes(index, n)` to peek without advancing the
read pointer.
