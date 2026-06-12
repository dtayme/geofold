<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Undocumented Archived Protocols

This folder contains archived Groovy driver scripts and Java protocol
implementations for protocols that do not currently have supporting downloaded
source documentation under `docs/driver-source-docs/`.

Each protocol folder uses this layout:

- `<name>.groovy` - the archived Groovy driver script, moved out of active
  `drivers/`.
- `*Protocol*.java`, `*Frame*.java`, and related protocol-specific Java files -
  archived Java reference files moved out of active
  `src/main/java/org/traccar/protocol/`.
- `*Protocol*Test.java` - archived protocol tests moved out of active
  `src/test/java/org/traccar/protocol/` when their implementation is archived.

These archives are useful for parity checks, but they are not a substitute for
vendor or public protocol documentation when auditing a Groovy driver for
feature-complete protocol coverage.

As of the 2026-06-12 documentation pass, undocumented Java protocol
implementations were also removed from `PortConfigSuffix.java`, so they do not
receive default listening ports while they are archived.
