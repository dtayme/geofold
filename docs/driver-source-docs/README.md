<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Driver Source Documentation

Research date: 2026-06-12

This directory contains downloaded protocol documentation for the active Groovy
drivers under `drivers/`. The main source is Traccar's public protocol archive:

- https://www.traccar.org/protocols/

Additional vendor documents were downloaded where a public vendor protocol page
was available:

- https://help.mictrack.com/articles/protocols/

`drivers/example.groovy` is intentionally excluded because it documents a
fictional sample protocol.

## Download Summary

- Groovy production drivers checked during research and migration: 67
- Active documented or alias-documented production drivers retained under
  `drivers/`: 30
- Undocumented Groovy drivers moved to
  `archived-protocols/undocumented/<name>/<name>.groovy`: 37
- Downloaded documentation files: 48
- Drivers with exact public documentation match: 26
- Drivers covered by alias/vendor-family documentation: 4
- Drivers without a public downloadable protocol document found in this pass: 37

## Driver Mapping

| Driver | Status | Local documentation |
| --- | --- | --- |
| `ardi01` | Exact Traccar archive match | `traccar-protocols/ardi01/` |
| `box` | Exact Traccar archive match | `traccar-protocols/box/` |
| `cartrack` | Exact Traccar archive match | `traccar-protocols/cartrack/` |
| `carscop` | Exact Traccar archive match | `traccar-protocols/carscop/` |
| `enfora` | Exact Traccar archive match | `traccar-protocols/enfora/` |
| `gl100` | Alias: Queclink GL100/GL200-family docs | `traccar-protocols/gl200/` |
| `gotop` | Exact Traccar archive match | `traccar-protocols/gotop/` |
| `gpsmarker` | Exact Traccar archive match | `traccar-protocols/gpsmarker/` |
| `gpsmta` | Exact Traccar archive match | `traccar-protocols/gpsmta/` |
| `gt02` | Exact Traccar archive match | `traccar-protocols/gt02/` |
| `gt30` | Tentative alias: Meiligao GT30i/GT60/VT family | `external/gt30-alias-meiligao/` |
| `h02` | Exact Traccar archive match | `traccar-protocols/h02/` |
| `haicom` | Exact Traccar archive match | `traccar-protocols/haicom/` |
| `kenji` | Exact Traccar archive match | `traccar-protocols/kenji/` |
| `laipac` | Exact Traccar archive match | `traccar-protocols/laipac/` |
| `m2m` | Exact Traccar archive match | `traccar-protocols/m2m/` |
| `mictrack` | Vendor protocol downloads | `external/mictrack/` |
| `nto` | Exact Traccar archive match | `traccar-protocols/nto/` |
| `orion` | Exact Traccar archive match | `traccar-protocols/orion/` |
| `pt3000` | Exact Traccar archive match | `traccar-protocols/pt3000/` |
| `r16h` | Exact Traccar archive match | `traccar-protocols/r16h/` |
| `riti` | Exact Traccar archive match | `traccar-protocols/riti/` |
| `tk102` | Exact Traccar archive match | `traccar-protocols/tk102/` |
| `tlt2h` | Exact Traccar archive match | `traccar-protocols/tlt2h/` |
| `topflyftech` | Alias: Topflytech/T800X docs | `traccar-protocols/topflytech/`, `traccar-protocols/t800x/` |
| `tr20` | Exact Traccar archive match | `traccar-protocols/tr20/` |
| `tr900` | Exact Traccar archive match | `traccar-protocols/tr900/` |
| `trackbox` | Exact Traccar archive match | `traccar-protocols/trackbox/` |
| `wondex` | Exact Traccar archive match | `traccar-protocols/wondex/` |
| `ywt` | Exact Traccar archive match | `traccar-protocols/ywt/` |

## No Public Download Found

No public downloadable protocol document was found in the official Traccar
protocol archive for these drivers during this pass:

`appello`, `arknav`, `auro`, `austinnb`, `autograde`, `bstpl`, `cautela`,
`cguard`, `cradlepoint`, `disha`, `dway`, `ennfu`, `envotech`, `extremtrac`,
`flextrack`, `fox`, `freedom`, `gnx`, `homtecs`, `hunterpro`, `idpl`,
`ivt401`, `jido`, `manpower`, `mtx`, `neos`, `net`, `oko`, `pretrace`,
`raveon`, `sanav`, `siwi`, `smartsole`, `supermate`, `svias`, `swiftech`,
`xt013`.

These Groovy scripts have been moved out of active `drivers/` and into
`archived-protocols/undocumented/<name>/<name>.groovy`. When available, the
matching archived Java decoder is kept in the same protocol folder. The
undocumented drivers `cguard`, `fox`, and `ivt401` have Groovy archive folders
there, but this checkout does not include matching archived Java protocol files
for them.

## Notes

- `gt30` is not listed as a standalone protocol in the Traccar protocol archive.
  The downloaded Meiligao document is included as a likely family reference only.
- `gl100` is implemented as a Queclink `+RESP:GT...` text protocol, so the
  Queclink GL200-family archive documents are included as the closest public
  protocol references.
- `topflyftech` maps to the historical Topflytech protocol naming used by the
  archived Java decoder, so both `topflytech` and `t800x` documents are included.
