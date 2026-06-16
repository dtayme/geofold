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

    @Test
    public void testCellocatorDecode() throws Exception {
        var decoder = decoder("cellocator");

        // MSG_CLIENT_STATUS — driver unique ID
        verifyAttribute(decoder, binary(
                "4d4347500003310f004018331424641a1d002000638000002a00b67ff6000000b8a5c7010000402d00040210af1ba9f88fb383fc10080100000000000000190015050ce80714"),
                Position.KEY_DRIVER_UNIQUE_ID, "b8a5c7010000");

        // MSG_CLIENT_STATUS — ignition on
        verifyAttribute(decoder, binary(
                "4d4347500098ab31000856b12b2c041016002c0023b3000021f3f5ffb04c8f0100000000000078dd0004020f716445f75f3b0701126e0200b303000036002538151b0ce607ab"),
                Position.KEY_IGNITION, true);

        // MSG_CLIENT_STATUS — normal coordinate mode (non-alternative)
        verifyPosition(decoder, binary(
                "4D43475000856308000004B2DE1F04009E00200100000000696CF7AB002F1A00000000000000325C000402069BFDE70857E22502F41C000036000000DF0B0932100B09DC0719"));

        verifyPosition(decoder, binary(
                "4D4347500006000000081A02021204000000210062300000006B00E100000000000000000000E5A100040206614EA303181A57034E1200000000000000001525071403D60749"));

        verifyPosition(decoder, binary(
                "4d434750000101000008011f041804000000200100000000005e750000000000000000000000548500040204da4da30367195703e80300000000000000002014151007dd07f7"));

        verifyPosition(decoder, binary(
                "4d434750005e930100080102041804000000200f20000000005e7500000000000000000000005af400040204da4da30367195703e8030000000000000000021a111e08dd0760"));

        // MSG_CLIENT_STATUS — alternative coordinate mode (byte 3 = 'G', not 'P')
        verifyPosition(decoder, binary(
                "4d4350470041420f000402021226d8a70221d801010000000001000000000000000000000000c4d90000000ca7a741ff0096dd15a40700000000000000001619130c01e307e4"));

        // MSG_CLIENT_MODULAR_EXT with module types 6 (GPS) and 7 (time)
        verifyPosition(decoder, binary(
                "4d4347500bde66220048165400cb0000000000080600000124161400061300050402095501aaf8218787fcac390100010000070700013133150f07131905001e000100000293001e00697e6f24148240040000000083400400000000844004000000008540040000000086400400000000874004000000008840040000000089400400000000814004000000008c4004000000008d4004000000008e4004000000009140040000000090400400000000804004000000008a400400000000974004000000008b4004000000009d4004000000009b400400000000da"));

        verifyPosition(decoder, binary(
                "4D4347500BA9880B00880A3900EE00000000000806000001210A140002B6001E0034419B1B1900400401000000014004000000000240046000000003400480000000044004DA0A0000054004000000000640045E000000074004310000000840042B000000094004870000000B4004B10B00000C4004590000000D40040000000010400465000000114004780C000012400465650700144004A4000000154004207F000016400400000000174004000000001E4004000000001F40040000000020400400000000214004000000002440040000000006130003040211D67DA4F7883AAF028403000001000007070001250A1004090E1905001E0001000062"));
    }

    @Test
    public void testWatchDecode() throws Exception {
        var decoder = decoder("watch");

        // AL_LTE — SOS alarm
        verifyAttribute(decoder, text(
                "[3G*6907919734*003e*AL_LTE,170525,214118,V,0,N,0,E,0,0,0,0,0,22,0,0,00010000,0,0,0]"),
                Position.KEY_ALARM, Position.ALARM_SOS);

        // oxygen health
        verifyAttribute(decoder, text(
                "[3G*9705141740*000B*oxygen,0,98]"),
                "bloodOxygen", 98);

        // UD_LTE with WiFi access points
        verifyPosition(decoder, text(
                "[3G*9705141740*00C2*UD_LTE,260723,185105,V,00.000000,,00.0000000,,0.00,0.0,0.0,0,100,67,0,0,00000000,2,0,605,1,10006,65799,14,10020,4104,4,3,,34:60:f9:ec:19:f8,-82,,98:48:27:55:18:20,-96,,34:e8:94:e4:06:18,-104,0.0]"));

        // SG AL with hex LAC/CID cell towers
        verifyPosition(decoder, text(
                "[SG*9059011020*0067*AL,240123,181628,V,54.427538,N,6.409275,W,0.00,0,0,0,19,90,0,0,00000000,1,1,234,10,55C0,3B882A2,132,,10]"));

        // UD2 with altitude and alarm bits
        verifyPosition(decoder, text(
                "[SG*9059011020*006b*UD2,240123,162011,A,54.427621,N,6.409190,W,0.00,0,0,8,19,88,0,0,00000000,1,1,FFFF,FFFF,FFFE,3B882A2,132,,00]"));

        // TEMP health
        verifyAttribute(decoder, text(
                "[ZJ*5678901234*0001*0009*TEMP,36.5]"),
                Position.PREFIX_TEMP + 1, 36.5);

        // AL with REMOVING alarm (ZJ with index)
        verifyAttribute(decoder, text(
                "[ZJ*689466020014198*0003*0113*AL,221121,085515,V,00.000000,N,000.000000,E,0,0,0,0,0,44,0,0,00100000,1,255,460,0,16399,234887445,0,6,WIFI00,68:77:24:1b:e7:a7,-59,WIFI01,68:77:24:1b:e3:30,-75,WIFI02,68:77:24:1b:e3:27,-75,WIFI03,00:41:d2:c0:f2:f1,-76,WIFI04,00:41:d2:c0:f2:f0,-77,WIFI05,68:77:24:1b:e3:d8,-82]"),
                Position.KEY_ALARM, Position.ALARM_REMOVING);

        // UD2 — expected coordinates
        verifyPosition(decoder, text(
                "[3G*9031853319*004E*UD2,220322,055105,A,22.761162,N,114.360192,E,0,0,47,14,100,64,0,0,00000008,0,0]"));

        // btemp2 health
        verifyAttribute(decoder, text(
                "[3G*2104326058*000E*btemp2,1,35.29]"),
                Position.PREFIX_TEMP + 1, 35.29);

        // SG UD2 with cells + WiFi
        verifyPosition(decoder, text(
                "[SG*9159059735*0066*UD2,230322,082138,A,59.55285,N,016.66185,E,0.0,000,26,14,80,70,0,50,00000000,1,1,240,7,34505,80806406,,00]"));

        // SG UD with no cell data
        verifyPosition(decoder, text(
                "[SG*9059056143*0053*UD,251021,223408,A,41.46500,N,081.53128,W,0.926,000,0,00,70,70,0,50,00000000,0,1,,,,00]"));

        // 3G UD_LTE with both cells and WiFi
        verifyPosition(decoder, text(
                "[3G*2104326058*00E9*UD_LTE,300621,135101,A,32.162652,N,34.888748,E,30.84,265.158,65.621,18,100,83,0,0,00000000,1,1,425,01,10223,8012811,100,3,ES4104,22:74:1d:39:64:ff,-46,metropoline-wifi,a8:3f:a1:e0:66:ba,-89,Egged.co.il,00:0c:42:51:cf:cd,-81,1.7055488]"));

        // 3G AL with 7 cells
        verifyPosition(decoder, text(
                "[3G*8809008845*00C0*AL,271219,094744,V,00.000000,N, 0.0000000,E,0.00,0.0,0.0,0,100,81,0,0,00010000,7,0,460,0,9336,3981,141,9336,3912,141,9336,3982,140,9765,4233,134,9765,4071,134,9765,4321,134,9336,4353,132,0,0.0]"));

        // UD2 — negative coordinates (no hemisphere negation)
        verifyPosition(decoder, text(
                "[3G*6105117105*008D*UD2,210716,231601,V,-33.480366,N,-70.7630692,E,0.00,0.0,0.0,0,100,34,0,0,00000000,3,255,730,2,29731,54315,167,29731,54316,162,29731,54317,145]"),
                position("2016-07-21 23:16:01.000", false, -33.48037, -70.76307));

        // SG UD with exact expected coordinates
        verifyPosition(decoder, text(
                "[SG*8800000015*0087*UD,220414,134652,A,22.571707,N,113.8613968,E,0.1,0.0,100,7,60,90,1000,50,0000,4,1,460,0,9360,4082,131,9360,4092,148,9360,4091,143,9360,4153,141]"),
                position("2014-04-22 13:46:52.000", true, 22.57171, 113.86140));

        // SG AL with general alarm fallback
        verifyPosition(decoder, text(
                "[SG*8800000015*0087*AL,220414,134652,A,22.571707,N,113.8613968,E,0.1,0.0,100,7,60,90,1000,50,0001,4,1,460,0,9360,4082,131,9360,4092,148,9360,4091,143,9360,4153,141]"));

        // PULSE health
        verifyAttributes(decoder, text(
                "[CS*8800000015*0008*PULSE,72]"));

        // heart health (value 0 — still returns position)
        verifyAttributes(decoder, text(
                "[3G*6005412902*0007*heart,0]"));

        // heart health (value present)
        verifyAttributes(decoder, text(
                "[3G*6005412902*0008*heart,71]"));

        // ZJ UD with index — expected coordinates
        verifyPosition(decoder, text(
                "[ZJ*014111001350304*0033*0064*UD,070318,020827,V,00.000000,N,000.000000,E,0,0,0,0,100,19,1000,50,00000000,1,255,460,0,9346,5223,42]"));

        // LK with 2 values only → null (insufficient fields)
        verifyNull(decoder, text(
                "[SG*9081000548*0009*LK,0,100]"));

        // LK with 3 values → steps + battery
        verifyAttributes(decoder, text(
                "[3G*4700186508*000B*LK,0,10,100]"));

        // TKQ → null (just sends ACK)
        verifyNull(decoder, text(
                "[3G*8800000015*0003*TKQ]"));

        // LK with no content → null
        verifyNull(decoder, text(
                "[SG*8800000015*0002*LK]"));

        // BPHRT health data
        verifyAttributes(decoder, text(
                "[3G*4700609403*0013*bphrt,120,79,73,,,,]"));

        // BLOOD with ZJ index
        verifyAttributes(decoder, text(
                "[ZJ*357653059860416*0007*000c*BLOOD,109,68]"));

        // UD with WiFi only — no cell parsing (skip condition: values[3] is MAC)
        verifyPosition(decoder, text(
                "[3G*8800000015*00DD*UD,010120,025946,V,0.0,N,0.0,E,22.0,0,-1,0,100,98,0,0,00000000,0,5,eduroam,f4:db:e6:d2:a8:00,-53,eduroam,f4:db:e6:da:d0:80,-79,eduroam,78:0c:f0:24:f9:80,-82,Lions,b0:be:76:0a:05:9a,-82,tubs-guest,f4:db:e6:d2:a8:01,-53,0.0]"));
    }

    @Test
    public void testMegastekDecode() throws Exception {
        var decoder = decoder("megastek");

        // New format with WiFi and binary I/O
        verifyPosition(decoder, text(
                "0323$MGV002,861045082971493,,R,090126,134250,V,5231.64780,N,01323.48837,E,00,00,00,7.682,0.227,116.709,42.5,,262,01,5D8,1922400,20,0000,0000,0,,,,,,01,092,Timer,0268eb529865:50|3ca62f1ff798:63|b8bef41b75a0:64|1ced6f4ad291:72|b4f267537963:76|d2f267537963:76|58d7599127f8:82|32cda7ad09f3:83|485d35090af8:87|b0fc88ab407e:88,,0,,;!"));

        // Battery level 100
        verifyAttribute(decoder, text(
                "$MGV002,862311065582635,,R,311025,144117,V,5231.64099,N,01323.47200,E,00,00,00,99.9,,,25.7,,262,01,05D8,1924F03,18,,,,,,,,,10,100,Timer,32cda7ad09f3:42|1ced6f4ad291:47|b4f267537963:69|d2f267537963:70|3ca62f1ff798:73|04b4fe4955c7:75|74427f7dce16:77|ea55a82da860:80|30d32d9aec05:82|f086201d99ba:82,,0,,;!"),
                Position.KEY_BATTERY_LEVEL, 100);

        // New format archive (S flag)
        verifyPosition(decoder, text(
                "$MGV002,860719020193193,,S,050123,054156,V,2238.26167,N,11401.99217,E,00,00,00,99.9,,,,,460,08,262C,FFC,15,,,,,,,,,100,100,Timer,bc5ff67daf8f:38|9289179f1d46:46|0071cc32f67f:59|a41a3a6ab665:72|ec26ca48faa5:72|a61a3a5ab665:73|fcd733e2c310:75|48a74e34ac58:85|3436543ec64e:85|c8bf4c074f92:87,;!"));

        // Length-prefix with battery
        verifyAttribute(decoder, text(
                "0226$MGV002,860537065044539,,S,020824,120719,V,5339.11529,N,01011.15575,E,00,00,00,99.9,3.255,,52.1,,262,01,FFFE,277A602,14,000,0000,0000,0,,,,,01000,078,Timer,dc15c8984804:65|50e63698d1d5:70|44053fdacd6e:73|e0516314f2a7:88,,0,,;!"),
                Position.KEY_BATTERY_LEVEL, 78);

        // Sparse fields (many empty)
        verifyPosition(decoder, text(
                "$MGV002,860719020193193,,S,070521,160748,V,2255.09165,N,11404.01322,E,00,00,00,,,,,,,,,,,,,,,,,,,10,015,Restart;!"));

        // No IO block (all empty after gsm)
        verifyPosition(decoder, text(
                "$MGV002,860719020193193,,R,070621,115717,V,2255.09165,N,11404.01322,E,00,00,00,99.9,,,,,460,07,262C,0F54,20,,,,,,,,,10,039,Timer;!"));

        // With temperatures
        verifyPosition(decoder, text(
                "0125$MGV002,860719020193193,DeviceName,R,240214,104742,A,2238.20471,N,11401.97967,E,00,03,00,1.20,0.462,356.23,137.9,1.5,460,07,262C,0F54,25,0000,0000,0,0,0,28.5,28.3,,,100,Timer;"));

        // charge=true
        verifyAttribute(decoder, text(
                "$MGV002,860719020193193,DeviceName,R,240214,104742,A,2238.20471,N,11401.97967,E,00,03,00,1.20,0.462,356.23,137.9,1.5,460,07,262C,0F54,25,0000,0000,0,0,0,28.5,28.3,,10,100,Timer;!"),
                Position.KEY_CHARGE, true);

        // belt=2
        verifyAttribute(decoder, text(
                "$MGV002,860719020193193,DeviceName,R,240214,104742,A,2238.20471,N,11401.97967,E,00,03,00,1.20,0.462,356.23,137.9,1.5,460,07,262C,0F54,25,0000,0000,0,0,0,28.5,28.3,,02,100,Timer;!"),
                "belt", 2);

        // Confirmed position and coordinates
        verifyPosition(decoder, text(
                "$MGV002,860719020193193,DeviceName,R,240214,104742,A,2238.20471,N,11401.97967,E,00,03,00,1.20,0.462,356.23,137.9,1.5,460,07,262C,0F54,25,0000,0000,0,0,0,28.5,28.3,,,100,Timer;!"),
                position("2014-02-24 10:47:42.000", true, 22.63675, 114.03299));

        // Empty lat/lon → null
        verifyNull(decoder, text(
                "0112$MGV002,,GVT900-3,S,010114,000003,,,,,,00,00,00,,0.000,0.00,,0.0,,,,,,0000,0000,14,10,0, , ,,1-0,0,Low Ext Vol;!"));

        // Empty lat/lon → null
        verifyNull(decoder, text(
                "0140$MGV002,354550056642321,GVT900-3,S,300917,071731,V,,,,,00,00,00,99.9,0.000,0.00,,0.0,457,01,0741,00CD,,0000,0000,20,10,0, , ,,1-1,94,PW ON;!"));

        // ALARM_POWER_ON
        verifyAttribute(decoder, text(
                "$MGV002,869152024446923,,S,290816,200627,V,5056.21059,N,00439.25034,E,00,00,00,99.9,,,-25.1,,206,01,0BBB,4418,28,,,,,,,,,01,093,PW ON;"),
                Position.KEY_ALARM, Position.ALARM_POWER_ON);

        // Simple old format — Belt Up alarm, imei in status
        verifyPosition(decoder, text(
                "STX,013950007137061,$GPRMC,191959.000,A,5203.09602,N,00830.77057,E,5.73,255.27,240716,,,A*62,L,Belt Up,imei:013950007137061,0/5,,Battery=52%,,1,262,03,0084,B20E;FD"));

        // Simple old format — Nil-Alarms
        verifyPosition(decoder, text(
                "STX,865067021328417,$GPRMC,064721.000,A,4241.2793,N,02321.9762,E,6.74,346.90,300316,,,1*CA,F,Nil-Alarms,imei:865067021328417,9,559.8,Battery=82%,0,284,03,047E,2B5F;99"));

        // Non-simple old format (16-char id + 2-byte separator)
        verifyPosition(decoder, text(
                "STX2010101801      j$GPRMC,101053.000,A,2232.7607,N,11404.7669,E,0.00,,231110,,,A*7F,460,00,2795,0E6A,14,94,1000,0000,91,Timer;1D"));

        // Simple old format — imei with optional fields
        verifyPosition(decoder, text(
                "STX,861001005215757,$GPRMC,180118.000,A,4241.330116,N,2321.931251,E,0.00,182.19,130915,,E,A,F,Nil-Alarms,imei:861001005215757,8,577.0,Battery=38%,0,284,03,03E8,3139;7A"));

        // Simple old format — minimal (no status section)
        verifyPosition(decoder, text(
                "STX,865067020439090,$GPRMC,171013.000,A,5919.1411,N,01804.1681,E,0.000,294.41,140815,,,A"));

        // Simple old format — GerAL22 id, imei in status
        verifyPosition(decoder, text(
                "STX,GerAL22,$GPRMC,174752.000,A,3637.060059,S,6416.2354,W,0.00,0.00,030812,,,A*55,F,,imei:861785000249353,05,180.6,Battery=100%,,1,722,310,0FA6,39D0;8F"));

        // Simple old format — LOGSTX prefix, empty alarm
        verifyPosition(decoder, text(
                "LOGSTX,123456789012345,$GPRMC,225419.000,A,3841.82201,N,09494.73357,W,12.46,135.33,270914,,,A*47,F,,imei:123456789012345,0/6,,Battery=100%,,0,,,5856,78A3;24"));

        // Non-simple — alternative status
        verifyPosition(decoder, text(
                "STX863070014949464   $GPRMC,215942.290,A,4200.1831,N,02128.5904,E,003.1,079.8,090813,,,A*6E,294,02,0064,0F3D,18,17,0000,000000,0000,0.00,0.02,0.00,Store;D8"));

        // Non-simple — short id
        verifyPosition(decoder, text(
                "STX123456            $GPRMC,063709.000,A,2238.1998,N,11401.9670,E,0.00,,250313,,,A*7F,460,01,2531,647E,11,87,1000,001001,0000,0.00,0.02,0.00,Timer;4A"));

        // Simple — SOS (Help)
        verifyAttribute(decoder, text(
                "STX,,$GPRMC,001339.000,A,4710.85395,N,02733.58209,E,1.65,238.00,010109,,,A*67,L,Help,imei:013227009737796,0/8,137.1,Battery=100%,,0,226,01,2B9B,BBBF;8D"),
                Position.KEY_ALARM, Position.ALARM_SOS);
    }

    @Test
    public void testGps103Decode() throws Exception {
        var decoder = decoder("gps103");

        // Handshake: short message → null, reply LOAD
        verifyNull(decoder, text(
                "##,imei:359586015829802,A\n"));

        verifyNull(decoder, text(
                "imei:359586015829802\n"));

        // Heartbeat (digit prefix) → null, reply ON
        verifyNull(decoder, text(
                "359586015829802\n"));

        // Vibration alarm, L-branch no GPS
        verifyAttribute(decoder, text(
                "imei:865456055519122,sensor alarm,2208011920,,L,;"),
                Position.KEY_ALARM, Position.ALARM_VIBRATION);

        // Regular F-branch position with course
        verifyAttribute(decoder, text(
                "imei:868683023212255,tracker,190205084503,,F,064459.000,A,4915.1221,N,01634.5655,E,3.91,83.95;"),
                "course", 83.95);

        // Regular position with fuel
        verifyAttribute(decoder, text(
                "imei:353451044508750,001,0809231929,13554900601,F,055403.000,A,2233.1870,N,11354.3067,E,0.00,30.1,65.43,1,0,10.5%,0.0%,28;"),
                "fuel1", 10.5);

        // Temperature alarm (T: prefix)
        verifyPosition(decoder, text(
                "imei:868683026321020,T:+11,181217080050,,F,080047.000,A,3227.3057,N,11649.4754,W,0.00,0,,0,0,0.00%,,+11;"));

        // Temperature attribute from alarm
        verifyAttribute(decoder, text(
                "imei:868683026321020,tracker,181217080106,,F,080102.000,A,3227.3057,N,11649.4754,W,0.00,0,,0,0,0.00%,0,+11;"),
                Position.PREFIX_TEMP + 1, 11);

        // SOS alarm → not null, sends ACK
        verifyPosition(decoder, text(
                "imei:359586015829802,help me,0809231429,13554900601,F,062947.294,A,2234.4026,N,11354.3277,E,0.00,;"));

        // Low battery alarm
        verifyPosition(decoder, text(
                "imei:359586015829802,low battery,0809231429,13554900601,F,062947.294,A,2234.4026,N,11354.3277,E,0.00,;"));

        // ac alarm → power cut
        verifyAttribute(decoder, text(
                "imei:862106021237716,ac alarm,1611291645,,F,204457.000,A,1010.2783,N,06441.0274,W,0.00,,;"),
                Position.KEY_ALARM, Position.ALARM_POWER_CUT);

        // L-branch with cell towers
        verifyNotNull(decoder, text(
                "imei:864895030279986,ac alarm,180404174252,,L,,,296a,,51f7,,,\n"));

        verifyNotNull(decoder, text(
                "imei:359710049075097,help me,,,L,,,113b,,558f,,,,,0,0,,,\n"));

        // L-branch no cell data
        verifyNotNull(decoder, text(
                "imei:359586015829802,tracker,000000000,13554900601,L,;"));

        // S-hemisphere latitude
        verifyPosition(decoder, text(
                "imei:353451047570260,tracker,1302110948,,F,144807.000,A,0805.6615,S,07859.9763,W,0.00,,\n"),
                position("2013-02-11 14:48:07.000", true, -8.09436, -78.99960));

        // N/S before latitude variant
        verifyPosition(decoder, text(
                "imei:353552045403597,tracker,150420050648,53.0,F,0.0,A,N,5306.64155,E,00700.77848,0.0,,1.0,;"));

        // N after lat, E after lon (standard) — local time kept (utc field = 0.0, non-capturing)
        verifyPosition(decoder, text(
                "imei:353552045403597,tracker,150420051153,53.0,F,0.0,A,5306.64155,N,00700.77848,E,0.0,,1.0,;"));

        // Slashed date YY/MM/DD HH:MM
        verifyPosition(decoder, text(
                "imei:359710040656622,tracker,13/02/27 23:40,,F,125952.000,A,3450.9430,S,13828.6753,E,0.00,0\n"));

        // Digit prefix: 15-digit IMEI prepended
        verifyPosition(decoder, text(
                "359769031878322imei:359769031878322,tracker,1602160718,2,F,221811.000,A,1655.2193,S,14546.6722,E,0.00,,\n"));

        // acc on / acc off → ignition
        verifyAttribute(decoder, text(
                "imei:864180036029895,acc on,180508145653,,F,065645.000,A,4729.1497,N,01904.2342,E,0.00,0,,1,,0.00%,,;"),
                Position.KEY_IGNITION, true);

        // Fuel leak alarm (oil prefix)
        verifyAttribute(decoder, text(
                "imei:353451044508750,oil 51.67,0809231929,,F,055403.000,A,2233.1870,N,11354.3287,E,0.00,,\n"),
                Position.KEY_ALARM, Position.ALARM_FUEL_LEAK);

        // DTC alarm
        verifyAttribute(decoder, text(
                "imei:353451044508750,DTC,0809231929,,F,055403.000,A,2233.1870,N,11354.3067,E,0.00,30.1,,1,0,10.5%,P0021,;"),
                Position.KEY_ALARM, Position.ALARM_FAULT);

        // RFID
        verifyPosition(decoder, text(
                "imei:868683020235846,rfid,160202091347,49121185,F,011344.000,A,0447.7273,N,07538.9934,W,0.00,0,,0,0,0.00%,,\n"));

        // Timezone correction: local 19:29 UTC+8:45 → UTC 05:54 next day
        verifyPosition(decoder, text(
                "imei:359710045559474,tracker,151030080103,,F,000101.000,A,5443.3834,N,02512.9071,E,0.00,0;"),
                position("2015-10-30 00:01:01.000", true, 54.72306, 25.21512));

        // OBD: full fields
        verifyAttributes(decoder, text(
                "imei:868683027758113,OBD,180905200218,,,,0,0,0.39%,70,9.41%,494,0.00,P0137,P0430,,;"));

        // OBD: with odometer and hours
        verifyAttributes(decoder, text(
                "imei:359710049057798,OBD,161003192752,1785,,,0,54,96.47%,75,20.00%,1892,0.00,P0134,P0571,,\n"));

        // OBD: sign-only temperature field → null return on missing battery
        verifyNull(decoder, text(
                "imei:865328021049167,OBD,141118115036,,,0.0,,000,0.0%,+,0.0%,00000,,,,,\n"));

        // Alternative format with dashes for empty fields
        verifyPosition(decoder, text(
                "imei:861359038609986,Equipo 1,---,------,----,214734,241018,26,1,-33.42317,-70.61930,067,229,0674,1.00,08,0,1,---,*"));

        // Alternative format gps=0 → invalid fix
        verifyPosition(decoder, text(
                "imei:861359038609986,Equipo 1,---,------,----,214812,241018,14,0,-33.42317,-70.61930,000,000,0000,99.9,00,0,1,---,*"));

        // Alternative format null check: short + imei → handshake
        verifyNull(decoder, text(
                "imei:123451234512345,L,*"));
    }

    @Test
    public void testTopinDecode() throws Exception {
        var decoder = decoder("topin");

        // Login — null, registers session
        verifyNull(decoder, binary(
                "78780d0103593390754169634d0d0a"));

        // MSG_VIBRATION (0x94) — vibration alarm
        verifyAttribute(decoder, binary(
                "787801940D0A"),
                Position.KEY_ALARM, Position.ALARM_VIBRATION);

        // MSG_SOS_ALARM (0x99) — SOS alarm
        verifyAttribute(decoder, binary(
                "787801990D0A"),
                Position.KEY_ALARM, Position.ALARM_SOS);

        // MSG_STATUS (0x13) — battery, fw, rssi, charge
        verifyAttributes(decoder, binary(
                "78780A13424008196400041F000D0A"));

        // MSG_GPS (0x10) — full position
        verifyPosition(decoder, binary(
                "787812100A03170F32179C026B3F3E0C22AD651F34600D0A"));

        // MSG_GPS_2 (0x08) — valid position with custom coordinate encoding
        verifyPosition(decoder, binary(
                "7878200813081A0733211608C8D1710DED1D1608DFFB710E06D51039050100286489000D0A"));

        // MSG_GPS_OFFLINE_2 (0x09) — invalid fix
        verifyPosition(decoder, binary(
                "78782008140709121f36300d769f02058cfd300d771202058c6f0000000300005c99000d0a"));

        // MSG_GPS repeated (same port, second position)
        verifyPosition(decoder, binary(
                "787812100a03170f32179c026b3f3e0c22ad651f34600d0a"));

        // MSG_STATUS (0x13) — second variant with fewer optional fields
        verifyAttributes(decoder, binary(
                "78780713514d0819640d0a"));

        // MSG_TIME_UPDATE (0x30) — no position
        verifyNull(decoder, binary(
                "787801300d0a"));

        // MSG_WIFI (0x69) — BCD time, 0 APs, 2 cells, alarm=vibration
        verifyAttribute(decoder, binary(
                "7878006921120412565802010601071e4a9764071e4a9864010d0a"),
                Position.KEY_ALARM, Position.ALARM_VIBRATION);

        // MSG_WIFI_OFFLINE (0x17) — BCD time, 0 APs, multiple cells, no alarm
        verifyNotNull(decoder, binary(
                "7878001719111120141807019456465111aa3c465111ab464651c1a550465106b150465342f750465342f65a465111a95a000d0a"));

        // Empty frame — null (frame too short)
        verifyNull(decoder, binary(
                "787801080D0A"));

        // MSG_STATUS (0x13) — short variant with only 4 status bytes
        verifyAttributes(decoder, binary(
                "78780a132827010063000000000d0a"));
    }

    @Test
    public void testMinifinder2Decode() throws Exception {
        var decoder = decoder("minifinder2");

        // MSG_DATA with IMEI + status (0x24) + alarm (0x02, SOS) + cell (0x21)
        verifyPositions(decoder, binary(
                "ab104b00cab208010110013836323232313038373738303438310d24d0b71d6a0800f864000000000d0200100000d0b71d6a000000001c23c69b531448e06ed4e621e254cf084d696e6946696e646572204851"));

        // MSG_DATA batch — two positions, GPS + WiFi (key 0x22)
        verifyPositions(decoder, binary(
                "ab105b0063ca28000110013836323737313037363837383334300d246eaeb2690103fb2b030001001620eacce6217a59cf0800001e01a600050000000000160b2c00d14699811df7d600640b2c0187442817d4fdd100640b2c020a2f7f89cfc8cc0064"));

        // MSG_DATA with beacon tag (0x2c, Obey tag)
        verifyPositions(decoder, binary(
                "ab103b00a1fedd010110013836323331313036373836373131370d249846dd671008db47030000001a2cc087442817d4fdd1c364bc77f0212481d408044f626f79f100"));

        // MSG_DATA — long WiFi-beacon batch
        verifyPositions(decoder, binary(
                "ab107000362c05000110013836323331313036393136323437350d247233dc671203ab33030000004f19c40492263a35680a4d696e6946696e646572c038d5472dc22807554e49464c4558be88d7f6807de80a415355535f45385f3247bb7e8a20846eb50b4176656b692047c3a47374bbb56e84208a8200"));

        // MSG_DATA batch — many positions with cell towers (0x29 LTE)
        verifyPositions(decoder, false, binary(
                "ab10b803daa44c0101100138363136323930353036323530313509244290bf668600764a5f19ba74acb9b465ba084b756c6d6f73656eb618e829e7737008544c422d694e6574ae5c648e75a72104576f7278ad7483c2275cb808544c422d694e6574ade465b89319551a5368656c6c79506c757331504d2d4534363542383933313935340b29ee00010ee40c6e23c9000924ae91bf668600764a5f19b618e829e7737008544c422d694e6574ade465b89319551a5368656c6c79506c757331504d2d453436354238393331393534ac7483c2275cb808544c422d694e6574b674acb9b465ba084b756c6d6f73656ead5c648e75a72104576f72780b29ee00010ee40c6e23c90009241793bf668600764a6319b574acb9b465ba084b756c6d6f73656eade465b89319551a5368656c6c79506c757331504d2d453436354238393331393534b718e829e7737008544c422d694e6574ace063da3a591a084b756c6d6f73656eab7483c2275cb808544c422d694e65740b29ee00010ee40c6e23c90009248094bf668600764a6319b774acb9b465ba084b756c6d6f73656eaee465b89319551a5368656c6c79506c757331504d2d453436354238393331393534ab7483c2275cb808544c422d694e6574aae063da3a591a084b756c6d6f73656eb818e829e7737008544c422d694e65740b29ee00010ee40c6e23c9000924e995bf668600764a5f19b718e829e7737008544c422d694e6574b674acb9b465ba084b756c6d6f73656eaee465b89319551a5368656c6c79506c757331504d2d453436354238393331393534af5c648e75a72104576f7278ad7483c2275cb808544c422d694e65740b29ee00010ee40c6e23c90009245297bf668600764a5f19b818e829e7737008544c422d694e6574b274acb9b465ba084b756c6d6f73656eae7483c2275cb808544c422d694e6574ade465b89319551a5368656c6c79506c757331504d2d453436354238393331393534ae5c648e75a72104576f72780b29ee00010ee40c6e23c9000924bc98bf66860076495f19b818e829e7737008544c422d694e6574b074acb9b465ba084b756c6d6f73656eaee465b89319551a5368656c6c79506c757331504d2d453436354238393331393534ac7483c2275cb808544c422d694e6574ad5c648e75a72104576f72780b29ee00010ee40c6e23c9000924259abf66060076494d19b718e829e7737008544c422d694e6574b374acb9b465ba084b756c6d6f73656ead7483c2275cb808544c422d694e6574ad5c648e75a72104576f7278abe063da3a591a084b756c6d6f73656e0b29ee00010ee40c6e23c900"));

        // MSG_SERVICES batch (type=0x03) — many timestamped positions
        verifyPositions(decoder, false, binary(
                "ab105a0512e19404011001383632333131303632373037333735093743c3ec640000000009374dc3ec6400000000093750c3ec6400000080092455c3ec640203935e0f22a318d6c7baacd6a2546751467bd009246ac3ec640203b35e0f22a318d6c7baacd6a2546751467bd009246cc3ec640203b35e0f22a318d6c7baacd6a2546751467bd009247ec3ec640203b35e0f22a318d6c7baacd6a2546751467bd0092492c3ec640203b35e0f22a318d6c7baacd6a2546751467bd00924a6c3ec640203b35e0f22a318d6c7baacd6a2546751467bd00924bac3ec640203b35e0f22a318d6c7baacd6a2546751467bd00924d2c3ec640203b35e0f22a7083a2f201a83a3f8084f84ae560924e7c3ec640203b35e0f22a7083a2f201a83a3f8084f84ae560924fbc3ec640203b35e0f22a7083a2f201a83a3f8084f84ae5609240fc4ec640203b35e0f22a7083a2f201a83a3f8084f84ae56092423c4ec640203b35d0f22a7083a2f201a83a3f8084f84ae56092437c4ec640203cb5d0f22a7083a2f201a83a3f8084f84ae5609244fc4ec640003cb5d092464c4ec640003cb5d092478c4ec640003cb5d09248cc4ec640003cb5d0924a0c4ec640003cb5d0924b4c4ec640003cb5d0924ccc4ec640003cb5d0924e5c4ec640003cb5d0924fec4ec6400037b5d092413c5ec6400037b5d092427c5ec6400017b5d0924b785ed640003cb530924d085ed640003ab530924e985ed640003ab530924fe85ed640003ab5309241286ed640003ab5309242686ed640003ab5309243a86ed640003ab5309244e86ed640003ab5309246786ed640003ab5309248086ed640003ab5309249986ed6400037b530924b286ed6400037b530924c686ed6400037b530924da86ed6400037b530924ee86ed6400037b5309240287ed6400037b5309241687ed6400037b5309242f87ed6400037b5309244787ed640003835309246187ed640003835309247a87ed640003835309249287ed64000383530924ab87ed64000383530924c487ed64000383530924d987ed64000383530924ed87ed640003835309240188ed640003835309241588ed640003835309242988ed640003d35309243a88ed640003d3530d02000000803788ed640000000009374188ed640400000009244188ed640003d35309244288ed640003d35309374b88ed640500000009244b88ed640003d35309375588ed640500000009245588ed640003d35309245788ed640003d35309375f88ed640700000009245f88ed640003d35309376988ed640800000009246988ed640003d35309246b88ed640203d3530f22a502184a2cfba0a42c768af4ab5009247188ed640203d3530f22a502184a2cfba0a42c768af4ab5009377388ed640a00000009247688ed640203d3530f22a502184a2cfba0a42c768af4ab5009247b88ed640203d3530f22a502184a2cfba0a42c768af4ab5009377d88ed640300000009247e88ed640203d3530f22a502184a2cfba0a42c768af4ab5009248088ed640203d3530f22a502184a2cfba0a42c768af4ab5009248588ed640203d3530f22a502184a2cfba0a42c768af4ab5009378788ed640000000009248a88ed640203d3530f22a502184a2cfba0a42c768af4ab5009248f88ed640203d3530f22a502184a2cfba0a42c768af4ab5009379188ed640000000009379288ed640000008009249288ed640203d3530f22a502184a2cfba0a42c768af4ab5009249488ed640203d3530f22a502184a2cfba0a42c768af4ab5009249988ed640203d3530f22a502184a2cfba0a42c768af4ab5009249e88ed640203d3530f22a502184a2cfba0a42c768af4ab500924a388ed640203d3530f22a502184a2cfba0a42c768af4ab500924a688ed640203d3530f22a502184a2cfba0a42c768af4ab50"));

        // MSG_CONFIGURATION (type=0x02)
        verifyAttributes(decoder, binary(
                "ab00cc029c9b0000020501040518200502cf290001100338363233313130363534393538393515043839343632303338303735303031383830343034070539eed3f9cec705064f93a7650507010b00002908cf2900010020050000d0000068915b00ae0000004f637420313920323032330031303a35393a3332261b53494d37353030457c4231315630325f3231303330337c50312e30325f3230323231313034050900000000050a00000003070b000000001e01070b010000001e01070b020000001e01070b030000001e01060c0000000000050d6e600580020e04050f0708008002100002110f02126406134d46303758041404a20e08160000000000000008160100000000000008160200000000000008160300000000000008160400000000000008160500000000000008160600000000000008160700000000000008160800000000000008160900000000000020177777772e676f6f676c652e636f6d2f6d6170733f713d252e37662c252e3766211868747470733a2f2f6c6f632e6d696e6966696e6465722e636f6d2f25732f25730519000001fe101a4d4630372e343631302e3233303300021c640a1d802acee6212966cf0803207b3e03217b040230000230010230020230030230040230050230060230070230080230090231000532580214010533820000000e406d326d2e74656c65322e636f6d014101421c4380431468756e7465726465762e6d696e6966696e6465722e636f6d0d440e01008005000000100e0000054505002c01014705500f1400f00d511002e803b84d7f0d0246f6430d511100f40100000000000000000d511200f40100000000000000000d511300f401000000000000000005527800030005532c0100000354500005551e002d400256050b57201c00002c010000ed61025d01055c890a0000057000000000037100001472000000000000000000000000000000000000000568f0000100057500000000074de36000000000"));

        // MSG_DATA with bark (key 0x37)
        verifyAttribute(decoder, binary(
                "ab101c00d6f61e000110013836333932313033393939363038300937efd201640c000000"),
                "barkCount", 12L);

        // MSG_RESPONSE (type=0x7F)
        verifyAttribute(decoder, binary(
                "ab00030008c700007f0100"),
                Position.KEY_RESULT, "0");

        // MSG_DATA with bark alarm bit (key 0x02 bit 31)
        verifyAttribute(decoder, binary(
                "ab102600080f1400011001383633393231303339393833343736092429b347633003a96409020000008027b34763"),
                "bark", true);

        // MSG_DATA with GPS + cell towers (0x09 = 0x21 extended TLV)
        verifyPositions(decoder, binary(
                "AB103D0035A700000110013836373733303035333430333237390924AC5783620103C250162030CC5F0D5002FB432D00AF005A3158006D0A00000B0931EC5783620A000000"));

        // MSG_DATA with activity and steps TLV
        verifyPositions(decoder, binary(
                "ab10350015ae59010110013836333932313033333836353231360924723a12610042535a182ac0f6b4f2923100c900af02215c2b9bfb5461736b4c4d53"));

        // MSG_SERVICES (type=0x03) without GPS — batch fallback
        verifyPositions(decoder, false, binary(
                "ab10150076f1320003100133353534363530373130323933303602105a"));

        // MSG_SERVICES — another batch fallback
        verifyPositions(decoder, false, binary(
                "AB101400594A01000310013836333932323033343437333734350112"));

        // MSG_DATA with WiFi (key 0x19 with name, key 0x22 no name)
        verifyPositions(decoder, binary(
                "ab183200c6bd020101100138363838333230343730323133363209247a0b146090087a641528c03a79ba309be5dec3c2024122c21c2407676267"));
    }

    @Test
    public void testUlbotechDecode() throws Exception {
        var decoder = decoder("ulbotech");

        // Binary: GPS + STATUS + ODOMETER + ADC + J1708 + CANBUS + EVENT
        verifyPosition(decoder, binary(
                "f8010103515810532780699f7e2e3f010e015ee4c906bde45c00000000008b0304004000000404002c776005060373193622110b00240b00fee8ffff807dffff606d0b00fee9af000000af0000000b00feee7d78807dffffffff100101cc2af8"));

        // Binary: GPS with valid hdop
        verifyPosition(decoder, binary(
                "F80101035785203457289495D60235010E016175A506C2C838000000000064"));

        // Binary: no GPS → lastLocation
        verifyNotNull(decoder, binary(
                "F8010108683230231070781EA3676E020BFFFFFFFFFFFFFFFFFFFF780304000000030404000002C20506032A1790220E100101AC72F8"));

        // Binary: CANBUS type 0x0B with 2-byte length field
        verifyNotNull(decoder, binary(
                "f8010108683230220996561ea6ce1c020bffffffffffffffffffff78030400000000040400087b710506035519ad2214060800000000000000006220f8"));

        // Binary: GPS + LBS (4-byte cid, length=11)
        verifyPosition(decoder, binary(
                "f8010108679650230646339de69054010e015ee17506bde2c60000000000ac0304024000000404000009f705060390181422170711310583410c0000310d00312f834131018608040003130a100101136cf8"));

        // Binary: GPS + OBD2 + J1708
        verifyPosition(decoder, binary(
                "f8010103596580420045259CFB3329010E015ED91506BDE5A800000000009E030402420000040400492AA405060344197E220D071131058F410C1591310D48312F8F413107C60804027666B00C138254D182607A826EE083BE554385F50019423CAD1DF8"));

        // Text: login-only (no time/date/command) → null
        verifyNull(decoder, buffer("*TS01,353323081464660#"));

        // Text: command response → attributes
        verifyAttributes(decoder, buffer("*TS01,868323025245751,134955140317,WFE:0#"));
    }

    @Test
    public void testBceDecode() throws Exception {
        var decoder = decoder("bce");

        // Unknown chunk type (0xC1) → null
        verifyNull(decoder, binary(
                "3ab90b71bc1503000300c10bff11"));

        // MSG_ASYNC_STACK with dallas temperature (mask3 bit 7)
        verifyAttribute(decoder, binary(
                "a59821bf480f0300cd00a5196d6770b6b90bc0db80408005010f9d5d426138ca41002900410000000000b080a32fa8010222103d37004a000000000000000000000000000000000000000000000000000000de0b7a3fdb0a00000000de0b4ee5dc0a0000000000000000000000000000000000000000000000005c5771b6b90ac0db8040800501b080a32fa8010222103d37004d000000000000000000000000000000000000000000000000000000de0b7a3fdb0a00000000de0b4ee5dc0a000000000000000000000000000000000000000000000000c7"),
                Position.PREFIX_TEMP + 1, 30.8);

        verifyPositions(decoder, false, binary(
                "cdc3440cf31403001902a58c0a06e0ceb0009f4e4452419417e0ceb08bc0ffcf428014463627b24018104b425b1c508b00d16a9743d188da6e0110ce001455069262002e4c5adabb810200418728157501004229460377000bb4d04b10c000ffff335aa800000000000000000000000000a912963d0042313130303030313236313432303031202020202020202020202020202020203f2946030301f70100007b0400009f130000762700000a26e0ceb06f074e4452419427e0ceb08bc0ffcf42801446ee28b240a40f4b425c1c518a00df414c42d188936eff0fce001455069262002e4c5adabb810200417b28157501004c294603770008b4d04b10c000ffff4c5a89000000000000000000000000009a2c8c3b004231313030303031323631343230303120202020202020202020202020202020492946030301f80100007c040000a9130000802700009477e0ceb08bc0ffcf42801446eb2fb2405c0d4b425d1c53830089e07e43d188a56eff0fce001455069262002eb35700bb810200415227167501007e294603780000b4d04b10c000ffff995700000000000000000000000000008bd9f43b004231313030303031323631343230303120202020202020202020202020202020802946030301fd01000081040000e0130000b72700000a86e0ceb064464e4452410a96e0ceb000a54e4452410aa6e0ceb000914e4452410ab6e0ceb068334e4452410ac6e0ceb0009f4e4452410ac6e0ceb06f074e445241f4"));

        verifyPositions(decoder, false, binary(
                "cc2c5792c6160300b000a5520aa6c813ae64465343513840a7c813ae0bc0fd800080040036093f427884ea41001c900e00000000009088c562a301024156d12a004c00006df80c0000000086fb0200562a08005a000000000ac6c813ae0091534351380af6c813ae009f534351380af6c813ae6f075343513840f7c813ae0bc0fd800080040036093f427884ea41001c900e00000000009088f162a301024156d12a004c00006df80c0000000086fb0200562a08005a000000003f"));

        verifyPositions(decoder, false, binary(
                "76145792c61603003402a59b59a7f722aa8ac00080c086000121800000280f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff000059f7f722aa8ac00080c086000121800000260f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff00000a16f822aa6f07534352325917f822aa8ac00080c086000120800000190f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff00005957f822aa8ac00080c086000121800000240f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff00000a66f822aa6f07534352325967f822aa8ac00080c086000121a00000160f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff000059b7f822aa8ac00080c086000121800000170f9401056804d181006222ea4201000000000000008081008081008081008081000022ea4201000000000000ffffffffffff00000000ffffffffffff00000000ffffffffffff0000ef"));

        verifyPositions(decoder, false, binary(
                "789622d1cb1303003401a53365b70f4a9babc0ffd700c04400f0b6c741e63933428f1c431c015468de43f18221341b007e0ae20001430a698f003f008d000000000031f85900000000f0831c018400000000000000000000000000000000000209000000000000000000000000000000030065f70f4a9babc0ffd700c0440069bcc741e73733427f1c431a01a9378343f1829c391b00a80be2000170056da7003e007c000000000031c04e00000000f0831c01810000000000000000000000000000000000060100000000000000000000000000000003006537104a9babc0ffd700c0440051c1c74129363342721c421801e4809543f18210341b00710ae2000170056da7003e0072000000000031c04800000000b8841c017e00000000000000000000000000000000000306000000000000000000000000000000030069"));

        verifyPositions(decoder, false, binary(
                "be76619c834601004200a0003fd769c568ffc3db0079161d420683a9414918b1150000000000d102660167040000000000009f06357f0000a401042ea415e10232000000000000000000000051"));

        verifyPositions(decoder, false, binary(
                "be76619c834601004200a0003ff76cc568ffc3db00bd151d423c8ca9410a18af150000000000d1023a0160040000000000009f06427f0000a401042ea416e1003e00000000000000000000009a"));

        verifyPositions(decoder, false, binary(
                "be76619c834601004202a5863f57f8b868ffc3db0001712642b70b9d41221946200246d23342d1023e016404000000000000a0065a7f0000a4010496f277e3064300000000000000000000003f97f8b868ffc3db0074712642ae0a9d412919452102fff19042d102a4026304000000000000a006487f0000a4010496f277e3064300000000000000000000003fb7f8b868ffc3db00c6712642000a9d413019442002a6074542d102300165040000000000009f064f7f0000a4010496f277e3064300000000000000000000003fd7f8b868ffc3db002872264245099d413518421f02bea35e42d1021e0164040000000000009f06377f0000a4010496f277e3064300000000000000000000003fe7f8b868ffc3db0061722642e3089d413a28421f02a05ff641d102580163040000000000009f06577f0000a4010496f277e3064300000000000000000000003f17f9b868ffc3db0021732642a3079d414119411d02d69fcc42d102440165040000000000009f06437f0000a4010496f277e3064300000000000000000000003f37f9b868ffc3db00ae732642b4069d414628421b02e0629742d1024c0167040000000000009f06557f0000a4010496f277e3064300000000000000000000003f57f9b868ffc3db0044742642ae059d414c28421a027540a342d102860163040000000000009f065b7f0000a4010496f277e3064300000000000000000000003f97f9b868ffc3db007275264256039d4153284417029e1f2f43d1024a016704000000000000a0064e7f0000a4010496f277e306430000000000000000000000db"));

        verifyPositions(decoder, false, binary(
                "2d41abfa2e4501004e02a5a0068609f96a009106260af96a00a006260af96a009106960af96a00a306a60af96a008f06b60af96a009106960cf96a00a03e0715f96affc300804000e6a23a4230ccc441001f47850200000000a0000000bd6542651a110d004b1000000000a401045a56bf4d02480000000000000000061623f96a00a0062623f96a00913ea728f96affc300804000e6a23a4230ccc441001f7f850200000000a0000000bd6542651a110d004a1000000000a401045a56bf4d02480000000000000000069639f96a00a006a639f96a00913e373cf96affc300804000e6a23a4230ccc441001f7f850200000000a0000000ad6534651a110d004a1000000000a401045a56bf4d024800000000000000003ed74ff96affc300804000e6a23a4230ccc441001f7f850200000000a0000000ad6534651a111b004a1000000000a401045a56bf4d01480000000000000000061650f96a00a0062650f96a00913e6763f96affc300804000e6a23a4230ccc441001f7f850200000000a0000000ad6534651a110d004a1000000000a401045a56bf4d01480000000000000000069666f96a00a006a666f96a00913e0777f96affc300804000e6a23a4230ccc441001f7f850200000000a0000000ad6534651a110d004a1000000000a401045a56bf4d0148000000000000000006067df96a00a006167df96a0091063687f96a00a3064687f96a008f065687f96a0091063689f96a00a03e978af96affc300804000e6a23a4230ccc441001f87850200000000a0000000ad6527651a110d004a1000000000a401045a56bf4d024800000000000000000e"));

        verifyPositions(decoder, false, binary(
                "be76619c834601003302a5e8327764726bff432fc52a420e2c93410028afd2070000000080024a0005040000000000008e06547f0000a401043cf21f390e54328764726bff432fc52a420e2c93410028afd2070000000080024c0005040000000000008e064f7f0000a401043cf21f390e54329764726bff432fc52a420e2c93410028afd2070000000080024e0002040000000000008d064f7f0000a401043cf21f390e5432a764726bff432fc52a420e2c93410028afd2070000000080024e0004040000000000008e06587f0000a401043cf21f390e5432b764726bff432fc52a420e2c93410028afd207000000008002460005040000000000008e06557f0000a401043cf21f390e5432c764726bff432fc52a420e2c93410028afd2070000000080024e0004040000000000008e06347f0000a401043cf21f390e5432d764726bff432fc52a420e2c93410028afd2070000000080024e0002040000000000008e06547f0000a401043cf21f390e5432e764726bff432fc52a420e2c93410028afd207000000008002540002040000000000008e06477f0000a401043cf21f390e5432f764726bff432fc52a420e2c93410028afd207000000008002540004040000000000008d064f7f0000a401043cf21f390e54320765726bff432fc52a420e2c93410028afd207000000008002540004040000000000008e064d7f0000a401043cf21f390e54321765726bff432fc52a420e2c93410028afd207000000008002540004040000000000008e06467f0000a401043cf21f390e544200a0003f3743c96bffc3db0060c81c42d885ab41002aaf060000000000d102380167040000000000008a064f7f0000a4010412a46b330033000000000000000000000025"));

        verifyPositions(decoder, false, binary(
                "ca07629c834601002702a58f3c278ff96a0bc000a0c00140bc3a42508bc541002a70a905000000009000c101a40103d904440e003000000000000000000000000000000000000001013c878ff96a0bc000a0c00140bc3a42508bc541002970a905000000009000c301a40103d904440e003000000000000000000000000000000000000001013cb7d2f96a0bc000a0c00124bc3a426b8fc5410428000404000000009000c401a40103d904440e003500000000000000000000000000000000000001013cc7d2f96a0bc000a0c00124bc3a426b8fc5410428000404000000009000c301a40103d904440e003500000000000000000000000000000000000001013cd7f2f96a0bc000a0c00114bc3a42a48fc5410029027e03000000009000c301a40103d904440e003000000000000000000000000000000000000001013c670dfa6a0bc000a0c001f1bb3a42418dc541002a484904000000009000c001a40103d904440e003a00000000000000000000000000000000000001013c770dfa6a0bc000a0c001f1bb3a42418dc5410028484904000000009000bf01a40103d904440e003a00000000000000000000000000000000000001013c470efa6a0bc000a0c001f1bb3a42418dc5410029484904000000009000bf01a40103d904440e003a00000000000000000000000000000000000001013c5711fa6a0bc000a0c001f1bb3a42418dc5410029484904000000009000c101a40103d904440e003000000000000000000000000000000000000001013f00a0003cc795866b0bc000a0c00144bc3a423a90c541003697cb03000000008000cf01a40103d9040d0f0030000000000000000000000000000000000000010100"));
    }

    @Test
    public void testFifotrackDecode() throws Exception {
        var decoder = decoder("fifotrack");

        verifyAttributes(decoder, buffer(
                "$$159,866344056951341,399D,A03,,230716222659,240|8|2724|20EEF33,4.20,100,003E,1,AE233FC0D2E0:-65|3E286D5FB6E8:-65|28BD890A4A0E:-67|8ED81B5DFC3A:-70|8AD81B5DFC3A:-70*5F"));

        verifyAttribute(decoder, buffer(
                "$$99,865413050150407,7F,A03,,230626072722,460|0|25FC|AC2AB0B,3.74,52,0019,0,A,0,13,22.643466,114.018211*74"),
                Position.KEY_SATELLITES, 13);

        verifyPosition(decoder, buffer(
                "$$95,866104023192332,1,A03,,210414055249,460|0|25FC|104C,4.18,100,000F,0,A,2,9,22.643175,114.018150*75"));

        verifyAttributes(decoder, buffer(
                "$$136,866104023192332,1,A03,,210414055249,460|0|25FC|104C,4.18,100,000F,1,94D9B377EB53:-60|EC6C9FA4CAD8:-55|CA50E9206252:-61|54E061260A89:-51*3E"));

        verifyPosition(decoder, buffer(
                "$$274,863003046499158,18D0,A01,,211026081639,A,13.934116,100.000463,0,263,16,366959,345180,80000040,02,0,520|0|FA8|1A9B5B9,9DE|141|2D,%  ^YENSABAICHAI$SONGKRAN$MR.^^?;6007643190300472637=150519870412=?+             14            1            0000155  00103                     ?,*69"));

        verifyAttribute(decoder, buffer(
                "$$25,863003046473534,1,B03,OK*4D"),
                Position.KEY_RESULT, "OK");

        verifyPosition(decoder, buffer(
                "$$118,863003046473534,258,A01,,201007231735,V,3.067783,101.672858,0,176,96,189890,0,A0,03,0,502|19|5C1|93349F,196|4E0|6C,1,*13"));

        verifyPosition(decoder, buffer(
                "$$116,869270049149999,5,A01,4,190925080127,V,-15.804260,35.061506,0,0,1198,0,0,900000C0,02,0,650|10|12C|B24,18B|4C8|72,1,*01"));

        verifyAttribute(decoder, buffer(
                "$$123,869467049296388,B996,A01,2,190624131813,V,22.333746,113.590670,0,124,-1,26347,0,0004,00,0,460|0|2694|5A5D,174|0|0|0,B48CEB,*77"),
                Position.KEY_ALARM, Position.ALARM_SOS);

        verifyAttribute(decoder, buffer(
                "$$125,869467049296388,548,A01,38,190619025856,A,22.333905,113.590261,0,12,60,16666,0,0000,00,0,460|0|2694|13F8,1A2|4C1|0|0,B4A067,*7A"),
                Position.KEY_DRIVER_UNIQUE_ID, "11837543");

        verifyNull(decoder, buffer(
                "$$79,868345037864709,382,D05,190220085833,22.643210,114.018176,1,1,1,13152,23FFD339*25"));

        verifyNull(decoder, binary(
                "2424313036332c3836383334353033373836343730392c312c4430362c32343434424438362c302c313032342cffd8ffdb008400140e0f120f0d14121012171514181e32211e1c1c1e3d2c2e243249404c4b47404645505a736250556d5645466488656d777b8182814e608d978c7d96737e817c011517171e1a1e3b21213b7c5346537c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7cffc000110801e0028003012100021101031101ffdd0004000affc401a20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa0100030101010101010101010000000000000102030405060708090a0b1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00b0148a705c8cd00479e6917ef7b5003c9ec29b90bf5a00457c366a4620806801921f41da999cf02801ebf4a4e73cf14002b153f2d4a5cb0c8506802261cf4a50b8a007053d718a4c1cf340099c526ecd007fffd07f6a55c86140126e19e69acdcd0037a9a4a002909a004eb4e030334001a4ce280141cd2138a004ed4982074e6800ed49de801698793401ffd18cf4a65002af5a4ce1a8026cf14d278a008f760d20ebf350031cf6149183bb8a009c03de901f9a801c0e78a31400b9c518a004c5140094b8a00fffd28b1462800c518a00414b400b8a00e68016814001a2800a5eb40062908cd002628a0028a00fffd3998e4734b1b7c981400c3d79a7829b7ef73e98a0069f6a4c50034a926a551b47340037a1e4d424734012c43820529001e72680060bfc34a1f6f02800618e6a3c9cd003c336304d0091d680187ad211401fffd47f34a48079a0091946327d2a173e9400a290d002f6a4c7ad00205cf4a7f3b680131c52639a00304521140098a42c68010138a00e28014034d391401fffd58c9a69e6801a3341a004dc69439140085b3da909cd001b69369cf14013019148cb40028229dcd0014b4005142a3739"));

        verifyPosition(decoder, buffer(
                "$$105,866104023179743,AB,A00,,161007085534,A,54.738791,25.271918,0,350,151,0,17929,0000,0,,246|1|65|96DB,936|0*0B"));

        verifyPosition(decoder, buffer(
                "$$103,866104023179743,5,A00,,161006192841,A,54.738791,25.271918,0,342,200,0,4265,0000,0,,246|1|65|96DB,9C4|0*75"));

        verifyPosition(decoder, buffer(
                "$$103,866104023179743,4,A00,,161006192810,V,54.738791,25.271918,0,158,122,0,4235,0000,0,,246|1|65|96DB,9C5|0*69"));

        verifyPosition(decoder, buffer(
                "$$135,866104023192332,29,A01,,160606093046,A,22.546430,114.079730,0,186,181,0,415322,0000,02,2,460|0|27B3|EA7,A2F|3B9|3|0,940C7E,31.76|30.98*46"));
    }

    @Test
    public void testGalileoDecode() throws Exception {
        var decoder = decoder("galileo");

        verifyPositions(decoder, binary(
                "011801018202130338363833343530333230343234323604640010a406207caa9f5b300c830a7901ca0ec802330000000034b802350540003e41703f422b1043234504004600e09000000000a000a100a200a300a400a500a600a700a800a900aa00ab00ac00ad00ae00af00b00000b10000b20000b30000b40000b50000b60000b70000b80000b90000c000000000c100000000c200000000c300000000c400c500c600c700c800c900ca00cb00cc00cd00ce00cf00d000d100d200d4d3140000d60000d70000d80000d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f300000000f400000000f500000000f600000000f700000000f800000000f9000000008960"));

        verifyPositions(decoder, binary(
                "01bf83043200101ee4209832bc62300549589302511aaa013300002e00342e02350440003b41b15d42d50e4326450e0046040050000051000052fc5c5300006100008b009000000000d400000000e201000000e376000000e4efce0100e53b590200e600000000e773000000e800000000e9a002d007ea140000d6021b00f8430220ac760000000000000000043200101de4201232bc62300549589302511aaa013300002e00342e02350440012b41b55d42d40e4326450e0046040050000051000052145d5300006100008b009000000000d400000000e201000000e376000000e4efce0100e53b590200e600000000e773000000e800000000e9a002d007ea140000d6021b00f8430220ac760000000000000000043200101ce4208e2ebc62300549589302511aaa013300002e00342e02350440013b41a95d42cd0e4325450f0046040050000051000052235d5300006100008b009000000000d400000000e201000000e376000000e4efce0100e53b590200e600000000e773000000e802000000e9a002d007ea140000d6021b00f8430220ac760000000000000000043200101be4208b2ebc62300549589302511aaa013300002e00342e02350440013b41a45d42cd0e432545090046040050000051000052115d5300006100008b009000000000d400000000e201000000e375000000e48ac90100e53a590200e673000000e773000000e806000000e9a002d007ea140000d6021b00f8430220ac760000000000000000043200101ae420642ebc62300549589302511aaa013300002e00342e02350440013b419f5d42cd0e4324450b00460600500000519313521c5d5300006100008b009000000000d400000000e201000000e300000000e406000000e5c5580200e673000000e700000000e801000000e9a002d007ea140000d6021b00f8430220ac7600000000000000000432001019e420632ebc62300549589302511aaa013300002e00342e02350440013b41725d42cd0e4324450b0046060050000051ab1352035d5300006100008b009000000000d400000000e201000000e300000000e406000000e5c5580200e673000000e700000000e8d6021b00e9a002d007ea140000d6021b00f8430220ac7600000000000000000432001018e4205c2ebc62300549589302511aaa013300002e00342e02350440013b41955d42cd0e4324450a00460400500000510000520b5d5300006100008b009000000000d400000000e201000000e30d000000e4a4350000e5c5580200e600000000e700000000e8d6021b00e9a002d007ea140000d6021b00f8430220ac76000000000000000099f3"));

        verifyPositions(decoder, binary(
                "017583018202120338363833343530333230363635373304520010384520c850975b300cc03a910107cbf9023365000607341300350640012a41236a4215104329450400460020500000510000520000530000540000550000c000000000c100000000c44bc500c6ffc700c800c900ca00cb00d4993b0500d64100d70000d8be02d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f300000000018202120338363833343530333230363635373304520010394520c950975b300cab3a91010ecbf902336000be06341300350640012a41266a4216104329450400460020500000510000520000530000540000550000c000000000c100000000c44bc500c6ffc700c800c900ca00cb00d49b3b0500d64100d70000d8bc02d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f3000000000182021203383638333435303332303636353733045200103a4520ca50975b300c953a910113cbf9023358008f06341300350640012a41206a4215104329450400460020500000510000520000530000540000550000c000000000c100000000c44bc500c6ffc700c800c900ca00cb00d49e3b0500d64100d70000d8ba02d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f3000000000182021203383638333435303332303636353733045200103b45204251975b300c6d3a91011dcbf9023300008a06341300350640013a41726a4216104329450400460020500000510000520000530000540000550000c000000000c100000000c44bc500c6ffc700c800c900ca00cb00d4a33b0500d64800d70000d80003d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f3000000000182021203383638333435303332303636353733045200103c4520bb51975b300c6d3a91011dcbf9023300008a06341300350640013a41816a4216104329450400460020500000510000520000530000540000550000c000000000c100000000c44bc500c6ffc700c800c900ca00cb00d4a33b0500d64800d70000d80003d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f300000000e007"));

        verifyNull(decoder, binary(
                "07e10300ffd8ffe000114a464946000101010000000000000affe1011445786966000049492a000800000005000e010200140000004a0000000f0102000a0000005e000000100102000a0000006800000032010200130000007200000025880400010000008600000000000000494d45492033353336313230383730353035393247616c696c656f536b7971717a6d202020202020323031383a30383a30392030363a35393a303100060001000200020000004e0000000200050003000000d40000000300020002000000450000000400050003000000ec000000050001000100000000000000060005000100000004010000000000008e7f930240420f0000000000010000000000000001000000a11aaa0140420f00000000000100000000000000010000003300000001000000ffdb0084000d09090b09080d0b0a0b0e0d0d0f131f1413111113261b1d171f2d28302f2d282c2b3238483d323544362b2c3f553f444a4d505150303c585f584e5e484f504d010d0e0e131013251414254d342c344d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4dffc000110801e0028003012100021101031101ffdd0004000affc401a20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa0100030101010101010101010000000000000102030405060708090a0b1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f41237526768c119a0811803c8e29a7eb40075151ba9e2801436060d0df779ef408685c50092db6801e63c9c83834c772a3140c818163522924fa5310367b9a8f3ce2806382734e2bc7340c68031c52ab1e86811ffd0edcf0335170fd7b5333023151c83d4ba"));

        verifyPositions(decoder, false, binary(
                "01560003383636303530303338343337353836044701e000000000e13c494e414c4c3a696e303d31313230362c696e313d302c696e323d302c696e333d302c696e343d302c696e353d302c4163633d3536363932343732353bfdef"));

        verifyPositions(decoder, false, binary(
                "012a0003383633353931303233353137333732046600e000000000e1104f555428332e2e3029203d2031313130bb29"));

        verifyPositions(decoder, binary(
                "0144030338363832303430303132363939333404320010ee0f20f5a86c57300570172f03bc7dfd023363002604343e00351c40092a414a6842af0e432445000046030050246b51666a524c055300000338363832303430303132363939333404320010ed0f20f4a86c57300570172f03b47dfd023363000d05343e00351140090a41c56742a60e432445000046030050b56a514f6a521b045300000338363832303430303132363939333404320010ec0f20e6a86c57300b34172f03287efd023300000000344900350d40290a41562742030b43234500004603205023455190445295005300000338363832303430303132363939333404320010eb0f20e4a86c57300b34172f03287efd023300000000344900350d40290b41000042bd0b432345000046032050dc31518c315200005300000338363832303430303132363939333404320010ea0f20c7a86c57300b34172f03287efd023300000000344900350d40a90b41000042050d43234500004600205000005100005200005300000338363832303430303132363939333404320010e90f204fa86c57300b34172f03287efd023300000000344900350d40a90b41000042ff0c43244500004600205000005100005200005300000338363832303430303132363939333404320010e80f20d7a76c57300b34172f03287efd023300000000344900350d40a90b41000042fd0c43244500004600205000005100005200005300000338363832303430303132363939333404320010e70f205fa76c57300b34172f03287efd023300000000344900350d40a90b41000042fd0c43254500004600205000005100005200005300000338363832303430303132363939333404320010e60f20e7a66c57300b34172f03287efd023300000000344900350d40a90b41000042fd0c43264500004600205000005100005200005300000338363832303430303132363939333404320010e50f206fa66c57300468172f03907cfd023300007a0a343600352b40a90b41000042030d43274500004600205000005100005200005300000338363832303430303132363939333404320010e40f2051a66c5730048c172f03ac7cfd02335300980a341600352b40a12b41000042040d43274500004600e0500000510000520000530000abde"));

        verifyNull(decoder, binary(
                "011380033836383230343030313534393038370432008590"));

        verifyPositions(decoder, binary(
                "01cf030446ba10630320a7054c533008f86c8e0310062c043347049e02344000350940013241506b428f10432244aeea572045f9004604a0500000510000529a6b5300000446ba10712420ce1c4b533009b4f06703043df4033381037b0a343800350a40093241db6b428f10432544c05ef81f45f9004604a050000051000052886b5300000446ba10702420c11c4b53300a54f16703c450f403336e034e0a343900350840093241dd6b428f1043254491eaf71f45f9004604a050000051000052c26b5300000446ba106f2420b31c4b53300cecf267033865f403336a03300a343800350740093241e66b429010432544b446582045f9004604a050000051000052f76b5300000446ba106e2420a61c4b53300c9cf467038878f403337b03370a343800350740093241b56b428f10432544ba46f81f45f9004604a050000051000052c66b5300000446ba106d2420991c4b53300bc8f56703508cf403338d036e0a343700350840093241d66b428f10432544b4ea572045f9004604a050000051000052846b5300000446ba106c24208c1c4b533008c8f5670370a0f403338703920a343a00350e40093241c76b428f10432544c0fef71f45f9004604a0500000510000528d6b5300000446ba106b24207f1c4b533009a4f5670338b4f403337603920a343c00350a40093241d06b428f104325449146a81f45f9004604a0500000510000528a6b5300000446ba106a2420721c4b53300b9cf56703ecc7f403337103810a343a00350840093241ca6b428f10432544d12e582045f9004604a050000051000052996b5300000446ba10692420651c4b53300a64f6670358dbf403337a03490a343900350840093241e56b429010432544aed2f71f45f9004604a050000051000052b26b5300000446ba10682420581c4b5330094cf86703e0eef4033381030c0a343a00350940093241f96b428f10432544cb2e182145f9004604a050000051000052926b5300000446ba106724204b1c4b533009f8fa67032802f503337b03fc09343b00350a40093241d86b428f10432544c0ea772145f9004604a0500000510000529e6b5300000446ba106624203e1c4b533009a0fd67036815f503338403fd09343c00350a40093241a86b428f10432544ae2e582045f9004604a050000051000052a86b5300000446ba10652420311c4b53300944006803b028f503338003ff09343d00350940093241dc6b428e10432544a8fea71f45f9004604a050000051000052e26b5300000446ba10642420241c4b533008f0026803083cf503338b03f909343c00350d40093241d36b428f10432544c0eaa71f45f9004604a050000051000052ab6b530000ff3f"));
        
        verifyPositions(decoder, binary(
                "011e8304320010270220dbd2f051300a90cf740328ac59033300000000347600351240012a41e92e42500f431f440006c814450f00460020500000510000520000530000540000550000560000570000580000600000610000620000a000a100a200a300a400a500a600a700a800a900aa00ab00ac00ad00ae00af00b00000b10000b20000b30000b40000b50000b60000b70000b80000b90000c000000000c100000000c200000000c300000000c400c500c600c700c800c900ca00cb00cc00cd00ce00cf00d000d100d200d471020000d60000d70000d80000d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f30000000004320010260220bdd2f051300590cf740328ac59033300000000347600351440090a41f02e427b0f431f44ff0db814450f00460000500000510000520000530000540000550000560000570000580000600000610000620000a000a100a200a300a400a500a600a700a800a900aa00ab00ac00ad00ae00af00b00000b10000b20000b30000b40000b50000b60000b70000b80000b90000c000000000c100000000c200000000c300000000c400c500c600c700c800c900ca00cb00cc00cd00ce00cf00d000d100d200d471020000d60000d70000d80000d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f300000000043200102502208ed2f051300ed8d0740304ac5903330000000034a500350a40012a41ec2e422d0f431f440016b814450f00460020500000510000520000530000540000550000560000570000580000600000610000620000a000a100a200a300a400a500a600a700a800a900aa00ab00ac00ad00ae00af00b00000b10000b20000b30000b40000b50000b60000b70000b80000b90000c000000000c100000000c200000000c300000000c400c500c600c700c800c900ca00cb00cc00cd00ce00cf00d000d100d200d44d020000d60000d70000d80000d90000da0000db00000000dc00000000dd00000000de00000000df00000000f000000000f100000000f200000000f300000000622e"));

        verifyPositions(decoder, binary(
                "01d48304320010020520a5829f58300f50dc8a024c0965013300000000344102350740003a41e14b426610431b4459fa672a4500004601a050364c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e293000000043200100105202d829f58300f50dc8a024c0965013300000000344102350740003a41d04b426110431b445702882a4500004601a050374c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29400000004320010000520b5819f58300f50dc8a024c0965013300000000344102350740003a419e4b426a10431c4456fab72a4500004601a050434c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29500000004320010ff04203d819f58300f50dc8a024c0965013300000000344102350740003a41874b426310431c4454fe572a4500004601a050334c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29600000004320010fe0420c5809f58300f50dc8a024c0965013300000000344102350840003a41a24b426710431c4457fea72a4500004601a050214c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29700000004320010fd04204d809f58300f50dc8a024c0965013300000000344102350840003a41a34b426310431c4455f6772a4500004601a0502e4c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29900000004320010fc0420d57f9f58300f50dc8a024c0965013300000000344102350840003a41bd4b426510431d4458fe672a4500004601a0501f4c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29700000004320010fb04205d7f9f58300f50dc8a024c0965013300000000344102350840003a41b54b426310431d4456fa772a4500004601a0502d4c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29500000004320010fa0420e57e9f58300f50dc8a024c0965013300000000344102350840003a41b24b426210431e4454fa872a4500004601a050fe4b510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e29000000004320010f904206d7e9f58300f50dc8a024c0965013300000000344102350a40003a41af4b426710431f4458fea72a4500004601a0500a4c510000520000530000c000000000c100000000c200000000c300000000d80000dd00000000e28900000067c5"));

        verifyPositions(decoder, binary(
                "019f8304320010d61e208c92a066300c2348fe006d69bd053300000000341600350440002b41096242960e432c450f004600004700000000500000593210891f9000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d51e206091a066300c2348fe006d69bd053300000000341600350440002b41fb6142950e432c450f004600004700000000500000593210891f9000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d41e203490a066300c2348fe006d69bd053300000000341600350440002b410b6242950e432c450f004600004700000000500000593210891f9000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d31e20088fa066300c2348fe006d69bd053300000000341600350440002b41196242940e432c450f00460000470000000050000059331089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d21e20dc8da066300c2348fe006d69bd053300000000341600350440002b41026242950e432c450f00460000470000000050000059331089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d11e20b08ca066300c2348fe006d69bd053300000000341600350440002b411e6242930e432c450f00460000470000000050000059331089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010d01e20848ba066300c2348fe006d69bd053300000000341600350440002b41226242950e432c450f00460000470000000050000059331089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010cf1e20588aa066300c2348fe006d69bd053300000000341600350440002b41126242940e432c450f00460000470000000050000059341089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e70000000004320010ce1e202c89a066300c2348fe006d69bd053300000000341600350440002b410e6242960e432c450f00460000470000000050000059331089209000000000d4e5ac0200d500e201000000e300000000e400000000e500000000e600000000e7000000001f5b"));

        verifyPositions(decoder, binary(
                "01ba83106c022016427763300694c274028f8251043300000000345c05351840002a41086842da0e43194600004700000000500000d4915b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000c0000002700230000002800a8ffffff106b0220fb417763300551c27402ba8251043332000000345d05352740012a41786842da0e43194600004700000000500000d4895b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000c0000002700230000002800a8ffffff106a0220fa41776330054cc27402bf825104333b005f02345d05352740012a417e6842da0e43194600004700000000500000d4895b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000c0000002700230000002800a8ffffff10690220eb4177633006ffc17402ff8051043300000000343905351840012a419f6842da0e43194600004700000000500000d4515b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000b0000002700230000002800acffffff10680220af4177633006ffc17402ff8051043300000000343905351840092a413e6a42db0e43194600004700000000500101d4515b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000b0000002700230000002800aaffffff10670220ac4177633006ffc17402ff8051043300000000343905351840092a41dd6942db0e43194601004701000200501269d4515b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000b0000002700230000002800aaffffff106602208e417763300603c27402fc8051043337000000343905351840092a41826d42da0e431946010047000b050750a56cd4515b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000b0000002700230000002800adffffff106502208b4177633005fec17402f38051043362008c01343905352640092a416e6d42da0e431946010047080a160750d96cd44c5b0000fe300021004d03000022000b0000002300230000002400a8ffffff25000700000026000b0000002700230000002800adffffff106402208241776330060cc174021480510433c6002301343505351840092a41966d42da0e4319460100470a000a0550c26cd4235b0000fe300021004d03000022000b0000002300230000002400aaffffff25000700000026000b0000002700230000002800adffffff6121"));
    }

    @Test
    public void testGalileoDecodeIridium() throws Exception {
        var decoder = decoder("galileo");

        verifyPosition(decoder, binary(
                "01004e01001c0747ea59333030323334303639363034353930000012000063c85e6903000b0321a8f846aba50000000202001e205f5ec863300c4643fdfdbbe6c8fb330000000034e7013505d400000000"));
    }


    @Test
    public void testTzoneDecode() throws Exception {

        var decoder = decoder("tzone");

        verifyAttribute(decoder, binary(
                "545A006C2424040A010100000190023000000002170801093709001305170801093908015976CA06CB9E5A00000001001401D204600000279E3B4F4D6E00650005144F0D2C000EAA101F3700640142028B088C0000001588259344518B51001F7AA412115AD8D86629C74F560010AFD50D0A"),
                Position.KEY_BATTERY, 1.0);

        verifyAttribute(decoder, binary(
                "545a00d424240153011300000863835029944118170316023b180016040485c73d2479187e170316023b1800000000060c000000000d1cc0406303019904aa00000000008a012520205e544f4e474c4f4d245049544f4f4e244d522e5e5e3f3b363030373634333132303130303134323234323d3139303631393538313032363d3f2b2020202020202020202020202032322020202020202020202020203120202020202020202020202030303234363238202031303730302020202020202020202020202020202020202020203f00030080000006e80e0d0a"),
                Position.KEY_CARD, "%  ^TONGLOM$PITOON$MR.^^?;6007643120100142242=190619581026=?+             22            1            0024628  10700                     ?");

        verifyAttributes(decoder, binary(
                "545a003724240407020200000180322000001610160b151019100000000c010a07320101088600007dca000baa102837016a0114025500000169e80d0a"));

        verifyAttributes(decoder, binary(
                "545a004d24240407010d0000018032100000031515090c052c2100000022030a033400201347000056860a03340020134700002feb0a03340020134700007d96000baa10211f01810127022d000001ebe00d0a"));

        verifyAttributes(decoder, binary(
                "545A004B2424041302000000086706003324776413030C0A1A2900180513030C0A1A25080F7E1028CAC830000A000F0000000005000AA53201633D05046000010009AA201737019408973B0032B0260D0A"));

        verifyAttributes(decoder, binary(
                "545a005b24240406010800000866050033819630120911071824000472bd8e5b0008aac01b07019b04bb002f00040b06161154000e100132ff2006161152000e080096ff4606161151000e1e0101ff1406161156000db6405bff490024469e0d0a"));

        verifyAttributes(decoder, binary(
                "545a006624240406010800000866050033819630120911070e1d000472bd8e5b0008aac01b17019b04bc003a00050b06161151000e1e00ffff1406161152000e08008aff4706161154000e100134ff1f06161156000db0406cff4906161155000df44011ff4e0023811a0d0a"));

        verifyPosition(decoder, binary(
                "545a005624240111010e0000086169303626931411091b151d2600160801de26ec002f633411091b151d2500000000160c0000040d2a34df000eaa4000001b37016000000000319c0000000000000000000000000000003a84240d0a"));

        verifyPosition(decoder, binary(
                "545a005024240153011000000863835025559464110103080a22001609011bed79245964a9110103080a22000a0000550c00000604396f04222c000daac000151701a204870000000000000003000959000546190d0a"));

        verifyPosition(decoder, binary(
                "545A005224240153010E000008638350256668551008130616090016050079F63D2527FAF710081306160900A000002F0D33000803015B07013D7976000DAAE0400537016C049E000000000000000300800002B65EEA0D0A"));

        verifyPosition(decoder, binary(
                "545a00582424010b022000000860041028904798100803030c2700160a007da96203356669100803030c2700000000000e000004002813730010aa4000000617017100000000000080000000000000000000000000000000007701fe0d0a"));

        verifyPosition(decoder, binary(
                "545a00582424010b022000000860041028904798100803030d1a001609007da9620335666a100803030d1900000000000e000004002813730010aa400000063701720000000000008000000000000000000000000000000000787f0c0d0a"));

        verifyPosition(decoder, binary(
                "545a00582424010b021e000008637710239476270f080b0a3228001600000000000000000000000000000000000000000000000401a00822001088c00020183701a6053800000000800000000000000000000000000000000077c9860d0a"),
                position("1999-11-30 00:00:00.000", false, 0.0, 0.0));

        verifyPosition(decoder, binary(
                "545A00912424010B021E000008661040203754350F061807083800160400CE5ADC041447620F0618070838000A0000060C7C0004253378370010AAC000000C37018504E500000000800000000000000000390B0A0014061113000000051200140610600014061220001000133800140610070010001473001000151100101500640010000920001000148400000000000000F2EF570D0A"),
                position("2015-06-24 07:08:56.000", true, 22.53946, 114.06310));

        verifyAttributes(decoder, binary(
                "545A009E2424010A0205000008637710225481290F010F081E33000000000010A0C000310E35000005840000000000000000000000000066140A00140612200010001511001406101000140612490014061308001015006400051400170014061012000000050200140612470000000504001406100700140612510014061260001015012000000005080014061252001406130900101501410000000506000853A40D0A"));

        verifyAttributes(decoder, binary(
                "545A00992424010A0205000008637710225481290F010F082634000000000010A0C000311035000005870000000000000000000000000061130A000000050800101500640014061251001406130800051400170010150141001406101000140612200014061309000000050200140610070014061260001406124900140612470014061012001406125200100015110010150120000000050400183E8A0D0A"));

        verifyAttributes(decoder, binary(
                "545A00942424010A0205000008637710225481290F010F091C1F000000000010A1C000310F3500000586000000000000000000000000005C120A001406101000140612490014061012001406125200000005040000000502001015012000000005080010001511001406122000140612600014061247001406130900140610070010150141000514001700140612510010150064007A907C0D0A"));

        verifyAttribute(decoder, binary(
                "545A006C2424040A010100000190023000000002170801093709001305170801093908015976CA06CB9E5A00000001001401D204600000279E3B4F4D6E00650005144F0D2C000EAA101F3700640142028B088C0000001588259344518B51001F7AA412115AD8D86629C74F560010AFD50D0A"),
                Position.KEY_BATTERY, 1.0);

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
