// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

public record DriverEndpoint(DriverTransport transport, int port) {

    public DriverEndpoint {
        if (port <= 0) {
            throw new IllegalArgumentException("Driver endpoint port must be positive");
        }
    }
}
