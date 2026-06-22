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

    @Test
    public void testRuptelaDecode() throws Exception {

        var decoder = decoder("ruptela");

        verifyAttribute(decoder, binary(
                "01460003115c885fa8c6440003674f43df002000fb7291ce13fc9230040e400618003d0500080f00ce0001990100823d008600008700008800000201000300000400019500019601001b1e00ad0000b03d01a201070083000000890000008b00030016009d0017009f001d33d6001e1038020041000a6b3f00960000ebf201002201d042f31d000030674f43df002100fb7291ce13fc9230040e400618003d0500080803a000005f3c00607600613c00622000630400650000670009023000000399003d039c0009039806c6005e1b180064004600660000006b066a0282000901039a00005a67030068555531444a46303000693537303637383230006a3500000000000000674f43df002200fb7291ce13fc9230040e400618003d0500080302d2ff047cff047eff04019700f60198000302d3ffff03b6000002028500005a6702f2000068a800092b"),
                Position.KEY_DRIVER_UNIQUE_ID, "01d042f31d000030");

        verifyNull(decoder, binary(
                "002e000316d53d58d6020f4573303430302e30332e36382e30340000c2b3090d0e950000827b000003e80000003c003c1681"));

        verifyPositions(decoder, false, binary(
                "03fb0003137ca79f856d01011d386d438b000080000000800000008000ffffffffffff070220211bff011d30c50000386d438b000080000000800000008000ffffffffffff0702201f1bff011d30c30000386d43a9000180000000800000008000ffffffffffffad0320211b16ad01011d30950000386d43c7000080000000800000008000ffffffffffff070220211b00011d30a60000386d4403000080000000800000008000ffffffffffff070220221b00011d30ae0000386d443f000080000000800000008000ffffffffffff070220231b00011d30ae000064c692cb000080000000800000008000ffffffffffff070220231b18011d3091000064c69306000080000000800000008000ffffffffffff070220231b14011d30a7000064c69322000180000000800000008000ffffffffffffad0320241b14ad00011d30a3000064c69342000080000000800000008000ffffffffffff070220241b13011d30ad000064c6934a000180000000800000008000ffffffffffffad0320241b10ad01011d30c3000064c6937e000080000000800000008000ffffffffffff070220241b12011d3092000064c6938b000180000000800000008000ffffffffffffad0320241b12ad00011d30bd000064c69395000180000000800000008000ffffffffffffad0320241b10ad01011d30a6000064c693ba000080000000800000008000ffffffffffff070220251b17011d30a2000064c693d4000180000000800000008000ffffffffffffad0320251b17ad00011d30cc000064c693f6000080000000800000008000ffffffffffff070220251b15011d3090000064c69404000180000000800000008000ffffffffffffad0320251b16ad01011d30a9000064c69432000080000000800000008000ffffffffffff070220261b14011d30be000064c6946d000180000000800000008000ffffffffffffad0320261b15ad00011d30b1000064c6946e000080000000800000008000ffffffffffff070220261b15011d3096000064c694aa000080000000800000008000ffffffffffff070220261b15011d30a8000064c694b2000180000000800000008000ffffffffffffad0320261b15ad01011d30a5000064c694e6000080000000800000008000ffffffffffff070220261b17011d309a000064c694f5000180000000800000008000ffffffffffffad0320261b17ad00011d309c000064c694f6000180000000800000008000ffffffffffffad0320261b17ad01011d3099000064c69522000080000000800000008000ffffffffffff070220261b14011d3094000064c6955e000080000000800000008000ffffffffffff070220261b15011d30b2000064c6959a000080000000800000008000ffffffffffff070220261b14011d30970000ad9e"));

        verifyPositions(decoder, binary(
                "00800003167d765c155d01000160cd0a310000faae43f7176ee45702332b0c12000006070d05007300cfff260082008600870088000f00d7021100d801c900061d0000c500001e0e988300008900008b000002d0000c9bca720c889a0b047e00000000000000007f0000000000000000800000000000000000810000000000000000a341"));

        verifyNull(decoder, binary(
                "03fc0003142b0c152acd2502003544444131464144000a0000ffd8ffe000104a46494600010100000100010000ffdb00c50006040506050406060506070706080a100a0a09090a140e0f0c1017141818171416161a1d251f1a1b231c1616202c20232627292a29191f2d302d283025282928010707070a080a130a0a13281a161a2828282828282828282828282828282828282828282828282828282828282828282828282828282828282828282828282828020707070a080a130a0a13281a161a2828282828282828282828282828282828282828282828282828282828282828282828282828282828282828282828282828ffc000110800f0014003012200021101031102ffc401a20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa0100030101010101010101010000000000000102030405060708090a0b1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffdd00040000ffda000c03010002110311003f00e27534fde484fa66950079add40e153754d73892794f552e00a6da0c4b282794f947d2aa92b2b1d7887795fb9b5a4200eee7e957e6bdfb2b0f2cb6eff669cba4dee99656d25f5acb02dd209622e38753d08fc315177cf151562e337196e8e46f5352cb5d6603cd52ebeb8c1adcb4be82e07eedc67d0f06b8f071daa64618c8383508573ae991251891430f7a836cd07fa97de9fdc7fe86b0adf51b8830377989e8d5a96daa413603131bfa350061fc49d41adbc39e5edc34ee14f7c639af1b91f2719aef7e2c5d4bf6cb780b7ee7607500f19e735e7a06e3c9e2b4a4b4b88b51b2aaf14bbc1351226796a79e3815b81283c500d460d381a405988d7a6785ad8c3a241bc9f9f2e47d7ffad5e73a5db35dddc30a757603e83bd7aba011c6a8bc2a8000a89e8807b1551815a5636fba38ce3af359248cd7516298862ff7456713fc58"));

        verifyNull(decoder, binary(
                "002e000315bc70d3e2ff0f4f42443130302e30312e30382e30300000c2b30ea77e430000601b000001f40000003c00144aa0"));

        verifyAttributes(decoder, binary(
                "0011000315A07F440B1D07534554494f20636f6e66696775726174696f6e2064617461206f6b341C"));

        verifyAttributes(decoder, binary(
                "0044000313612d76c5cb0744494e313d312c44494e323d302c44494e333d302c44494e343d302c444f5554313d312c444f5554323d312c41494e313d31372c41494e323d3236ac80"));

        verifyPositions(decoder, binary(
                "000B00000B1A29F64B1A0902FF4E9CAF2C07D608F11A1480BA015030303130FF4E9CAF2C07D608F11A1480BA0250303031318C91"));

        verifyPositions(decoder, binary(
                "01a4000315bc70f9b69244000458068f4a0030000d11398a1c0c19fd056524040b000c0a00090c0005010031f40032fd0033f200ce47002400002500001c010199000195010196010086000900aa0000001e0ff000d3ffff0043ffff01930000019200000194000002220000022300000200300000000200af000e872401008e000000000000000058068f4a0031000d11398a1c0c19fd056524040b000c0a00090400870000880000a90000820010008b0002021e0000021f0000021d0000021c0000022400000225000000890000008505f00220000002210000008300000084000002260000022700000228000003008a00000000008d00000000008c000000000058068f4a0032000d11398a1c0c19fd056524040b000c0a000905019f01005800001b1f00ad0000cfb10b02290000022a0000022b0000022c0000022d00000012000000130000001d367400c52f8000740055023e0502060097000000000096000058520041007746cb00d0000003f1005c0007c21b0072001864880058068f4a0033000d11398a1c0c19fd056524040b000c0a000900000001008e0000000000000000e815"));

        verifyPositions(decoder, binary(
                "033d000315bc70f9b69244000858068f3b0030010d11354e1c0c17a5055d54560c00000900050c0005010031f30032fb0033f300ce00002400002500001c010199000195010196010086000900aa0000001e0ff300d3ffff0043ffff01930000019200000194000002220000022300000200300000000000af000e872401008e000000000000000058068f3b0031010d11354e1c0c17a5055d54560c00000900050400870000880000a90000820010008b0000021e0000021f0000021d0000021c0000022400000225000000890000008500000220000002210000008300000084000002260000022700000228000003008a00000000008d00000000008c000000000058068f3b0032010d11354e1c0c17a5055d54560c000009000505019f01005800001b1f00ad0000cfac0b02290000022a0000022b0000022c0000022d00000012000000130000001d31b100c5000000740000023e0502060097000000000096000058520041007746be00d0000003f1005c0007c2150072001864880058068f3b0033010d11354e1c0c17a5055d54560c000009000500000001008e000000000000000058068f3b0130000d11354e1c0c17a5055d54560d00000900070c0005010031f30032fb0033f300ce00002400002500001c010199000195010196010086000900aa0000001e0ff300d3ffff0043ffff01930000019200000194000002220000022300000200300000000000af000e872401008e000000000000000058068f3b0131000d11354e1c0c17a5055d54560d00000900070400870000880000a90000820010008b0000021e0000021f0000021d0000021c0000022400000225000000890000008500000220000002210000008300000084000002260000022700000228000003008a00000000008d00000000008c000000000058068f3b0132000d11354e1c0c17a5055d54560d000009000705019f01005800001b1f00ad0000cfac0b02290000022a0000022b0000022c0000022d00000012000000130000001d31ae00c5000000740000023e0502060097000000000096000058520041007746be00d0000003f1005c0007c2150072001864880058068f3b0133000d11354e1c0c17a5055d54560d000009000700000001008e0000000000000000084d"));

        verifyPositions(decoder, binary(
                "0050000310f5615f419c0100015613d8ed0000fff5b37a035af37801e700000900000d07071b0c020003001c01202cad000500064302a81d33e61e100116317cd3ffff174ad60241000077fa960000f232003c2e"));

        verifyPositions(decoder, binary(
                "00560003116e7438a7a50100015565cbb9000020fd21300f113f4600005f000600090d090805011b13cf00020003001c012029ad00041d31dd1e0ebd160000c50000047200000000d0000000004100016a2a960000a5a300c9ee"));

        verifyPositions(decoder, binary(
                "00a10003116e7438a7a5010002553dddbe000020fddaff0f12289b007200000600000c070805011b18cf00020003001c01201dad01041d32d81e0d7d160000c50000047200000000d000000000410000b1ae960000a5a300553dddd4000020fdd96f0f122bfe005c16f80700050b090805011b18cf00020003001c01201ead01041d338a1e0d8d160000c50000047200000000d000000000410000b1bd960000a5a3001681"));

        verifyPositions(decoder, binary(
                "007900000b1a2a5585c30100024e9c036900000f101733208ff45e07b31b570a001009090605011b1a020003001c01ad01021d338e16000002960000601a41014bc16d004e9c038400000f104fdf20900d20075103b00a001308090605011b1a020003001c01ad01021d33b116000002960000601a41014bc1ea0028f9"));

        verifyPositions(decoder, binary(
                "009200000c07a6bacd4701000552db5cc20000187b8b251ace478e087c044c0a000009070000000052db5cfe0000187b8ab01ace47190879044c0900000b070000000052db5d3a0000187b8b251ace474b089d044c09000009070000000052db5d760000187b8b9a1ace475c08cd044c08000009070000000052db5db20000187b8b141ace46e708b3044c08000009070000000041cb"));

    }

    @Test
    public void testEelinkDecode() throws Exception {

        var decoder = decoder("eelink");

        verifyPositions(decoder, binary(
                "454c029249a50354679090044671676712004321315f3cf43503fc94d3760c79328a0129000000000a01f9000190330905580d2e046f118a04ec00000000ccc7086c02fe000000000000000000000000000000000000676712004321325f3cf43e03fc94d3760c79328a0129000000000901f9000190330905580d2e046f117b04ec00000000ccc7086d02ff000000000000000000000000000000000000676712004321335f3cf44703fc94d3760c79328a0129000000000901f9000190330905580d2e046f117f04ec00000000ccc7086d02ff000000000000000000000000000000000000676712004321345f3cf45303fc94d3760c79328a0129000000000901f9000190330905580d2e046f119d04ec00000000ccc7086d02ff000000000000000000000000000000000000676712004321355f3cf45c03fc94d3760c79328a0129000000000801f9000190330905580d2e046f11a304ec00000000ccc7086d02ff000000000000000000000000000000000000676712004321365f3cf46603fc94d3760c79328a0129000000000801f9000190330905580d2e046f118804df00000000ccc7086d02ff000000000000000000000000000000000000676712004321375f3cf47103fc94d3760c79328a0129000000000901f9000190330905580d2e046f119704ec00000000ccc7086d02ff000000000000000000000000000000000000676712004321385f3cf47a03fc94d3760c79328a0129000000000901f9000190330905580d2e046f118204ec00000000ccc7086e0300000000000000000000000000000000000000676712004321395f3cf48303fc94d3760c79328a0129000000000901f9000190330905580d2e046f117604df00000000ccc7086e0300000000000000000000000000000000000000"));

        verifyPosition(decoder, binary(
                "6767120056096661d38e0091fbf0aa3a0f8fa08500060051015f09002542e50e7ea6080101f90001304e304e0818390d000000c524c2ae0699102b00000000000115b0040504050000000014000000000000000000000000000002"));

        verifyAttribute(decoder, binary(
                "676714001500035f74a2940201360104591100a7160122250400"),
                Position.KEY_ALARM, Position.ALARM_REMOVING);

        verifyNull(decoder, binary(
                "454C0027E753035254407167747167670100180002035254407167747100200205020500010432000086BD"));

        verifyAttribute(decoder, binary(
                "6767070006000e0077035d"),
                Position.KEY_IGNITION, true);

        verifyAttributes(decoder, binary(
                "676707006502df5c89fde800bc3fa8030302005555045b555555057a5555550b225555550c105c55550d115555550e7e5555550f4555555510017b5555112b5555551f01ed5555208005b0012100005555407ad000004237f5555589000000498a0000aef78b00000000"));

        verifyAttribute(decoder, binary(
                "676712003400e45c5b0ade02012e03702d87064546aa24066a1086018a0000002dc1a0ffffffff0afd074d000000000000000000000000fce0"),
                Position.PREFIX_TEMP + 2, -50.0);

        verifyAttribute(decoder, binary(
                "6767120043000e5c37387c0304e4e1b4f8194fa800160013009408012e03702d8706453c6e5b066f115f05710000001b067f8d248d240313020500000000000000000000000001cc"),
                Position.PREFIX_TEMP + 2, 28.75);

        verifyPosition(decoder, binary(
                "676714002414B05AD43A7D03026B92B10C395499FFD7000000000701CC00002495000014203604067B"));

        verifyPosition(decoder, binary(
                "676780005a000001000000004c61743a4e33312e38333935352c4c6f6e3a5738322e36313334362c436f757273653a302e30302c53706565643a302e30306b6d2f682c4461746554696d653a323031372d31322d30322031313a32393a3433"));

        verifyPosition(decoder, binary(
                "676780005E5788014C754C754C61743A4E32332E3131313734330A4C6F6E3A453131342E3430393233380A436F757273653A302E30300A53706565643A302E31374B4D2F480A446174652054696D653A323031352D30392D31332032303A32313A3230"));

        verifyPosition(decoder, binary(
                "454C0050EAE2035254407167747167671200410021590BD93803026B940D0C3952AD0021000000000501CC0001A53F0170F0AB1305890F11000000000000C2D0001C001600000000000000000000000000000000"));

        verifyNull(decoder, binary(
                "676701000c002603541880486128290120"));

        verifyPosition(decoder, binary(
                "676704001c01a4569ff2dd0517a0f7020b0d9a06011000d8001e005b0004450183"));

        verifyPosition(decoder, binary(
                "676705002200ba569fc3520517a0d8020b0f740f007100d8001e005b0004460101569fd162001f"));

        verifyPosition(decoder, binary(
                "676702002500bb569fc3610517a091020b116000001900d8001e005b00044601001f1170003200000000"));

        verifyPosition(decoder, binary(
                "676704001c00b7569fc3020517a2d7020b08e100000000d8001e005b0004460004"));

        verifyNull(decoder, binary(
                "676701000b001b035418804661834901"));

        verifyAttributes(decoder, binary(
                "6767030004001A0001"));

        verifyPosition(decoder, binary(
                "676702001b03c5538086df0190c1790b3482df0f0157020800013beb00342401"));

    }

    @Test
    public void testTotemDecode() throws Exception {

        var decoder = decoder("totem");

        verifyAttribute(decoder, text(
                "$$0494E2123456789012345|150425223945,113.925525,22.55814,1122334455|38"),
                Position.KEY_DRIVER_UNIQUE_ID, "1122334455");

        verifyPosition(decoder, text(
                "$$0111AA353081090067318|0804400022070722520240400005B364ED5003107300001.700000002245.3919N10231.6952W000001860E"));

        verifyPosition(decoder, text(
                "$$0112E5864606045334223|201112223514,-68.923106,-22.455926,$Cloud,1738,621,730,12100,0,0,255,0,40,40,0,0,255,|13"));

        verifyPosition(decoder, text(
                "$$0113AA862010037348253|588040001901220851494212000000753AE901655121700100000.800000002632.6084S02803.3289E29497E"),
                position("2019-01-22 08:51:49.000", true, -26.54347, 28.05548));

        verifyPosition(decoder, text(
                "$$011602867119025755430|50099800180420045019401400000000000000B8797D110816811201.500002132615.7037S02801.8099E056149"));

        verifyPosition(decoder, text(
                "$$0108AB863835028447675|5004C0001710250234064214059828A058AE121010604000.600000320304.7772N10134.8238E11625B"));

        verifyPosition(decoder, text(
                "$$0108AA863835028447675|5004C0001710250234134114057728A058AE112108305100.600000660304.7787N10134.8719E116458"));

        verifyPosition(decoder, text(
                "$$0112AA864244026065291|180018001409160205244011000027BA0E57063100000001.200000002237.8119N11403.5075E05202D"));

        verifyPosition(decoder, text(
                "$$0116AA864244026065291|18001800140916020524401100000000000027BA0E57063100000001.200000002237.8119N11403.5075E052020"));

        verifyPosition(decoder, text(
                "$$0116AA867119025683137|108000001611020925324112000000000000616027F7001300000099.900000000000.0000N00000.0000E531824"));

        verifyPosition(decoder, text(
                "$$0128AA864244026065291|18001800140916020524401100000000000000000000000027BA0E57063100000001.200000002237.8119N11403.5075E05202D"));

        verifyPosition(decoder, text(
                "$$0128AA867965024919124|10010800160223032415401203270321032103270189000027BA0E4E001800200001.000000002237.7581N11403.5088E000957"),
                position("2016-02-23 03:24:15.000", false, 22.62930, 114.05848));

        verifyPosition(decoder, text(
                "$$0108AA863835024426319|18004000160216160756411100007DCD0000111000000000.800000000316.3519N10228.5086E126522"));

        verifyPosition(decoder, text(
                "$$0128AA867521029231005|1880100015101802314842140000000000000000000000001AB48366093127600000.900000000806.1947N09818.4795E080355"));

        verifyPosition(decoder, text(
                "$$0108AA864244026063437|1A0000001401010101014111000027BA0E57003100000000.000000000000.0000N00000.0000E048156"));

        verifyPosition(decoder, text(
                "$$BE863771024392112|AA$GPRMC,044704.000,A,1439.3334,N,12059.1417,E,0.00,0.00,200815,,,A*67|01.7|00.8|01.4|000000000000|20150820044704|14291265|00000000|4EECBF8B31|0000|0.0000|0002|00000|56E7"),
                position("2015-08-20 04:47:04.000", true, 14.65556, 120.98570));

        verifyPosition(decoder, text(
                "$$AE860990002922822|AA$GPRMC,051002.00,A,0439.26245,N,10108.94448,E,0.023,,140315,,,A*71|02.98|01.95|02.26|000000000000|20150314051003|13841157|105A3B1C|0000|0.0000|0005|5324"),
                position("2015-03-14 05:10:02.000", true, 4.65437, 101.14907));

        verifyPosition(decoder, text(
                "$$AE860990002922822|AA$GPRMC,051002.00,A,0439.26245,N,10108.94448,E,0.023,,140315,,,A*71|02.98|01.95|02.26|000000000000|20150314051003|13841157|105A3B1C|0000|0.0000|0005|5324\r"));

        verifyNull(decoder, text(
                "$$BB862170017856731|AA$GPRMC,000000.00,V,0000.0000,N,00000.0000,E,000.0,000.0,000000,,,A*73|00.0|00.0|00.0|000000001000|20000000000000|13790000|00000000|00000000|00000000|0.0000|0007|8C23"));

        verifyPosition(decoder, text(
                "$$B8862170017856731|AA$GPRMC,171849.00,A,3644.9893,N,01012.9927,E,0.049,51,200813,,,A*73|1.59|0.97|1.25|100000001000|20130820171849|13690000|00000000|019BD508|00000000|0.0000|0026|1B2C"));

        verifyPosition(decoder, text(
                "$$B2359772032984289|AA$GPRMC,104446.000,A,5011.3944,N,01439.6637,E,0.00,,290212,,,A*7D|01.8|00.9|01.5|000000100000|20120229104446|14151221|00050000|046D085E|0000|0.0000|1170|29A7"));

        verifyPosition(decoder, text(
                "$$8B862170017861566|AA180613080657|A|2237.1901|N|11402.1369|E|1.579|178|8.70|100000001000|13811|00000000|253162F5|00000000|0.0000|0014|2B16"),
                position("2013-06-18 08:06:57.000", true, 22.61984, 114.03562));

        verifyPosition(decoder, text(
                "$$72862170017856731|3913090911165280000370000000000000000019BD508A0400000003.400000093644.9817N01012.9944E00506F2E"));

        verifyPosition(decoder, text(
                "$$B0456123|61$GPRMC,114725.00,A,1258.68276,N,07730.60237,E,0.410,,080113,,,A*79|1.44|0.66|1.27|000000000000|20130108114425|03600000|00000000|053C2BFE|0000|0.3325|0063|2005"));

        verifyNull(decoder, text(
                "$$AE359772033395899|AA000000000000000000000000000000000000000000000000000000000000|00.0|00.0|00.0|000000000000|20090215000153|13601435|00000000|00000000|0000|0.0000|0007|2DAA"));

        verifyNull(decoder, text(
                "$$AE359772033395899|AA000000000000000000000000000000000000000000000000000000000000|00.0|00.0|00.0|00000000|20090215001204|14182037|00000000|0012D888|0000|0.0000|0016|5B51"));

        verifyNull(decoder, text(
                "$$AE359772033395899|AA00000000000000000000000000000000000000000000000000000000000|00.0|00.0|00.0|00000000000|20090215001337|14182013|00000000|0012D888|0000|0.0000|0017|346E"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|60$GPRMC,094859.000,A,3648.2229,N,01008.0976,E,0.00,,221211,,,A*79|02.3|01.3|02.0|000000000000|20111222094858|13360808|00000000|00000000|0000|0.0000|0001||A977"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|09$GPRMC,094905.000,A,3648.2229,N,01008.0976,E,0.00,,221211,,,A*71|02.1|01.3|01.7|000000000000|20111222094905|03210533|00000000|00000000|0000|0.0000|0002||FA58"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|AA$GPRMC,093911.000,A,3648.2146,N,01008.0977,E,0.00,,140312,,,A*7E|02.1|01.1|01.8|000000000000|20120314093910|04100057|00000000|0012D887|0000|0.0000|1128||C50E"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|AA$GPRMC,094258.000,A,3648.2146,N,01008.0977,E,0.00,,140312,,,A*7F|02.1|01.1|01.8|000000000000|20120314094257|04120057|00000000|0012D887|0000|0.0000|1136||CA32"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|AA$GPRMC,234603.000,A,3648.2179,N,01008.0962,E,0.00,,030412,,,A*74|01.8|01.0|01.5|000000000000|20120403234603|14251914|00000000|0012D888|0000|0.0000|3674||940B"));

        verifyPosition(decoder, text(
                "$$B3359772032399074|AA$GPRMC,234603.000,A,3648.2179,N,01008.0962,E,0.00,,030412,,,A*74|01.8|01.0|01.5|000000000000|20120403234603|14251914|00000000|0012D888|0000|0.0000|3674|940B"));

        verifyPosition(decoder, text(
                "$$B2356895037578518|AA$GPRMC,173829.000,A,3740.4107,N,02129.9815,E,0.00,,111113,,,A*7B|02.6|01.6|02.1|000000000000|20131111173829|14041251|00000000|002E0DD7|0000|0.0240|6010|8128"));

        verifyPosition(decoder, text(
                "$$B2356895037578518|AA$GPRMC,203823.000,A,3740.3285,N,02129.9295,E,0.00,,111113,,,A*79|01.5|01.0|01.1|000000000000|20131111203823|14041251|00000000|002E0DD7|0000|0.0000|6371|3824"));

    }

    @Test
    public void testMeitrackDecode() throws Exception {

        var decoder = decoder("meitrack");

        verifyPositions(decoder, binary(
                "242466313039352c3836373935313037383031303536342c4343452cfd0b000003005d0117000705010611071914001501fe69641b000808180009c6000a05000b4c0016070019a6011a91054021000502f5a90502037a7296ff04a5ec62310cb2e907000d3f800200030e0c5c0202001705213e9701a0ff4b02fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8280201ff620118000705010611071914001501fe69641b000808180009c7000a05000b4d0016060019a1011a8d054023000602bba9050203637296ff04a7ec62310cb2e907000d418002001c01000000030e0c5c0202001705213e9701a0ff4b02fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8280201ff620118000705010611071914001501fe69641b0008081c0009cd000a05000b4d0016080019a1011a8d0540230006023ba9050203297296ff04a9ec62310cc7e907000d438002001c01000000030e0c5c0202001705213e9701a0ff4b02fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8280201ff2a41440d0a"));

        verifyAttribute(decoder, binary(
                "2424623139352c3836363439363037373538323136342c4343452c000000000100a1001b00050501060e0718140015020a0826000965000a08000b00001600001700001800001992001a5a05408f0205029e1460fe03470f1f0904785ed62f0c4cbc00000d2af20100070e0cf901010031700210cc080000fe311c020732303235303630373132323034315f4348315f6463612e6a706749090403000000000000004b0a010107464444204c54454c0a03010000020000228435fe79020102fe800201022a34310d0a"),
                Position.KEY_IMAGE, "2025-06-07/20250607122041_CH1_dca.jpg");

        verifyAttribute(decoder, binary(
                "2424483136302c3836323039303035303436323733362c4343452c0000000001007e0018000705010609071714001500fe69601b0008080000093e010a0b000b4f001608001993011af40440230006028e03a0ff036bc85d06046b1f582e0cbc1100000dfd8507001c01000000030e0cfe010a00de0419019a06a1fffe731a010554656d7031ac233faf076e645df5913200000000000000004b060101034c54452a39450d0a"),
                "tagTemp", -10.63671875);

        verifyAttribute(decoder, binary(
                "24246D3230312C3836393430393036323730323834332C4343452C000000000100A7002A000D05010626070914001502930194009500960097629D209E63A1640E0824000956000A07000B2A001606001704001901001A0E0B91280092280099EF049C52009F1B004023000C0215B9F2FF035855F506041E0F142D0C01708C010D748AC2001C012000009A000000009BD0623E02A0889FF201A2D61A0000A542020000FEF4A3D50900030E0CFE010A007F466FFC0000000049090401000000000000004B0501010232472A44360D0A"),
                Position.KEY_HOURS, 644515 * 60000L);

        verifyAttribute(decoder, binary(
                "2424593136312c3836323039303035303031363139332c4343452c0000000001007f0017000705010607071714001500fe69601b00070800000971000a13000b19001605001acc0440230006029779570103eb5bcc06041ff0e8290c430100000d780400001c01000000030e0ccc010000922781abb90ca4fffe731e0109746e6873656e736f72ac233f6e219064051b753b00000000000000004b060101034c54452a42380d0a"),
                "tagName", "tnhsensor");

        verifyAttribute(decoder, binary(
                "2424683136342C3836363334343035333039353238322C4343452C000000000100820018000505000600070B14001500080800000900000A00000B00001606001A0000402300FE90000006022E79570103E55CCC0604E1FDB32B0CC32C00000D58EB02001C01000000050E0CCC010000B627BF11000000004B1001010D475052532847534D2039303029FEA50601FFFFFF7FFFFEA80701010258023800FEB20501010000002A41360D0A"),
                "battery2Level", 88);

        verifyPositions(decoder, binary(
                "2424533232312c3836323331313036323737393431362c4343452c000000000100bb001c0006012305000600071f1500fe6961050800000900000a00000b00001aca000702a72c52030340a0c90004f3408b2c0ca80100000d238d08001c01000000fe37000000000a0e0cf00001002700167aa601a0ff1d082abd890a491cd2ff1e0828bd890a491cd2ff1f0842490f526db0caff20083c286d5b082cc9ff21083e286d5b082cc9ff2208ac233fc0d2e0c7ff2308b0411d64d9d5c7ff2408ae233fc0d496c3ff4b150101124c54452845555452414e2d42414e443230292a38420d0a"));

        verifyAttribute(decoder, binary(
                "2424593434312c3836353431333035303839313733372c4343452c00000000030088001800050501061607191400150008080000098e000a05000b0c001608001a0000402300fe9000000602c3fe5ffe03e22a1f0904e6688d2b0cd94002000d5f6f03001c01000000050e0cf901010032700298c80899ff4b16010113464444204c5445284c54452042414e44203329fea50601ffffff7ffffea807024d0000000000feb205010000000083001700050501061607191400150008080000098e000a05000b0c001608001a0000405100fe9000000502c3fe5ffe03e22a1f0904e6688d2b0cd94002000d606f0300050e0cf901010032700298c80899ff4b16010113464444204c5445284c54452042414e44203329fea50601ffffff7ffffea807024d0000000000feb205010000000088001800050501061607151400150008080000098e000a05000b0c001607001a0000402300fe9000000602c3fe5ffe03e22a1f0904f0688d2b0cd94002000d696f03001c01000000050e0cf901010032700298c80897ff4b16010113464444204c5445284c54452042414e44203329fea50601ffffff7ffffea807024d0000000000feb20501000000002a36320d0a"),
                Position.KEY_BATTERY_LEVEL, 77);

        verifyAttribute(decoder, binary(
                "24245b3131342c3836343630363034343939333938372c4343452c0000000001005000130006012305000600070f1b004702060800000900000a00000b0000199d011a00000602d179570103b25ccc0604cf04862b0cc65b01000da4090d001c01000000010e0ccc010000b627be11000000002a41300d0a"),
                Position.KEY_LOCK, true);

        verifyAttribute(decoder, buffer(
                "$$u28,864606044993987,D82,0*D6"),
                Position.KEY_RESULT, "D82,0");

        verifyPositions(decoder, binary(
                "24245B3139312C3836343630363034343939333938372C4343452C010000000200500013000601250500060007111B00470206080000093E000AE7030B0000199E011A850306028D7A570103F35ACC0604F9D06C2B0CB92E00000D3FA40C00250CA2B900010E0CCC010000B6276313000000004B00120006012A0500060007111B00470206080000093E000AE7030B0000199E011A7C0305028D7A570103F35ACC0604F9D06C2B0CB92E00000D3FA40C00010E0CCC010000B6276313000000002A31340D0A"));

        verifyPositions(decoder, binary(
                "24246a3138312c3836343238313034313930383330332c4343452c00000000010093001f000505000600070714001502090800000900000a00000b0000160a001706001904001ad90440230006023279570103305ccc0604f536492b0c510300000d495701001c014000000b0e0ccc010000922781abb90c00002a030034212b03008b082c030053082d03009e082e030034212f030034213003003421310300342149090400000000000000004b07010104574946492a36310d0a"));

        verifyPositions(decoder, binary(
                "2424423233322c3836323039303035303030323831332c4343452c0400000003004400110004050006000700fe6962060800000900000a00000b00001aef044023000602d65fbcfd03173b9c0804cc76ae2a0c14ae1b000d00aa0d001c01000000014b030101003f00100004050006000700fe695f060800000900000a00000b00001aea044016000502d65fbcfd03173b9c0804cf76ae2a0c14ae1b000d03aa0d00014b030101003f00100004050006000700fe695f060800000900000a00000b00001aed044001000502d65fbcfd03173b9c0804d076ae2a0c14ae1b000d04aa0d00014b030101002a30460d0a"));

        verifyAttribute(decoder, buffer(
                "$$F160,861412043027965,AAA,22,45.499458,-82.493581,220718171428,V,0,0,0,0,0.0,0,227940,119812,302|220|D8D6|086E1B2B,0000,0000|0000|0000|0191|0573,,,3,,002134,0,0*FA"),
                Position.KEY_POWER, 13.95);

        verifyPositions(decoder, binary(
                "2424423233392c3836323039303035303030373436352c4343452c0100000003004300130006050006000700140015801b00080800000900000a00000b0000165105198d011a630540160005024c5e910103590bfe0204922153290c6b2501000dd5b50200004300130006050006000700140015011b00080800000900000a00000b0000165005198d011a630540010005024c5e910103590bfe0204932153290c6b2501000dd6b50200004300130006050006000700140015011b00080800000900000a00000b0000165205198d011a630540230005024c5e910103590bfe0204942153290c6b2501000dd7b50200002a43330d0a"));

        verifyPosition(decoder, buffer(
                "$$D149,867047043162018,AAA,35,-1.264865,36.800705,211001105240,A,9,20,41.0,323,1,1697,1,0,000|00||,0000,4.33|12.96|1.92|2.72|2.69,0.000000|0|0.000000,*E1"));

        verifyPositions(decoder, binary(
                "2424413132332c3836313538353034333230303836322c4343452c010000000100590015000305010609071b0b081c000939010a07000b1700199e011a9505921a0099c4089c5500c93e00405a000602a8b114000343f12e0604d18806270c654a2e000da20537009bb8963904010e0c0d020300aa7a0af69e0100002a35340d0a"));

        verifyAttribute(decoder, buffer(
                "$$F153,867144025101013,AAA,35,25.219431,55.279918,200916155923,V,0,25,0,0,0.0,0,249701532,98374503,424|2|101C|A3AE,0800,0000|0000|0000|02D3|0103,00000011,*A0"),
                Position.KEY_INPUT, 8);

        verifyPosition(decoder, buffer(
                "$$O160,863835028611502,AAA,35,7.887840,98.375193,200202020238,A,12,4,0,279,0.6,45,32121,442492,520|3|12DF|015273E2,0000,0000|0000|0000|018D|04F0,00000001,,1,0000*F3"));

        verifyNull(decoder, binary(
                "242441313038362c3836343530373033313231393937342c4430302c3138303232343037323631345f4331453130395f4e31553144312e6a70672c31342c302cffd8ffdb008400140e0f120f0d14121012171514181e32211e1c1c1e3d2c2e243249404c4b47404645505a736250556d5645466488656d777b8182814e608d978c7d96737e817c011517171e1a1e3b21213b7c5346537c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7cffc000110801e0028003012100021101031101ffdd0004000affc401a20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa0100030101010101010101010000000000000102030405060708090a0b1100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00cca69ac8d06e3348569884db4845021b498a60371494008692980119a8ca7a5342101a5cd5221a0ab312ed1ee68b943e80dce2a467ffd0c806a48e592270f13b230e841a0096eeea7bb09e6c85b667033552800069c2980e14f15422418a916ad099228a95455089505584140993a2d5fb598a7cae72bd8fa536ae892e8e69e2b9d971168a459fffd1ece8a0028a006b534f4a68ce5b9130a89ab444919a61a6c634d34d21894952310d25002514084a4a00ffd2d2349564086929082929805250025140094940c4a4a04251400949408292819fffd3cca31591a098a5c62801a45464531098a69a6210d371400629a6980628eb400c64cd3791c1aa16c491479393563343105424fcc4d007ffd4c3463c0a94500381a5e3b8cd000c99e57f2a8f3835402834e0d4d08914d4aa6ac4c954d4aad4c4c955aa647a6496236ab51b552132e412e383d3f955b0722b09ab32a0c5a2a0d0ffd5ece8a0028a0061a6d5193dc8daa36ab422334c34c634"));

        verifyPositions(decoder, binary(
                "24246b3131342c3836353738393032343134303439352c4343452c0000000001005000130006011f05010607071415001b00060800000949010a0c000b9b0119a1011afe010602e934ce0203fc9aeb0004309f13220cafc503000d97741e001c01000000010e0ce8000300092f2e060000b7ff2a33330d0a"));

        verifyPosition(decoder, buffer(
                "$$^182,864507031245110,AAA,109,13.844553,100.644360,171227173141,A,11,19,0,359,0.8,8,15075,934591,520|4|0643|07D20555,8400,0000|0000|0000|018D|04CB,,,108,0000,,6,0,,,,,10|171227173100*7C"));

        verifyPosition(decoder, buffer(
                "$$S214,864507031219974,AAA,109,13.844643,100.644395,171207021520,A,10,28,0,31,0.8,6,390,421327,520|0|0016|000F2DB0,8400,0000|0000|0000|018D|04C6,,,108,0000,,6,0,,,,,11|171207091500|171207091500|78|3500|000000|000003*12"));

        verifyPositions(decoder, binary(
                "24245f3237382c3836353738393032313434373233322c4343452c5b00000003005000130006012305010608070d15001b0006080000091e010a09000b2e0019a1011af90106025c033300039be60c06044f6678210c6f1806000d48db06001c41000000010e0cf60113002005912b830001ff5000130006012305010608070d15001b0006080000091e010a09000b2e0019a0011af90106025c033300039be60c0604506678210c6f1806000d49db06001c41000000010e0cf60113002005912b830001ff5000130006012305010608070d15001b0006080000091e010a09000b2e0019a1011af90106025c033300039be60c0604516678210c6f1806000d4adb06001c41000000010e0cf60113002005912b830001ff2a37460d0a"));

        verifyPosition(decoder, buffer(
                "$$V177,863835026871173,AAA,35,34.516428,10.470160,170915154043,A,9,12,68,74,0.9,9,1988259,525882,605|2|008C|0007B5A6,0200,0003|0000|0000|01A6|0571,00000001,,3,0000,06FB2E,360,511*74"));

        verifyPosition(decoder, buffer(
                "$$V177,863835026871173,AAA,35,34.516428,10.470160,170915154043,A,9,12,68,74,0.9,9,1988259,525882,605|2|008C|0007B5A6,0200,0003|0000|0000|01A6|0571,00000001,,3,0000,010A92,360,511*74"));

        verifyPosition(decoder, buffer(
                "$$B136,011691002364761,AAA,29,47.055220,28.893193,170914144240,V,0,7,0,0,0,132,129754946,129793197,259|2|02F8|413F,0000,000D|000C||028C|,*9E"));

        verifyNotNull(decoder, buffer(
                "$$F153,863835026880190,AAA,29,25.313160,55.422473,170628150902,V,0,0,0,0,0.0,0,6553,6697,0|0|0000|00000000,0000,0002|0000|0000|018B|0000,,,3,0000,,110,386*22"));

        verifyPosition(decoder, buffer(
                "$$T143,869013024733944,AAA,1,18.459575,-69.947161,170220142912,A,5,15,10,300,1.6,115,3989,187884,370|2|5337|2B2C,0100,0000|0000|0000|0964|0B04,,*C2"));

        verifyPosition(decoder, buffer(
                "$$K157,866771027160687,AAA,3,37.040231,10.042391,160412151656,A,10,11,0,48,0.8,21,1035518,774980,605|2|0010|307B,0400,0000|0000|0000|0A47|03E3,,,1,0000,001206*2C"));

        verifyNull(decoder, buffer(
                "$$D28,353358017784062,D03,OK*F3"));

        verifyPosition(decoder, buffer(
                "$$A158,79007001520234,AAA,35,40.996370,-8.575065,150730184834,A,8,24,0,1,1.3,173,32573389,31405012,268|3|2BC0|250B,2000,|||0A2D|0000,00000001,,50,,,,,,,,,,,,,*4A"),
                position("2015-07-30 18:48:34.000", true, 40.99637, -8.57507));

        verifyPosition(decoder, buffer(
                "$$G145,862106024274815,AAA,35,-1.287125,36.906061,150530054639,A,10,13,12,67,0.8,1621,38359791,42330881,639|2|FB2|2F3,0000,3|0|0|A58|432,,,1,0009,*26"));

        verifyPosition(decoder, buffer(
                "$$I152,013949004569813,AAA,37,54.739468,25.273648,150208173414,A,5,24,0,73,1.5,165,74,3381,246|1|0065|118A,0000,0003|0003|0000|08D4|0002,006380DF,,1,0008*7C"));

        verifyPosition(decoder, buffer(
                "$$E141,863071013799553,AAA,35,-1.264521,36.801128,150307132846,A,11,20,0.2,0,5,1767,84045888,36496633,639|02|100E|844,1234,0018|||025D|00CB,*17"));

        verifyPosition(decoder, buffer(
                "$$m140,013777008931857,AAA,1,54.739580,25.273263,141120144603,V,0,25,0,6,50.0,159,19825,13940,246|1|0065|118A,0100,0000|0000|0000|092A|0001,,*1C"));

        verifyPosition(decoder, buffer(
                "$$X138,862170010187175,AAA,35,-29.960365,-51.655455,130507201625,A,8,9,0,107,0.9,7,169322,126582,724|6|0547|132B,0000,0009|000A||0278|0000,*BE"));

        verifyPosition(decoder, buffer(
                "$$X138,862170010187175,AAA,35,-29.960365,-51.655455,130507201625,A,8,9,0,107,0.9,-7,169322,126582,724|6|0547|132B,0000,0009|000A||0278|0000,*BE"));

        verifyPosition(decoder, buffer(
                "$$]138,012896000475498,AAA,35,-6.138255,106.910545,121205074600,A,5,18,0,0,0,49,3800,24826,510|10|0081|4F4F,0000,0011|0012|0010|0963|0000,,*94"));

        verifyPosition(decoder, buffer(
                "$$d138,012896000475498,AAA,35,-6.138255,106.910545,121205074819,A,7,18,0,0,0,49,3800,24965,510|10|0081|4F4F,0000,000D|0010|0012|0963|0000,,*BF"));

        verifyPosition(decoder, buffer(
                "$$j138,012896000475498,AAA,35,-6.138306,106.910655,121205103708,A,3,11,0,0,1,36,4182,35025,510|10|0081|4F4F,0000,000A|000C|000A|0915|0000,,*BF"));

        verifyPosition(decoder, buffer(
                "$$m139,012896005334567,AAA,35,-33.866423,151.190060,121208020649,A,7,27,0,32,4,13,6150,49517,505|2|0B67|5A6C,0000,0000|0000|0000|0977|0000,,*F1"));

        verifyPosition(decoder, buffer(
                "$$A141,012896005334567,AAA,35,-33.866543,151.190148,121209081758,A,6,27,0,16,1,48,65551,152784,505|2|0B5F|D9D3,0000,0000|0000|0000|0A39|0000,,*5B"));

        verifyPosition(decoder, buffer(
                "$$_128,861074020109479,AAA,34,22.512618,114.057065,090215000318,V,0,31,0,0,0,0,0,733,302|720|3EE4|BBB5,0000,0006|0006||028C|0000,*E3"));

        verifyPosition(decoder, buffer(
                "$$K146,013227004985762,AAA,35,28.618005,-81.246783,131101213828,A,9,22,0,209,1.1,23,80974,1187923,310|260|2A13|634E,0000,0000|0000|0000|09DA|0B34,,*51"));

        verifyPosition(decoder, buffer(
                "$$E150,013777001165479,AAA,35,10.296601,123.872115,140501161505,A,4,22,1,170,1.4,77,39097,393563,515|3|A0CC|ED96,0000,0008|0003|0000|09D5|0000,,,1,0009*1E"));

        verifyPosition(decoder, buffer(
                "$$B140,013777001293701,AAA,35,-7.266760,112.743550,140521095314,A,3,22,0,275,2.7,45,1984,8059,510|1|3504|EBFE,0000,0000|0000|0000|0914|0002,,*F9\r\n"));

        verifyPosition(decoder, buffer(
                "$$J163,123123123123123,AFF,0004,35,58.588926,16.180473,140928192856,A,10,27,0,161,1.2,19,1648894,435695,240|24|88B9|E435,0000,|||0A22|0000,00000001,,50,,,,,,,,,,,,,*70\r\n"));

        verifyPositions(decoder, binary(
                "24245838362c3336393830303031343039303032312c4343432c020134000100000023381f91ffe354b806c5e3121b0009130000000000000000d33801007cbf0200fe0101000435feeb02000500a3010000000000002a62650d0a"),
                position("2014-05-24 04:59:49.000", false, -7.26650, 112.74365));

        verifyPositions(decoder, binary(
                "2424473937302c3336393830303031333436303637342c4343432c020134005b000000010ce304035db9e000ec6f591a000013000000000c001801edb70200c96d0100e60001004838576501000300a101c20400000000010ce304035db9e000ee6f591a000013000000000c001801edb70200ca6d0100e60001004838576501000300a101c20400000000010ce304035db9e000ef6f591a000013000000000c001801edb70200cc6d0100e60001004838576501000300a101c20400000000020ce304035db9e000f76f591a000016000000000c001801edb70200d36d0100e60001004838576502000300a101bf04000000000a0ce304035db9e000f76f591a000016000000000c001801edb70200d46d0100e60001004838576500000300a101bf0400000000020ce304035db9e000fb6f591a000016000000000c001801edb70200d86d0100e60001004838576502000300a101760400000000180ce304035db9e000fc6f591a0000120000000000008c00edb70200d96d0100e60001004838576502000300a10176040000000019b1e2040323b9e0000b70591a0105150600bb0012002901edb70200e76d0100e60001004838576502000300a2017005000000002023e304031fb9e0001070591a010615070027010d001601fcb70200ec6d0100e60001004838576502000300a201800500000000201fe3040302b9e0001170591a010615090019010d001501feb70200ed6d0100e60001004838576502000300a2018005000000002018e30403dcb8e0001270591a0106150b0011010d00150100b80200ee6d0100e60001004838576502000300a2018005000000002036e3040345b8e0001570591a0107150b002d010b0013010ab80200f16d0100e60001004838576502000300a2018005000000002053e3040326b8e0001670591a0107150d0041010b0013010eb80200f26d0100e60001004838576502000300a2018005000000002070e3040310b8e0001770591a0107150e004f010b00130111b80200f36d0100e60001004838576502000300a2018005000000002095e3040306b8e0001870591a0107150d005a010b00140115b80200f46d0100e60001004838576502000300a20180050000000020b3e3040305b8e0001970591a0107150b0060010b00140118b80200f56d0100e60001004838576502000300a20180050000000020cfe3040308b8e0001a70591a0107150b0066010b0014011bb80200f66d0100e60001004838576502000300a20183050000000020eee304030cb8e0001b70591a0106170b0004000d0014011eb80200f76d0100e60001004838576502000300a2018305000000002a62350d0a"));

        verifyNull(decoder, buffer(
                "$$z27,861451040910625,AAC,1*D3"));

    }

    @Test
    public void testJt600Decode() throws Exception {

        var decoder = decoder("jt600");

        // WLNET peripheral with temperature (binary text frame)
        verifyPosition(decoder, binary(
                "28383035343230313937332c312c3136382c574c4e45542c352c322c28022508595325169288057327940900002802250859551021220618730140644701011d4300000029"));

        verifyAttribute(decoder, binary(
                "28373530303331333630392C32332C32392C574C4E45542C352C322C22071916311822350966113549495E1F232207191622591190702002FE0117623C0101134700000029"),
                Position.PREFIX_TEMP + 1, 27.5);

        // Text P45 frame
        verifyPosition(decoder, buffer(
                "(2050018634,P45,051124,204046,9.861502,N,83.950336,W,A,1,0,1,1,0012260888,0,0,0)"));

        // Binary long format
        verifyPositions(decoder, binary(
                "2480433966040111002718031919195822424550114158888E15A40000F124080000000000F00F110A24991900000DF0C7"));

        // Text P45 frame (southern hemisphere)
        verifyPosition(decoder, buffer(
                "(8000632862,P45,290322,132412,25.28217,S,57.54683,W,A,0,0,5,0,0000000000,0,0,9,0)"));

        // Binary long format (version 0x19)
        verifyPositions(decoder, binary(
                "2480413009781914003406102107544354193631006213423b00000000006c070000000020e064f91ea0671d00020f0f0f0f0f0f0f0f0f0f07f100ea0f6e"));

        verifyPositions(decoder, binary(
                "2478807035371711003419081920061851380856003256223b000000000000070000000020c0ff965d54de1800000f0f0f0f0f0f0f0f0f0f02d600ea0a21"));

        // Binary short format version 1
        verifyPositions(decoder, binary(
                "2475201509261611002313101503464722331560113555309F00000000002D0500CB206800F064109326381A03"));

        // Binary short format version 0
        verifyPositions(decoder, binary(
                "2475810297431713003401010000030100000000000000000e000000000001000000000020e0641aba1b6f1b00000f0f0f0f0f0f0f0f0f0f000001942803"));

        // Binary short format version 2, batch
        verifyPositions(decoder, binary(
                "2440811188882400A209060908045322564025113242329F0598000001003F0000002D0009060908050322564025113242329F0598000001003F0000002D0009060908051322564025113242329F0598000001003F0000002D0009060908052322564025113242329F0598000001003F0000002D0009060908053322564025113242329F0598000001003F0000002D0009060908054322564025113242329F0598000001003F0000002D001F"));

        verifyPositions(decoder, binary(
                "2475801263981711002713061813333723501622090221558f012f0000002a070000000020c055b88552191f000f0f0f07"));

        // Binary short version 2, single position
        verifyPositions(decoder, binary(
                "24408111888821001B09060908045322564025113242329F0598000001003F0000002D00AB"));

        verifyPositions(decoder, binary(
                "2475609213701711002701010000020200000000000000000e00000000000f000000000020c164cd7b00d516000f0f0f02"));

        // Binary short version 3 (BitBuffer)
        verifyPositions(decoder, binary(
                "24657060730131001b13111710361906538525079524797f000000000000000003f300036c"));

        verifyPositions(decoder, binary(
                "24624090196121001b19071703493631277203074235752f295800005308010000768b0822"));

        // Binary short version 1, verified position
        verifyPositions(decoder, binary(
                "24311021600111001B16021105591022329862114046227B0598095080012327951435161F"),
                position("2011-02-16 05:59:10.000", true, 22.54977, -114.07705));

        verifyPositions(decoder, binary(
                "24312082002911001B171012052831243810120255336425001907190003FD2B91044D1FA0"));

        verifyPositions(decoder, binary(
                "24312082002911001B1710120533052438099702553358450004061E0003EE000000000C00"));

        verifyPositions(decoder, binary(
                "24608111888821001B09060908045322564025113242329F0598000001003F0000002D00AB"));

        // Text W01 frame (lon first)
        verifyPosition(decoder, buffer(
                "(3110312099,W01,11404.6204,E,2232.9961,N,A,040511,063736,4,7,100,4,17,1,1,company)"),
                position("2011-05-04 06:37:36.000", true, 22.54994, 114.07701));

        verifyPosition(decoder, buffer(
                "(3120820029,W01,02553.3555,E,2438.0997,S,A,171012,053339,0,8,20,6,31,5,20,20)"));

        // Text U01 frames
        verifyPosition(decoder, buffer(
                "(3301210003,U01,040812,185302,T,22.564025,N,113.242329,E,5.21,152,9,32%,00000000000011,10133,5173,22,100,1)"));

        verifyPosition(decoder, buffer(
                "(3301210003,U02,040812,185302,T,22.564025,N,113.242329,E,5,152,9,32%,00000000000011,10133,5173,22,100,1)"));

        verifyPosition(decoder, buffer(
                "(3301210003,U03,040812,185302,T,22.564025,N,113.242329,E,5,152,9,32%,00000000000011,10133,5173,22,100,1)"));

        verifyNull(decoder, buffer(
                "(3301210003,U04)"));

        verifyPosition(decoder, buffer(
                "(3301210003,U06,1,040812,185302,T,22.564025,N,113.242329,E,5,152,9,32%,0000000000011,10133,5173,22,100,1,300,100,10)"));

        verifyPosition(decoder, buffer(
                "(3460311327,U01,220916,135251,T,9.552607,N,13.658292,W,0.31,0,9,0%,00001001000000,11012,10,27,0,0,33)"));

        verifyPosition(decoder, buffer(
                "(3460311327,U01,010100,000024,F,0.000000,N,0.000000,E,0.00,0,0,100%,00000001000000,263,1,18,0,0,33)"));

        verifyNull(decoder, buffer(
                "(3460311327,@JT)"));

        verifyPosition(decoder, buffer(
                "(3460311327,U06,11,220916,135643,T,9.552553,N,13.658265,W,0.61,0,9,100%,00000001000000,11012,10,30,0,0,126,0,30)"));

        verifyPosition(decoder, buffer(
                "(3460311327,U06,10,220916,140619,T,9.552495,N,13.658227,W,0.43,0,7,0%,00101001000000,11012,10,0,0,0,126,0,30)"));

        verifyPosition(decoder, buffer(
                "(3330104377,U01,010100,010228,F,00.000000,N,000.000000,E,0,0,0,0%,00001000000000,741,14,22,0,206)"));

        verifyNull(decoder, buffer(
                "(6221107674,2,U09,129,2,A,280513113036,E,02711.0500,S,1721.0876,A,030613171243,E,02756.7618,S,2300.0325,3491,538200,14400,1)"));

        verifyPosition(decoder, buffer(
                "(3301210003,U02,040812,185302,T,00.000000,N,000.000000,E,0,0,0,0%,00000000000011,741,51,22,0,1,05)"));

        verifyPosition(decoder, buffer(
                "(3301210003,U06,4,250916,133207,T,7.011013,N,25.060708,W,27.61,102,10,0%,00101011000000,0,1,0,448,0,126,1,30)"));

        verifyPosition(decoder, buffer(
                "(3551001012,U01,010100,000032,F,0.000000,N,0.000000,E,0.00,0,0,10%,00000000010000,15748,7923,23,0,0,3E)"));

    }

    @Test
    public void testMeiligaoDecode() throws Exception {

        var decoder = decoder("meiligao");

        // MSG_LOGIN (0x5000) → null
        verifyNull(decoder, binary(
                "24240011671440258855405000b24d0d0a"));

        // MSG_POSITION (0x9955) with satellites attribute
        verifyAttribute(decoder, binary(
                "2424008f142180340967ff99553033333233302e3030302c412c313531362e383039392c4e2c31303435322e383835352c452c302e30302c33332c3038313232302c2c2a33367c302e387c3132337c323130307c303030302c303030302c303230452c303241417c30323038303030353038394530304531434638347c31437c31373243353832437c3042a8060d0a"),
                Position.KEY_SATELLITES, 11);

        // MSG_POSITION (0x9955) — verify fix
        verifyPosition(decoder, binary(
                "242400716578902405843299553136323533332e3937382c412c343632332e313137392c4e2c30373932342e323437312c572c303030302c3030302c3139313231372c2c2a31437c31312e357c3139347c303030307c313139322c303030307c3835383030307c30303331343809540d0a"));

        // MSG_ALARM (0x9999)
        verifyPosition(decoder, binary(
                "2424000045124220306FFF9999143135353432322e3030302c562c323233302e373632332c4e2c31313430332e343231382c452c302e30302c302c3036303231312c2c2a31417c302e307c32367c303030307c303030302c303030307c303030303030303030303030303030307c36337c3030303030303030BAC10D0A"));

        // MSG_RFID (0x9966) — verify position
        verifyPosition(decoder, binary(
                "24240076220720151fffff99660012b3ab00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000007c3135303634382c3233303731352c313931352e37323835362c4e2c30373235322e35333034342c456dd00d0a"));

        // MSG_DTC (0x9903)
        verifyAttribute(decoder, binary(
                "242400166578902354329399034331453838d2c40d0a"),
                Position.KEY_DTCS, "C1E88");

        // MSG_OBD_RT (0x9901)
        verifyAttributes(decoder, binary(
                "2424004e66104024211743990131342e312c323638372c39302c32312e35372c342e37312c38352c372e31302c382e31362c342e32372c3130342c302e33342c392e33342c302c312c30b7160d0a"));

        // MSG_OBD_RTA (0x9902)
        verifyAttributes(decoder, binary(
                "2424003266104024211743990232352c322e34302c302e37392c32322c34332c3131392c333735362c37352c3132e4c90d0a"));

        // MSG_RETRANSMISSION (0x6688) — batch
        verifyPositions(decoder, binary(
                "242401d961172036237118668805003039353830332e3030302c412c303330332e333431392c4e2c31303134372e343130342c452c372e30342c3230362e36312c3235313031377c302e307c302e307c303230307c303030302c303030307c3030303031313532325c003039353833332e3030302c412c303330332e323630302c4e2c31303134372e333734342c452c31302e33382c3236332e31342c3235313031377c302e307c302e307c303230307c303030302c303030307c3030303031313734355c003039353930332e3030302c412c303330332e313833382c4e2c31303134372e333735362c452c382e34392c3232332e37372c3235313031377c302e307c302e307c303230307c303030302c303030307c3030303031313839375c003039353933332e3030302c412c303330332e313033312c4e2c31303134372e333435332c452c382e37312c3139312e35302c3235313031377c302e307c302e307c303230307c303030302c303030307c3030303031323130325c003130303030302e3030302c412c303330332e313032332c4e2c31303134372e333338372c452c302e30302c3231332e36392c3235313031377c302e307c302e307c303030307c303030312c303030307c3030303031323131380d110d0a"));

    }

    @Test
    public void testT55Decode() throws Exception {

        var decoder = decoder("t55");

        verifyNull(decoder, text(
                "$PSIWMDID,6Q5161694402B133*2F"));

        verifyAttributes(decoder, text(
                "$GPTXT,NET,1003,A1,-53,232 01*77"));

        verifyPosition(decoder, text(
                "$PUBX,00,130209.00,3650.51159,N,01346.10602,E,785.947,D3,4.1,5.2,0.163,87.43,-0.054,7.0,0.88,1.21,0.88,24,01012,0*6D"));

        verifyPosition(decoder, text(
                "$GNRMC,164414.90,A,4650.5156500,N,01246.1059604,E,0.018,,091123,,,A,V*15"));

        verifyPosition(decoder, text(
                "$GNGGA,164414.90,4650.5156500,N,01246.1059604,E,1,12,0.84,740.729,M,44.804,M,,*4E"));

        verifyPosition(decoder, text(
                "$GNGLL,4650.5156500,N,01246.1059604,E,164414.90,A,A*77"));

        verifyPosition(decoder, text(
                "QZE,868994033976700,35,28062020,113553,22.13673,114.57263,0,22,A,0"));

        verifyNull(decoder, text(
                "$DEVID,0x0103846677F21422*41"));

        verifyAttribute(decoder, text(
                "$GPIOP,01000000,00000000,0.00,0.00,0.00,0.00,4.69,4.24*49"),
                Position.KEY_BATTERY, 4.24);

        verifyPosition(decoder, text(
                "660420156A0066AA$GPRMC,122806.0,A,0119.212178,N,10355.000942,E,0.0,,230119,0.0,E,A*27"));

        verifyNull(decoder, text(
                "$IMEI=355797031609284"));

        verifyNull(decoder, text(
                "086415031C20"));

        verifyNull(decoder, text(
                "358244017671308"));

        verifyPosition(decoder, text(
                "$GPGGA,082350.000,5355.0314,N,01044.1271,E,1,10,0.7,-46.0,M,0.0,M,0.0,0000"));

        verifyPosition(decoder, text(
                "$GPRMC,082350.000,A,5355.0314,N,01044.1271,E,26.20,184.27,080518,,"));

        verifyPosition(decoder, text(
                "$GPRMC,192350.000,V,0000.0000,N,00000.0000,E,,,110318,,*12"));

        verifyPosition(decoder, text(
                "$GPRMC,073446.000,A,1255.5125,N,07738.2948,E,0.00,0.53,080316,D*71,11,865733027593268,1,090,086,123,456,789,987,12345"));

        verifyNotNull(decoder, text(
                "$GPRMC,161223.000,A,2517.0545,S,05739.1788,W,0.0,0.0,011196,,,A*61"));

        verifyPosition(decoder, text(
                "4711/022789000688081/$GPRMC,133343,A,5308.56325,N,1029.12850,E,0.000000,0.000000,290316,,*2A"));

        verifyPosition(decoder, text(
                "$GPRMC,073446.000,A,1255.5125,N,07738.2948,E,0.00,0.53,080316,D*71,11,865733027593268,1,090,086"));

        verifyNull(decoder, text(
                "$PGID,359853000144328*0F"));

        verifyNull(decoder, text(
                "$PCPTI,CradlePoint Test,184453,184453.0,6F*57"));

        verifyNull(decoder, text(
                "IMEI 351467108700000"));

        verifyPosition(decoder, text(
                "$GPRMC,012006,A,4828.10,N,1353.52,E,0.00,0.00,180915,020.3,E*42"));

        verifyPosition(decoder, text(
                "$GPRMC,094907.000,A,6000.5332,N,03020.5192,E,1.17,60.26,091111,,*33"));

        verifyPosition(decoder, text(
                "$GPRMC,115528.000,A,6000.5432,N,03020.4948,E,,,091111,,*06"));

        verifyPosition(decoder, text(
                "$GPRMC,064411.000,A,3717.240078,N,00603.046984,W,0.000,1,010313,,,A*6C"));

        verifyPosition(decoder, text(
                "$GPGGA,000000.0,4337.200755,N,11611.955704,W,1,05,3.5,825.5,M,-11.0,M,,*6F"));

        verifyPosition(decoder, text(
                "$GPGGA,000000,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"));

        verifyPosition(decoder, text(
                "$GPRMA,V,0000.00,S,00000.00,E,,,00.0,000.,11.,E*7"));

        verifyPosition(decoder, text(
                "$TRCCR,20140101001122.333,V,60.0,-100.0,1.1,2.2,3.3,4.4,*00"));

        verifyPosition(decoder, text(
                "$TRCCR,20140111000000.000,A,60.000000,60.000000,0.00,0.00,0.00,50,*3a"));

        verifyPosition(decoder, text(
                "$GPRMC,125735.000,A,6010.34349,N,02445.72838,E,1.0,101.7,050509,6.9,W,A*1F"));

        verifyPosition(decoder, text(
                "$GPGGA,000000.000,6010.34349,N,02445.72838,E,1,05,1.7,0.9,M,35.1,M,,*59"));

        verifyPosition(decoder, text(
                "123456789$GPGGA,000000.000,4610.1676,N,00606.4586,E,0,00,4.3,0.0,M,50.7,M,,0000*59"));

        verifyPosition(decoder, text(
                "123456789$GPRMC,155708.252,V,4610.1676,N,00606.4586,E,000.0,000.0,060214,,,N*76"));

        verifyPosition(decoder, text(
                "990000561287964,$GPRMC,213516.0,A,4337.216791,N,11611.995877,W,0.0,335.4,181214,,,A * 72"));

        verifyPosition(decoder, text(
                "355096030432529$GPGGA,000000.00,3136.599,S,5213.981,W,1,7,2.13,250.00,M,-16.384,M,3550960304325290.0,1"));

        verifyPosition(decoder, text(
                "355096030432529$GPGGA,000000.00,3136.628,S,5213.990,W,1,7,2.13,250.00,M,-16.384,M,0.0,1"));

        decoder = decoder("t55");

        verifyNull(decoder, text(
                "$CONNECT,350030950116536,NETWORK,\"Ben NL\",RO,001,LAC,B02C,CID,521B,V,120.69"));

        verifyPosition(decoder, text(
                "$ID,350030950116536,4,WMCS-34,0,IOP,O,0x01,I,0x00,GPSEX,A,D,050703,T,095121,S,9:5,La,51.89573,N,Lo,5.89860,E,DD,0"));

        verifyPosition(decoder, text(
                "$ID,355826017981316,666,370,ALARM,0x00004000,IOP,M,48,GPSET,V,D,160517,T,161628,S,0:0,La,51.64175,N,Lo,2.21816,W,H,21.47,V,0.0,DD,0"));

    }

    @Test
    public void testGl200Decode() throws Exception {
        var decoder = decoder("gl200");

        // Binary: +EVT position
        verifyPosition(decoder, binary(
                "2b4556542d00fc1fbf0063450102020956325403000343056437f8220700000200000000010000160100f2007eff75a1f0025c6b1a07e1080108241a02680003189c1ac500000000000002100800000000000000000007e1080108241a19e24e4e0d0a"));

        // Binary: +RSP compressed batch
        verifyPositions(decoder, binary(
                "2b5253506400fc1fbf058e450102020956325403000343056438ed2205010e61c6f0ff75a1b4025c6af959803d8ba07ffe17dea03f7e1fdda0007df7dfa03e7e3fd0a0befdf7cea001fddfd8a000fdefdca042fd9fe1a0427d6fe9a0017db7dca0407d47e7a0027d67e5bfc0fd77eca03ffd8fe4bfff7dcfddbffd7dbfdebffdfddfe2bfbe7e0fe1bf7f7e67e2bf7bfed7e2bf7c7f5fe2bffbffc7e3a12880a7daa0b9013fe3a0f801b7dfa0bd81efe1a03f8207e0a03e8217e4a07e023fe9a0bd824feca03a02affda07b02d004a07f02e007a00002d808a041830001a003834fefa00402b7eebf8382a7ebbfc28267e9bf81821fe3bf0181d7e3bf01016fe9bf010117edbf4080c7f6bf7f8087fabfbf805ff9a0fa8097fca23401300aa0b4016019a13a817026a13b81883ea0be81a83fa0bd81b03ba00101d83abfc0039874bfc081b835bfbf819834bfc081982fa01004702ea00502500da002827802bfc0825fffbf41821fffbf4081d801bf3f816802bec180fffebec20077fdbf80002801bfc0000800a000000800e0e0a202804ffba14a8127eea0460107e4a0cc809fd9a0c4004fcda0c2004fcaa080007fbfa0410067bebfc100c7b6a03f8037c1bfbf004fc6a03f0057c6a0410027c5a081001fbaa0418017baa001001fb8a0007fe7bca000ffdfb7a0817fc7b7a040ffbfb3a0407fb7b4a0407fb7b1bf807fbfb3a0007fb7b5a0007fb7b1a0007fb7b2a0007fbfb3a0407fafaba000ffb7ada0017f97aba040ff7faca001ff77b6bf3fff67b3bf007f87bea082ff47b4bfc27f17c1bfffff3fc2bebdff9fcabe3effbfe0bf3cff47e9a03c002ff0a1740097e9a1f8813fe1a12f01f7fca0fa028ff8a07f02a7fea041829007a00302bff8bf810287f2a0080257e1a0050207dbbfc481cfd3a044819fcda043015fc3a043810fc2a0c680a7b2a0448027b0a0857fa7aea0c37f67a2a0017ee7a7a0407f0f9fa000ff079fa03ffeffa1a03ffeffa2a07fff17a0a03fff1fa0a03fff2fa2a03fff3fa0a07fff47a2a0007f579fa03fff4f9da03fff679ca0007f679ea000ff4f9ca07fff5f9ca0007f579cbfc07f5f9fbf407f6fa6bf807f6fabbfc07f7fadbf807f87b2a0407f87b0a0407f77aba000ff77afbfc07f77aea03fff7fada07fff7faca000ff7fada0007f77abbfc0ff77b0a000ff7faea042ff2faba0037ee7ada0437e57a1a0037e27abbfc1fdf7bcbf827defc5bf01fe0fcebf017e3fd5bfc17e2fdca0ff7e27d7a13cfe27cba0bd7e0fa7a07d7e6fb9a07d7eb7b3a07efedfaea03ffef7afa0c07eefa7a07f7f07a3a03fff0fa3a03f7f27a2a03f7f37a4bfff7f6fa3bfff7f5fa3a07eff979fa03f7fbfa2a03f7fdfa1bfbf8017a5bfbf0037adbeff004fb8bfbf004fc1beff804fcbbf7f8047d5bf408027debf408007e8a03e804fdebf7e8027f2bf00ffefffa0400017efa0418017f1a041002feca0410017edbf007fbffea0007fdff6a1018027e4bf81ffc7f6a1008017dca0c10087bea0018097baa083ffc7cda0837fafc7a102ff0fc8a0c27effb9a0c2fe87c1a143fbf78ea07f7dcfc0a0bd7dffb3a03d7e47b1a03ffe77afa07ffe77ada0007e77aebfc07e77afbfc07e8faea03ffea7afbfbf7ecfb4bfbcfeffbbbf7bff8fb8bf7d0027bebffb809fc7bffa00ffc9bf78813fd9a03d8197e1a03b81d7e6a07e01efdda00081bfdda00101bfdba03f81a7dfbfc001afdbbfbd81a7f1bfbe0187eebf80814feea0028127e7a081813fe2a0010147e6a03e8147f4a0408167eca040817fe7a0018157e4bfc8011fdaa08b002fd1a009ffa7d5a009fe57f6a04b7df7f4a0097e07eca0027df7edbf807e2feca000fe3feca07ffe37f0a0407e0ff7a040fde7f0a0007de7eea000fdcff4a001fddffaa000fdcff8a0027dcffda07f7dbff3a03f7dc7f402680003189c355300000109000002120700000000000000000007e1080108290019e63b5c0d0a"));

        // Binary: +RSP standard (null — no fix)
        verifyNotNull(decoder, binary(
                "2B5253500300FC1FFF0064450102020867623130302D446F642F442105007018217345005F010100000001100045073C4D4101DB86BD07E106130B2B0F0460000018770013000000030000000106020F2300002714301107E106130B2B1003424EFB0D0A"));

        // Binary: +RSP standard positions
        verifyPosition(decoder, binary(
                "2b5253500700fc1fbf005d4501020209563254030003430564377e42071001000000000000007eff75a151025c6a8107e10801081a2a02680003189c1ac500000000000002100700000000000000000007e1080108241019e17ebe0d0a"));

        // Binary: +INF information
        verifyAttributes(decoder, binary(
                "2b494e4601fd7f0076676231303000000045010202090104020500004100054007e107150b061d0000003f010e02580000000000d0312a1013648935103226313921591f1200000000000302680003189c1ac3001b02680003189c1ac4000d02680003189c1ac5001207e107150b0d3704f658060d0a"));

        // Binary: +ACK null
        verifyNull(decoder, binary(
                "2b41434b017f244501010108676231303000000000ffff07e1070b03112d054dfe030d0a"));

        // Text: FRI batch (count=1, unwrapped to single position)
        verifyPosition(decoder, buffer(
                "+RESP:GTFRI,DF0200,868487004353181,cv100,14051,10,1,0,0.0,0,264.1,114.015515,22.537178,20210608064328,0460,0001,25F8,061A7D02,,0.0,,,,100,21,,,,20210608144354,32DB$"));

        // Text: RTL single position
        verifyPosition(decoder, buffer(
                "+RESP:GTRTL,DF0200,868487004353181,cv100,,00,1,0,0.0,0,102.2,114.015295,22.537250,20210608063942,0460,0001,25F8,061A7D02,,0.0,20210608143939,32CF$"));

        // Text: IGL
        verifyPosition(decoder, buffer(
                "+RESP:GTIGL,DF0200,868487004353181,cv100,,00,1,1,0.0,0,264.8,114.015502,22.537327,20210608064027,0460,0001,25F8,061A7D02,,0.0,20210608144025,32D1$"));

        // Text: SOS alarm
        verifyAttribute(decoder, buffer(
                "+RESP:GTSOS,DF0200,868487004358800,cv100,,00,1,1,0.0,0,138.0,114.015465,22.537372,20210714115224,0460,0001,25F8,061A7D02,,,20210714195224,20210714195224,03A6$"),
                Position.KEY_ALARM, Position.ALARM_SOS);

        // Text: ERI with extended fields
        verifyPosition(decoder, buffer(
                "+RESP:GTERI,4F0D06,865585041396684,,00000100,12665,10,1,1,0.0,342,38.6,49.846792,40.426182,20260220064016,0400,0010,0F6E,08D7,00,0.0,,,,100,110000,,0,20260220064016,91C9$"));

        // Text: IGN with CV200 model
        verifyPosition(decoder, buffer(
                "+RESP:GTIGN,BD0221,861778061565387,CV200,,0,140944,0,0.0,358,17.6,-2.223488,51.882342,20251222020123,0234,0015,6093,000F830E,,,5810.9,20251222070035,20251222070035,C60E$"));

        // Text: INF information attributes
        verifyAttribute(decoder, buffer(
                "+RESP:GTINF,C20113,869653060997976,,1A,89464278206103756482,19,0,11,12295,13985,4.25,0,0,,,20241104220934,0,0,,00,00,,,20241104162009,06D6$"),
                "power2", 13.985);

        // Text: CAN bus
        verifyAttribute(decoder, buffer(
                "+BUFF:GTCAN,8020050402,867488060267845,,00,1,E00FFFFF,YS2K4X20001928588,1,H149381,4236.08,0,0,58,,P94.80,,0,529.00,0.03,0.33,0.77,8688,0008,0042,00,00,001FFFFF,P100.00,5571,,,0,0,,,20,7,0,0.36,0.00,0.00,0,E E05653940B000003,,C*********,,4054MTX,0000,,,1,0.0,101,698.5,-3.647673,40.481997,20241213113715,0214,0003,04D2,B801,00,20241213113715,1A47$"),
                "adBlueLevel", 100.0);

        // Text: GTHBD heartbeat ACK (returns null)
        verifyNull(decoder, buffer(
                "+ACK:GTHBD,450300,860201067200001,,20200101000000,1234$"));

    }

    @Test
    public void testTk103Decode() throws Exception {
        var decoder = decoder("tk103");

        // BS51 BMS
        verifyAttributes(decoder, text(
                "(007030201454BS5190:02150000753001DC,91:0EE8060EDC0A01DC,92:42014201DC0A01DC,93:00010127000037C8,94:0E01000002000000,95:020EE10EE20EE800030EE40EE00EE700040EDD0EE40EE400050EDC0EDF0EE400,96:0142000000000000,97:0000000000000000,98:0000000000000000)"));

        // BS50 battery temperature
        verifyAttribute(decoder, text(
                "(352602014867BS500064FF0EF10FF10FF00FF20FF30FF20FF20FF40FF20FF40FF40FF20FF30FF20F0000000000000000000000000000000000000000000000001663000000010004000000000000000002444444420000000000A00FA000000000000000200000000315E2000000)"),
                "batteryTemp2", 26);

        // BZ00 network (cell towers)
        verifyAttributes(decoder, text(
                "(027046434858BZ00,{460,0,20949,58711}\n{460,0,20494,54003}\n{460,0,20951,19569}\n,01000000)"));

        // BP05 with cell data (sends ack and decodes cells)
        verifyAttributes(decoder, text(
                "(027045009305BP05355227045009305,{413,2,30073,16724}\n{413,2,30073,16730}\n{413,2,30073,49860}\n,01000000)"));

        // DW3B position (alternative mode, comma-separated device ID)
        verifyPosition(decoder, text(
                "(868822040452227,DW3B,150421,A,4154.51607N,45.78950E,0.050,103142,0.000,595.200,7,0)"));

        // BR00 standard position (12-char device ID, no comma)
        verifyPosition(decoder, text(
                "(086375304593BR00210119A2220.0160N11335.4073E0000014000309.84001000293L0000015FP23BS27F)"));

        // BV00 VIN
        verifyAttribute(decoder, text(
                "(027023361470BV005J6RW2H53HL066029)"),
                Position.KEY_VIN, "5J6RW2H53HL066029");

        // BQ81 alarm
        verifyAttribute(decoder, text(
                "(044027395704BQ81,ALARM,1,164,151101A2238.5237N11349.4571E0.7031241010.0000,00000000)"),
                Position.KEY_ALARM, Position.ALARM_OVERSPEED);

        // BP00 handshake → null
        verifyNull(decoder, text(
                "(027044702512BP00027044702512HSO01A4)"));

        // ZC11 movement alarm (alternative mode)
        verifyPosition(decoder, text(
                "(864768011069660,ZC11,250517,V,0000.0000N,00000.0000E,000.0,114725,000.0,0.00,11)"));

        // ZC17 removing alarm (alternative mode)
        verifyPosition(decoder, text(
                "(864768011069660,ZC17,250517,A,3211.7118N,03452.8086E,0.68,115525,208.19,64.50,9)"));

        // DW5B LBS+WiFi
        verifyPosition(decoder, text(
                "(358511020000026,DW5B,310,6,29876,30393,0,041217,102211)"));

        // ZC20 battery status
        verifyAttributes(decoder, text(
                "(013632651491,ZC20,040613,040137,6,42,112,0)"));

        // ZC03 command result
        verifyAttribute(decoder, text(
                "(864768010869060,ZC03,050117,154745,$Notice: Device version: 1.0$)"),
                Position.KEY_RESULT, "Notice: Device version: 1.0");

        // BR00 with temperature
        verifyPosition(decoder, text(
                "(094625928000BR00190213A1156.0431S07705.6145W000.000023521.40000000007L00000314T113)"));

    }

    @Test
    public void testCastelDecode() throws Exception {
        var decoder = decoder("castel");

        // SC GPS (version 5, 0x4001)
        verifyPosition(decoder, binary(
                "40405f000536303331353030303335313200000000000000004001040212102a2f72b29302a0af8512b40787018e000000000043e4ae000000007ca0f7224d5049503632305f56312e312e30004d5049502d3632302056322e300072140d0a"));

        // SC alarm (version 4, 0x4007, count=1)
        verifyPosition(decoder, binary(
                "40406000043231334550323031363030303538350000000000400708000000831c1c58f4fb1c58ae94040012220000f604000058000000200007630168000084c401040b10090c3532db3f07f07f7520090100000101010e00000000c7920d0a"));

        // SC comprehensive with battery (0x401F)
        verifyAttribute(decoder, binary(
                "40406600043233344c53413230323430303030303400000000401f00581d0000929bda67f5c0db6700000000280000000000000000000082000000002ec71500002c0114031907112f4c9fd604887472180000b004ff10000200ff00210002003c00d5210d0a"),
                Position.KEY_BATTERY, 0.6);

        // SC DTCS commercial (0x400B)
        verifyAttribute(decoder, binary(
                "40404700043231335732303139303033353400000000000000400BBE723A5DEF723A5D000000000000000000000000000000000000030100011900030001012603030145C90D0A"),
                Position.KEY_DTCS, "P0326");

        // SC DTCS passenger (0x4006, version 3)
        verifyAttribute(decoder, binary(
                "40404500033231334c323031373030303432320000000000004006e1ad205bf1ad205b48510f000000000050160000000000020400053f007c000083040001511346160d0a"),
                Position.KEY_DTCS, "P1351");

        // SC query response (0xA002)
        verifyAttribute(decoder, binary(
                "40403a00043231334744503230313830323133343300000000a002000001000001012011004d414c43333831434d4b4d353637313438c8fc0d0a"),
                Position.KEY_RESULT, "MALC381CMKM567148");

        // SC heartbeat → null (0x1003)
        verifyNull(decoder, binary(
                "40401F00043130303131313235323939383700000000000000100303320D0A"));

        // SC login (0x1001, version 4) — no historical skip, count=1 → position
        verifyPosition(decoder, binary(
                "40407F000431303031313132353239393837000000000000001001C1F06952FDF069529C91110000000000698300000C0000000000036401014C00030001190A0D04121A1480D60488C5721800000000AF4944445F3231364730325F532056312E322E31004944445F3231364730325F482056312E322E31000000DF640D0A"));

        // CC heartbeat (0x4206, version 0x0c)
        verifyPosition(decoder, binary(
                "404044000c3631313135303030303935360000000000000000420600011e0a0f0b1312864fcd08c07a13030100640acf000004000a000000000000007ba083a66ad80d0a"));

        // CC login (0x4001, version 0x0c)
        verifyPosition(decoder, binary(
                "40405c000c363131313530303030393536000000000000000040011c0a0f0e362dca53cd0860831303000000000300000000ff000000000000007ba083a650542d3639305f56312e312e320050542d3639302056312e32008a020d0a"));

        // MPIP 0x2001
        verifyPosition(decoder, binary(
                "24243f00676e6768656636313031313132393030313734002001840d000000dfb556020602100b36298256cf0956ebac020000990c7f0000000001b4830d0a"));

    }

    @Test
    public void testAplicomDecode() throws Exception {
        var decoder = decoder("aplicom");

        // C protocol → null (unknown)
        verifyNull(decoder, binary(
                "434946010A0100075253F85F0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000FEEA0000FEE90000F0030000F0040000FEF10000FEF20000FEF50000FEFC0000FEC10000FEE500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"));

        // D protocol with event 0x77 (EVENT_DATA=119) and GPS
        verifyPosition(decoder, binary(
                "44c3014645e8e91b66002300a21f0b01f056d3e62856d3e626031f845f00c6ee440800000000000000000017bd1cb30000"));

        // D protocol with custom selector, GPS
        verifyPosition(decoder, binary(
                "44c3014645e8e91b66001f00221f0b01f456ba1e0d56ba1e0b031f842200c6ef550c000000000017bd1cb30004"));

        // D protocol with EB event data (event 188)
        verifyAttributes(decoder, binary(
                "44c3014645e8ecff3c00ea03ffffbc00f457d68a6557d68a6303bb55fa018843da1100009881000000000000000000000000000000000000000000000000000000000000000000000000000000ff0056007600000000000000014542016d0001010095070e14014645e8ecff3c57d68a6403bb55fa018843dac0010d14ff050102030405060708090a0b0c0d0e0f10112a01010730343f3c1ff5cf01020700007d007d23010103022f2e01060c67452301efcdab8967452301010b10000000007d007d007d7dffffffffffff010a2400000000000000010000000000000000ffffffffffffffff00010001ffff00000000ffff010c02fec6"));

        // E protocol (tachograph)
        verifyAttributes(decoder, binary(
                "45c20144f667c06ff9005d0161ef17000104596da2dc4b10c0c01d99020d6c04004cba7a010d44463030303235333731363238303030000000000000000000000000000000000000000000000000000001010d44463030303235333731363238303030000000000000031c"));

        // E protocol with VIN and cards
        verifyAttributes(decoder, binary(
                "45c20144f667c07287008c01ffff6d01000059368963d0340a0616207d7f4b10c0c019e6000039d7000039d71f40ffff5001574442393036363035533132333435363700014142432d33343520202020202000011231303331373139343039303030303031000000000000000000000000000000000000000000000000000001011231303331373139343039303030303031000000000000005a"));

        // F protocol (CAN/J1939 engine data)
        verifyAttributes(decoder, binary(
                "46c30144f667c1711f00340007ff750058b8f77701037c06b8000000330033000000000b760000425e0100640000b3a90185d5823155000131070204000219641004"));

        // H protocol (histograms)
        verifyAttributes(decoder, binary(
                "48C1014143B4493145004900203F6D014B5557C20003000015060110FF00C800000000000000003D01141E283C500100260404010200000000000000000000000000C8000000000000010200110019001E0064019003E8"));

    }

    @Test
    public void testTeltonikaDecode() throws Exception {
        var decoder = decoder("teltonika");

        // Ping
        verifyNull(decoder, binary("ff"));

        // IMEI identification → null, establishes session
        verifyNull(decoder, binary(
                "000F313233343536373839303132333435"));

        // CODEC_8, 1 position
        verifyPosition(decoder, binary(
                "000000000000003508010000014f8e016420002141bbaf0f4e96a7fffa0000120000000602010047030242669c92000002c7000000009100000000000100002df3"));

        // CODEC_8, 1 position, multi-IO (comment previously said "4 positions" but count byte = 01)
        verifyPosition(decoder, binary(
                "00000000000000A708010000016269E7D9A8000A5A0F0A1CBF8F3300880046120000001C0801014F005100550F740073007801790103430000440000426F980B540000000056000045275700000047580000022659000000005D0000000068000003D07100007355870000000288000000008A000045270669584C5241534834336A30304731363538326B3600FFFFFF0000008155412055414430308230303039383236368330303000000000000100008396"));

        // CODEC_8_EXT, tag1Voltage
        verifyAttribute(decoder, binary(
                "00000000000000768e010000018fdc4b27cb015b3e33ceefa529030009013f0e0000022400010000000000000000000102240049010f0001c60106babbf36300550202806d0f0001ca01063456555565690202806b0f0001d10106467975425450020280690b0001c90106fa54ba8d00550b0001cf0106cabbf36300550100005455"),
                "tag1Voltage", 3090);

        // CODEC_8_EXT, tag1Battery
        verifyAttribute(decoder, binary(
                "000000000000004b8e010000018368952793000f0e54fc209ab05800b300b40e00002a4f0001000000000000000000012a4f001e011c0001a40110eb47706aa38255aa96f21a154e2d00550d01000e020bd6010000823f"),
                "tag1Battery", 3030);

        // CODEC_16, 2 positions
        verifyPositions(decoder, binary(
                "000000000000009D10020000013feb55ff74000f0ea850209a690000AE00B90B00000000070A050001000002000003000004000120000200180000004601290200C700000000004C0000000001003E00000000000000000000015B198C7498000F0DBC502095872F00AE00B90B00000000070A050001000002000003000004000120000200180000004601290200C700000000004C0000000001003E000000000000000002000009A5"));

        // CODEC_13, GTSL driver identification
        verifyAttribute(decoder, binary(
                "00000000000000240d01060000001c642b3ad14754534c7c367c317c307c31323734393838347c317c0d0a010000ec11"),
                Position.KEY_DRIVER_UNIQUE_ID, "12749884");

        // CODEC_12, binary result
        verifyAttributes(decoder, binary(
                "00000000000000100C010600000008010300010015D5C5010000D988"));

        // CODEC_12, axle weight data
        verifyAttributes(decoder, binary(
                "000000000000004f0c01060000004755555555777730362e343b30342e323b30302e303b30302e303b30302e303b30302e303b30302e303b30302e303b30312e333b30302e303b31302e373b30302e303b5353530d0a010000e371"));

        // CODEC_GH3000, 4 positions (mix of last-location and GPS positions)
        verifyPositions(decoder, false, binary(
                "0000000000000055070450aa14320201f00150aa17f3031f42332a4c4193d68c008d00020901f00150aa1b6a031f423383f54193624f009d00000a01f00150aa1c230fc01a0000552b040164f400dd00f0010143100c0105000000050400006846"));
    }

    @Test
    public void testAtrackDecode() throws Exception {
        var decoder = decoder("atrack");

        // Keep-alive: echoed back, no position
        verifyNull(decoder, binary(
                "fe0200014104d8f196820001"));

        // Binary, 1 position at epoch/zero coordinates
        verifyPositions(decoder, binary(
                "40503835003300070001441c3d8ed1c400000000000000c9000000c900000000000000000000020000000003de0100000000000007d007d000"),
                position("1970-01-01 00:00:00.000", true, 0.00000, 0.00000));

        // Binary, 2 positions
        verifyPositions(decoder, binary(
                "4050993f005c000200014104d8f19682525666c252568c3c52568c63ffc8338402698885000002000009cf03de0100000000000007d007d000525666c252568c5a52568c63ffc8338402698885000002000009cf03de0100000000000007d007d000"));

        // Binary with FULS fuel string in message field
        verifyPositions(decoder, binary(
                "4050b5ed004a2523000310c83713f8c05a88b43e5a88b43f5a88b43f021e0ad5fffdc0a800f3020003059100080000000000000007d007d046554c533a463d3230393120743d3137204e3d3039303100"));

        // Text @P with Unix timestamp
        verifyPositions(decoder, buffer(
                "@P,93D1,419,0,357766091026083,1557178589,1557178590,1557178590,-121899637,37406241,338,230,2809,8,0,0,0,0,,2000,2000,\r\n"));

        // Text @P with yyyyMMddHHmmss date format and trailing fields
        verifyPositions(decoder, buffer(
                "@P,3A34,146,41431,353816057242284,20180622015809,20180622015809,20180622015809,9720689,4014230,61,2,0,20,1,0,0,0,0,2000,2000,12160,42,624,002,20009,20014,\r\n"));

        // $INFO attributes
        verifyAttributes(decoder, buffer(
                "$INFO=358683066267395,AX7,Rev.0.61 Build.1624,358683066267395,466924131626767,89886920041316267670,144,0,9,1,12,1,0\r\n"));
    }

    @Test
    public void testGlobalstarDecode() throws Exception {
        var decoder = httpDecoder("globalstar");

        // JSON with empty coordinates uses lastLocation
        String jsonPayload = "{\"version\": 1,\"entity\": \"Mayacom\",\"resource\": \"GPS Device\",\"entry\": {\"devices\": [{\"gpsCoordinate\": {\"latitude\": \"\",\"longitude\": \"\"},\"deviceIdentify\": {\"esn\": \"0-99990\",\"unixTime\": 1034268516},\"deviceInfo\": {\"messageType\": 3,\"batteryStatus\": \"Good\",\"gpsDataValid\": \"Invalid\",\"missedEventInput1\": \"No\",\"missedEventInput2\": \"No\",\"gpsFailCounter\": 1,\"diagnosticMessage\": \"Replace Battery\",\"numberOfTransmissions\": 3,\"gpsSystemOk\": \"OK\",\"transmitterOk\": \"OK\",\"schedulerSubsystemOk\": \"OK\",\"minTransmissionInterval\": \"300 seconds\",\"maxTransmissionInterval\": \"600 seconds\",\"meanGpsSearchTime\": \"79 seconds\",\"failedGpsAttempts\": 0,\"transmissionsSinceLastDiagnostic\": 9,\"input1Triggered\": \"Yes\",\"input1State\": \"Closed\",\"input2Triggered\": \"No\",\"input2State\": \"Open\",\"messageSubType\": \"Location Message\",\"vibrationTriggered\": \"Yes\",\"unitInVibration\": \"No\",\"gps3DFix\": \"No\",\"deviceAtRest\": \"Yes\",\"highGpsFixAccuracy\": \"No\"}}]}}";
        verifyNotNull(decoder, request(HttpMethod.POST, "/", new io.netty.handler.codec.http.ReadOnlyHttpHeaders(true, "Content-Type", "application/json"), buffer(jsonPayload)));

        // XML: payload with all-0xFF data (non-Atlas path: returns position, not null)
        verifyPosition(decoder, request(HttpMethod.POST, "/", buffer(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n",
                "<stuMessages xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"http://cody.glpconnect.com/XSD/StuMessage_Rev1_0_1.xsd\" timeStamp=\"16/09/2020 01:33:07 GMT\" messageID=\"567207180ae9100687cef8c81978371a\">\n",
                "<stuMessage>\n",
                "<esn>0-4325340</esn>\n",
                "<unixTime>1600220003</unixTime>\n",
                "<gps>N</gps>\n",
                "<payload length=\"9\" source=\"pc\" encoding=\"hex\">0x63FFFF1BB4FFFFFFFF</payload>\n",
                "</stuMessage>\n",
                "</stuMessages>")));

        // XML with valid coordinates
        verifyPosition(decoder, request(HttpMethod.POST, "/", buffer(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<stuMessages xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"http://cody.glpconnect.com/XSD/StuMessage_Rev1_0_1.xsd\" timeStamp=\"25/03/2020 03:02:32 GMT\" messageID=\"300421a0fd2a100585bdde409d6f601a\">",
                "<stuMessage>",
                "<esn>0-2682225</esn>",
                "<unixTime>1585105370</unixTime>",
                "<gps>N</gps>",
                "<payload length=\"9\" source=\"pc\" encoding=\"hex\">0x00C583EACD37210A00</payload>",
                "</stuMessage>",
                "</stuMessages>")));

        verifyPosition(decoder, request(HttpMethod.POST, "/", buffer(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<stuMessages xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"http://cody.glpconnect.com/XSD/StuMessage_Rev1_0_1.xsd\" timeStamp=\"17/02/2019 21:56:15 GMT\" messageID=\"2a471778dda31005850dc52bb93ae81a\">",
                "<stuMessage>",
                "<esn>0-2654816</esn>",
                "<unixTime>1550440592</unixTime>",
                "<gps>N</gps>",
                "<payload length=\"9\" source=\"pc\" encoding=\"hex\">0x00337BA619B7250A00</payload>",
                "</stuMessage>",
                "</stuMessages>")));
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
