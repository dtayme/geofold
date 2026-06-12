# GEOFOLD

## Overview

GEOFOLD is a fork of the Traccar GPS tracking server. This repository contains
the Java-based back-end service with fork-specific protocol and driver changes.
It supports GPS tracking devices, SQL database back ends, and a REST API.

The upstream Traccar project is available at:

- [Traccar website](https://www.traccar.org)
- [Traccar source repository](https://github.com/traccar/traccar)

## Features

Some of the available features include:

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms and notifications
- Account and device management
- Email and SMS support

## Build

Please read the upstream [build from source documentation](https://www.traccar.org/build/)
as a starting point. Fork-specific changes may require additional local setup.

## Upstream Credits

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## Licensing

The combined work in this repository is distributed under the GNU Affero General
Public License v3.0 or later. See [LICENSE](LICENSE).

Upstream-original Traccar code remains licensed under the Apache License,
Version 2.0. The upstream Apache license text is preserved in
[LICENSE.upstream](LICENSE.upstream), and upstream notices are retained per
Apache-2.0 section 4.

Everything at or before the `apache-2.0-final` tag is available solely under
Apache-2.0. Subsequent fork-original contributions and modifications are
licensed under AGPL-3.0-or-later.

This fork is not affiliated with or endorsed by Traccar Ltd. The Traccar name is
used only to identify the upstream project and is not used to brand this
distribution.
