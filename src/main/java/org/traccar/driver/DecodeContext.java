// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import org.traccar.NetworkMessage;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
    private final DriverDefinition driver;
    private final VariantDefinition variant;
    private final List<Position> emitted = new ArrayList<>();

    DecodeContext(DriverProtocolDecoder decoder, Channel channel,
                  SocketAddress remoteAddress, DriverDefinition driver, VariantDefinition variant) {
        this.decoder = decoder;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.driver = driver;
        this.variant = variant;
    }

    /** Gets or creates a device session for the given IMEI/unique ID. */
    public DeviceSession session(String uniqueId) {
        return decoder.session(channel, remoteAddress, uniqueId);
    }

    /**
     * Returns the existing session for the current channel without registering
     * a new device. Useful for protocols where some messages (e.g. command
     * responses) arrive after the device has already been identified.
     * Returns {@code null} if no session exists yet.
     */
    public DeviceSession session() {
        return decoder.session(channel, remoteAddress);
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
     * Populates {@code position} with the last known fix using the supplied
     * device time for timestamp-sensitive fallback logic.
     */
    public void lastLocation(Position position, Date deviceTime) {
        decoder.lastLocation(position, deviceTime);
    }

    /** Returns the remote network address for the current message, when available. */
    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    /** Returns the local channel address, when available. */
    public SocketAddress localAddress() {
        return channel != null ? channel.localAddress() : null;
    }

    /** Returns the local listener port, or {@code null} when unavailable. */
    public Integer localPort() {
        SocketAddress localAddress = localAddress();
        return localAddress instanceof InetSocketAddress address ? address.getPort() : null;
    }

    /** Returns {@code true} when the current channel is UDP/datagram based. */
    public boolean isUdp() {
        return channel instanceof DatagramChannel;
    }

    /** Returns {@code true} when the current channel is TCP/socket based. */
    public boolean isTcp() {
        return channel instanceof SocketChannel;
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
     * Sends a raw binary response back to the device on the current channel.
     * The byte array is copied into a Netty buffer; the caller's array is
     * not retained.
     */
    public void ack(byte[] response) {
        if (channel != null) {
            channel.writeAndFlush(new NetworkMessage(Unpooled.copiedBuffer(response), remoteAddress));
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

    /**
     * Reads a protocol-scoped integer config value for this driver. For example,
     * {@code ctx.configInt("mask", 0)} in {@code drivers/skypatrol.groovy}
     * reads the Traccar config key {@code skypatrol.mask}.
     */
    public int configInt(String suffix, int defaultValue) {
        return decoder.configInt(driver.getName(), suffix, defaultValue);
    }

    /**
     * Reads a protocol-scoped boolean config value for this driver.
     */
    public boolean configBoolean(String suffix, boolean defaultValue) {
        return decoder.configBoolean(driver.getName(), suffix, defaultValue);
    }

    /**
     * Reads a protocol-scoped string config value for this driver.
     */
    public String configString(String suffix, String defaultValue) {
        return decoder.configString(driver.getName(), suffix, defaultValue);
    }

    /**
     * Returns a mutable, channel-scoped key-value map that persists across decode calls
     * for the lifetime of the TCP connection. Use this for stateful protocols that need
     * to accumulate partial data across multiple frames (e.g. photo upload packets).
     *
     * <p>When the channel is {@code null} (unit tests without a real channel), each
     * call returns a fresh throwaway map — state will not persist across calls.
     */
    public Map<String, Object> store() {
        return ChannelStore.get(channel);
    }

    /**
     * Accumulates a position for protocols that produce multiple positions per
     * frame (e.g. TLT2H batch uploads). The decoder collects all emitted
     * positions alongside any value returned by the decode closure.
     */
    public void emit(Position position) {
        if (position != null) {
            emitted.add(position);
        }
    }

    /**
     * Returns a {@link DeviceAttrs} for looking up per-device attributes
     * (password, model, custom keys) from the given session.
     *
     * <p>Example:
     * <pre>
     * def session = ctx.session(imei)
     * def attrs   = ctx.deviceAttrs(session)
     * def pwd     = attrs.password('00000000')
     * </pre>
     */
    public DeviceAttrs deviceAttrs(DeviceSession session) {
        return new DeviceAttrs(decoder.cacheManager(), session.getDeviceId(), decoder.protocolName());
    }

    List<Position> collected() {
        return emitted;
    }
}
