// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
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

    private TestDriverHttpProtocolDecoder httpDecoder(String name) throws Exception {
        DriverDefinition definition = loadDriver(name);

        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.match(any(), any(), any())).thenAnswer(invocation -> {
            Object message = invocation.getArgument(0);
            VariantDefinition variant = definition.matchVariant(message);
            return variant != null ? new DriverRegistry.DriverMatch(definition, variant) : null;
        });

        Protocol protocol = mock(Protocol.class);
        when(protocol.getName()).thenReturn(name);

        return inject(new TestDriverHttpProtocolDecoder(protocol, registry));
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
    public void testRitiDecode() throws Exception {
        var decoder = decoder("riti");

        verifyPosition(decoder, binary(
                "3b28a2a2056315316d4000008100000000000000005f710000244750524d432c3138303535332e3030302c412c353532342e383437312c4e2c30313133342e313837382c452c302e30302c2c3032313231332c2c2c412a37340d0a00000000000000000000000000000000040404"),
                position("2013-12-02 18:05:53.000", true, 55.41412, 11.56980));

        verifyPosition(decoder, binary(
                "3b2864a3056300006d40000003000000000000000000000000244750524d432c3231313734332e3030302c412c313335372e333637352c4e2c31303033362e363939322c452c302e30302c2c3031303931342c2c2c412a37380d0a00000000000000000000000000000000040404"),
                position("2014-09-01 21:17:43.000", true, 13.95613, 100.61165));
    }

    @Test
    public void testM2mDecode() throws Exception {
        var decoder = decoder("m2m");

        verifyNull(decoder, binary(
                "235A3C2A2624215C287D70212A21254C7C6421220B0B0B"));

        verifyPosition(decoder, binary(
                "A6E12C2AAADA4628326B2059576E30202A2FE85D20200B"),
                position("2012-01-06 10:10:58.000", true, 38.13646, 57.92969));
    }

    @Test
    public void testEnforaDecode() throws Exception {
        var decoder = decoder("enfora");

        verifyNull(decoder, binary(
                "000A08002020202020303131303730303030353730323637"));

        verifyNull(decoder, binary(
                "003B000502000000000820202020202030313130373030303035373032363720383A000000000D00508401358E640032B37700000367B00000A804"));

        verifyPosition(decoder, binary(
                "007100040200202020202020202020382020202020202031323334353637383930313233343520313320244750524D432C3232333135322E30302C412C333530392E3836303539342C4E2C30333332322E3734333838372C452C302E302C302E302C3032303631322C2C2C412A35320D0A"),
                position("2012-06-02 22:31:52.000", true, 35.16434, 33.37906));

        verifyPosition(decoder, binary(
                "007600040200202020202020202020382020202020202030313138393230303036303831383920313320244750524D432C3137313834312E30302C412C333530392E3835323431302C4E2C30333332322E3735393131332C452C302E302C302E302C3137303731322C332E342C572C412A32350D0A00"));

        verifyPosition(decoder, binary(
                "006a000a081000202020202020202020333320202020202038363130373430323137313936353620204750524d432c3136313234382e30302c412c333433322e36393231312c532c30353833312e30323231372c572c302e3034382c2c3232303831342c2c2c412a3734"));
    }

    @Test
    public void testOrionDecode() throws Exception {
        var decoder = decoder("orion");

        verifyPositions(decoder, binary(
                "5057000137bf6236235a0331b5c6e402a3b5ecff5102980003000e0c1d172936080e0c1d172936b03b01000882050000008e080000000000008c0300940500000084030085030003067600900113150000000000000000000000000000000000000004a4c8"));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(DriverFrameDecoder.DRIVER_KEY).set("orion");
        channel.attr(DriverFrameDecoder.VARIANT_KEY).set("userlog");
        decoder.decode(channel, null, new BufReader(binary(
                "5057004107367C242B440901ADE97D0163143B07B003000000000D041917382D000B0101000511000000000682050000008E080000000000008C0300840300850300090A0000000048010000008AFC")));
        assertBinaryAck(channel, "2a8afc00");

        verifyPositions(decoder, binary(
                "5057004107367C242C440901ADE97D0163143B07B003000000000D041917382D000B0101000513000000000682050000008E080000000000008C0300840300850300090A000000003BFEFFFF01FAE5"));

        verifyPositions(decoder, binary(
                "5057004107367C242D440901ADE97D0163143B07B003000000000D041917382D000B0101000514000000000682050000008E080000000000008C0300840300850300090A00000000FDFDFFFF023721"));

        verifyPositions(decoder, binary(
                "505700412ac86236354009114d20e402210f1f00d204000000000e06110d3414000b0101001228000000000682050000008e080000000000008c030084030085030003067b006801000930"));
    }

    @Test
    public void testRamacDecode() throws Exception {
        var decoder = httpDecoder("ramac");

        verifyAttributes(decoder, request(HttpMethod.POST, "/",
                buffer("{\"PacketType\": 0,\"SeqNumber\": 4,\"UpdateDate\": \"2022-05-06 12:25:35\",\"Alert\": 42,\"AlertMessage\": \"Low Battery\",\"Mode\": 1,\"ModeText\": \"Help Me\",\"SigfoxTXInterval\": 2,\"GpsFixInterval\": 3,\"SigfoxTXIntervalText\": \"2 Seconds\",\"GpsFixIntervalText\": \"3 Seconds\",\"BatteryPercentage\": 4,\"Battery\": 0.1,\"Temperature\": -22,\"HwVersion\": 7,\"FirmwareVersion\": 8,\"DeviceId\": \"A10001\",\"DeviceType\": \"12\",\"DeviceTypeText\": \"RAMAC P1\"}")));

        verifyPosition(decoder, request(HttpMethod.POST, "/",
                buffer("{\"PacketType\": 1,\"SeqNumber\": 4,\"UpdateDate\": \"2022-05-06 12:25:35\",\"Alert\": 0,\"AlertMessage\": \"\",\"Latitude\": -25.87586735939189,\"Longitude\": 28.179579268668846,\"Speed\": 1,\"COG\": 3,\"EstimatedAccuracy\": 3,\"LastLocation\": 0,\"LastLocationText\": \"NEW LOCATION\",\"IsMoving\": 0,\"IsMovingText\": \"STATIONARY\",\"GpsEvent\": 5,\"GpsEventText\": \"Heartbeat\",\"DeviceId\": \"A10001\",\"DeviceType\": \"12\",\"DeviceTypeText\": \"RAMAC P1\"}")));

        EmbeddedChannel channel = new EmbeddedChannel();
        Object result = decoder.decode(channel, null, request(HttpMethod.POST, "/",
                buffer("{\"PacketType\": 2,\"SeqNumber\": 4,\"UpdateDate\": \"2022-05-06 12:25:35\",\"Alert\": 19,\"AlertMessage\": \"P1 Panic\",\"Event\": 16,\"DeviceId\": \"A10001\",\"DeviceType\": \"12\",\"DeviceTypeText\": \"RAMAC P1\",\"Latitude\": -25.875867359392,\"Longitude\": 28.179579268669,\"LocationDateTime\": \"2022-05-05 08:48:11\"}")));
        assertInstanceOf(Position.class, result);
        assertHttpResponse(channel, 200, "{\"CaseID\":1,\"EventID\":1}");
    }

    @Test
    public void testCityeasyDecode() throws Exception {
        var decoder = decoder("cityeasy");

        verifyNotNull(decoder, binary(
                "545400853575570249020100033b3430342c34352c31303638312c31313632312c33352c31303638312c31313632322c32332c31303638312c32383938332c32332c31303638312c31313632332c32312c31303638312c32333338312c31372c31303638312c32323538332c31372c31303638312c32363434312c31330000000d352e0d0a"));

        verifyNull(decoder, binary(
                "54540019357557024902010002520704100000000bbe700d0a"));

        verifyNull(decoder, binary(
                "5454001735755702490201434a01000000000c24280d0a"));

        verifyNull(decoder, binary(
                "545400153520000000000100010000000111000D0A"));

        verifyNull(decoder, binary(
                "54540019357557024902000002520704300000000376390d0a"));

        verifyPosition(decoder, binary(
                "5454006135200000000001000332303134313131303039353430392C412C342C4E2C32322E3533373232382C452C3131342E3032323737342C302E312C312E392C35302E363B3436302C302C31303137332C343635322C34310000000B63130D0A"),
                position("2014-11-10 09:54:09.000", true, 22.53723, 114.02277));

        verifyPosition(decoder, binary(
                "5454006135200000000001000432303134313131303039353330362C412C352C4E2C32322E3533373233352C452C3131342E3032323838312C302E322C312E362C35342E313B3436302C302C31303137332C343635322C343100000045EC620D0A"));

        verifyPosition(decoder, binary(
                "5454009035755702490200000332303135303732393033303834352c412c362c4e2c31322e3833353735362c452c37372e3638373039362c302e332c312e322c3931302e303b3430342c34352c31303638312c31313632312c34332c31303638312c31313632332c32312c31303638312c32323538332c32302c31303638312c32333338312c31380000000267370d0a"));
    }

    @Test
    public void testV680Decode() throws Exception {
        var decoder = decoder("v680");

        verifyPosition(decoder, text(
                "#867967020910610#01234567890#1#0000#AUT#1#0500000000120000#114.036291,E,22.665795,N,111.00,000.00#111116#193333##"),
                position("2016-11-11 19:33:33.000", true, 22.66579, 114.03629));

        verifyPosition(decoder, text(
                "#355488020168617##1#0000#AUT#01#260001a412966f#1834.790700,E,5302.748800,N,0.00,0.00#310316#174538.000##"));

        verifyPosition(decoder, text(
                "#355488020168617##1#0000#AUT#01##1834.770100,E,5302.742800,N,0.62,0.00#310316#211537.000##"));

        verifyNull(decoder, text("#353588102019155"));

        verifyPosition(decoder, text(
                "#135790246811222#13486119277#1#0000#SOS#1#27bc10af#11407.4182,E,2232.7632,N,0.00,79.50#070709#134147.000##"));

        verifyPosition(decoder, text(
                "#356823031193431##0#0000#SF#1#72403#V#04702.3025,W,2252.18380,S,008.18,0#090413#134938"),
                position("2013-04-09 13:49:38.000", false, -22.86973, -47.038375));

        verifyPosition(decoder, text(
                "#356823033219838#1000#0#1478#AUT#1#66830FFB#03855.6628,E,4716.6821,N,001.41,259#130812#143905"));

        verifyPosition(decoder, text(
                "#353588102019155##1#0000#AUT#01#7240060be7873f#4849.079800,W,2614.458200,S,0.00,0.00#130413#182110.000"));

        verifyPosition(decoder, text(
                "#353588302045917##1#0000#AUT#01#7243141c2b14c3#4738.442300,W,2334.874000,S,0.00,0.30#170413#004831.000"));

        verifyPosition(decoder, text(
                "#352897045085282##0#0000#AUT#1#72400510730208,00d36307,10734fc4#4647.8922,W,2339.1956,S,2.60,63.74#200413#094310.000"));

        verifyPosition(decoder, text(
                "#356823033537791##0#0000#AUT#1#V#03610.2179,E,5004.5796,N,000.01,349#180513#073758"));

        verifyPosition(decoder, text(
                "#356823031236214##0#0000#AUT#1#V#01904.5491,E,6941.0085,N,000.09,248#170513#160140"));

        verifyNull(decoder, text(
                "#353588550032869##1#0000#AUT#01#72400401cd01a5#00000.0000,E,0000.0000,N,0.00,#000000#000000.000"));

        verifyPosition(decoder, text(
                "#352897045085282##0#0000#AUT#1#72400510730208,00d36307,10734fc4#4647.8922,W,2339.1956,S,2.60,63.74#200413#094310.000##"));

        verifyPosition(decoder, text(
                "#352165050199210##13#0000#AUT#1#72400605471305,054712fd,054712ff#05144.0008,W,3005.5011,S,0.11,201.46#260713#172647.000##"));

        verifyPosition(decoder, text(
                "#356823031166908#13001190527#0#0000#AUT#4#V#07136.4070,W,1040.0575,N,000.35,257#280813#142836#V#07136.4088,W,1040.0580,N,000.49,288#280813#142846#V#07136.4098,W,1040.0590,N,000.59,264#280813#142856#V#07136.4093,W,1040.0605,N,000.30,264#280813#142906##"));

        verifyPosition(decoder, text(
                "#355488020132015##1#0000#AUT#01#510089246a34c0#10641.338800,E,619.427100,S,0.00,0.00#011113#161942.000##"));

        verifyPosition(decoder, text(
                "#359094025419110#bigfriend#0#1234#AUTO#1##04632.8846,W,2327.2264,S,0.00,0.00#220913#234808##"));

        verifyPosition(decoder, text(
                "#353588102031599##1#0000#AUT#01#41300304843fc1#7955.124400,E,642.095500,N,5.28,95.21#041213#074431.000##"));

        verifyPosition(decoder, text(
                "1#0000#AUT#01#23403007fa650e#16.747700,W,5136.356500,N,0.00,0.00#040415#002051.000"));
    }

    @Test
    public void testStl060Decode() throws Exception {
        var decoder = decoder("stl060");

        verifyPosition(decoder, text(
                "$1,357804048043099,D001,AP29AW0963,23/02/14,14:06:54,17248488N,078342226E,0.08,193.12,1,1,1,1,1,A"),
                position("2014-02-23 14:06:54.000", true, 17.41415, 78.57038));

        verifyPosition(decoder, text(
                "$1,357804048043099,D001,AP29AW0963,12/05/14,07:39:57,1724.8564N,07834.2199E,0.00,302.84,1,1,1,1,1,A"));

        verifyPosition(decoder, text(
                "$1,357804047969310,D001,AP29AW0963,01/01/13,13:24:47,1723.9582N,07834.0945E,00100,010,0,0,0,0,0,A,"));

        verifyPosition(decoder, text(
                "$1,357804047969310,D001,AP29AW0963,01/01/13,13:24:47,1723.9582N,07834.0945E,00100,010,0,0,0,0,0,0008478660,1450,40,34,0,0,0,A"));
    }

    @Test
    public void testGpsGateDecode() throws Exception {
        var decoder = decoder("gpsgate");

        verifyPosition(decoder, text(
                "$FRCMD,0097,_SendMessage,,7618.51990,S,4002.26182,E,350.0,1.08,0.0,250816,183522.000,0*7F"));

        verifyPosition(decoder, text(
                "$FRCMD,356406061385182,_SendMessage,,5223.88542,N,11440.45866,W,951.2,0.027,,220716,153507.00,1*5F"));

        verifyPosition(decoder, text(
                "$FRCMD,353067011068246,_SendMessage,,1918.1942,N,09906.3696,W,2246.5,000.0,295.9,150416,213147.00,1,Odometer=*70"));

        verifyNull(decoder, text(
                "$FRCMD,862950025974620,_Ping,voltage=4*4F"));

        verifyPosition(decoder, text(
                "$FRCMD,862950025974620,_SendMessage, ,2721.5781,S,15259.145,E,61,0.00,61,080316,092612,1,SosButton=0,voltage=4*60"));

        verifyNull(decoder, text(
                "$FRLIN,,user1,8IVHF*7A"));

        verifyNull(decoder, text(
                "$FRLIN,,354503026292842,VGZTHKT*0C"));

        verifyNull(decoder, text(
                "$FRLIN,IMEI,1234123412341234,*7B"));

        verifyNull(decoder, text(
                "$FRLIN,,saab93_device,KLRFBGIVDJ*28"));

        verifyPosition(decoder, text(
                "$GPRMC,154403.000,A,6311.64120,N,01438.02740,E,0.000,0.0,270707,,*0A"),
                position("2007-07-27 15:44:03.000", true, 63.19402, 14.63379));

        verifyPosition(decoder, text(
                "$GPRMC,074524,A,5553.73701,N,03728.90491,E,10.39,226.5,160614,0.0,E*75"));

        verifyPosition(decoder, text(
                "$GPRMC,154403.000,A,6311.64120,N,01438.02740,E,0.000,0.0,270707,,*0A"));
    }

    @Test
    public void testThinkPowerDecode() throws Exception {
        var decoder = decoder("thinkpower");

        verifyNull(decoder, binary(
                "0103002C01020F38363737333030353038323030343606544C3930344111522D312E302E31372E32303231303431300011C3"));

        verifyPosition(decoder, binary(
                "05300012016099E995010D743CC943EB481500000000EED4"));

        verifyPosition(decoder, binary(
                "05000007016099E768020162D8"));

        verifyNull(decoder, binary(
                "03040000C3DC"));
    }

    @Test
    public void testMxtDecode() throws Exception {
        var decoder = decoder("mxt");

        verifyPosition(decoder, binary(
                "01a631a7627b00087dc41c40850006aab70affecdf23fd32200080000600000000000000000000001b2ff03b1bb9c4c60214f40100050000006c2d0000f427600051051101de0704"));

        verifyPosition(decoder, binary(
                "01a631144c7e0008643ad2f456fb2d49747cfe4cbe0ffd002008800000001021000fd43d3f1403000000ff300000f42760001031102445a81fda04"));

        verifyPosition(decoder, binary(
                "01a631361e7a00082471418b052a2c46b587ffc01ae3fd000008800000000000003345422203000000f000f00000000000ea1e04"));

        verifyPosition(decoder, binary(
                "01a63118787d00086440628d226e2bc26a97feac8a3afd10210010308000000000000018003d2b10240000005e2f0000f427f21031feff0000593804"));

        verifyPosition(decoder, binary(
                "01a631bd777d0008646e319e17292ce86798fed4cd3afd102110211030800000102403001f15003e2b102400000034300000f4271021007b175535a7be04"));

        verifyPosition(decoder, binary(
                "01a631e3f97e00087cf40a98151c2cc46898fee0ce3afd1021001030c0000006102116072e003829bb00000036102100001024000000062b0000f42730004b06a6384b4304"));

        verifyPosition(decoder, binary(
                "01a63118787d00086468457a466a2bc26a97feac8a3afd10212010308000000000001fe1053d291024000000922f0000f4271021007b17553599bb04"));

        verifyPosition(decoder, binary(
                "01a63118787d0008648645ec486a2bc26a97feac8a3afd1021001030c0000000001419eb05372b1024000000982a0000f4271021007b17000010308c04"));

        verifyPosition(decoder, binary(
                "01a631e3f97e00087cfa0af3151c2c126798febace3afd1021801030c0000006102122082f003e29bb00000037102100001024000000ab2f0000f42730004b060000488c04"));

        verifyPosition(decoder, binary(
                "01a631e3f97e00087cfe0a4b161c2c126798febace3afd1021801030800000071021240731003e2abb00000038102100001024000000c12f0000f42730004b06a638633104"));

        verifyPosition(decoder, binary(
                "01a63118787d0008648645ec486a2bc26a97feac8a3afd1021001030c0000000001419eb05372b1024000000982a0000f4271021007b17000010308c04"));
    }

    @Test
    public void testNoranDecode() throws Exception {
        var decoder = decoder("noran");

        verifyNull(decoder, binary(
                "0d0a2a4b57000d000080010d0a"));

        verifyPosition(decoder, binary(
                "34000800010b0000000000003f43bb8da6c2ebe229424e523039423233343439000031362d30392d31352030373a30303a303700"));

        verifyPosition(decoder, binary(
                "28003200c380000000469458408c4ad340ad381e3f4e52303947313336303900000001ff00002041"));

        verifyPosition(decoder, binary(
                "28003200c38000d900fcc97a416b1a7a42b43eef3d4e523039473034383737000000000092fcda4a"));

        verifyPosition(decoder, binary(
                "3400080001090000000000001D43A29BE842E62520424E523039423036363932000031322D30332D30352031313A34373A343300"));

        verifyPosition(decoder, binary(
                "34000800010c000000000080a3438e20944149bd07c24e523039423139323832000031352d30342d32362030383a34333a353300"));

        verifyNull(decoder, binary(
                "0f0000004e52303946303431353500"));

        verifyPosition(decoder, binary(
                "22000800010c008a007e9daa42317bdd41a7f3e2384e523039463034313535000000"));

        verifyPosition(decoder, binary(
                "34000800010c0000000000001c4291251143388d17c24e523039423131303930000031342d31322d32352030303a33333a303700"));

        verifyPosition(decoder, binary(
                "34000800010c00000000000000006520944141bd07c24e523039423139323832000031352d30342d32352030303a30333a323200"));
    }

    @Test
    public void testSkypatrolDecode() throws Exception {
        var decoder = decoder("skypatrol");

        verifyNull(decoder, binary(
                "000a02171101303131373232303031333537393833060200000006202020202020202020312020202020202030313137323230303133353739383320"));

        verifyNull(decoder, binary(
                "000402171101303131373232303031333537393833060200081046202020202020202020392020202020202030313137323230303133353739383320244750524d432c3134303931372e30302c412c333330322e3230313132352c532c30373133352e3837383338332c572c302e302c302e302c3036303731372c322e382c572c412a32370d0a00"));

        verifyPosition(decoder, binary(
                "0005021004FFFFFFFF0000000D313134373735383300CB000000000E11070C010184D032FB3841370000000016072B000017050032000000000000024E0C071116072C105900050000000000050000000000050000000003100260B7363B6306C11A00B73637F206BF19B73637F106B50EB73638B106BB0BB7363B6106B80AB73637F306B709000000000000000000"));

        verifyNull(decoder, binary(
                "000500030101383637383434303031373832333336420102000c0000fa07b5e101876c5b0e0a111606131c1b5e"));

        verifyNull(decoder, binary(
                "000502000000f1143035303031393031d1df002f00000d0187120115e556ff762aa90000000000aae40005d2000ee1bc0e010a042530000000000000070004000002233c096c00ee2a00233c008500f022233c0b0500f21d233c000000fb23000000000000000000000000000000000000000000000000"));

        verifyNull(decoder, binary(
                "00040200202020202020202020382020202020202030313137323230303131383531373820313220244750524d432c3232343833392e30302c412c303332382e3433383830362c4e2c30373633312e3630373731372c572c302e302c302e302c3139303731342c332e382c452c412a32420d0a00"));
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

    private void assertHttpResponse(EmbeddedChannel channel, int expectedStatus, String expectedBody) {
        NetworkMessage response = assertInstanceOf(NetworkMessage.class, channel.readOutbound());
        FullHttpResponse httpResponse = assertInstanceOf(FullHttpResponse.class, response.getMessage());
        assertEquals(expectedStatus, httpResponse.status().code());
        assertEquals(expectedBody, httpResponse.content().toString(java.nio.charset.StandardCharsets.UTF_8));
        httpResponse.release();
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

    private static class TestDriverHttpProtocolDecoder extends DriverHttpProtocolDecoder {

        TestDriverHttpProtocolDecoder(Protocol protocol, DriverRegistry registry) {
            super(protocol, registry);
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
