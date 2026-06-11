package org.traccar.driver;

import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

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

    @Inject
    public DriverProtocol(Config config, DriverRegistry registry) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new DriverFrameDecoder(registry, config.getInteger(Keys.DRIVER_FRAME_MAX_LENGTH)));
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new DriverMessageAdapter(registry));
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry));
            }
        });
        addServer(new TrackerServer(config, getName(), true) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new DriverMessageAdapter(registry));
                pipeline.addLast(new DriverProtocolEncoder(DriverProtocol.this, registry));
                pipeline.addLast(new DriverProtocolDecoder(DriverProtocol.this, registry));
            }
        });
    }
}
