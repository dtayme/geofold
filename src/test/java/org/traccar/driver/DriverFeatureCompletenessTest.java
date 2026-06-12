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
