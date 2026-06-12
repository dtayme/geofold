<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Groovy Driver Feature Completeness Audit

Audit date: 2026-06-12

This audit compares the documented active Groovy drivers under `drivers/` with
the local protocol source documents in `docs/driver-source-docs/` and, where the
documents are not machine-readable locally, the matching archived Java protocols
under `archived-protocols/`.

The goal for Groovy drivers is feature-complete protocol implementations:
framing, login and heartbeat behavior, position parsing, alarms and attributes,
batch messages, acknowledgements, and the protocol command surface should match
the available protocol documentation.

## Scope

- Production Groovy drivers checked during research and migration: 63
- Active documented or alias-documented production drivers retained under
  `drivers/`: 26
- Undocumented Groovy drivers archived under
  `archived-protocols/undocumented/<name>/<name>.groovy`: 37
- Downloaded documentation files available locally: 43
- Drivers with exact downloaded protocol documentation: 22
- Drivers covered by alias or vendor-family documentation: 4
- Drivers without downloaded public documentation in this pass: 37

The local environment does not currently include PDF, DOC, or XLS extraction
tools or libraries, so this pass did not perform full text extraction from every
downloaded binary document. For those documents, the audit uses the local
documentation inventory plus archived Java decoder and encoder parity as the
practical completeness baseline.

## Summary

Most documented Groovy drivers are at or near archived-Java parity for basic
decode behavior. The 2026-06-12 implementation pass addressed the command
surface and verified framing gaps, including PT3000 after checking the readable
remote Traccar PDF rendering. The remaining completeness gaps are concentrated
in:

1. Alias-document drivers where the downloaded document is a family reference,
   not a confirmed exact protocol document.
2. Drivers with no downloaded documentation, which have been moved out of active
   `drivers/` and can only be parity-audited from the archive until source
   documents are found.

## Required Implementation Work

### Resolved: Command Surface Gaps

The new Groovy command declarations should be used by every driver that supports
outbound commands. The following gaps were fixed in the 2026-06-12
implementation pass:

| Driver | Current status | Required work |
| --- | --- | --- |
| `laipac` | Has an `encode` block for `TYPE_CUSTOM`, `TYPE_POSITION_SINGLE`, and `TYPE_REBOOT_DEVICE`. | Fixed: added matching `commands(...)` declaration. |
| `mictrack` | Has HQ and MT700 `encode` blocks covering custom, reboot, periodic position, deep sleep, connection, status, engine, and alarm commands. | Fixed: added matching `commands(...)` declaration for the union of both variants. |
| `pretrace` | Archived undocumented Groovy driver has an `encode` block for custom and periodic position. | Fixed before archival: added matching `commands(...)` declaration. |
| `svias` | Archived Java supports commands but Groovy only decoded. | Fixed before archival: added `commands(...)` and migrated encoder support for custom, position single, odometer, engine stop/resume, alarm arm/disarm, and alarm remove. |
| `wondex` | Archived Java declares two command variants, but Groovy only decoded keepalive, positions, and command responses. | Fixed: added command declarations and encoder support for device status, modem status, reboot, position single, version, and identification. |

### Framing Status

These drivers have framing behavior that differs from the archived Java decoder
and should be fixed or explicitly justified from documentation:

| Driver | Current Groovy framing | Archived/documented indication | Required work |
| --- | --- | --- | --- |
| `gl100` | Fixed: `+RESP...` and `AT+GTHBD...` are now null-terminated variants with first-byte hints. | Queclink GL100/GL200-family traffic is null-terminated in the archived decoder, and the heartbeat response already includes `\0`. | Add real fixture coverage as exact source-doc examples are extracted. |
| `gotop` | Fixed: now uses `readUntil('#')`. | Archived Java uses `#` as the frame delimiter. | Add real fixture coverage from the downloaded document. |
| `pt3000` | Verified unchanged: `readUntil("d")`. | The readable remote Traccar PDF rendering shows the parsed `%<imei>,$GPRMC,...` response ending with message code `N028d`. | No driver change needed; retained archived Java/Groovy framing and added remote extraction notes. |

### P1: Alias Documentation Needs Confirmation

These drivers have local documentation, but it is a family or tentative alias
rather than an exact driver-name match:

| Driver | Local documentation | Audit status |
| --- | --- | --- |
| `gl100` | `traccar-protocols/gl200/` | Needs explicit GL100/GL200-family delimiter and message-type confirmation. |
| `gt30` | `external/gt30-alias-meiligao/` | Decode parity is good, but the source document is a tentative Meiligao family match. |
| `mictrack` | `external/mictrack/` | Vendor documents are present; command declarations still need migration. |
| `topflyftech` | `traccar-protocols/topflytech/`, `traccar-protocols/t800x/` | Archived decoder parity looks consistent; command support should be revisited if the family docs include outbound commands. |

### P1: Test Coverage Gaps

The migrated driver decoder tests currently cover a small representative set.
Feature-completeness work should add fixture tests as protocol behavior is
verified, especially for:

- `gl100` null-terminated heartbeat and position frames.
- `gotop` `#`-terminated position frames.
- `svias` command encoding.
- `wondex` command encoding and keepalive echo.
- `mictrack`, `laipac`, and `pretrace` command declaration visibility.
- H02 binary, batch, ACK, and command variants from the expanded driver.

## Documented Driver Coverage

| Driver | Documentation status | Completeness status |
| --- | --- | --- |
| `ardi01` | Exact Traccar archive match | Decode parity appears good after migrated test cleanup. |
| `box` | Exact Traccar archive match | Decode and CR framing appear aligned with archived Java. |
| `cartrack` | Exact Traccar archive match | Migrated from Java; decode covers documented position, login, ping/error notices, IO, odometer, alarm status, stored/realtime marker, optional ADC, and documented outbound commands. |
| `carscop` | Exact Traccar archive match | Decode behavior appears aligned with archived Java for the covered message family. |
| `gl100` | Alias: Queclink GL100/GL200-family docs | Framing gap: Groovy uses line framing while archived Queclink handling is null-terminated. |
| `gotop` | Exact Traccar archive match | Framing gap: Groovy uses line framing while archived Java uses `#`. |
| `gpsmarker` | Exact Traccar archive match | Decode and CR framing appear aligned with archived Java. |
| `gpsmta` | Exact Traccar archive match | Decode behavior appears aligned with archived Java. |
| `gt02` | Exact Traccar archive match | Decode, login, heartbeat ACK, and length-field framing appear aligned with archived Java. |
| `gt30` | Tentative alias: Meiligao GT30i/GT60/VT family | Decode parity appears good; exact documentation match remains unconfirmed. |
| `h02` | Exact Traccar archive match | Coverage has been expanded for text, binary, ACK, batch, and commands; keep adding fixtures for each documented variant. |
| `haicom` | Exact Traccar archive match | Decode behavior appears aligned with archived Java. |
| `kenji` | Exact Traccar archive match | Decode behavior appears aligned with archived Java. |
| `laipac` | Exact Traccar archive match | Decode and encoder logic are present; command declaration added. |
| `mictrack` | Vendor protocol downloads | Decode and encoder logic are present for HQ and MT700 variants; command declaration added. |
| `nto` | Exact Traccar archive match | Decode behavior appears aligned with archived Java. |
| `pt3000` | Exact Traccar archive match | Delimiter verified against the remotely readable Traccar PDF rendering; `readUntil("d")` is retained. |
| `r16h` | Exact Traccar archive match | Decode, delimiter, and alarm handling appear aligned with archived Java. |
| `tk102` | Exact Traccar archive match | Decode, login, heartbeat ACK, and length-field framing appear aligned with archived Java. |
| `tlt2h` | Exact Traccar archive match | Large `##`-terminated messages, GPS, Wi-Fi, batch handling, and alarms appear aligned with archived Java. |
| `topflyftech` | Alias: Topflytech/T800X docs | Decode framing appears aligned with archived Java; outbound command coverage needs doc review. |
| `tr20` | Exact Traccar archive match | Decode and ACK behavior appear aligned with archived Java. |
| `tr900` | Exact Traccar archive match | Decode behavior appears aligned with archived Java. |
| `trackbox` | Exact Traccar archive match | Decode and ACK behavior appear aligned with archived Java. |
| `wondex` | Exact Traccar archive match | Decode, keepalive echo, and archived command support are migrated. |
| `ywt` | Exact Traccar archive match | Decode and ACK behavior appear aligned with archived Java. |

## Drivers Without Downloaded Protocol Documentation

The following drivers do not yet have local downloaded public protocol
documentation. They should not be called feature-complete from documentation
alone until a source document is found or a deliberate archived-Java parity
exception is recorded.

`appello`, `arknav`, `auro`, `austinnb`, `autograde`, `bstpl`, `cautela`,
`cguard`, `cradlepoint`, `disha`, `dway`, `ennfu`, `envotech`, `extremtrac`,
`flextrack`, `fox`, `freedom`, `gnx`, `homtecs`, `hunterpro`, `idpl`,
`ivt401`, `jido`, `manpower`, `mtx`, `neos`, `net`, `oko`, `pretrace`,
`raveon`, `sanav`, `siwi`, `smartsole`, `supermate`, `svias`, `swiftech`,
`xt013`.

The corresponding Groovy scripts are archived under
`archived-protocols/undocumented/<name>/<name>.groovy`. Archived Java protocol
files are kept in the same folder when available. The undocumented drivers
`cguard`, `fox`, and `ivt401` have archived Groovy scripts there, but this
checkout does not include matching archived Java protocol files for them.

## Recommended Next Steps

1. Add real protocol-document fixture samples for `gl100`, `gotop`, `svias`,
   `wondex`, `mictrack`, `laipac`, and `pretrace` as source examples become
   machine-readable.
2. Continue sourcing protocol documentation for the 37 undocumented drivers, then
   repeat this audit with the new documents.
