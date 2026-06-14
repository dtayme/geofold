// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DriverDslUtilityTest {

    @Test
    public void bufWriterRejectsNegativeZeroLength() {
        assertThrows(IllegalArgumentException.class, () -> new BufWriter().writeZero(-1));
    }

    @Test
    public void bufWriterRejectsNullBytes() {
        assertThrows(IllegalArgumentException.class, () -> new BufWriter().writeBytes(null));
    }

    @Test
    public void writesBinaryPackets() {
        byte[] packet = new BufWriter()
                .writeByte(0x78)
                .writeShort(0x1234)
                .writeShortLE(0x5678)
                .writeBcd("12345")
                .toByteArray();

        assertArrayEquals(new byte[] {
                0x78, 0x12, 0x34, 0x78, 0x56, 0x12, 0x34, 0x5f}, packet);
    }

    @Test
    public void exposesChecksumHelpers() {
        assertEquals(0x40, DriverDSL.xor("ABC"));
        assertEquals(198, DriverDSL.sum("ABC"));
        assertEquals("*40", DriverDSL.nmea("ABC"));
    }

    @Test
    public void defaultsDriversToTcpTransport() throws Exception {
        DriverDefinition definition = parse("""
                protocol('defaultTransport') {
                    port 5555
                    variant('main') {
                        frame readLine()
                        matches { msg -> true }
                        decode { msg, ctx -> null }
                    }
                }
                """);

        assertEquals(5555, definition.getDefaultPort());
        assertEquals(1, definition.getTransports().size());
        assertTrue(definition.supportsTransport(DriverTransport.TCP));
    }

    @Test
    public void parsesExplicitTransportDeclarations() throws Exception {
        DriverDefinition definition = parse("""
                protocol('httpTransport') {
                    port 8088
                    transport 'http'
                    variant('main') {
                        matches { req -> req.path() == '/uplink' }
                        decode { req, ctx -> null }
                    }
                }
                """);

        assertEquals(8088, definition.getDefaultPort());
        assertEquals(1, definition.getTransports().size());
        assertTrue(definition.supportsTransport(DriverTransport.HTTP));
    }

    @Test
    public void wrapsHttpRequests() {
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/uplink?id=123",
                Unpooled.copiedBuffer("{\"ok\":true}", StandardCharsets.UTF_8));

        DriverHttpRequest wrapped = new DriverHttpRequest(request);

        assertEquals("POST", wrapped.method());
        assertEquals("/uplink", wrapped.path());
        assertEquals("123", wrapped.param("id"));
        assertTrue(wrapped.jsonObject().getBoolean("ok"));
    }

    @Test
    public void frameBufferIndexOfFindsAsciiSubstring() {
        byte[] data = "GPRMC,imei:123".getBytes(StandardCharsets.US_ASCII);
        FrameBuffer fb = new FrameBuffer(Unpooled.wrappedBuffer(data));

        assertEquals(0, fb.indexOf("GPRMC"));
        assertEquals(6, fb.indexOf("imei:"));
        assertEquals(11, fb.indexOf("123"));
        assertEquals(-1, fb.indexOf("GNRMC"));
        assertEquals(6, fb.indexOf("imei:", 5));
        assertEquals(-1, fb.indexOf("GPRMC", 1));
    }

    @Test
    public void channelStoreReturnsUsableMapWhenChannelIsNull() {
        DecodeContext ctx = new DecodeContext(null, null, null,
                new DriverDefinition("test"), new VariantDefinition("main"));
        Map<String, Object> store = ctx.store();
        assertNotNull(store);
        store.put("key", 42);
        assertEquals(42, store.get("key"));
    }

    @Test
    public void readUntilAnyRequiresAtLeastTwoTerminators() {
        assertThrows(IllegalArgumentException.class, () -> FrameSpec.readUntilAny(";"));
        assertThrows(IllegalArgumentException.class, () -> FrameSpec.readUntilAny());
    }

    private DriverDefinition parse(String source) throws Exception {
        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(DriverDSL.class.getName());
        GroovyShell shell = new GroovyShell(
                Thread.currentThread().getContextClassLoader(), new Binding(), compilerConfig);
        DriverDSL script = (DriverDSL) shell.parse(source);
        script.run();
        return script.getDefinition();
    }
}
