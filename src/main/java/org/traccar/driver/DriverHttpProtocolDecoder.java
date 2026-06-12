// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.traccar.NetworkMessage;
import org.traccar.BaseHttpProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.model.Command;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;
import org.traccar.session.cache.CacheManager;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class DriverHttpProtocolDecoder extends BaseHttpProtocolDecoder {

    private final DriverRegistry registry;

    public DriverHttpProtocolDecoder(Protocol protocol, DriverRegistry registry) {
        super(protocol);
        this.registry = registry;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) {
        DriverHttpRequest request = new DriverHttpRequest((FullHttpRequest) msg);
        DriverRegistry.DriverMatch match = registry.match(request, DriverTransport.HTTP, localPort(channel));
        if (match == null) {
            sendResponse(channel, HttpResponseStatus.NOT_FOUND);
            return null;
        }

        DriverHttpContext ctx = new DriverHttpContext(this, channel, remoteAddress, match.variant());
        Object result = match.variant().getDecodeClosure().call(request, ctx);
        Object collected = collectResult(ctx, result);
        if (!ctx.hasResponded()) {
            sendResponse(channel, collected != null ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST);
        }
        return collected;
    }

    private Object collectResult(DriverHttpContext ctx, Object result) {
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

    DeviceSession session(Channel channel, SocketAddress remoteAddress, String uniqueId) {
        return getDeviceSession(channel, remoteAddress, uniqueId);
    }

    DeviceSession session(Channel channel, SocketAddress remoteAddress) {
        return getDeviceSession(channel, remoteAddress);
    }

    void lastLocation(Position position, Date deviceTime) {
        getLastLocation(position, deviceTime);
    }

    String protocolName() {
        return getProtocolName();
    }

    CacheManager cacheManager() {
        return getCacheManager();
    }

    Command nextQueuedCommand(long deviceId) {
        Collection<Command> commands = getCommandsManager().readQueuedCommands(deviceId, 1);
        return commands.isEmpty() ? null : commands.iterator().next();
    }

    void sendHttpResponse(Channel channel, int status, String body, String contentType) {
        if (channel == null) {
            return;
        }
        var content = body != null
                ? Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
                : Unpooled.buffer(0);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), content);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        if (contentType != null) {
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        channel.writeAndFlush(new NetworkMessage(response, channel.remoteAddress()));
    }

    void sendHttpResponse(Channel channel, int status, byte[] body, String contentType) {
        if (channel == null) {
            return;
        }
        var content = body != null ? Unpooled.copiedBuffer(body) : Unpooled.buffer(0);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), content);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        if (contentType != null) {
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        channel.writeAndFlush(new NetworkMessage(response, channel.remoteAddress()));
    }

    private Integer localPort(Channel channel) {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress address) {
            return address.getPort();
        } else {
            return null;
        }
    }

    @Override
    protected void sendQueuedCommands(Channel channel, SocketAddress remoteAddress, long deviceId) {
    }
}
