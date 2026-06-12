// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;

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

    public void variant(String name, Closure<?> body) {
        VariantDefinition variant = new VariantDefinition(name);
        VariantBuilder builder = new VariantBuilder(variant);
        body.setDelegate(builder);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.call();
        definition.addVariant(variant);
    }
}
