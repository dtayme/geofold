// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.NetworkMessage;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single Traccar protocol entry point for all script-based drivers.
 * One TCP server (with frame detection) and one UDP server (already packetized)
 * both funnel into {@link DriverProtocolDecoder} which dispatches to the
 * matching driver script at runtime.
 *
 * <p>Pipeline (TCP):
 * <pre>
 *   DriverFrameDecoder → StringEncoder → DriverMessageAdapter
 *       → DriverProtocolEncoder → DriverProtocolDecoder
 * </pre>
 *
 * {@link DriverMessageAdapter} replaces Netty's {@code StringDecoder}: it converts
 * each extracted frame to a {@code String} for text variants or a {@link BufReader}
 * for binary variants (determined by the channel attrs set by {@link DriverFrameDecoder}).
 */
@Singleton
public class DriverProtocol extends BaseProtocol {

    private final DriverRegistry registry;

    @Inject
    public DriverProtocol(Config config, DriverRegistry registry) {
        this.registry = registry;
        Set<DriverEndpoint> endpoints = new LinkedHashSet<>(registry.endpoints());

        int legacyPort = config.getInteger(Keys.PROTOCOL_PORT.withPrefix(getName()));
        if (legacyPort > 0) {
            endpoints.add(new DriverEndpoint(DriverTransport.TCP, legacyPort));
            endpoints.add(new DriverEndpoint(DriverTransport.UDP, legacyPort));
        }

        for (DriverEndpoint endpoint : endpoints) {
            switch (endpoint.transport()) {
                case TCP -> addTcpServer(config, endpoint.port());
                case UDP -> addUdpServer(config, endpoint.port());
                case HTTP -> addHttpServer(config, endpoint.port());
            }
        }
    }

    private void addTcpServer(Config config, int port) {
        addServer(new TrackerServer(config, getName(), false, port) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new DriverFrameDecoder(
                        registry, config.getInteger(Keys.DRIVER_FRAME_MAX_LENGTH), port));
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new DriverMessageAdapter(registry));
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry, config));
            }
        });
    }

    private void addUdpServer(Config config, int port) {
        addServer(new TrackerServer(config, getName(), true, port) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new DriverMessageAdapter(registry));
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry, config));
            }
        });
    }

    private void addHttpServer(Config config, int port) {
        addServer(new TrackerServer(config, getName(), false, port) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new HttpResponseEncoder());
                pipeline.addLast(new HttpRequestDecoder());
                pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_LENGTH));
                pipeline.addLast(new DriverHttpProtocolDecoder(DriverProtocol.this, registry, config));
            }
        });
    }

    @Override
    public Collection<String> getSupportedDataCommands() {
        Set<String> commands = new LinkedHashSet<>();
        commands.add(Command.TYPE_CUSTOM);
        for (DriverDefinition driver : registry.all()) {
            commands.addAll(driver.getSupportedCommands());
        }
        return commands;
    }

    @Override
    public void sendDataCommand(Channel channel, SocketAddress remoteAddress, Command command) {
        if (command.getType().equals(Command.TYPE_CUSTOM)) {
            super.sendDataCommand(channel, remoteAddress, command);
            return;
        }
        if (!getSupportedDataCommands().contains(command.getType())) {
            throw new RuntimeException("Command " + command.getType() + " is not supported in protocol " + getName());
        }
        channel.writeAndFlush(new NetworkMessage(command, remoteAddress));
    }
}
