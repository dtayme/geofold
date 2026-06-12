// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import java.util.ArrayList;
import java.util.List;

/**
 * The compiled result of one driver script — holds all variants for a protocol family.
 */
public final class DriverDefinition {

    private final String name;
    private int defaultPort;
    private final List<VariantDefinition> variants = new ArrayList<>();

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

    public List<VariantDefinition> getVariants() {
        return variants;
    }

    public void addVariant(VariantDefinition variant) {
        variants.add(variant);
    }

    /**
     * Returns the first variant whose match closure accepts the given message,
     * or null if none match.
     */
    public VariantDefinition matchVariant(String message) {
        for (VariantDefinition v : variants) {
            if (v.matches(message)) {
                return v;
            }
        }
        return null;
    }
}
