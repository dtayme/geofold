<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>
-->

# Java Protocol Documentation Audit

Audit date: 2026-06-12

This audit evaluates documentation coverage for protocol implementations under
`src/main/java/org/traccar/protocol/`.

The comparison source is Traccar's public protocol documentation index:

- https://www.traccar.org/protocols/

This is a documentation-availability audit only. It does not claim that a Java
decoder or encoder is feature-complete against the protocol document; that
requires a protocol-by-protocol source review.

## Scope

- Java `*Protocol.java` implementations checked: 208
- Public Traccar protocol-document table entries found: 89
- Exact Java protocol name matches in the public document table: 66
- Official Traccar documentation outside the table: 1
- Clear family or alias documentation matches: 1
- Java protocol implementations without an exact public Traccar archive match:
  140
- Undocumented Java protocol implementations archived from the active source
  tree: 140
- Active documented, official, or alias-documented Java protocol
  implementations remaining in `src/main/java/org/traccar/protocol/`: 60
- Documented Java protocol implementations migrated to Groovy after this audit:
  8

## Method

1. Enumerated `src/main/java/org/traccar/protocol/*Protocol.java`.
2. Normalized each class by removing the `Protocol` suffix and lowercasing the
   result, for example `Gt06Protocol` -> `gt06`.
3. Parsed protocol keys and document links from the public Traccar protocol
   index.
4. Classified exact matches, known official non-table documentation, clear
   family or alias matches, and protocols requiring further documentation
   research.

## Exact Public Documentation Matches

These Java protocols have exact entries in Traccar's public protocol-document
table:

`adm`, `apel`, `aplicom`, `atrack`, `autofon`, `bce`, `cartrack`, `castel`,
`cellocator`, `cityeasy`, `easytrack`, `eelink`, `enfora`, `fifotrack`,
`galileo`, `gator`, `gl200`, `globalsat`, `globalstar`, `gps103`, `gpsgate`,
`gt06`, `huasheng`, `intellitrac`, `jmak`, `jt600`, `jt808`, `khd`, `m2m`,
`megastek`, `meiligao`, `meitrack`, `minifinder`, `minifinder2`, `mobilogix`,
`mta6`, `mxt`, `navigil`, `navis`, `noran`, `orion`, `progress`, `ramac`,
`riti`, `ruptela`, `skypatrol`, `startek`, `stl060`, `suntech`, `t55`,
`t622iridium`, `t800x`, `taip`, `teltonika`, `thinkpower`, `tk103`, `totem`,
`tramigo`, `tzone`, `ulbotech`, `v680`, `watch`, `wialon`, `xexun`, `xexun2`,
`xirgo`.

## Non-Table Official Documentation

| Java protocol | Documentation | Notes |
| --- | --- | --- |
| `osmand` | https://www.traccar.org/osmand/ | The public protocol index explicitly points Traccar Client/OsmAnd documentation to this separate page rather than the downloadable-protocol table. |

## Clear Family Or Alias Documentation

| Java protocol | Documentation table key | Evidence |
| --- | --- | --- |
| `topin` | `gt06` | The `gt06` documentation set includes `ZhongXun Topin Locator Communication Protocol.pdf`, which is a direct family document for the Topin protocol implementation. |

## Migrated To Groovy

| Protocol | Documentation | Notes |
| --- | --- | --- |
| `cartrack` | `docs/driver-source-docs/traccar-protocols/cartrack/` | Migrated to `drivers/cartrack.groovy`; Java implementation and protocol test archived under `archived-protocols/cartrack/`. |
| `cityeasy` | `docs/driver-source-docs/traccar-protocols/cityeasy/` | Migrated to `drivers/cityeasy.groovy`; Java implementation, encoder, and protocol test archived under `archived-protocols/cityeasy/`. |
| `enfora` | `docs/driver-source-docs/traccar-protocols/enfora/` | Migrated to `drivers/enfora.groovy`; Java implementation, encoder, and protocol test archived under `archived-protocols/enfora/`. |
| `m2m` | `docs/driver-source-docs/traccar-protocols/m2m/` | Migrated to `drivers/m2m.groovy`; Java implementation and protocol test archived under `archived-protocols/m2m/`. |
| `orion` | `docs/driver-source-docs/traccar-protocols/orion/` | Migrated to `drivers/orion.groovy`; Java implementation, frame decoder, and protocol test archived under `archived-protocols/orion/`. |
| `ramac` | `docs/driver-source-docs/traccar-protocols/ramac/` | Migrated to `drivers/ramac.groovy`; Java implementation and protocol test archived under `archived-protocols/ramac/`. |
| `riti` | `docs/driver-source-docs/traccar-protocols/riti/` | Migrated to `drivers/riti.groovy`; Java implementation and protocol test archived under `archived-protocols/riti/`. |
| `v680` | `docs/driver-source-docs/traccar-protocols/v680/` | Migrated to `drivers/v680.groovy`; Java implementation and protocol test archived under `archived-protocols/v680/`. |

## No Exact Public Traccar Archive Match

No exact downloadable protocol document was found in the public Traccar protocol
index for these Java protocol implementations during this pass:

`ais`, `alematics`, `anytrek`, `aquila`, `arknavx8`, `armoli`, `arnavi`,
`astra`, `at2000`, `autotrack`, `avema`, `avl301`, `b2316`, `blackkite`,
`blue`, `bws`, `c2stek`, `calamp`, `carcell`, `cguard`, `continental`,
`dingtek`, `dmt`, `dmthttp`, `dolphin`, `dragino`, `dsf22`, `dualcam`, `egts`,
`eseal`, `esky`, `fleetguide`, `flespi`, `flexapi`, `flexcomm`,
`flexiblereport`, `fox`, `freematics`, `futureway`, `g1rus`, `genx`, `gl601`,
`gosafe`, `gps056`, `granit`, `gs100`, `hoopo`, `hyn600`, `iotm`, `its`,
`ivt401`, `jimiphoto`, `jpkorjar`, `jt1078`, `l100`, `leafspy`, `m2c`,
`maestro`, `mavlink2`, `maxpb`, `milesmate`, `moovbox`, `motor`, `naviset`,
`ndtpv6`, `niot`, `nvs`, `nyitech`, `obddongle`, `oigo`, `omnicomm`,
`opengts`, `orbcomm`, `outsafe`, `owntracks`, `pacifictrack`, `pathaway`,
`piligrim`, `plugin`, `polte`, `portman`, `positrex`, `pricol`, `pst`,
`pt215`, `pt502`, `pt60`, `pui`, `r12w`, `racedynamics`, `radar`, `radshid`,
`recoda`, `retranslator`, `rftrack`, `robotrack`, `rst`, `s168`, `sabertek`,
`sanul`, `satsol`, `sigfox`, `smartcar`, `smokey`, `snapper`, `solarpowered`,
`spot`, `starcom`, `starlink`, `stb`, `t57`, `techtlt`, `techtocruz`, `tek`,
`telemax`, `telic`, `teratrack`, `thinkrace`, `thuraya`, `tlv`, `tmg`,
`trakmate`, `transync`, `trv`, `tt8850`, `ttnhttp`, `tytan`, `upro`, `uux`,
`valtrack`, `visiontek`, `vlt`, `vnet`, `vt200`, `vtfms`, `wli`, `wristband`,
`xexun3`, `xrb28`, `xt2400`.

These implementations have been moved to
`archived-protocols/undocumented/<protocol>/` with their protocol-specific
decoder, encoder, frame decoder, frame encoder, and poller classes where
present. They were also removed from `PortConfigSuffix.java` so the active
server no longer opens default listeners for undocumented Java protocols.
Protocol tests for those archived implementations were moved into the same
archive folders so active test compilation only targets active protocols.

## Notes

- Some protocols without an exact Traccar archive match may have vendor
  documentation outside the public Traccar protocol index. Examples likely
  include open ecosystem protocols such as OwnTracks, OpenGTS, Flespi, and TTN.
  They require separate source-specific research before being marked
  documented.
- Some public table entries now correspond to Groovy drivers or archived
  converted protocols instead of Java implementations, so they are not listed in
  the Java exact-match count.
- This report intentionally avoids treating substring matches as documentation
  coverage. For example, `pst` appearing inside `iStartek...` is not evidence for
  `PstProtocol`.

## Recommended Next Steps

1. Create a Java-protocol source-doc folder parallel to
   `docs/driver-source-docs/`, then download exact or vendor-family
   documentation for archived protocols that should be restored or migrated.
2. For protocols backed by public standards or third-party platforms, record the
   authoritative external source URL instead of forcing them into the Traccar
   archive-document model.
3. After documentation acquisition, run a feature-completeness audit for each
   Java protocol that is a candidate for Groovy migration or active maintenance.
