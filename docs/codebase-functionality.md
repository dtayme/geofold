# Codebase Functionality Map

This document maps the main functional areas under `src/` and the runtime flows that connect them. It is intended as a starting point for maintainers who need to understand where behavior lives before making changes.

## Runtime Overview

The server entry point is `src/main/java/org/traccar/Main.java`.

Startup flow:

1. `Main` selects the configuration file, supports Windows service actions, and creates the Guice injector.
2. `MainModule`, `DatabaseModule`, and `WebModule` bind shared services, storage, web resources, provider integrations, and optional handlers.
3. Lifecycle services start in this order: `ScheduleManager`, `ServerManager`, `WebServer`, and `BroadcastService`.
4. `ServerManager` scans `org.traccar.protocol` for `BaseProtocol` subclasses and enables only protocols with configured ports.
5. Each enabled protocol creates Netty connectors and pipelines that decode device traffic into `Position` objects.
6. `ProcessingHandler` runs positions through enrichment, filtering, persistence, event detection, forwarding, notification, and websocket update paths.

Shutdown stops the lifecycle services and then shuts down the shared executor service.

## Core Server Framework

Location: `src/main/java/org/traccar`

This package contains the protocol server framework and shared Netty plumbing.

Key classes:

- `Main` starts the application and owns the global Guice injector.
- `MainModule` provides application-wide dependencies such as `Storage`, `ObjectMapper`, HTTP client, mail, SMS, geocoder, geolocation, map matching, forwarding, broadcast, and optional position handlers.
- `ServerManager` discovers enabled protocol classes and starts their connectors.
- `BaseProtocol` is the base for all device protocols. It tracks supported data and text commands and implements command delivery over live channels or SMS.
- `TrackerServer` creates TCP or UDP Netty servers for one protocol.
- `BasePipelineFactory` builds the common Netty pipeline: transport handlers, timeout, open channel registration, forwarding, `NetworkMessage` wrapping, logging, protocol handlers, remote address handling, `ProcessingHandler`, and final network event handling.
- `NetworkMessage`, `WrapperInboundHandler`, `WrapperOutboundHandler`, and `WrapperContext` preserve remote address metadata while letting protocol handlers work with raw payloads.
- `BaseProtocolDecoder`, `BaseProtocolEncoder`, `BaseFrameDecoder`, `BaseHttpProtocolDecoder`, and `BaseMqttProtocolDecoder` provide common decoding and encoding behavior for protocol implementations.
- `ProcessingHandler` is the central post-decode position pipeline.

Inbound device data generally follows this shape:

```text
socket/datagram
  -> BasePipelineFactory common handlers
  -> protocol frame/message decoder
  -> BaseProtocolDecoder subclass
  -> Position or collection of Position
  -> ProcessingHandler
  -> event handling, persistence, notifications, websocket updates
```

## Device Protocols

Location: `src/main/java/org/traccar/protocol`

The protocol package is the largest area of the server. It contains Java implementations for individual tracker protocols. Most protocols follow a common naming convention:

- `XProtocol` registers one or more `TrackerServer` connectors and wires protocol-specific pipeline handlers.
- `XProtocolDecoder` parses inbound device messages into `Position` instances.
- `XProtocolEncoder` converts `Command` objects into device-specific outbound payloads when supported.
- `XFrameDecoder` or `XFrameEncoder` handles protocol-specific message framing when the common Netty codecs are not enough.

Protocol decoders usually extend `BaseProtocolDecoder`, which provides:

- device session lookup through `ConnectionManager`;
- protocol-aware speed and timezone conversion;
- last-location fallback handling;
- media file persistence;
- message statistics;
- device online status updates;
- queued command delivery after successful device communication.

Adding or changing a Java protocol usually affects:

- the protocol class and decoder/encoder/frame decoder in `org.traccar.protocol`;
- tests in `src/test/java/org/traccar/protocol`;
- configured ports in the server configuration key set;
- command support declarations in the protocol class if outbound commands are supported.

## Groovy Driver System

Location: `src/main/java/org/traccar/driver`

This fork adds a script-based driver system for implementing tracker protocols as `.groovy` files in `drivers/` without Java changes or restarts. The detailed authoring reference is `docs/driver-development.md`.

Core responsibilities:

- `DriverRegistry` loads scripts from `drivers/`, caches `DriverDefinition` instances, and hot-reloads created, modified, or deleted `.groovy` files.
- `DriverDSL`, `ProtocolBuilder`, `VariantBuilder`, `FrameSpec`, and `AlarmMapBuilder` define the Groovy authoring API.
- `DriverProtocol` exposes a normal Traccar protocol entry point backed by one TCP and one UDP server.
- `DriverFrameDecoder` extracts TCP frames and uses first-byte hints to select text or binary framing.
- `DriverMessageAdapter` converts extracted frames to `String` for text variants or `BufReader` for binary variants.
- `DriverProtocolDecoder` invokes the selected variant's decode closure and returns positions.
- `DriverProtocolEncoder` invokes encode closures for outbound commands.
- `DecodeContext`, `EncodeContext`, `DeviceAttrs`, and `BufReader` are the controlled API exposed to scripts.

Public driver API changes must keep `docs/driver-development.md` and `drivers/example.groovy` in sync.

## Position Processing Pipeline

Locations:

- `src/main/java/org/traccar/ProcessingHandler.java`
- `src/main/java/org/traccar/handler`
- `src/main/java/org/traccar/handler/events`
- `src/main/java/org/traccar/handler/network`

`ProcessingHandler` receives decoded `Position` objects and serializes processing per device. It temporarily adds the device to `CacheManager`, passes the position through ordered handlers, then removes the temporary cache reference.

Position handlers run in this order when enabled:

1. early computed attributes
2. outdated position handling
3. time validation
4. network geolocation
5. hemisphere correction
6. map matching
7. distance calculation
8. filtering
9. geofence checks
10. reverse geocoding
11. speed limit lookup
12. motion state
13. late computed attributes
14. driver assignment
15. attribute copying
16. engine hours
17. position forwarding
18. database persistence

Event handlers analyze unfiltered positions after the position handler chain:

- media
- command results
- overspeed
- behavior
- fuel
- motion
- geofence
- proximity
- alarm
- ignition
- maintenance
- driver

`PostProcessHandler` updates live caches and broadcasts position updates after event analysis. `PositionLogger` writes the final position log entry. Filtered positions still acknowledge delayed acknowledgements but skip persistence and event analysis.

Network handlers in `handler/network` cover open/close channel tracking, logging, delayed acknowledgement coordination, remote address assignment, transparent network forwarding, and final network event handling.

## Sessions, Cache, and Broadcast

Locations:

- `src/main/java/org/traccar/session`
- `src/main/java/org/traccar/session/cache`
- `src/main/java/org/traccar/broadcast`

`ConnectionManager` owns live device sessions and websocket listener updates. It maps devices to protocol channels, handles unknown devices, optionally auto-registers new devices, updates online/offline/unknown status, and forwards device, position, event, and log updates to users.

`CacheManager` keeps the live object graph needed during processing and notifications. It caches server settings, active devices, recent positions, permission links, grouped relationships, calendars, notifications, geofences, maintenance records, drivers, and users. It listens to `BroadcastService` so local and clustered nodes can invalidate objects and permissions consistently.

`BroadcastService` abstracts cross-node propagation. `MainModule` can provide multicast, Redis, or null broadcast implementations depending on configuration.

Motion and overspeed state processors under `session` support event handlers and reporting logic.

## Storage and Database

Locations:

- `src/main/java/org/traccar/storage`
- `src/main/java/org/traccar/database`
- `schema/`

`Storage` is the persistence abstraction. `DatabaseStorage` uses SQL through `QueryBuilder`; `MemoryStorage` is available for memory-backed operation. Storage requests are built with `Request`, `Columns`, `Condition`, and `Order`.

`DatabaseModule` configures HikariCP, optionally loads an external JDBC driver, and runs Liquibase migrations from the configured changelog.

Database service classes provide higher-level workflows:

- `DeviceLookupService` resolves device identifiers during protocol decoding.
- `CommandsManager` sends live commands, SMS commands, push/service commands, or stores queued commands.
- `NotificationManager` persists events, forwards event payloads, filters matching notifications, and dispatches notificators.
- `BufferingManager` controls buffered position release into `ProcessingHandler`.
- `MediaManager` writes media files.
- `StatisticsManager` tracks runtime statistics.
- `LocaleManager`, `LdapProvider`, and `OpenIdProvider` support user-facing services and authentication integrations.

## Web, API, and Security

Locations:

- `src/main/java/org/traccar/web`
- `src/main/java/org/traccar/api`
- `src/main/java/org/traccar/api/resource`
- `src/main/java/org/traccar/api/security`
- `openapi.yaml`

`WebServer` embeds Jetty. It serves the static web application, `/api/*` Jersey resources, media files, the async socket endpoint, optional console servlet, optional MCP endpoint, and an OsmAnd client proxy when that protocol is enabled.

`WebModule` installs servlet filters and servlet mappings:

- override text and file filters for custom web assets;
- media filtering for `/api/media/*`;
- well-known endpoint servlet;
- async socket servlet at `/api/socket`.

API resources are grouped by domain object and workflow:

- object resources: users, devices, groups, geofences, calendars, attributes, drivers, maintenance, notifications, reports, commands, positions, events, statistics, server, permissions, orders, media, video streams;
- security resources and services: sessions, passwords, tokens, OpenID Connect, shares, request filtering, principals, and permission checks.

Most resources extend `BaseResource`, `BaseObjectResource`, `SimpleObjectResource`, or `ExtendedObjectResource`, sharing access to `Storage` and `PermissionsService`.

## Models and Domain Objects

Location: `src/main/java/org/traccar/model`

The model package defines persistent and transient domain objects used by storage, API resources, protocol decoders, handlers, notifications, and reports.

Important model groups:

- core identity and authorization: `User`, `ManagedUser`, `Permission`, `Server`;
- fleet structure: `Device`, `Group`, `LinkedDevice`;
- telemetry and events: `Position`, `Event`, `Message`, `Statistics`, `LogRecord`;
- user configuration: `Attribute`, `Notification`, `Calendar`, `Geofence`, `Maintenance`, `Driver`;
- commands: `Command`, `BaseCommand`, `QueuedCommand`;
- reporting: `Report`, `Order`;
- supporting value objects: `CellTower`, `WifiAccessPoint`, `Network`, `Pair`, `AttributeMap`, `DeviceAccumulators`.

Most persistent models inherit from `BaseModel`; configurable models typically extend `ExtendedModel` to carry arbitrary attributes.

## Commands

Locations:

- `src/main/java/org/traccar/command`
- `src/main/java/org/traccar/database/CommandsManager.java`
- protocol encoders in `src/main/java/org/traccar/protocol`

Commands can be delivered through several paths:

- live device channel through `DeviceSession.sendCommand`;
- protocol SMS path through `BaseProtocol.sendTextCommand`;
- custom push/service senders selected by `CommandSenderManager`;
- queued database commands when the device is offline and queuing is allowed.

`CommandSenderManager` selects configured senders such as Firebase, Traccar client service, or FindHub based on device attributes and server configuration. `CommandsManager.readQueuedCommands` removes queued commands after read and emits queued-command-sent events.

## Notifications and Notificators

Locations:

- `src/main/java/org/traccar/notification`
- `src/main/java/org/traccar/notificators`
- `src/main/java/org/traccar/database/NotificationManager.java`

Event handlers emit `Event` objects. `NotificationManager` persists events, optionally forwards them, filters device notifications by type, alarm, geofence, calendar, and blocked users, then dispatches through configured notificators.

Supported notificator classes include command, Firebase, mail, Pushover, SMS, Telegram, Traccar, web callback, and WhatsApp.

`NotificationFormatter`, `TextTemplateFormatter`, and `PropertiesProvider` prepare localized and templated notification messages.

## Reports and Scheduled Jobs

Locations:

- `src/main/java/org/traccar/reports`
- `src/main/java/org/traccar/schedule`
- `templates/`

The reports package builds query-backed report data and exports it in several formats.

Report providers include:

- route
- trips
- stops
- summary
- events
- combined
- geofence
- devices

Export providers support CSV, GPX, and KML. `ReportExecutor` runs report generation, and `ReportMailer` sends scheduled reports. Report models and sections define the shape of tabular output.

`ScheduleManager` runs scheduled tasks such as report delivery, statistics updates, status checks, and cleanup jobs.

## External Provider Integrations

Locations:

- `src/main/java/org/traccar/geocoder`
- `src/main/java/org/traccar/geolocation`
- `src/main/java/org/traccar/mapmatcher`
- `src/main/java/org/traccar/speedlimit`
- `src/main/java/org/traccar/forward`
- `src/main/java/org/traccar/mail`
- `src/main/java/org/traccar/sms`

Provider implementations are selected in `MainModule` from configuration.

Reverse geocoding supports providers such as Google, Nominatim, LocationIQ, OpenCage, HERE, TomTom, Mapbox, MapTiler, Geoapify, Baidu, Tencent, AutoNavi, and others. Geolocation supports Google, OpenCellId, Unwired, and universal endpoint providers. Map matching currently routes through the Traccar provider. Speed limits route through Overpass.

Forwarding supports:

- event forwarding as JSON, AMQP, Kafka, or MQTT;
- position forwarding as URL form/json, AMQP, Kafka, MQTT, Redis, or Wialon;
- raw network forwarding through `NetworkForwarderHandler`.

Mail and SMS integrations are abstracted by `MailManager` and `SmsManager`.

## Configuration

Location: `src/main/java/org/traccar/config`

`Config` loads server configuration and exposes typed reads through `ConfigKey` definitions in `Keys`. `PortConfigSuffix` and `ConfigSuffix` support per-protocol settings such as ports, SSL, address binding, timeouts, speed units, and protocol-specific server identifiers.

Most optional features are enabled by configuration and bound to `null` when disabled. Callers commonly use nullable injection and filter out disabled handlers or providers.

## Helpers and Utilities

Location: `src/main/java/org/traccar/helper`

Helper classes centralize common parsing, conversion, validation, and logging behavior used throughout protocol decoders and handlers.

Common utility areas:

- byte and binary helpers: `BitUtil`, `BitBuffer`, `BufferUtil`, `BcdUtil`, `DataConverter`, `Checksum`;
- parsing helpers: `Parser`, `PatternBuilder`, `PatternUtil`, `DateBuilder`;
- geospatial helpers: `DistanceCalculator`, `CoordinateUtil`, `GeofenceUtil`, `PositionUtil`;
- domain helpers: `AttributeUtil`, `DeviceUtil`, `SessionHelper`, `UserUtil`;
- web and JSON helpers: `WebHelper`, `ObjectMapperContextResolver`;
- logging and diagnostics: `Log`, `LogAction`, `PositionLogger`;
- protocol-specific utility decoding such as `ObdDecoder`.

## Tests

Location: `src/test/java/org/traccar`

Tests mirror the main package layout. The largest test area is `protocol`, with decoder and frame decoder coverage for many device protocols. Other focused tests cover storage query building, helper utilities, geofence geometry, handlers, reports, geocoding/geolocation providers, cache/session state, speed limits, configuration, and forwarding.

Use targeted tests when changing a small subsystem, and broaden test selection when changing shared classes such as `BaseProtocolDecoder`, `ProcessingHandler`, `CacheManager`, `Storage`, or driver APIs.

Typical commands:

```powershell
.\gradlew.bat test
.\gradlew.bat compileJava
.\gradlew.bat test --tests org.traccar.protocol.TeltonikaProtocolDecoderTest
```

## Where to Start for Common Changes

Use this map to find the first files to inspect:

| Change type | Start here |
| --- | --- |
| New Java tracker protocol | `BaseProtocol`, similar classes in `protocol`, protocol tests |
| New Groovy driver protocol | `docs/driver-development.md`, `drivers/example.groovy`, `driver` package |
| Decode bug for one protocol | matching `*ProtocolDecoder`, `*FrameDecoder`, protocol test |
| Position enrichment or filtering | `ProcessingHandler`, `handler/*Handler` |
| New event type | `handler/events`, `NotificationManager`, `Event`, API/report consumers |
| API endpoint change | `api/resource`, `api/security`, `openapi.yaml` |
| Storage query behavior | `storage`, `database`, query builder tests |
| Permission or cache issue | `CacheManager`, `ConnectionManager`, `PermissionsService` |
| Command delivery issue | `CommandsManager`, `CommandSenderManager`, protocol encoder |
| Notification delivery issue | `NotificationManager`, `notification`, `notificators` |
| Web server behavior | `WebServer`, `WebModule`, servlet filters |
| Provider integration | provider package plus `MainModule` binding |

