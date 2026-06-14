<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Java To Groovy Protocol Migration Status

Status date: 2026-06-14

The migration goal is feature-complete Groovy protocol coverage from source
documentation, with archived Java parity used only as supporting evidence.

## Completed In This Pass

| Protocol | Result |
| --- | --- |
| `easytrack` | Migrated to `drivers/easytrack.groovy`, backed by Java-parity test fixtures (OBD, cell-tower, location, E3+4G model variants, TX/MQ echo suppression). Java source, encoder, and old Java protocol tests moved to `archived-protocols/easytrack/`. Requires DSL extension: `session.model` for model-aware decode. |
| `wialon` | Migrated to `drivers/wialon.groovy` covering L/P/D/SD/B/M message types, outer IMEI prefix stripping, full/short position patterns with NA fallback, extended param block (type:1/2/3 params, bat/temp remapping, cell towers up to 9 towers), batch with `KEY_ARCHIVE`, and 4 commands. Java source, encoder, and old Java protocol test moved to `archived-protocols/wialon/`. Uses `ctx.store()` for IMEI persistence after login. |
| `cartrack` | Migrated to `drivers/cartrack.groovy`, backed by the downloaded CarTrack GTP PDF, driver decode tests, command-declaration tests, and command encoder tests. Java source and old Java protocol test moved to `archived-protocols/cartrack/`. |
| `cityeasy` | Migrated to `drivers/cityeasy.groovy`, backed by the downloaded Cityeasy protocol PDF, driver decode tests, command-declaration tests, and command encoder tests. Java source, encoder, and old Java protocol test moved to `archived-protocols/cityeasy/`. |
| `enfora` | Migrated to `drivers/enfora.groovy`, backed by the downloaded Enfora API and Mini-MT PDFs, driver decode tests, command-declaration tests, and command encoder tests. Java source, encoder, and old Java protocol test moved to `archived-protocols/enfora/`. |
| `gpsgate` | Migrated to `drivers/gpsgate.groovy`, backed by the downloaded GpsGate Server protocol PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/gpsgate/`. |
| `m2m` | Migrated to `drivers/m2m.groovy`, backed by the downloaded M2M protocol spreadsheet and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/m2m/`. |
| `mxt` | Migrated to `drivers/mxt.groovy`, backed by the downloaded MXT protocol PDF and Java-parity driver decode tests. Java source, frame decoder, and old Java protocol test moved to `archived-protocols/mxt/`. |
| `noran` | Migrated to `drivers/noran.groovy`, backed by downloaded Noran protocol documents, driver decode tests, command-declaration tests, and command encoder tests. Java source, encoder, and old Java protocol test moved to `archived-protocols/noran/`. |
| `orion` | Migrated to `drivers/orion.groovy`, backed by the downloaded Orion binary protocol PDF, scripted-frame driver tests, ACK coverage, and Java-parity position fixtures. Java source, frame decoder, and old Java protocol test moved to `archived-protocols/orion/`. |
| `ramac` | Migrated to `drivers/ramac.groovy`, backed by the downloaded Ramac callback PDF, HTTP driver decode tests, and JSON acknowledgement coverage. Java source and old Java protocol test moved to `archived-protocols/ramac/`. |
| `riti` | Migrated to `drivers/riti.groovy`, backed by the downloaded Riti Air Communication Protocol PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/riti/`. |
| `skypatrol` | Migrated to `drivers/skypatrol.groovy`, backed by the downloaded SkyPatrol position report PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/skypatrol/`. |
| `stl060` | Migrated to `drivers/stl060.groovy`, backed by the downloaded STL060 protocol document and Java-parity driver decode tests. Java source, frame decoder, and old Java protocol test moved to `archived-protocols/stl060/`. |
| `t800x` | Migrated to `drivers/t800x.groovy`, backed by the downloaded Topflytech/T800X protocol documents, driver decode tests, command-declaration tests, and command encoder tests. Java source, encoder, and old Java protocol tests moved to `archived-protocols/t800x/`. |
| `thinkpower` | Migrated to `drivers/thinkpower.groovy`, backed by the downloaded ThinkPower tracker protocol PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/thinkpower/`. |
| `v680` | Migrated to `drivers/v680.groovy`, backed by the downloaded V680 GPRS protocol PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/v680/`. |
| `khd` | Migrated to `drivers/khd.groovy` covering binary `0x29 0x29` framing with `readLengthField(3, 2)`, BCD date/coordinate helpers, dual device-ID lookup (hex and decimal-with-offset), MSG_LOGIN/CONFIRMATION/POSITION/ALARM/PERIPHERAL/REPLY message types, peripheral subtypes (fuel, temp, driver ID, multi-fuel, battery, cell towers), and 7 commands. Java source, encoder, and old Java protocol tests moved to `archived-protocols/khd/`. |
| `xirgo` | Migrated to `drivers/xirgo.groovy` covering `##`-terminated text framing, auto-detection of old vs new fixed format (per-channel store), custom configurable field-name form, UDP ACK, event→alarm/ignition/motion mapping, and output-control command. Java source, encoder, and old Java protocol tests moved to `archived-protocols/xirgo/`. |
| `minifinder` | Migrated to `drivers/minifinder.groovy` covering `;`-terminated text framing, channel-session login (`!1`), message types !3/!4/!5/!A/!B/!C/!D, flag-word decoding (validity/alarms/RSSI/charge), and 11 commands (timezone, voice-monitoring, speed/geofence/vibration/fall alarms, AGPS, power-saving/deep-sleep modes, SOS number, indicator). Java source, encoder, and old Java protocol tests moved to `archived-protocols/minifinder/`. |
| `tramigo` | Migrated to `drivers/tramigo.groovy` covering scripted-frame detection (type-dependent LE/BE length field), three protocol variants (0x01 fixed-binary position, 0x04 TLV-loop with CRC16-CCITT ACK, 0x80 binary-header + ASCII-text with regex coordinate/direction/time parsing). Java source, frame decoder, and old Java protocol test moved to `archived-protocols/tramigo/`. |

| `xexun` | Migrated to `drivers/xexun.groovy` covering scriptedFrame with `FrameBuffer.indexOf(String)` for GPRMC/GNRMC scan, NMEA coordinate parsing via lazy `(\d*?)(\d?\d\.\d+)` groups, both basic and full deployment modes (serial/phone prefix, sats/alt/power suffix), alarm/ignition mapping, and 2 commands (ENGINE_STOP, ENGINE_RESUME). Java source, frame decoder, encoder, and old Java protocol tests moved to `archived-protocols/xexun/`. |

| `jmak` | Migrated to `drivers/jmak.groovy` covering three frame types on the same port (balanced-brace JSON `{…}`, `^…$` keep-alive, `~…$` position), 64-bit mask-based field parsing in decodeEvent (bits 0–34 via `BitUtil.check`), CAN/OBD section in decodeCan (bits 0–14), ACK "ACK" for all types, driver ID from eventId/eventStatus combo (126/4), and IO bitmask decode. Java source, frame decoder, and old Java protocol tests moved to `archived-protocols/jmak/`. |

| `navigil` | Migrated to `drivers/navigil.groovy` covering scriptedFrame with optional 4-byte preamble detection (LE 0x2477F5F6), length field at byte-offset 6, GPS-to-UTC leap-second correction (−25 s), CRC-16/CCITT-FALSE ACK when flags bit 0 is clear, and 6 message types: MSG_UNIT_REPORT (8), MSG_TG2_REPORT (12), MSG_POSITION_REPORT (13, 3-byte MediumLE coords), MSG_POSITION_REPORT_2 (15), MSG_SNAPSHOT4 (17), MSG_TRACKING_DATA (18). Java source, frame decoder, and old Java protocol tests moved to `archived-protocols/navigil/`. |

## Remaining Active Java Protocols

46 documented, official, or alias-documented Java protocol implementations
remain under `src/main/java/org/traccar/protocol/`.

HTTP protocols such as `osmand` and `globalstar` cannot be represented by the
current driver DSL without adding HTTP request/response support. Large binary
protocols such as `jt808`, `teltonika`, `gt06`, and `ruptela` need
protocol-specific frame, checksum, batch, attachment, and command audits before
safe migration.

Recommended order:

1. Continue with simple documented text or simple binary protocols whose Java
   pipelines use standard DSL framing and no custom HTTP handlers.
2. Add driver framework support for HTTP protocols before migrating OsmAnd,
   Globalstar, or other servlet-style protocols.
3. Migrate complex binary protocols only after each protocol has complete
   source-doc fixture coverage and command coverage mapped to Traccar command
   types.
