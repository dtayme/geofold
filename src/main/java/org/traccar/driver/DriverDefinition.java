// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The compiled result of one driver script — holds all variants for a protocol family.
 */
public final class DriverDefinition {

    private final String name;
    private int defaultPort;
    private EnumSet<DriverTransport> transports = EnumSet.of(DriverTransport.TCP);
    private final List<VariantDefinition> variants = new ArrayList<>();
    private final Set<String> supportedCommands = new LinkedHashSet<>();

    public DriverDefinition(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public Set<DriverTransport> getTransports() {
        return Set.copyOf(transports);
    }

    public void setTransports(Set<DriverTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            throw new IllegalArgumentException("At least one driver transport is required");
        }
        this.transports = EnumSet.copyOf(transports);
    }

    public boolean supportsTransport(DriverTransport transport) {
        return transports.contains(transport);
    }

    public List<VariantDefinition> getVariants() {
        return variants;
    }

    public void addVariant(VariantDefinition variant) {
        variants.add(variant);
    }

    public Set<String> getSupportedCommands() {
        return supportedCommands;
    }

    public void addSupportedCommands(String... commandTypes) {
        for (String commandType : commandTypes) {
            if (commandType != null && !commandType.isBlank()) {
                supportedCommands.add(commandType);
            }
        }
    }

    /**
     * Returns the first variant whose match closure accepts the given message,
     * or null if none match.
     */
    public VariantDefinition matchVariant(Object message) {
        for (VariantDefinition v : variants) {
            if (v.matches(message)) {
                return v;
            }
        }
        return null;
    }
}
