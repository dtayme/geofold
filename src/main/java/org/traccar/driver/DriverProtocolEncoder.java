package org.traccar.driver;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.model.Command;

/**
 * Reads the driver/variant names from the channel attribute (set by
 * {@link DriverProtocolDecoder} on first message) and calls the matching
 * variant's encode closure.
 */
public class DriverProtocolEncoder extends BaseProtocolEncoder {

    private final DriverRegistry registry;

    public DriverProtocolEncoder(Protocol protocol, DriverRegistry registry) {
        super(protocol);
        this.registry = registry;
    }

    @Override
    protected Object encodeCommand(Channel channel, Command command) {
        if (channel == null) {
            return null;
        }

        String driverName  = channel.attr(DriverFrameDecoder.DRIVER_KEY).get();
        String variantName = channel.attr(DriverFrameDecoder.VARIANT_KEY).get();

        if (driverName == null || variantName == null) {
            return null;
        }

        DriverDefinition driver = registry.get(driverName);
        if (driver == null) {
            return null;
        }

        for (VariantDefinition variant : driver.getVariants()) {
            if (variant.getName().equals(variantName) && variant.getEncodeClosure() != null) {
                EncodeContext ctx = new EncodeContext(this, command);
                Object result = variant.getEncodeClosure().call(command, ctx);
                return result;
            }
        }

        return null;
    }

    // Expose getUniqueId to EncodeContext
    String uniqueId(long deviceId) {
        return getUniqueId(deviceId);
    }
}
