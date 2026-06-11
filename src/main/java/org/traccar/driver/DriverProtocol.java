package org.traccar.driver;

import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Single Traccar protocol entry point for all script-based drivers.
 * One TCP server (with frame detection) and one UDP server (already packetized)
 * both funnel into {@link DriverProtocolDecoder} which dispatches to the
 * matching driver script at runtime.
 */
@Singleton
public class DriverProtocol extends BaseProtocol {

    @Inject
    public DriverProtocol(Config config, DriverRegistry registry) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new DriverFrameDecoder(registry));
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new StringDecoder());
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry));
            }
        });
        addServer(new TrackerServer(config, getName(), true) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new StringDecoder());
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry));
            }
        });
    }
}
