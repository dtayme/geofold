// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared channel-scoped key-value store used by both {@link DecodeContext} and
 * {@link EncodeContext}. Allows decode closures to write state (e.g. detected
 * protocol variant or device prefix) that encode closures can read on the same
 * channel.
 */
final class ChannelStore {

    static final AttributeKey<Map<String, Object>> KEY =
            AttributeKey.valueOf("driver.channel.store");

    static Map<String, Object> get(Channel channel) {
        if (channel == null) {
            return new HashMap<>();
        }
        var attr = channel.attr(KEY);
        if (attr.get() == null) {
            attr.set(new HashMap<>());
        }
        return attr.get();
    }

    private ChannelStore() {
    }
}
