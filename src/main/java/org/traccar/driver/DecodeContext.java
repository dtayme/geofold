package org.traccar.driver;

import io.netty.channel.Channel;
import org.traccar.NetworkMessage;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;

/**
 * Passed as the second argument to a variant's {@code decode} closure.
 * Provides all Traccar infrastructure the closure needs without exposing
 * the full decoder internals.
 *
 * <p>Typical usage inside a driver script:
 * <pre>
 * decode { msg, ctx ->
 *     def session = ctx.session("123456789012345")
 *     if (!session) return null
 *     def pos = ctx.newPosition()
 *     // ... populate pos ...
 *     return pos
 * }
 * </pre>
 */
public final class DecodeContext {

    private final DriverProtocolDecoder decoder;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final VariantDefinition variant;

    DecodeContext(DriverProtocolDecoder decoder, Channel channel,
                  SocketAddress remoteAddress, VariantDefinition variant) {
        this.decoder = decoder;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.variant = variant;
    }

    /** Gets or creates a device session for the given IMEI/unique ID. */
    public DeviceSession session(String uniqueId) {
        return decoder.session(channel, remoteAddress, uniqueId);
    }

    /** Creates a new {@link Position} pre-tagged with the protocol name. */
    public Position newPosition() {
        return new Position(decoder.protocolName());
    }

    /**
     * Populates {@code position} with the last known fix when GPS is unavailable.
     * Mirrors {@code BaseProtocolDecoder.getLastLocation()}.
     */
    public void lastLocation(Position position) {
        decoder.lastLocation(position);
    }

    /**
     * Sends a raw string response back to the device on the current channel.
     * Used for acknowledgement messages (e.g. HQ R12 ACK).
     */
    public void ack(String response) {
        if (channel != null) {
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    /**
     * Resolves a device event string to a Traccar alarm constant using this
     * variant's alarm map, optionally considering the device model.
     */
    public String alarm(String event, String model) {
        return variant.resolveAlarm(event, model);
    }

    /** Convenience overload when the variant has no model-aware alarms. */
    public String alarm(String event) {
        return variant.resolveAlarm(event, null);
    }

    /** Returns the device model extracted from the message by this variant's model closure. */
    public String model(String message) {
        return variant.extractModel(message);
    }
}
