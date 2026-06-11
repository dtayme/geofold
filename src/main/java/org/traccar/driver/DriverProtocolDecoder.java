package org.traccar.driver;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;
import org.traccar.session.cache.CacheManager;

import java.net.SocketAddress;
import java.util.List;

/**
 * Finds the driver variant that matches the incoming message, calls its decode
 * closure, and tags the driver/variant names on the channel for the encoder.
 *
 * <p>Accepts two message types from {@link DriverMessageAdapter}:
 * <ul>
 *   <li>{@code String} — text protocol; variant is resolved by calling each
 *       registered driver's match closure.
 *   <li>{@link BufReader} — binary protocol; variant was already resolved by
 *       {@link DriverFrameDecoder} (channel attrs are pre-set).
 * </ul>
 */
public class DriverProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DriverProtocolDecoder.class);

    private final DriverRegistry registry;

    public DriverProtocolDecoder(Protocol protocol, DriverRegistry registry) {
        super(protocol);
        this.registry = registry;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        if (msg instanceof BufReader buf) {
            return decodeBinary(channel, remoteAddress, buf);
        }
        String message = msg.toString().trim();
        if (message.isEmpty()) {
            return null;
        }
        return decodeText(channel, remoteAddress, message);
    }

    private Object decodeText(Channel channel, SocketAddress remoteAddress, String message) {
        DriverRegistry.DriverMatch match = registry.match(message);
        if (match == null) {
            LOGGER.debug("No driver matched message: {}", message.length() > 80
                    ? message.substring(0, 80) + "…" : message);
            return null;
        }

        VariantDefinition variant = match.variant();
        DriverDefinition driver   = match.driver();

        if (channel != null) {
            channel.attr(DriverFrameDecoder.DRIVER_KEY).set(driver.getName());
            channel.attr(DriverFrameDecoder.VARIANT_KEY).set(variant.getName());
        }

        DecodeContext ctx = new DecodeContext(this, channel, remoteAddress, variant);
        Object result = variant.getDecodeClosure().call(message, ctx);
        return collectResult(ctx, result);
    }

    private Object decodeBinary(Channel channel, SocketAddress remoteAddress, BufReader buf) {
        String driverName  = channel != null ? channel.attr(DriverFrameDecoder.DRIVER_KEY).get() : null;
        String variantName = channel != null ? channel.attr(DriverFrameDecoder.VARIANT_KEY).get() : null;

        if (driverName == null || variantName == null) {
            return null;
        }

        DriverDefinition driver = registry.get(driverName);
        if (driver == null) {
            return null;
        }

        VariantDefinition variant = null;
        for (VariantDefinition v : driver.getVariants()) {
            if (v.getName().equals(variantName)) {
                variant = v;
                break;
            }
        }

        if (variant == null || variant.getDecodeClosure() == null) {
            return null;
        }

        DecodeContext ctx = new DecodeContext(this, channel, remoteAddress, variant);
        try {
            Object result = variant.getDecodeClosure().call(buf, ctx);
            return collectResult(ctx, result);
        } finally {
            buf.release();
        }
    }

    private Object collectResult(DecodeContext ctx, Object result) {
        List<Position> collected = ctx.collected();
        if (!collected.isEmpty()) {
            if (result instanceof Position position) {
                collected.add(position);
            }
            return collected;
        }
        if (result instanceof Position position) {
            return position;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers exposed to driver decode closures via DecodeContext
    // -------------------------------------------------------------------------

    DeviceSession session(Channel channel, SocketAddress remoteAddress, String uniqueId) {
        return getDeviceSession(channel, remoteAddress, uniqueId);
    }

    void lastLocation(Position position) {
        getLastLocation(position, null);
    }

    String protocolName() {
        return getProtocolName();
    }

    CacheManager cacheManager() {
        return getCacheManager();
    }
}
