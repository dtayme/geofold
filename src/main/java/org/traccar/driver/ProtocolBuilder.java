// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;

import java.util.EnumSet;
import java.util.Set;

/**
 * Delegate for the {@code protocol(name) { }} block.
 */
public final class ProtocolBuilder {

    private final DriverDefinition definition;

    public ProtocolBuilder(DriverDefinition definition) {
        this.definition = definition;
    }

    public void port(int port) {
        definition.setDefaultPort(port);
    }

    public void transport(String... transports) {
        if (transports == null || transports.length == 0) {
            throw new IllegalArgumentException("At least one driver transport is required");
        }
        Set<DriverTransport> values = EnumSet.noneOf(DriverTransport.class);
        for (String transport : transports) {
            values.add(DriverTransport.from(transport));
        }
        definition.setTransports(values);
    }

    public void tcp() {
        definition.setTransports(EnumSet.of(DriverTransport.TCP));
    }

    public void udp() {
        definition.setTransports(EnumSet.of(DriverTransport.UDP));
    }

    public void http() {
        definition.setTransports(EnumSet.of(DriverTransport.HTTP));
    }

    public void commands(String... commandTypes) {
        definition.addSupportedCommands(commandTypes);
    }

    public void variant(String name, Closure<?> body) {
        VariantDefinition variant = new VariantDefinition(name);
        VariantBuilder builder = new VariantBuilder(variant);
        body.setDelegate(builder);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.call();
        definition.addVariant(variant);
    }
}
