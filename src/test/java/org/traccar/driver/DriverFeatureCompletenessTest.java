// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.traccar.helper.Checksum;
import org.traccar.model.Command;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DriverFeatureCompletenessTest {

    private DriverDefinition loadDriver(String name) throws Exception {
        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(DriverDSL.class.getName());

        GroovyShell shell = new GroovyShell(
                Thread.currentThread().getContextClassLoader(), new Binding(), compilerConfig);
        File scriptFile = new File("drivers", name + ".groovy");
        if (!scriptFile.isFile()) {
            scriptFile = new File(new File("archived-protocols/undocumented", name), name + ".groovy");
        }
        DriverDSL script = (DriverDSL) shell.parse(scriptFile);
        script.run();
        return script.getDefinition();
    }

    private VariantDefinition variant(DriverDefinition driver, String name) {
        return driver.getVariants().stream()
                .filter(variant -> variant.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Command command(String type) {
        Command command = new Command();
        command.setType(type);
        command.setDeviceId(1);
        return command;
    }

    @Test
    public void testCommandDeclarationsCoverDocumentedEncoders() throws Exception {
        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_POSITION_STOP,
                Command.TYPE_SET_SPEED_LIMIT,
                Command.TYPE_SET_ODOMETER,
                Command.TYPE_OUTPUT_CONTROL,
                Command.TYPE_MODE_POWER_SAVING,
                Command.TYPE_GET_DEVICE_STATUS), loadDriver("cartrack").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_REBOOT_DEVICE), loadDriver("laipac").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME), loadDriver("enfora").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_POSITION_STOP,
                Command.TYPE_SET_TIMEZONE), loadDriver("cityeasy").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_REBOOT_DEVICE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_MODE_DEEP_SLEEP,
                Command.TYPE_SET_CONNECTION,
                Command.TYPE_GET_DEVICE_STATUS,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_ALARM_ARM,
                Command.TYPE_ALARM_DISARM), loadDriver("mictrack").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_POSITION_PERIODIC), loadDriver("pretrace").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_CUSTOM,
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_SET_ODOMETER,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_ALARM_ARM,
                Command.TYPE_ALARM_DISARM,
                Command.TYPE_ALARM_REMOVE), loadDriver("svias").getSupportedCommands());

        assertEquals(Set.of(
                Command.TYPE_GET_DEVICE_STATUS,
                Command.TYPE_GET_MODEM_STATUS,
                Command.TYPE_REBOOT_DEVICE,
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_GET_VERSION,
                Command.TYPE_IDENTIFICATION), loadDriver("wondex").getSupportedCommands());
    }

    @Test
    public void testMigratedCommandEncoders() throws Exception {
        FakeEncodeContext ctx = new FakeEncodeContext();

        DriverDefinition svias = loadDriver("svias");
        assertEquals("AT+STR=1*", variant(svias, "main").getEncodeClosure().call(
                command(Command.TYPE_POSITION_SINGLE), ctx));
        assertEquals("AT+ODT=12345*", String.valueOf(variant(svias, "main").getEncodeClosure().call(
                commandWithData(Command.TYPE_SET_ODOMETER, "12345"), new FakeEncodeContext("12345"))));
        assertEquals("AT+OUT=1,1*", variant(svias, "main").getEncodeClosure().call(
                command(Command.TYPE_ENGINE_STOP), ctx));
        assertEquals("AT+PNC=600*", variant(svias, "main").getEncodeClosure().call(
                command(Command.TYPE_ALARM_REMOVE), ctx));

        DriverDefinition wondex = loadDriver("wondex");
        assertEquals("$WP+REBOOT=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_REBOOT_DEVICE), ctx)));
        assertEquals("$WP+TEST=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_GET_DEVICE_STATUS), ctx)));
        assertEquals("$WP+GSMINFO=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_GET_MODEM_STATUS), ctx)));
        assertEquals("$WP+IMEI=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_IDENTIFICATION), ctx)));
        assertEquals("$WP+GETLOCATION=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_POSITION_SINGLE), ctx)));
        assertEquals("$WP+VER=0000", String.valueOf(variant(wondex, "text").getEncodeClosure().call(
                command(Command.TYPE_GET_VERSION), ctx)));
    }

    @Test
    public void testExistingCommandEncodersAreDeclaredAndCallable() throws Exception {
        FakeEncodeContext ctx = new FakeEncodeContext();

        DriverDefinition pretrace = loadDriver("pretrace");
        Command periodic = command(Command.TYPE_POSITION_PERIODIC);
        periodic.set(Command.KEY_FREQUENCY, 60);
        String content = ctx.deviceId() + "D22160,60,,";
        assertEquals(String.format("(%s^%02X)", content, Checksum.xor(content)),
                variant(pretrace, "main").getEncodeClosure().call(periodic, ctx));

        DriverDefinition mictrack = loadDriver("mictrack");
        assertEquals("804,example.com,5055", String.valueOf(variant(mictrack, "mt700").getEncodeClosure().call(
                commandWithConnection(Command.TYPE_SET_CONNECTION, "example.com", "5055"),
                new FakeEncodeContext("", "example.com", "5055", 60))));
        assertEquals("RCONF,1", variant(mictrack, "mt700").getEncodeClosure().call(
                command(Command.TYPE_GET_DEVICE_STATUS), ctx));

        DriverDefinition cartrack = loadDriver("cartrack");
        assertEquals("@@12345678901234&A4101##", String.valueOf(variant(cartrack, "main").getEncodeClosure().call(
                command(Command.TYPE_POSITION_SINGLE), ctx)));
        assertEquals("@@12345678901234&A41020060##", String.valueOf(variant(cartrack, "main").getEncodeClosure().call(
                command(Command.TYPE_POSITION_PERIODIC), ctx)));
        assertEquals("@@12345678901234&A430700003039##", String.valueOf(variant(cartrack, "main").getEncodeClosure().call(
                commandWithData(Command.TYPE_SET_ODOMETER, "12345"), new FakeEncodeContext("12345"))));

        DriverDefinition enfora = loadDriver("enfora");
        assertArrayEquals(new byte[] {
                0x00, 0x10, 0x00, 0x00, 0x04, 0x00,
                0x41, 0x54, 0x24, 0x49, 0x4f, 0x47, 0x50, 0x33, 0x3d, 0x31},
                (byte[]) variant(enfora, "main").getEncodeClosure().call(
                        command(Command.TYPE_ENGINE_STOP), ctx));
        assertArrayEquals(new byte[] {
                0x00, 0x10, 0x00, 0x00, 0x04, 0x00,
                0x41, 0x54, 0x24, 0x49, 0x4f, 0x47, 0x50, 0x33, 0x3d, 0x30},
                (byte[]) variant(enfora, "main").getEncodeClosure().call(
                        command(Command.TYPE_ENGINE_RESUME), ctx));
        assertArrayEquals(new byte[] {
                0x00, 0x0e, 0x00, 0x00, 0x04, 0x00,
                0x41, 0x54, 0x2b, 0x54, 0x45, 0x53, 0x54, 0x3f},
                (byte[]) variant(enfora, "main").getEncodeClosure().call(
                        commandWithData(Command.TYPE_CUSTOM, "AT+TEST?"), new FakeEncodeContext("AT+TEST?")));

        DriverDefinition cityeasy = loadDriver("cityeasy");
        assertArrayEquals(cityeasyFrame(0x0004, new byte[0]),
                (byte[]) variant(cityeasy, "main").getEncodeClosure().call(
                        command(Command.TYPE_POSITION_SINGLE), ctx));
        assertArrayEquals(cityeasyFrame(0x0005, new byte[] {0x00, 0x3c}),
                (byte[]) variant(cityeasy, "main").getEncodeClosure().call(
                        command(Command.TYPE_POSITION_PERIODIC), ctx));
        assertArrayEquals(cityeasyFrame(0x0005, new byte[] {0x00, 0x00}),
                (byte[]) variant(cityeasy, "main").getEncodeClosure().call(
                        command(Command.TYPE_POSITION_STOP), ctx));
        assertArrayEquals(cityeasyFrame(0x0008, new byte[] {0x00, 0x00, 0x00}),
                (byte[]) variant(cityeasy, "main").getEncodeClosure().call(
                        commandWithTimezone("GMT"), ctx));
        assertArrayEquals(cityeasyFrame(0x0008, new byte[] {0x00, 0x01, 0x68}),
                (byte[]) variant(cityeasy, "main").getEncodeClosure().call(
                        commandWithTimezone("GMT+6"), ctx));
    }

    @Test
    public void testDocumentedFrameTerminators() throws Exception {
        DriverDefinition gl100 = loadDriver("gl100");
        VariantDefinition gl100Position = variant(gl100, "position");
        assertEquals((byte) '+', gl100Position.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, gl100Position.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {0}, gl100Position.getFrameSpec().getTerminator());

        VariantDefinition gl100Heartbeat = variant(gl100, "heartbeat");
        assertEquals((byte) 'A', gl100Heartbeat.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, gl100Heartbeat.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {0}, gl100Heartbeat.getFrameSpec().getTerminator());

        DriverDefinition gotop = loadDriver("gotop");
        VariantDefinition gotopMain = variant(gotop, "main");
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, gotopMain.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {'#'}, gotopMain.getFrameSpec().getTerminator());

        DriverDefinition pt3000 = loadDriver("pt3000");
        VariantDefinition pt3000Main = variant(pt3000, "main");
        assertEquals((byte) '%', pt3000Main.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, pt3000Main.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {'d'}, pt3000Main.getFrameSpec().getTerminator());

        DriverDefinition riti = loadDriver("riti");
        VariantDefinition ritiMain = variant(riti, "main");
        assertEquals((byte) 0x3B, ritiMain.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_LENGTH_FIELD, ritiMain.getFrameSpec().getMode());
        assertEquals(105, ritiMain.getFrameSpec().getLengthFieldOffset());
        assertEquals(2, ritiMain.getFrameSpec().getLengthFieldLength());
        assertEquals(3, ritiMain.getFrameSpec().getLengthAdjustment());
        assertEquals(true, ritiMain.getFrameSpec().isLengthFieldLittleEndian());

        DriverDefinition m2m = loadDriver("m2m");
        VariantDefinition m2mMain = variant(m2m, "main");
        assertEquals(5054, m2m.getDefaultPort());
        assertEquals(FrameSpec.Mode.READ_FIXED, m2mMain.getFrameSpec().getMode());
        assertEquals(23, m2mMain.getFrameSpec().getSize());
        assertEquals(null, m2mMain.getFrameByteHint());

        DriverDefinition enfora = loadDriver("enfora");
        VariantDefinition enforaMain = variant(enfora, "main");
        assertEquals(5008, enfora.getDefaultPort());
        assertEquals(FrameSpec.Mode.READ_LENGTH_FIELD, enforaMain.getFrameSpec().getMode());
        assertEquals(0, enforaMain.getFrameSpec().getLengthFieldOffset());
        assertEquals(2, enforaMain.getFrameSpec().getLengthFieldLength());
        assertEquals(-2, enforaMain.getFrameSpec().getLengthAdjustment());
        assertEquals(null, enforaMain.getFrameByteHint());

        DriverDefinition orion = loadDriver("orion");
        VariantDefinition orionUserlog = variant(orion, "userlog");
        assertEquals(5070, orion.getDefaultPort());
        assertEquals((byte) 0x50, orionUserlog.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_SCRIPTED, orionUserlog.getFrameSpec().getMode());

        DriverDefinition ramac = loadDriver("ramac");
        assertEquals(5251, ramac.getDefaultPort());
        assertEquals(Set.of(DriverTransport.HTTP), ramac.getTransports());
        assertEquals("json", variant(ramac, "json").getName());

        DriverDefinition cityeasy = loadDriver("cityeasy");
        VariantDefinition cityeasyMain = variant(cityeasy, "main");
        assertEquals(5088, cityeasy.getDefaultPort());
        assertEquals((byte) 0x54, cityeasyMain.getFrameByteHint());
        assertEquals(FrameSpec.Mode.READ_LENGTH_FIELD, cityeasyMain.getFrameSpec().getMode());
        assertEquals(2, cityeasyMain.getFrameSpec().getLengthFieldOffset());
        assertEquals(2, cityeasyMain.getFrameSpec().getLengthFieldLength());
        assertEquals(-4, cityeasyMain.getFrameSpec().getLengthAdjustment());

        DriverDefinition v680 = loadDriver("v680");
        VariantDefinition v680Main = variant(v680, "main");
        assertEquals(5016, v680.getDefaultPort());
        assertEquals(Set.of(DriverTransport.TCP, DriverTransport.UDP), v680.getTransports());
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, v680Main.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {'#', '#'}, v680Main.getFrameSpec().getTerminator());

        DriverDefinition stl060 = loadDriver("stl060");
        VariantDefinition stl060Main = variant(stl060, "main");
        assertEquals(5060, stl060.getDefaultPort());
        assertEquals(FrameSpec.Mode.READ_UNTIL_BYTES, stl060Main.getFrameSpec().getMode());
        assertArrayEquals(new byte[] {'#'}, stl060Main.getFrameSpec().getTerminator());
    }

    @Test
    public void testGl100NullTerminatedVariantsMatchExpectedMessages() throws Exception {
        DriverDefinition gl100 = loadDriver("gl100");

        assertEquals("position", gl100.matchVariant(
                "+RESP:GTFRI,123456789012345,1,0,0,0,1.0,2,3,4,5,6,20260612010203,").getName());
        assertEquals("heartbeat", gl100.matchVariant(
                "AT+GTHBD=ABC,123456789012345,20260612010203,0001").getName());
    }

    private Command commandWithData(String type, String data) {
        Command command = command(type);
        command.set(Command.KEY_DATA, data);
        return command;
    }

    private Command commandWithConnection(String type, String server, String port) {
        Command command = command(type);
        command.set(Command.KEY_SERVER, server);
        command.set(Command.KEY_PORT, port);
        return command;
    }

    private Command commandWithTimezone(String timezone) {
        Command command = command(Command.TYPE_SET_TIMEZONE);
        command.set(Command.KEY_TIMEZONE, timezone);
        return command;
    }

    private byte[] cityeasyFrame(int type, byte[] content) {
        byte[] result = new byte[14 + content.length];
        int index = 0;
        result[index++] = 'S';
        result[index++] = 'S';
        int length = result.length;
        result[index++] = (byte) (length >> 8);
        result[index++] = (byte) length;
        result[index++] = (byte) (type >> 8);
        result[index++] = (byte) type;
        System.arraycopy(content, 0, result, index, content.length);
        index += content.length;
        result[index++] = 0;
        result[index++] = 0;
        result[index++] = 0;
        result[index++] = 0x0b;
        int crc = Checksum.crc16(Checksum.CRC16_KERMIT, ByteBuffer.wrap(result, 0, index));
        result[index++] = (byte) (crc >> 8);
        result[index++] = (byte) crc;
        result[index++] = '\r';
        result[index] = '\n';
        return result;
    }

    public static class FakeEncodeContext {

        private final String data;
        private final String server;
        private final String port;
        private final int frequency;

        public FakeEncodeContext() {
            this("", "", "", 60);
        }

        public FakeEncodeContext(String data) {
            this(data, "", "", 60);
        }

        public FakeEncodeContext(String data, String server, String port, int frequency) {
            this.data = data;
            this.server = server;
            this.port = port;
            this.frequency = frequency;
        }

        public String deviceId() {
            return "123456789012345";
        }

        public String utcTime() {
            return "010203";
        }

        public int freq() {
            return frequency;
        }

        public String server() {
            return server;
        }

        public String port() {
            return port;
        }

        public String data() {
            return data;
        }

        public String devicePassword(String defaultPassword) {
            return defaultPassword;
        }

        public long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
