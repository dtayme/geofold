package org.traccar.driver;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;

/**
 * Finds the driver variant that matches the incoming message, calls its decode
 * closure, and tags the driver/variant names on the channel for the encoder.
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
        String message = msg.toString().trim();
        if (message.isEmpty()) {
            return null;
        }

        DriverRegistry.DriverMatch match = registry.match(message);
        if (match == null) {
            LOGGER.debug("No driver matched message: {}", message.length() > 80
                    ? message.substring(0, 80) + "…" : message);
            return null;
        }

        VariantDefinition variant = match.variant();
        DriverDefinition driver = match.driver();

        // Tag the channel so the encoder knows which variant to use
        if (channel != null) {
            channel.attr(DriverFrameDecoder.DRIVER_KEY).set(driver.getName());
            channel.attr(DriverFrameDecoder.VARIANT_KEY).set(variant.getName());
        }

        // Build a decode context and call the variant's decode closure
        DecodeContext ctx = new DecodeContext(this, channel, remoteAddress, variant);
        Object result = variant.getDecodeClosure().call(message, ctx);

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
}
