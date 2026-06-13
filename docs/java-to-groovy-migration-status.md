<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Java To Groovy Protocol Migration Status

Status date: 2026-06-12

The migration goal is feature-complete Groovy protocol coverage from source
documentation, with archived Java parity used only as supporting evidence.

## Completed In This Pass

| Protocol | Result |
| --- | --- |
| `cartrack` | Migrated to `drivers/cartrack.groovy`, backed by the downloaded CarTrack GTP PDF, driver decode tests, command-declaration tests, and command encoder tests. Java source and old Java protocol test moved to `archived-protocols/cartrack/`. |
| `enfora` | Migrated to `drivers/enfora.groovy`, backed by the downloaded Enfora API and Mini-MT PDFs, driver decode tests, command-declaration tests, and command encoder tests. Java source, encoder, and old Java protocol test moved to `archived-protocols/enfora/`. |
| `m2m` | Migrated to `drivers/m2m.groovy`, backed by the downloaded M2M protocol spreadsheet and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/m2m/`. |
| `orion` | Migrated to `drivers/orion.groovy`, backed by the downloaded Orion binary protocol PDF, scripted-frame driver tests, ACK coverage, and Java-parity position fixtures. Java source, frame decoder, and old Java protocol test moved to `archived-protocols/orion/`. |
| `riti` | Migrated to `drivers/riti.groovy`, backed by the downloaded Riti Air Communication Protocol PDF and Java-parity driver decode tests. Java source and old Java protocol test moved to `archived-protocols/riti/`. |

## Remaining Active Java Protocols

63 documented, official, or alias-documented Java protocol implementations
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
