// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import java.util.Locale;

public enum DriverTransport {

    TCP,
    UDP,
    HTTP;

    public static DriverTransport from(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "tcp" -> TCP;
            case "udp" -> UDP;
            case "http" -> HTTP;
            default -> throw new IllegalArgumentException("Unknown driver transport: " + value);
        };
    }
}
