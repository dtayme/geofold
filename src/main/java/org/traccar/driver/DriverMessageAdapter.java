// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Replaces Netty's {@code StringDecoder} in the driver pipeline.
 *
 * <p>Inbound {@link ByteBuf} frames arrive from {@link DriverFrameDecoder}.
 * This handler converts each frame to either:
 * <ul>
 *   <li>A {@link BufReader} — for binary variants (where the channel attrs were
 *       set by {@link DriverFrameDecoder} to identify a binary variant).
 *   <li>A {@code String} (UTF-8) — for all text variants (the default).
 * </ul>
 *
 * <p>The choice is determined by looking up the channel attrs set by
 * {@link DriverFrameDecoder} and checking {@link VariantDefinition#isBinary()}.
 */
public class DriverMessageAdapter extends MessageToMessageDecoder<ByteBuf> {

    private final DriverRegistry registry;

    public DriverMessageAdapter(DriverRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (isBinaryVariant(ctx)) {
            // Retain because MessageToMessageDecoder releases the input after decode(),
            // but BufReader wraps the ByteBuf and needs to keep it alive.
            out.add(new BufReader(msg.retain()));
        } else {
            out.add(msg.toString(StandardCharsets.UTF_8));
        }
    }

    private boolean isBinaryVariant(ChannelHandlerContext ctx) {
        String driverName  = ctx.channel().attr(DriverFrameDecoder.DRIVER_KEY).get();
        String variantName = ctx.channel().attr(DriverFrameDecoder.VARIANT_KEY).get();
        if (driverName == null || variantName == null) {
            return false;
        }
        DriverDefinition driver = registry.get(driverName);
        if (driver == null) {
            return false;
        }
        for (VariantDefinition variant : driver.getVariants()) {
            if (variant.getName().equals(variantName)) {
                return variant.isBinary();
            }
        }
        return false;
    }
}
