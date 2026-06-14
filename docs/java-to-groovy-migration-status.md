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

## Remaining Active Java Protocols

53 documented, official, or alias-documented Java protocol implementations
remain under `src/main/java/org/traccar/protocol/`.

HTTP protocols such as `osmand` and `globalstar` cannot be represented by the
current driver DSL without adding HTTP request/response support. Large binary
protocols such as `jt808`, `teltonika`, `gt06`, `ruptela`, and `xirgo` need
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
