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
import org.traccar.config.Config;
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
        return decoder(name, new Config());
    }

    private DriverProtocolDecoder decoder(String name, Config config) throws Exception {
        return decoder(name, config, null);
    }

    private DriverProtocolDecoder decoder(String name, Config config, String model) throws Exception {
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

        return inject(new TestDriverProtocolDecoder(protocol, registry, definition, config, model));
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
    public void testT622IridiumDecode() throws Exception {
        Config config = mock(Config.class);
        when(config.getString("t622iridium.format")).thenReturn("01,02,03,04,05,08");
        var decoder = decoder("t622iridium", config);

        verifyPosition(decoder, binary(
                "01003501001c68b2cb1733303034333430363735343836353000016e000064b5f497020013234c5ea0ff1c365d0600b1482c010000cf0004"),
                position("2023-07-18 02:10:08.000", true, -6.26732, 106.77200));
    }

    @Test
    public void testProgressDecode() throws Exception {
        var decoder = decoder("progress");

        verifyNull(decoder, binary(
                "020037000100000003003131310f003335343836383035313339303036320f00323530303136333832383531353535010000000100000000000000e6bb97b6"));
    }

    @Test
    public void testIntellitracDecode() throws Exception {
        var decoder = decoder("intellitrac");

        verifyPosition(decoder, text(
                "359316075744331,20201008181424,12.014662,57.826301,0,76,24,10,997,3,0,0.000,4.208,20201008181424,0"));

        verifyNull(decoder, text("$OK:TRACKING"));

        verifyPosition(decoder, text(
                "101000001,20100304075545,121.64547,25.06200,0,0,61,7,2,1,0,0.046,0.000,20100304075546,0"),
                position("2010-03-04 07:55:45.000", true, 25.06200, 121.64547));

        verifyPosition(decoder, text(
                "1010000002,20030217132813,121.646060,25.061725,20,157,133,7,0,11,15,0.096,0.000"));

        verifyPosition(decoder, text(
                "1010000002,20030217132813,121.646060,25.061725,20,157,-133,7,0,11,15,0.096,0.000"));

        verifyPosition(decoder, text(
                "1001070919,20130405084206,37.903730,48.011377,0,0,235,10,2,2,0,20.211,0.153"));

        verifyPosition(decoder, text(
                "1010000002,20030217144230,121.646102,25.061398,0,0,139,0,0,0,0,0.093,0.000"));

        verifyPosition(decoder, text(
                "1010000004,20050513153524,121.646075,25.063675,0,166,50,6,1,0,0,0.118,0.000"));

        verifyPosition(decoder, text(
                "1010000004,20050513154001,121.646075,25.063675,0,166,55,7,1,0,0,0.096,0.000"));

        verifyPosition(decoder, text(
                "1010000002,20030217132813,121.646060,25.061725,20,157,0,7,0,11,15"));

        verifyPosition(decoder, text(
                "12345,1010000002,20030217132813,121.646060,25.061725,20,157,0,7,0,11,15"));

        verifyPosition(decoder, text(
                "1010000002,20030217144230,121.646102,25.061398,0,0,0,7,2,0,0"));

        verifyPosition(decoder, text(
                "$RP:12345,1010000002,20030217144230,121.646102,25.061398,0,0,0,7,2,0,0"));

        verifyPosition(decoder, text(
                "1010000001,20030105092129,121.651598,25.052325,0,0,33,0,1,0,0"));

        verifyPosition(decoder, text(
                "1010000001,20030105092129,-121.651598,-25.052325,0,0,33,0,1,0,0"));

        verifyPosition(decoder, text(
                "1015210962,20131010144712,-77.070037,-12.097935,0,0,77,7,2,2,0,0,139446.8,2095,20131010144712,,0.103,0.000"));

        verifyPosition(decoder, text(
                "1003269480,20131126100258,10.32989,49.93836,0,304,217,6,2,0,0,0.000,0.000,20131126100258,0,0,0,-40,0,0,-273,0,0,0,0"));
    }

    @Test
    public void testMobilogixDecode() throws Exception {
        var decoder = decoder("mobilogix");

        verifyAttributes(decoder, text("[2021-08-20 19:27:14,T14,1,V1.3.5,201909000982,53,12.18"));
        verifyAttributes(decoder, text("\r\n[2021-08-20 19:27:14,T14,1,V1.3.5,201909000982,53,12.18"));
        verifyNull(decoder, text("[2020-12-01 14:00:22,T1,1,V1.1.1,201951132031,,,12345678,724108005415815,359366080211420"));
        verifyNull(decoder, text("[2020-10-25 20:44:08,T8,1,V1.2.3,201951132044,3596"));
        verifyPosition(decoder, text("[2020-10-25 20:45:09,T9,1,V1.2.3,201951132044,59,10.50,701,-25.236860,-45.708530,0,314"));
        verifyPosition(decoder, text("[2021-10-25 20:46:10,T10,1,V1.2.3,201951132044,59,0.50,082,-25.909590,-47.045387,0,145"));
        verifyPosition(decoder, text("[2021-10-25 20:47:11,T11,1,V1.2.3,201951132044,3F,9.23,991,-25.909262,-47.045387,1,341"));
        verifyPosition(decoder, text("[2021-10-25 20:54:11,T12,1,V1.2.3,201951132044,3F,9.23,991,-25.909262,-47.045387,1,341"));
        verifyAttributes(decoder, text("[2021-10-25 20:48:14,T14,1,V1.2.3,201951132044,51,0.50"));
        verifyPosition(decoder, text("[2021-10-25 20:49:15,T15,1,V1.2.3,201951132044,59,0.50,591,-25.908621,-47.045971,2,127"));
        verifyNull(decoder, text("[2021-10-25 20:50:16,T16,1,V1.2.3,201951132044,1"));
        verifyPosition(decoder, text("[2021-10-25 20:51:21,T21,1,V1.2.3,201951132044,37,12.18,961,-25.932310,-47.022415,0,82"));
        verifyPosition(decoder, text("[2021-10-25 20:52:22,T22,1,V1.2.3,201951132044,1B,12.05,082,-25.909590,-47.045387,0,145"));
        verifyPosition(decoder, text("[2021-10-25 20:53:31,T31,1,V1.2.3,201951132044,D3,26.17,961,-23.458092,-46.392132,0,8"));
        verifyAttribute(decoder, text("[2021-10-25 20:55:11,T13,1,V1.2.3,201951132044,3F,9.23,991,-25.909262,-47.045387,1,341"),
                Position.KEY_TYPE, "T13");
        verifyPosition(decoder, text("[2020-12-01 12:01:09,T3,1,V1.1.1,201951132031,3B,12.99,022,-23.563410,-46.588055,0,0"));
        verifyPosition(decoder, text("[2021-09-30 20:06:35,T21,1,V1.3.5,201950130047,37,14.97,092,-23.494715,-46.851341,0,240,4.08,0,19516,4431,0.78,724,10,09111,00771,31,4680"));
    }

    @Test
    public void testAutoFonDecode() throws Exception {
        var decoder = decoder("autofon");

        verifyNull(decoder, binary("10556103592310314825728F"));

        verifyPosition(decoder, binary(
                "02080000251848470afa010262daa690013aa4046da83745f8812560df010001126a"));

        verifyPosition(decoder, binary(
                "111E00000000000000000100007101010B0C020302010B0C0005A053FFFFFFFF02010B0C00276047FFFFFFFF1F5600FA000176F218C7850C0B0B0C203A033DBD46035783EF009E00320014FFFF45"));

        verifyNull(decoder, binary("41035151305289931441139602662095148807"));

        verifyPosition(decoder, binary(
                "023E00001E004D411EFA01772F185285009C48041F1E366C2961380F26B10B00911C"),
                position("2010-01-27 04:00:08.000", true, 54.73838, 56.10343));
    }

    @Test
    public void testAdmDecode() throws Exception {
        var decoder = decoder("adm");

        verifyPosition(decoder, binary(
                "38363931353330343235323337383400003728e000001402441d5f42c3711642930d000000c7000a461954f25fd82ed508000000000000000044000000010000000000140000"));

        verifyNull(decoder, binary(
                "000042033836393135333034323532333738340000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000078"));

        verifyPosition(decoder, binary(
                "01002e40041c0744009dfe6742c6c860427402000000f4ff077752c8f55b000000000b4132010213430100041e"));

        verifyNotNull(decoder, binary(
                "01008449443d3120536f66743d30783531204750533d313036382054696d653d30383a35393a32302031302e30392e31372056616c3d30204c61743d36312e36373738204c6f6e3d35302e3832343520563d3020536174436e743d342b3720537461743d30783030313020496e5f616c61726d3d30783030000000000000000000000000"));
    }

    @Test
    public void testGatorDecode() throws Exception {
        var decoder = decoder("gator");

        verifyPosition(decoder, binary(
                "24248000278632210440910210250107210008006368860032001400000057c047000000185c050da7f9960d"));

        verifyAttribute(decoder, binary(
                "24248000278632210440910210250107210008006368860032001400000057c047000000185c050da7f90000000001960d"),
                Position.KEY_ALARM, Position.ALARM_ACCELERATION);

        verifyNull(decoder, binary("242421000658e3d851150d"));

        verifyPosition(decoder, binary(
                "24248000260009632d141121072702059226180104367500000000c04700079c0c34000ad80b00ff000a0d"),
                position("2014-11-21 07:27:02.000", true, 59.37697, 10.72792));
    }

    @Test
    public void testGlobalSatDecode() throws Exception {
        Config config = mock(Config.class);
        when(config.getString("globalsat.format0")).thenReturn("TSPRXAB27GHKLMmnaictuvw*U!");
        var decoder = decoder("globalsat", config);

        verifyPosition(decoder, text(
                "GSb,GTR-388,358173053992353,0000,5,8080,3,270419,113326,E01020.6223,N6323.1937,129,0.01,154,10,0.8,12380mV,3128mV,0,0,11,242,02,10EB,120FC1B*5a!"));

        decoder = decoder("globalsat");
        verifyPosition(decoder, text(
                "$80050377796567,0,13,281015,173437,E08513.28616,N5232.85432,222.3,0.526,,07*37"),
                position("2015-10-28 17:34:37.000", true, 52.54757, 85.22144));
    }

    @Test
    public void testTaipDecode() throws Exception {
        var decoder = decoder("taip");

        verifyNull(decoder, text(">RAL19500+00230+00012;ID=3168;*48<"));

        verifyAttributes(decoder, text(
                ">RUS00,010170000000+0000000+000000000000001009999000011060074755268EF,0001139503871486,01,ZZZZZZZZZZ;ID=11817;#LOG:6AE4;*2C<"));

        verifyPosition(decoder, text(
                ">RGP230615010248-2682523-065236820000003007F4101;ID=0005;#0002;*2A<"),
                position("2015-06-23 01:02:48.000", true, -26.82523, -65.23682));

        verifyPosition(decoder, text(
                ">REV481599462982+2578391-0802945201228512;ID=Test"),
                position("2010-09-02 17:29:42.000", true, 25.78391, -80.29452));
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

        Config config = mock(Config.class);
        when(config.getString("skypatrol.mask")).thenReturn("-1");
        verifyPosition(decoder("skypatrol", config), binary(
                "00050210000000000D313134373735383300CB000000000E11070C010184D032FB3841370000000016072B000017050032000000000000024E0C071116072C105900050000000000050000000000050000000003100260B7363B6306C11A00B73637F206BF19B73637F106B50EB73638B106BB0BB7363B6106B80AB73637F306B709000000000000000000"));

        verifyNull(decoder, binary(
                "000500030101383637383434303031373832333336420102000c0000fa07b5e101876c5b0e0a111606131c1b5e"));

        verifyNull(decoder, binary(
                "000502000000f1143035303031393031d1df002f00000d0187120115e556ff762aa90000000000aae40005d2000ee1bc0e010a042530000000000000070004000002233c096c00ee2a00233c008500f022233c0b0500f21d233c000000fb23000000000000000000000000000000000000000000000000"));

        verifyNull(decoder, binary(
                "00040200202020202020202020382020202020202030313137323230303131383531373820313220244750524d432c3232343833392e30302c412c303332382e3433383830362c4e2c30373633312e3630373731372c572c302e302c302e302c3139303731342c332e382c452c412a32420d0a00"));
    }

    @Test
    public void testT800xDecode() throws Exception {
        var decoder = decoder("t800x");

        verifyPosition(decoder, binary(
                "262602005356f408696160608183510032012c140064001f51c000010100004004000000b187660025050814571700000000571b74c2b0fa0bc2000000bc130701500000000700000000035aff00855e0f0e20"));

        verifyAttributes(decoder, binary(
                "25251300594a1b0869738060144917003c0e101e03e85a2dc8c00005070000410000000000000000000000005b000003a5b45e00230919102252e3a5094288fabfc0e98b15420000010403921352ffff0000001cffffffffff25251300594a1c0869738060144917003c0e101e03e85a2ac7c00005070000410000000000000000000000002d000003a5b48b002309191023522d320642abfebfc0e98b15420000010c03921345ffff0000001bffffffffff25251300594a1d0869738060144917003c0e101e03e85a1ac9c000050700004100000000000000000000000024000003a5b4af00230919102452b81ef9410002c0c0ec8b15420108011403911345ffff0000001dffffffffff25251300594a1e0869738060144917003c0e101e03e85a3ec7c00005070000410000000000000000000000000e000003a5b4bd002309191025060ad7ec41da02c0c0058c15420084016303921345ffff0000001cffffffffff25251300594a1f0869738060144917003c0e101e03e85a3ec7c020050700004100000000000000000000000005000003a5b4c2002309191025090e2deb410203c0c0108c15420089014303921338ffff0000001dffffffffff25251300594a200869738060144917003c0e101e03e85a1ec5c000050700004100000000000000000000000020000003a5b4e20023091910260948e1bc412205c0c0458c15420040013603921355ffff0000001bffffffffff25251300594a210869738060144917003c0e101e03e85a00c5c020050700004100000000000000000000000000000003a5b4e20023091910270948e1bc412205c0c0458c15420040013603911332ffff0000001dffffffffff"));

        verifyAttributes(decoder, binary(
                "272704004901380864112055585747c612230321220006000036435fc8acc2ee600f420000000000000000909019003900001356a18000012c0000a8c00000001e20d4800000c00000"));

        verifyAttributes(decoder, binary(
                "2525110055000208677300508924902206262035310c540045004c00430045004c0004454447450847534d20313930300f323134303734323036373835323839143839333430373131373930303936383037363846"));

        verifyAttributes(decoder, binary(
                "27271000247bd00860112047066487210407034238000005d7d17365e625ff640a730148"));

        verifyAttributes(decoder, binary(
                "27271000277bb30860112047066487210407022840000004e6215130c50fff620a0c1518000156"));

        verifyPosition(decoder, binary(
                "252514005901c00867730050941347001e46501e03e80064f2c0001401000041000000000000000000ffffffff160000034ec40021100719073800000000c2fb90c21291fd400000000003961237ffff0000002effffffffff"));

        verifyPosition(decoder, binary(
                "27270200497d880860112047066487470021040702270500006442d4e2e342f671b441000000008000008080881dff3900000384700640003c0000001e1e00641e30d2800000000000"));

        verifyAttributes(decoder, binary(
                "252510003100180865284041080544201221191023000003ffff9702eff820014700000000912a6ac26dff09c200000000"));

        verifyAttribute(decoder, binary(
                "2727020049052e086528404072393849002008060310110000000068b7c8c286eaa441000000008000008100001617410700019ce782b0001e000002581e00000530d4801f00000000"),
                Position.KEY_BATTERY_LEVEL, 100);

        verifyPosition(decoder, binary(
                "262602005308090865284040309670000f000f0f0000005a47c000050100000020000000008bfd0020022505185300004041dcc9d6c243b3c6410000012712400000000009e2ffffffffffffffffffffffff09"));

        verifyPosition(decoder, binary(
                "2727040049001b0866425039645728c916190604005240000000007739d2c25b681f420000000080000081000020174105000005458216001e000000f01e00001e30d0000000000000"));

        verifyAttribute(decoder, binary(
                "272705005e000108664250328807851905301107481054002d004d006f00620069006c006500074341542d4e42310a4c54452042414e4420340f333130323430323030303032333030143839303132343032303531303030323330303746"),
                Position.KEY_OPERATOR, "T-Mobile");

        verifyPosition(decoder, binary(
                "272702004904a90866425032880785c800190530080350000000000705eec29bf50842000000000008008090502a003700000a9e358002003c000003841900001e3f90000000000000272702004904aa0866425032880785c800190530081851000000000705eec29bf50842000000000008008090602e003700000a9e358002003c000003841900001e3f90000000000000"));

        verifyNull(decoder, binary(
                "2727010017000108806168988888881016010207110111"));

        verifyNull(decoder, binary(
                "252501001504050880061689888888111111250350"));

        verifyAttribute(decoder, binary(
                "2525810128000108664250328959160149004d00450049003a003800360036003400320035003000330032003800390035003900310036002c005300450054002000560045005200530049004f004e0020004f004b002c00560065007200730069006f006e003a00420061007300690063003a00560031002e0030002e0030002c004100500050003a00560034002e0032002e0033002c004200550049004c0044003a0032003000310039002d00300033002d00330030002c00300038003a00300035002c0050004c0054003a0032003500300033004100560045002c00480057003a00560032002e0031002c004d004f00440045004c003a002c004d004f00440045004d003a0042003900470036004d0041005200300032004100300037004d00310047002300"),
                Position.KEY_RESULT, "IMEI:866425032895916,SET VERSION OK,Version:Basic:V1.0.0,APP:V4.2.3,BUILD:2019-03-30,08:05,PLT:2503AVE,HW:V2.1,MODEL:,MODEM:B9G6MAR02A07M1G#");

        verifyPosition(decoder, binary(
                "2525020044a66d0862522030401350001403841409c40064edc000051100960000071701370000003ea7ee0019032010581300000000aad3e1bda6f24d42000000001281"));

        verifyPosition(decoder, binary(
                "252502004400010880616898888888000A00FF2001000020409600989910101010055501550000101005050005051010050558866B4276D6E342912AB441111500051010"));

        verifyNull(decoder, binary(
                "232301001500000880316890202968140197625020"));

        verifyNull(decoder, binary(
                "232303000f00000880316890202968"));

        verifyAttributes(decoder, binary(
                "232302004200000880316890202968001e02582d00000000000000050000320000018901920000001dc1e2001601081154255d0202005a0053875a00a57e5a00af80"));

        verifyNull(decoder, binary(
                "232301001500020357367031063979150208625010"));

        verifyNull(decoder, binary(
                "232303000f00000357367031063979"));

        verifyPosition(decoder, binary(
                "232304004200030357367031063979003c03842307d00000c80000050100008000008900890100000017b100151022121648b8ef0c4422969342cec5944100000110"));

        verifyPosition(decoder, binary(
                "232302004200150357367031063979003c03842307d000004a0000050100004001009500940000000285ab001510281350477f710d4452819342d1ba944101160038"));

        verifyAttributes(decoder, binary(
                "232302004200000357367031063979003c03842307d000008000000501000000010094009400000002a0b90015102814590694015a00620cf698620cf49e620cf498"));

        verifyAttribute(decoder("t800x", new Config(), "TLW2-2BL"), binary(
                "2525140059002d0869084069062093001902581e000000644fc0000500000080010000ffffffffffff000000001300000000160125081006080400000a4317cdf4c20fc73c420000000003630263ffff0000001cffffffffff"),
                Position.KEY_POWER, 2.63);
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

    @Test
    public void testTk102Decode() throws Exception {
        var decoder = decoder("tk102");

        // Login (type 0x80): data = ASCII device ID "123456789012345" (15 bytes = 0x0f)
        verifyNull(decoder, binary(
                "5b80000000000000000000000f3132333435363738393031323334355d"));

        // Position (type 0x90): data = "(TRACK010203A2234.0680N11407.4040E010.000120626)"
        verifyPosition(decoder, binary(
                "5b90000000000000000000003028545241434b30313032303341323233342e"
                + "303638304e31313430372e34303430453031302e303030313230363236295d"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testTopflytechDecode() throws Exception {
        var decoder = decoder("topflyftech");

        verifyPosition(decoder, text(
                "(123456789012345,0,260612010203A2234.0680N11407.4040E10.09"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        verifyPosition(decoder, text(
                "(987654321098765,extra,data,260612010203V2234.0680S11407.4040W5.02"));
    }

    @Test
    public void testTrackboxDecode() throws Exception {
        var decoder = decoder("trackbox");

        // Login
        verifyNull(decoder, text("a=connect&foo=bar&i=123456789012345"));

        // Position: HHMMSSms,ddmm.mmmmNS,dddmm.mmmmEW,hdop,alt,fix,course,speed_kph,speed_kn,ddmmyy,sats
        verifyPosition(decoder, text(
                "010203.000,2234.0680N,11407.4040E,1.5,45.0,1,90.0,10.0,5.0,120626,8"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testBoxDecode() throws Exception {
        var decoder = decoder("box");

        // Login
        verifyNull(decoder, text("H,login,123456789012345,extra"));

        // Position: L,yymmddHHMMSS,G,lat,lon,speed_kph,course,dist_km,event,status
        verifyPosition(decoder, text(
                "L,260612010203,G,22.56780,114.12340,10.0,90.0,100.5,5,3"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        // Event echo (E,): server acks, no position returned
        verifyNull(decoder, text("E,ping"));
    }

    @Test
    public void testTr20Decode() throws Exception {
        var decoder = decoder("tr20");

        // Ping: %%anything,id → ack, null
        verifyNull(decoder, text("%%PING,123456789012345"));

        // Position: %%id,A,yymmddHHMMSS,NSdegmin,speed,course,NA,status,event
        verifyPosition(decoder, text(
                "%%123456789012345,A,260612010203,N2234.0680E11407.4040,10,90,NA,00000000,5"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        // With temperature
        verifyPosition(decoder, text(
                "%%123456789012345,A,260612010203,N2234.0680E11407.4040,10,90,B25,00000000,0"));
    }

    @Test
    public void testYwtDecode() throws Exception {
        var decoder = decoder("ywt");

        // Sync message → ack, null
        verifyNull(decoder, text("%SN,123456789012345:0,abc,def,ghi,jkl"));

        // Position: %type,unit:sub,yymmddHHMMSS,EWlon,NSlat,alt,speed_kph,course,sats,reportId,status
        verifyPosition(decoder, text(
                "%KP,123456789012345:0,260612010203,E114.12340,N22.56780,45,10,90,8,001,0001"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testHaicomDecode() throws Exception {
        var decoder = decoder("haicom");

        // $GPRS<imei>,<ver>,yymmdd,HHMMSS,<flags><latDeg><latFrac×1000><lonDeg><lonFrac×1000>,spd×10,crs×10,status,gprs,ps,in,out#V<bat×10>
        // flags=7: valid=1, lon-east=1, lat-north=1
        // lat = 22 + 34068/60000 = 22.567800, lon = 114 + 7404/60000 = 114.123400
        verifyPosition(decoder, text(
                "$GPRS123456789012345,V1,260612,010203,7223406811407404,100,900,0,,,0,0#V123"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));
    }

    @Test
    public void testPt3000Decode() throws Exception {
        var decoder = decoder("pt3000");

        // %<imei>,$GPRMC,HHMMSS.ms,AV,ddmm.mmmm,NS,dddmm.mmmm,EW,speed,course,ddmmyy
        verifyPosition(decoder, text(
                "%123456789012345,$GPRMC,010203.000,A,2234.0680,N,11407.4040,E,10.0,90.0,120626"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        verifyPosition(decoder, text(
                "%999888777666555,$GPRMC,010203,V,2234.0680,N,11407.4040,E,,,120626"));
    }

    @Test
    public void testNtoDecode() throws Exception {
        var decoder = decoder("nto");

        // ^NB,<imei>,<type>,ddmmyy,HHMMSS,AVM,NS,ddmm.mmmm,EW,dddmm.mmmm,speed,course,statushex,
        verifyPosition(decoder, text(
                "^NB,123456789012345,GPS,120626,010203,A,N,2234.0680,E,11407.4040,10.0,90,00000000,"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        // Alarm: bit 25 = power cut
        verifyAttribute(decoder, text(
                "^NB,123456789012345,SOS,120626,010203,A,N,2234.0680,E,11407.4040,10.0,90,02000000,"),
                Position.KEY_ALARM, Position.ALARM_POWER_CUT);
    }

    @Test
    public void testMictrackHqDecode() throws Exception {
        var decoder = decoder("mictrack");

        // V1 position: *HQ,id,V1,HHMMSS,AV,ddmm.mmmm,NS,dddmm.mmmm,EW,speed_kph,course,ddmmyy,status,mcc,mnc,lac,cid
        verifyPosition(decoder, text(
                "*HQ,123456789012345,V1,010203,A,2234.0680,N,11407.4040,E,10.0,90.0,120626,FFFFFFFF,460,0,1234,5678"),
                position("2026-06-12 01:02:03.000", true, 22.56780, 114.12340));

        // V4 heartbeat → null (acked but no position)
        verifyNull(decoder, text("*HQ,123456789012345,V4,,20260612010203"));
    }

    @Test
    public void testEasytrackDecode() throws Exception {
        var decoder = decoder("easytrack");

        // OBD report
        verifyAttributes(decoder, text(
                "*ET,358162092884226,OB,BD$V13.6;R01510;S023;P034.9;O035.2;C085;L000.0;XM035.170;M4.25;F001.197;T0000730;A09;B00;D00;GX0;GY0;GZ0;"));

        // Cell-tower fallback
        verifyNotNull(decoder, text(
                "*ET,354522180593498,JZ,0,20222,262,724,4#"));

        // TX message → null (echo only)
        verifyNull(decoder, text(
                "*ET,354522180045564,TX,V,14070E,122336"));

        // Location report with position
        verifyPosition(decoder, text(
                "*ET,135790246811221,DW,A,0A090D,101C0D,00CF27C6,0413FA4E,0000,0000,00000000,20,4,0000,00F123"),
                position("2010-09-13 16:28:13.000", true, 22.62689, 114.03021));

        // Location report without extended fields
        verifyPosition(decoder, text(
                "*ET,135790246811221,HB,A,050915,0C2A27,00CE5954,04132263,0000,F000,01000000,20,4,0000,00F123,100,4845423835,0091564212,0B45,10.00,9"));
    }

    @Test
    public void testEasytrackDecodeE3() throws Exception {
        var decoder = decoder("easytrack", new Config(), "E3+4G");

        // E3+4G model: hours field instead of driver ID
        verifyAttribute(decoder, text(
                "*ET,135790246811221,DW,A,180709,16081C,80D74F8D,81ACFAD6,04B0,1C20,00800000,23,0,0348,004491,725,0000000000,00181A8C,0DAC,13.41,15"),
                Position.KEY_HOURS, 94779600000L);
    }

    @Test
    public void testWialonDecode() throws Exception {
        var decoder = decoder("wialon");

        // Heartbeat → null
        verifyNull(decoder, text(
                "#P#"));

        // SD with outer IMEI (store not available in unit test)
        verifyPosition(decoder, text(
                "99999999#SD#270413;205601;5544.6025;N;03739.6834;E;1;2;3;4"),
                position("2013-04-27 20:56:01.000", true, 55.74338, 37.66139));

        // Full data with outer IMEI + extended fields
        verifyPosition(decoder, text(
                "99999999#D#151216;135910;5321.1466;N;04441.7929;E;87;156;265.000000;12;1.000000;241;NA;NA;NA;odo:2:0.000000,total_fuel:1:430087,can_fls:1:201,can_taho:1:11623,can_mileage:1:140367515"));

        // Full data with counter.version prefix + outer IMEI
        verifyPosition(decoder, text(
                "2.0;99999999#D#101118;061143;0756.0930;N;12338.6403;E;18.223;99.766;-4.000;10;0.800;NA;NA;NA;NA;101_521347:1:521249,101_521126:1:6593598,101_521127:1:774780,101_521072_21.1:1:0,101_521072_21.2:1:71353;F24A"));

        // Batch messages with outer IMEI (archive flag)
        verifyPositions(decoder, text(
                "99999999#B#080914;073235;5027.50625;N;03026.19321;E;0.700;0.000;NA;4;NA;NA;NA;;NA;|080914;073420;5027.50845;N;03026.18854;E;1.996;292.540;NA;4;NA;NA;NA;;NA;"));

        // boolean param
        verifyAttribute(decoder, text(
                "99999999#D#120319;112003;NA;NA;NA;NA;0.000;NA;NA;0;NA;NA;NA;NA;NA;motion:3:false"),
                "motion", false);

        // bat/temp remapping
        verifyAttribute(decoder, text(
                "99999999#D#NA;NA;NA;NA;NA;NA;NA;NA;NA;NA;NA;NA;NA;;NA;SOS:1:0,temp:3:18,bat:3:99"),
                Position.KEY_BATTERY_LEVEL, 99);
    }

    @Test
    public void testKhdDecode() throws Exception {
        var decoder = decoder("khd");

        verifyNull(decoder, binary(
                "2929b1000605162935b80d"));

        verifyPosition(decoder, binary(
                "2929800028258b8c10210731035840031534240542120200000337fb000000ffff5a00000a0000000005005d0d"));

        verifyPosition(decoder, binary(
                "29298100280a9f9538081228160131022394301140372500000330ff0000007ffc0f00001e000000000034290d"));

        verifyPosition(decoder, binary(
                "29298200230aa2cc391205030505220285947903109550008002078400000002000000000000750d"));

        verifyAttribute(decoder, binary(
                "2929a300403099934c2004030943310000000000000000000000007b0000007fff0e0000e70014000000000018050b01303030314330334437312102007b2203140dda610d"),
                Position.KEY_DRIVER_UNIQUE_ID, "0001C03D71");

        verifyAttribute(decoder, binary(
                "2929a3003e1680ba0a2304180759500000000000000000000000007b00000080001914000000000000000000162001641b0b0000249002bc58030001cc46020000e70d"),
                Position.KEY_BATTERY_LEVEL, 100);
    }

    @Test
    public void testXirgoDecode() throws Exception {
        var decoder = decoder("xirgo");

        verifyPosition(decoder, text(
                "$$354660046140722,6001,2013/01/22,15:36:18,25.80907,-80.32531,7.1,19,165.2,11,0.8,11.1,17,1,1,3.9,2##"),
                position("2013-01-22 15:36:18.000", true, 25.80907, -80.32531));

        verifyPosition(decoder, text(
                "$$354660046140722,4003,2013/01/22,15:36:20,25.80907,-80.32531,7.1,0,165.2,11,0.8,11.1,17,1,1,3.9,2##"));

        var decoderNew = decoder("xirgo");

        verifyPosition(decoderNew, text(
                "$$352054058132185,4001,2017/04/21,00:01:05,32.54659,-116.90670,143.2,0,0,0,598,0.0,12,0.9,765840,7.0,14.5,19,1,1,0011,8.5,63.2,5,21999,184,255,671,207,100,185##"),
                position("2017-04-21 00:01:05.000", true, 32.54659, -116.90670));

        verifyPosition(decoderNew, text(
                "$$352054058132185,6011,2017/04/21,04:57:10,32.49658,-116.85957,250.9,0,0,0,602,0.0,12,0.8,765876,7.0,14.1,21,1,1,0011,10.1,0.0,5,170917890,280,255,627,0,100,167##"));

        Config config = mock(Config.class);
        when(config.getString("xirgo.form")).thenReturn("UID,EV,D,T,LT,LN,AL,GSPT,HD,SV,HP,BV,CQ,GS,SI,IG,OT");
        var decoderCustom = decoder("xirgo", config);

        verifyPosition(decoderCustom, text(
                "$$183900034,4002,03/30/2019,02:15:22,46.848577,-114.022213,978,0.0,172.3,16,1.2,13.291,20,3,2,2,1##"),
                position("2019-03-30 02:15:22.000", true, 46.84858, -114.02221));
    }

    @Test
    public void testMiniFinderDecode() throws Exception {
        var decoder = decoder("minifinder");

        verifyNull(decoder, text(
                "!1,867273023933661,V07S.5701.1621,100"));

        verifyAttributes(decoder, text(
                "!3,ok"));

        verifyNull(decoder, text(
                "!1,123456789012345"));

        verifyAttribute(decoder, text(
                "!4,10,040123,,,1.0,110,0,0S,33"),
                "phone1", "040123");

        verifyAttribute(decoder, text(
                "!5,17,V,50"),
                Position.KEY_BATTERY_LEVEL, 50);

        verifyAttributes(decoder, text(
                "!5,17,V"));

        verifyNull(decoder, text(
                "!1,860719027585011"));

        verifyPosition(decoder, text(
                "!D,02/05/17,19:56:17,47.083542,15.482373,0,0,100001,479.3,100,4,9,0"));

        verifyPosition(decoder, text(
                "!D,15/04/17,13:58:53,51.483067,-0.452548,60,180,140001,28.7,47,4,13,0"));

        verifyPosition(decoder, text(
                "!D,07/04/17,05:42:26,-37.588970,145.121231,0,0,0c0001,185.2,92,7,14,1.2"));

        verifyPosition(decoder, text(
                "!C,30/1/16,1:1:6,31.259157,30.020910,0,0,100001,25.32,100,0.03,0.01,0"));

        verifyPosition(decoder, text(
                "!A,26/10/12,00:28:41,7.770385,-72.215706,0.0,25101,0"));

        verifyPosition(decoder, text(
                "!D,22/2/14,13:40:58,56.899601,14.811541,0,0,1,176.0,98,5,16,0"),
                position("2014-02-22 13:40:58.000", true, 56.89960, 14.81154));

        verifyPosition(decoder, text(
                "!D,3/7/13,6:35:30,22.645952,114.040436,0.0,225.8,1f0001,12.11,98,0,0,0"));
    }

    @Test
    public void testTramigoDecode() throws Exception {
        var decoder = decoder("tramigo");

        verifyNull(decoder, binary(
                "04d000a9e45c1b69101a023ef34f00549eec63047795ec63000000004eff5c0062c2e4ffbf612f00ce9aec63000000007700960180c0e4ff5f542f004c1200007b004a023d0200000001430000000000000000001f000b0006005a57436f63612d436f6c6120426f74746c696e6720506c616e74204861726172654772616e69746573696465486172617265014400000000000000000021000a0006005a574a6169726f7320486972692043656e747265205072696d617279205363686f6f6c536f7574686572746f6e486172617265"));

        verifyPosition(decoder, binary(
                "0480001df35b1b69101a023ef34f0090436d38003200380e0000850081c0e4ff6d542f00000015000000050000000000007600a20100008f436d3800014400000000000000000021000a0006005a574a6169726f7320486972692043656e747265205072696d617279205363686f6f6c536f7574686572746f6e486172617265"));

        verifyNull(decoder, binary(
                "80003d1ac0001c00010100000367152b13bc1d5970696e6720454f46"));

        verifyAttributes(decoder, binary(
                "8000c426b000a6000101c557037598050d5c8a595472616d69676f3a204d6f76696e672c20302e3132206b6d2045206f66204c617275742054696e2049736c616d6963205072696d617279205363686f6f6c2c2054616970696e672c20506572616b2c204d592c20342e38333134392c203130302e37333038352c204e572077697468207370656564203130206b6d2f682c2030303a34393a30382041756720392020454f46"));

        verifyAttributes(decoder, binary(
                "8000853eb000b8000101fcff032f14665a89e2564176656e7369732053797353657276653a2049676e6974696f6e206f6e2064657465637465642c206d6f76696e672c20302e3135206b6d205357206f66204261626120416e696d61736861756e205374726565742d426f64652054686f6d61732053742e2c20537572756c6572652c204c61676f7320436974792c204e472c20362e34383736352c20332e33343735352c2031303a3031204d6172203131202020454f46"));
    }

    @Test
    public void testNavigilDecode() throws Exception {
        var decoder = decoder("navigil");

        // MSG_INDICATION (msgId=4) → null
        verifyNull(decoder, binary(
                "01004300040020000000f60203080200e7cd0f510c0000003b00000000000000"));

        // MSG_POSITION_REPORT_2 (msgId=15) → valid position
        verifyPosition(decoder, binary(
                "0100b3000f0024000000f4a803080200ca0c1151ef8885f0b82e6d130400c00403000000"));
    }

    @Test
    public void testJmakDecode() throws Exception {
        var decoder = decoder("jmak");

        // JSON heartbeat → ACK, null
        verifyNull(decoder, text("{\"type\":\"heartbeat\",\"imei\":\"868695060715016\"}"));

        // Alternate keep-alive → ACK, null
        verifyNull(decoder, text("^TIMB;868695060715016$"));

        // Position with CAN section
        verifyPosition(decoder, text(
                "~000000041FE3FFFF;233333-33333-3333333;868695060715016;8224;NULL;1750689928520;-19.88245;-43.97853;837.10;B;0.06;18;31;0.89;0.00;1;0;52.50;4.74;11750;3875;TIMB;4;1750689928534;1;9;0;0.00|00000000000041DF;180.00;211.00;208.00;190.00;7;50;P;80;69$"));

        // Position with different CAN section
        verifyPosition(decoder, text(
                "~000000041FE3FFFF;233333-33333-3333333;868695060715016;8208;NULL;1750689688530;-19.88247;-43.97855;830.20;B;0.61;16;31;0.79;0.00;1;0;52.43;4.74;11750;3863;TIMB;4;1750689688544;1;9;0;0.00|000000000000405C;0.00;0.00;0;0;0$"));

        // Position without CAN section
        verifyPosition(decoder, text(
                "~000000041FE3FFFF;233333-33333-3333333;868695060715016;8210;NULL;1750689718530;-19.88246;-43.97855;825.60;B;0.53;16;31;0.87;0.00;1;0;52.45;4.74;11750;3863;TIMB;4;1750689718543;1;9;0;0.00$"));
    }

    @Test
    public void testXexunDecode() throws Exception {
        var decoder = decoder("xexun");

        // Basic mode — edge case: position at origin with no date/time
        verifyAttributes(decoder, text(
                "GPRMC,.000,A,0.000000,S,0.0000,W,0.00,0.00,,00,0000.0,A*55,L,,imei:353579010727036,"));

        // Basic mode — South lat, East lon, expected coordinates
        verifyPosition(decoder, text(
                "GPRMC,150120.000,A,3346.4463,S,15057.3083,E,0.0,117.4,010911,,,A*76,F,imei:351525010943661,"),
                position("2011-09-01 15:01:20.000", true, -33.77411, 150.95514));

        // Basic mode — embedded \r\n between NMEA checksum and signal field
        verifyPosition(decoder, text(
                "GPRMC,121535.000,A,5417.2666,N,04822.1264,E,1.452,30.42,031014,0.0,A*4D\r\n,L,imei:355227042011730,"));

        // Basic mode — GNRMC variant, space before imei
        verifyPosition(decoder, text(
                "GNRMC,134418.000,A,5533.8973,N,03745.4398,E,0.00,308.85,160215,,,A*7A,F,, imei:864244028033115,"));

        // Basic mode — empty course field
        verifyPosition(decoder, text(
                "GPRMC,233842.000,A,5001.3060,N,01429.3243,E,0.00,,210211,,,A*74,F,imei:354776030495631,"));

        // Full mode — serial + phone prefix, sats/alt/power suffix, expected coordinates
        verifyPosition(decoder, text(
                "130302125349,+79604870506,GPRMC,085349.000,A,4503.2392,N,03858.5660,E,6.95,154.65,020313,,,A*6C,F,, imei:012207007744243,03,-1.5,F:4.15V,1,139,28048,250,01,278A,5072"),
                position("2013-03-02 08:53:49.000", true, 45.05399, 38.97610));

        // Full mode — ACCStart alarm → ignition on
        verifyPosition(decoder, text(
                "171007160505,,GPRMC,160505.000,A,5323.4680,N,00252.4202,W,000.0,129.7,071017,,,A*7A,F,ACCStart, imei:864504031916915,10,41.1,F:4.28V,1,135,19824,234,15,0062,B7D5"));

        // Full mode — "help me!" alarm → SOS
        verifyPosition(decoder, text(
                "111111120009,+436763737552,GPRMC,120600.000,A,6000.0000,N,13000.0000,E,0.00,0.00,010112,,,A*68,F,help me!, imei:123456789012345,04,481.2,F:4.15V,0,139,2689,232,03,2725,0576"));

        // verifyNull — no GPRMC/GNRMC in message
        verifyNull(decoder, text(
                ",+48606717068,,L,, imei:012207005047292,,,F:4.28V,1,52,11565,247,01,000E,1FC5"));
    }

    @Test
    public void testStartekDecode() throws Exception {
        var decoder = decoder("startek");

        // Basic position (no extended fields)
        verifyPosition(decoder, text(
                "&&l141,863911061945394,000,0,,230918072531,A,22.678598,114.045970,26,0.6,0,0,74,2286304571,460|0|249F|00001093,20,001C,00,00,04A7|019C|0000|0000,1,C0\r\n"));

        // Event 53 → KEY_DRIVER_UNIQUE_ID from eventData field
        verifyAttribute(decoder, text(
                "&&a152,860262050010565,000,53,8F5300,210528015706,A,-38.229746,145.043446,6,1.5,0,285,84,2102994,505|1|306E|082D6101,31,0000003D,02,02,04C0|01A0|0000|0000,1,,DC\r\n"),
                Position.KEY_DRIVER_UNIQUE_ID, "8F5300");

        // Driver ID from long field (event 0, 20+ char driver ID)
        verifyAttribute(decoder, text(
                "&&L171,868825064282040,000,0,,241209063302,A,13.809656,100.558255,14,0.9,0,0,67,1560,520|4|A418|008AAC3F,31,000000BD,02,00,04E3|0171|0000|0000,131,,,,3100  1  61000541  10800  ?FE\r\n"),
                Position.KEY_DRIVER_UNIQUE_ID, "3100  1  61000541  10800  ?");

        // OBD fields — verify KEY_FUEL_CONSUMPTION
        verifyAttribute(decoder, text(
                "&&x164,869926040743375,000,0,,220705205955,A,33.326001,44.445318,10,1.2,0,57,8,925,418|40|038C|000083CD,31,00000015,00,00,0016|016A|0000|0000,1,,,686|33||44|99|14|124|11|8D\r\n"),
                Position.KEY_FUEL_CONSUMPTION, 1.1);

        // Hours field
        verifyAttribute(decoder, text(
                "&&s148,868703050178631,000,37,,230704040211,A,22.678565,114.046011,31,0.5,0,339,77,8,460|0|249F|0AC2620D,27,0000001D,02,00,04F2|01A1|0000|0000,129,,,,949037\r\n"),
                Position.KEY_HOURS, 9490000L);

        // Default type → KEY_RESULT
        verifyAttribute(decoder, text(
                "&&:23,860262050015424,129,OKA2\r\n"),
                Position.KEY_RESULT, "OK");
    }

    @Test
    public void testXexun2Decode() throws Exception {
        var decoder = decoder("xexun2");

        verifyPositions(decoder, false, binary(
                "faaf00140a5a8618810536243350005ed8e101005b64622880401b001482060864cc2296f840daa22aa884f008c87483c291efddc4f09fc2f49db3c058ef68005a9abe1ae8299d6449bac4e984e0c1d6baa8469d265ff2b60100cc00080000fb2e0013572a3600000002000000000000faaf"));

        verifyPositions(decoder, false, binary(
                "FAAF00140004863921033475388000AFB7D203003800380038F9608A7B801E0060820205788A205DF523D97844FDB90443D37844FDB90465CFB4FBF946B0E8CEF639095803F8CC00000002350000004000FA608A7BA81E0060820205788A205DF523D97844FDB90443D2F639095803F8CFB4FBF946B0E8CE7844FDB90465CD00000002350000004000FB608A7BD01E0060820205788A205DF523D97844FDB90443D2F639095803F8CFB4FBF946B0E8CE7844FDB90465CD00000002350000004000FAAF"));

        verifyPositions(decoder, false, binary(
                "faaf0014000286147503139003400032f2b001002f4260b0d6a0008019104a3378323130333135317c323130333132303100704020308715758089502023015648643670faaf"));

        verifyPositions(decoder, false, binary(
                "FAAF0014000486188105421927500035E6D2010032FC60EC264D00002003000000020205444E6DD72699D674427F7712CBC3BCF2AFD910BAC1C6FBE474CFC7A9B4FBE474CFC7A6FAAF"));

        verifyPositions(decoder, binary(
                "FAAF00140CF18626490454584530002BF2DD0200130013D360EFD7F514006402010D46322C4A450BA026D460EFD7FA14006402010D46322C4A450BA026FAAF"));

        verifyPositions(decoder, binary(
                "FAAF0014000C8622050512345670002DF3A001002A0062D9047400005E0280001E47001B400D4BA732DF505E40B4153AAF78FEF00109000000000042B36666FAAF"),
                position("2022-07-21 07:47:00.000", true, 51.68715, 0.06103));

        verifyPositions(decoder, binary(
                "FAAF0014000086220505123456700016F86F0100130065F0B2C200000002010Ac63daae045679dd0FAAF"),
                position("2024-03-12 19:53:38.000", true, 37.09772, -121.64531));
    }

    @Test
    public void testHuaShengDecode() throws Exception {
        var decoder = decoder("huasheng");

        // MSG_LOGIN (0xAA02) — registers IMEI, returns null
        verifyNull(decoder, binary(
                "c000000077aa0200000000000e000100143347315f48312e315f56312e30372e54000300133335353835353035303434303635380004000b3531323030303000050005010006000400070004000800050000090018383936313032353431343533333239313833360d000a000f796573696e7465726e6574c0"));

        // MSG_HSO_REQ (0x0002) — heartbeat, returns null
        verifyNull(decoder, binary(
                "c0010c003c0002000000000044020010a0014f42445f3347315f56312e302e330013a0043335353835353035313032303536360006a08700000006a0a105c9c0"));

        // MSG_UPFAULT (0xAA12) — DTC fault codes
        verifyAttribute(decoder, binary(
                "C00000001CAA120000000000020001001001000200030043008200C100C0"),
                Position.KEY_DTCS, "P0100 P0200 P0300 C0300 B0200 U0100");

        // MSG_POSITION (0xAA00) — odometer from string TLV 0x0010
        verifyAttribute(decoder, binary(
                "c0000000bdaa0000000000061d480000083233303132333039323634330000000000000000000000a600140000000100187e02de0a00290372000005951600260000004a0000040009080000004a0005000a0d0000000ad0000900154d414b474d363639484a4e333031383739000f00133836323230353035353338393836320010001031333231322e30303030303000110008000000000014000bf851084f000018001500060000002000153430344030354035363532403130363332c0"),
                Position.KEY_ODOMETER, 13212000.0);

        // Same packet — fuelLevel2 from OBD TLV
        verifyAttribute(decoder, binary(
                "c0000000bdaa0000000000061d480000083233303132333039323634330000000000000000000000a600140000000100187e02de0a00290372000005951600260000004a0000040009080000004a0005000a0d0000000ad0000900154d414b474d363639484a4e333031383739000f00133836323230353035353338393836320010001031333231322e30303030303000110008000000000014000bf851084f000018001500060000002000153430344030354035363532403130363332c0"),
                "fuelLevel2", 38);

        // Same packet — engine load from extended OBD TLV 0x0014
        verifyAttribute(decoder, binary(
                "c0000000bdaa0000000000061d480000083233303132333039323634330000000000000000000000a600140000000100187e02de0a00290372000005951600260000004a0000040009080000004a0005000a0d0000000ad0000900154d414b474d363639484a4e333031383739000f00133836323230353035353338393836320010001031333231322e30303030303000110008000000000014000bf851084f000018001500060000002000153430344030354035363532403130363332c0"),
                Position.KEY_ENGINE_LOAD, 81 / 255.0);

        // MSG_POSITION with adBlueLevel, fuelLevel2, coolant, rpm (shorter OBD TLV)
        verifyAttribute(decoder, binary(
                "c000000044aa00000000000030c000000031353035323630373538323800addcc100226aef00000000001200050001001880033c0300190bfb000804baff00645866487f28c0"),
                "fuelLevel2", 100);

        verifyAttribute(decoder, binary(
                "c000000044aa00000000000030c000000031353035323630373538323800addcc100226aef00000000001200050001001880033c0300190bfb000804baff00645866487f28c0"),
                "adBlueLevel", 40 * 0.4);

        verifyAttribute(decoder, binary(
                "c000000044aa00000000000030c000000031353035323630373538323800addcc100226aef00000000001200050001001880033c0300190bfb000804baff00645866487f28c0"),
                Position.KEY_COOLANT_TEMP, 88);

        verifyAttribute(decoder, binary(
                "c000000044aa00000000000030c000000031353035323630373538323800addcc100226aef00000000001200050001001880033c0300190bfb000804baff00645866487f28c0"),
                Position.KEY_RPM, 828);

        // Hours TLV 0x0011
        verifyAttribute(decoder, binary(
                "c000000049aa0000000000028e8800000032303038323630373534323800e1d47fffcd163d0000000000f30000000100157703f8000046000000000aade0ffffffff0011000800000496c0"),
                Position.KEY_HOURS, 58.7);

        // Simple position reports
        verifyPosition(decoder, binary(
                "c000000060aa000000000000fa8000000031393037303431363434323700e9900affd61c1b00000000003a000000010015ffffff0000000000000004c2ffffffffff0005000a0d080000ca6a000900155741555a5a5a344730454e313133373233c0"));

        verifyPosition(decoder, binary(
                "c00000004baa0000000000000f8000000031363130323030373236333600e6d4f9ffcc78c700000022003600000001001500000000000000000000059bffffffffff0005000a040300000253c0"));

        // Position from frame-decoder test — uses pre-unescaped bytes (binary() bypasses scriptedFrame)
        verifyPosition(decoder, binary(
                "C000000041AA00000000000030C000000031353035323630373538323800ADDCC100226AEF0000000000120005000100151206EF0504E99975002903EB80556492CEC0"));
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
        private final String model;

        TestDriverProtocolDecoder(
                Protocol protocol, DriverRegistry registry, DriverDefinition definition, Config config, String model) {
            super(protocol, registry, config);
            this.definition = definition;
            this.model = model;
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
            return new DeviceSession(1, uniqueId, model, mock(Protocol.class), mock(Channel.class), remoteAddress);
        }

        @Override
        DeviceSession session(Channel channel, SocketAddress remoteAddress) {
            return new DeviceSession(1, "", model, mock(Protocol.class), mock(Channel.class), remoteAddress);
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
