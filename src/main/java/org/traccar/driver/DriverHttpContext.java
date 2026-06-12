// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.channel.Channel;
import org.traccar.model.Command;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class DriverHttpContext {

    private final DriverHttpProtocolDecoder decoder;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final VariantDefinition variant;
    private final List<Position> emitted = new ArrayList<>();
    private boolean responded;

    DriverHttpContext(DriverHttpProtocolDecoder decoder, Channel channel,
                      SocketAddress remoteAddress, VariantDefinition variant) {
        this.decoder = decoder;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.variant = variant;
    }

    public DeviceSession session(String uniqueId) {
        return decoder.session(channel, remoteAddress, uniqueId);
    }

    public DeviceSession session() {
        return decoder.session(channel, remoteAddress);
    }

    public Position newPosition() {
        return new Position(decoder.protocolName());
    }

    public void lastLocation(Position position) {
        decoder.lastLocation(position, null);
    }

    public void lastLocation(Position position, Date deviceTime) {
        decoder.lastLocation(position, deviceTime);
    }

    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    public SocketAddress localAddress() {
        return channel != null ? channel.localAddress() : null;
    }

    public Integer localPort() {
        SocketAddress localAddress = localAddress();
        return localAddress instanceof InetSocketAddress address ? address.getPort() : null;
    }

    public void ok() {
        status(200);
    }

    public void ok(String body) {
        text(200, body);
    }

    public void ok(byte[] body) {
        binary(200, body, "application/octet-stream");
    }

    public void badRequest() {
        status(400);
    }

    public void notFound() {
        status(404);
    }

    public void status(int status) {
        decoder.sendHttpResponse(channel, status, (String) null, null);
        responded = true;
    }

    public void text(int status, String body) {
        decoder.sendHttpResponse(channel, status, body, "text/plain");
        responded = true;
    }

    public void json(int status, String body) {
        decoder.sendHttpResponse(channel, status, body, "application/json");
        responded = true;
    }

    public void binary(int status, byte[] body, String contentType) {
        decoder.sendHttpResponse(channel, status, body, contentType);
        responded = true;
    }

    public Command nextQueuedCommand(long deviceId) {
        return decoder.nextQueuedCommand(deviceId);
    }

    public String alarm(String event, String model) {
        return variant.resolveAlarm(event, model);
    }

    public String alarm(String event) {
        return variant.resolveAlarm(event, null);
    }

    public void emit(Position position) {
        if (position != null) {
            emitted.add(position);
        }
    }

    public DeviceAttrs deviceAttrs(DeviceSession session) {
        return new DeviceAttrs(decoder.cacheManager(), session.getDeviceId(), decoder.protocolName());
    }

    boolean hasResponded() {
        return responded;
    }

    List<Position> collected() {
        return emitted;
    }
}
