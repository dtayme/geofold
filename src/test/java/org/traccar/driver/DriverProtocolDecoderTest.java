// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.io.File;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DriverProtocolDecoderTest extends ProtocolTest {

    private DriverProtocolDecoder decoder(String name) throws Exception {
        DriverDefinition definition = loadDriver(name);

        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.get(name)).thenReturn(definition);
        when(registry.match(any(), any(), any())).thenAnswer(invocation -> {
            String message = invocation.getArgument(0);
            VariantDefinition variant = definition.matchVariant(message);
            return variant != null ? new DriverRegistry.DriverMatch(definition, variant) : null;
        });

        Protocol protocol = mock(Protocol.class);
        when(protocol.getName()).thenReturn(name);

        return inject(new TestDriverProtocolDecoder(protocol, registry, definition));
    }

    private DriverDefinition loadDriver(String name) throws Exception {
        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(DriverDSL.class.getName());

        GroovyShell shell = new GroovyShell(
                Thread.currentThread().getContextClassLoader(), new Binding(), compilerConfig);
        DriverDSL script = (DriverDSL) shell.parse(new File("drivers", name + ".groovy"));
        script.run();
        return script.getDefinition();
    }

    @Test
    public void testArdi01Decode() throws Exception {
        var decoder = decoder("ardi01");

        verifyPosition(decoder, text(
                "013227003054776,20141010052719,24.4736042,56.8445807,110,289,40,7,5,78,-1"),
                position("2014-10-10 05:27:19.000", true, 56.84458, 24.47360));

        verifyPosition(decoder, text(
                "013227003054776,20141010052719,24.4736042,56.8445807,110,289,40,7,5,78,-1"));
    }

    @Test
    public void testGt02Decode() throws Exception {
        var decoder = decoder("gt02");

        verifyAttributes(decoder, binary(
                "6868150000035889905895258400831c07415045584f4b210d0a"));

        verifyAttributes(decoder, binary(
                "68682d0000035889905895258400951c1f415045584572726f723a20506172616d65746572203120284f4e2f4f4646290d0a"));

        verifyAttributes(decoder, binary(
                "68680f0504035889905831401700df1a00000d0a"));

        verifyAttributes(decoder, binary(
                "6868130504035889905831401700001a040423261e290d0a"));

        verifyAttributes(decoder, binary(
                "68681905040358899058314017000e1a010a2623211b2722252329000d0a"));

        verifyAttributes(decoder, binary(
                "68681a060303588990500037252de91a010a171a191b171915191e10000d0a"));

        verifyPosition(decoder, binary(
                "68682500000123456789012345000110010101010101026B3F3E026B3F3E000000000000000000010D0A"),
                position("2001-01-01 01:01:01.000", true, -22.54610, -22.54610));

        verifyAttributes(decoder, binary(
                "6868110603035889905101276600001a0402292d0d0a"));

        verifyPosition(decoder, binary(
                "68682500a403588990510127660001100e09060a1d1b00ade1c90b79ea3000011b000000000000050d0a"));
    }

    @Test
    public void testGt30Decode() throws Exception {
        var decoder = decoder("gt30");

        verifyPosition(decoder, text(
                "$$005D3037811014    9955102834.000,A,3802.8629,N,02349.7163,E,0.00,,060117,,*13|1.3|26225BD"));

        verifyPosition(decoder, text(
                "$$005E3037811014    9999\u0003121909.000,A,3802.9133,N,02349.9354,E,0.00,,060117,,*18|1.8|264518B"));

        verifyPosition(decoder, text(
                "$$00633037811014    9999\u0002121901.000,A,3802.9137,N,02349.9334,E,2.86,18.16,060117,,*3E|1.8|262D752"));

        verifyPosition(decoder, text(
                "$$005E3037811014    9999\u0001121849.000,A,3802.9094,N,02349.9384,E,0.00,,060117,,*1C|1.2|2683812"));

        verifyPosition(decoder, text(
                "$$005B3037811124    9955161049.000,A,3802.9474,N,02241.1897,E,0.00,,021115,,*15|2.9|5A639"));
    }

    @Test
    public void testCarTrackDecode() throws Exception {
        var decoder = decoder("cartrack");

        verifyNull(decoder, text(
                "$$020040????????&A0000"));

        verifyPosition(decoder, text(
                "$$020040????????&A9955&B011939.000,A,4436.3804,N,02606.9434,E,0.00,0.00,190317,,,A*64|0.9|&C0100000000&D01830=?6&E00000001&Y00000000"));

        verifyPosition(decoder, text(
                "$$2222234???????&A9955&B102904.000,A,2233.0655,N,11404.9440,E,0.00,,030109,,*17|6.3|&C0100000100&D000024?>&E10000000"),
                position("2009-01-03 10:29:04.000", true, 22.55109, 114.08240));

        verifyPosition(decoder, text(
                "$$2222234???????&A9955&B102904.000,A,2233.0655,N,11404.9440,E,0.00,,030109,,*17|6.3|&C0100000100&D000024?>&E10000000&Y00100020"));

        verifyPosition(decoder, text(
                "$$2222234???????&A9955&B102904.000,A,2233.0655,N,11404.9440,E,0.00,,030109,,*17|6.3|&C0100000100&D000024?>&E10000000"));
    }

    @Test
    public void testGl100DecodeAndHeartbeatAck() throws Exception {
        var decoder = decoder("gl100");

        verifyPosition(decoder, text(
                "+RESP:GTFRI,123456789012345,1,0,0,0,12.5,180,45.5,0.8,114.1234,22.5678,"
                        + "20260612010203,"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        EmbeddedChannel channel = new EmbeddedChannel();
        assertNull(decoder.decode(channel, null, text(
                "AT+GTHBD=TAG,123456789012345,20260612010203,0001")));
        assertTextAck(channel, "+RESP:GTHBD,GPRS ACTIVE,TAG,123456789012345,20260612010203\0");
    }

    @Test
    public void testGotopDecode() throws Exception {
        var decoder = decoder("gotop");

        verifyPosition(decoder, text(
                "123456789012345,TRACK,A,DATE:260612,TIME:010203,LAT:22.567800N,LON:114.123400E,"
                        + "Speed:36,85-5,12.5,1.2"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testH02TextDecodeBatchAndHeartbeatAck() throws Exception {
        var decoder = decoder("h02");

        verifyPosition(decoder, text(
                "*HQ,123456789012345,V1,010203,A,2234.0680,N,11407.4040,E,10,180,120626,FFFFFFFF"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        verifyPositions(decoder, text(
                "*HQ,123456789012345,BC,0,0,A,2234.0680,N,11407.4040,E,10,180,12010203,FFFFFFFF;"
                        + "A,2234.0000,N,11407.0000,E,0,0,12010204,FFFFFFFF"));

        EmbeddedChannel channel = new EmbeddedChannel();
        Object result = decoder.decode(channel, null, text("*HQ,123456789012345,V0,80"));
        assertInstanceOf(Position.class, result);
        assertTextAck(channel, "*HQ,123456789012345,V0#");
    }

    @Test
    public void testH02BinaryDecode() throws Exception {
        var decoder = decoder("h02");

        verifyPosition(decoder, binary(
                "2412345678900102031206262234068005114074040e000180ffffffff000000"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testWondexKeepaliveEcho() throws Exception {
        var decoder = decoder("wondex");
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(DriverFrameDecoder.DRIVER_KEY).set("wondex");
        channel.attr(DriverFrameDecoder.VARIANT_KEY).set("keepalive");

        ByteBuf frame = binary("d001020304050607");
        assertNull(decoder.decode(channel, null, new BufReader(frame)));
        assertBinaryAck(channel, "d001020304050607");
    }

    @Test
    public void testLaipacDecode() throws Exception {
        var decoder = decoder("laipac");

        verifyPosition(decoder, text(
                "$AVRMC,80006405,212645,r,3013.9938,N,08133.3998,W,0.00,0.00,010317,a,4076,0,1,0,0,53170583,310260*78"));

        verifyNull(decoder, text("$AVSYS,99999999,V1.50,SN0000103,32768*15"));

        verifyNull(decoder, text("$ECHK,99999999,0*35"));

        verifyNull(decoder, text("$AVSYS,MSG00002,14406,7046811160,64*1A"));

        verifyAttributes(decoder, text("$EAVSYS,MSG00002,8931086013104404999,,Owner,0x52014406*76"));

        verifyNull(decoder, text("$ECHK,MSG00002,0*5E"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,0,3.727,17,1,0,0*37"),
                position("2003-07-18 16:43:39.000", true, 43.85090, -79.39241));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,a,4351.0542,N,07923.5445,W,0.29,78.66,180703,0,3.727,17,1,0,0*17"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,v,4351.0542,N,07923.5445,W,0.29,78.66,180703,0,3.727,17,1,0,0*00"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,r,4351.0542,N,07923.5445,W,0.29,78.66,180703,0,3.727,17,1,0,0*04"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,S,3.727,17,1,0,0*54"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,T,3.727,17,1,0,0*53"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,3,3.727,17,1,0,0*34"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,X,3.727,17,1,0,0*5F"));

        verifyPosition(decoder, text(
                "$AVRMC,99999999,164339,A,4351.0542,N,07923.5445,W,0.29,78.66,180703,4,3.727,17,1,0,0*33"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,003016,v,0000.0000,N,00000.0000,E,0.00,0.00,200614,0,3804,167,1,0,0,0D7AB913,020408*23"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,003049,V,0000.0000,N,00000.0000,E,0.00,0.00,200614,H,3804,167,1,0,0,0D7AB913,020408*71"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,041942,V,0000.0000,N,00000.0000,E,0.00,0.00,200614,H,4115,167,1,0,0*0E"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,043703,V,0000.0000,N,00000.0000,E,0.00,0.00,200614,H,4115,167,1,0,0*07"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,043750,V,0000.0000,N,00000.0000,E,0.00,0.00,200614,H,4115,167,1,0,0*01"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,124022,V,0000.0000,N,00000.0000,E,0.00,0.00,240614,3,4076,167,1,0,0,0D7AB913,020408*0D"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,124058,A,5053.0447,N,00557.8549,E,0.45,65.06,240614,0,4037,167,1,0,0,0D7AB913,020408*26"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,124144,A,5053.0450,N,00557.8544,E,0.00,65.06,240614,3,4076,167,1,0,0,0D7AB913,020408*26"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,125142,R,5053.0442,N,00557.8694,E,1.21,40.90,240614,0,4037,167,1,0,0,0D7AB913,020408*33"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,125517,R,5053.0442,N,00557.8694,E,0.00,0.00,240614,H,4076,167,1,0,0,0D7AB913,020408*75"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,043104,p,5114.4664,N,00534.3308,E,0.00,0.00,280614,0,4115,495,1,0,0,0D48C3DC,020408*52"));

        verifyPosition(decoder, text(
                "$AVRMC,MSG00002,050601,P,5114.4751,N,00534.3175,E,0.00,0.00,280614,0,4115,495,1,0,0,0D48C3DC,020408*7D"));

        verifyPosition(decoder, text(
                "$AVRMC,96414215,170046,p,4310.7965,N,07652.0816,E,0.00,0.00,071016,0,4069,98,1,0,0*04"));

        verifyPosition(decoder, text(
                "$AVRMC,999999999999999,111602,r,5050.1262,N,00419.9660,E,0.00,0.00,120318,0,3843,95,1,0,0,3EE4A617,020610*47"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143456,R,5050.1285,N,00420.0620,E,0.00,309.27,190318,0,3455,119,1,0,0,3EE4A617,020610*54"));

        verifyPosition(decoder, text(
                "$AVRMC,999999999999999,084514,r,5050.1314,N,00419.9719,E,0.68,306.39,120318,0,3882,84,1,0,0,3EE4A617,020610*4D"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,142945,R,5050.1254,N,00420.0490,E,0.00,0.00,190318,3,3455,119,1,0,0,3EE4A617,020610*53"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143407,R,5050.1254,N,00420.0490,E,0.00,0.00,190318,8,3455,119,1,0,0,3EE4A617,020610*52"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143648,A,5050.1141,N,00420.0525,E,1.24,174.38,190318,H,3455,119,1,0,0,3EE4A617,020610*3E"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143747,R,5050.1124,N,00420.0542,E,1.34,161.96,190318,a,3416,119,1,0,0*7D"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143747,P,5050.1124,N,00420.0542,E,1.34,161.96,190318,A,3416,119,1,0,0,0,0*5F"));

        verifyPosition(decoder, text(
                "$AVRMC,358174067149865,143747,P,5050.1124,N,00420.0542,E,1.34,161.96,190318,A,3416,119,1,0,0,0,0,0,0*5F"));
    }

    private void assertTextAck(EmbeddedChannel channel, String expected) {
        NetworkMessage response = assertInstanceOf(NetworkMessage.class, channel.readOutbound());
        assertEquals(expected, response.getMessage());
    }

    private void assertBinaryAck(EmbeddedChannel channel, String expectedHex) {
        NetworkMessage response = assertInstanceOf(NetworkMessage.class, channel.readOutbound());
        ByteBuf responseBuffer = assertInstanceOf(ByteBuf.class, response.getMessage());
        assertEquals(expectedHex, ByteBufUtil.hexDump(responseBuffer));
        responseBuffer.release();
    }

    private static class TestDriverProtocolDecoder extends DriverProtocolDecoder {

        private final DriverDefinition definition;

        TestDriverProtocolDecoder(Protocol protocol, DriverRegistry registry, DriverDefinition definition) {
            super(protocol, registry);
            this.definition = definition;
        }

        @Override
        protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
            if (msg instanceof ByteBuf buf) {
                EmbeddedChannel binaryChannel = new EmbeddedChannel();
                binaryChannel.attr(DriverFrameDecoder.DRIVER_KEY).set(definition.getName());
                binaryChannel.attr(DriverFrameDecoder.VARIANT_KEY).set(definition.getVariants().getFirst().getName());
                return super.decode(binaryChannel, remoteAddress, new BufReader(buf));
            }
            return super.decode(channel, remoteAddress, msg);
        }

        @Override
        DeviceSession session(Channel channel, SocketAddress remoteAddress, String uniqueId) {
            return new DeviceSession(1, uniqueId, null, mock(Protocol.class), mock(Channel.class), remoteAddress);
        }

        @Override
        DeviceSession session(Channel channel, SocketAddress remoteAddress) {
            return new DeviceSession(1, "", null, mock(Protocol.class), mock(Channel.class), remoteAddress);
        }
    }
}
