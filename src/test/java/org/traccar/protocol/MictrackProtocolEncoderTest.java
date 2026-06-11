package org.traccar.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MictrackProtocolEncoderTest extends ProtocolTest {

    private MictrackProtocolEncoder encoder;
    private final Date time = Date.from(
            LocalDateTime.of(LocalDate.now(), LocalTime.of(1, 2, 3)).atZone(ZoneOffset.UTC).toInstant());

    @BeforeEach
    public void before() throws Exception {
        encoder = inject(new MictrackProtocolEncoder(null));
    }

    @Test
    public void testMT700Encode() throws Exception {

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_REBOOT_DEVICE);
        assertEquals("REBOOT", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POSITION_PERIODIC);
        command.set(Command.KEY_FREQUENCY, 60);
        assertEquals("MODE,1,60", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_MODE_DEEP_SLEEP);
        command.set(Command.KEY_FREQUENCY, 3600);
        assertEquals("MODE,3,1", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_MODE_DEEP_SLEEP);
        command.set(Command.KEY_FREQUENCY, 90000);
        assertEquals("MODE,3,24", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_SET_CONNECTION);
        command.set(Command.KEY_SERVER, "traccar.example.com");
        command.set(Command.KEY_PORT, "5191");
        assertEquals("804,traccar.example.com,5191", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_GET_DEVICE_STATUS);
        assertEquals("RCONF,1", encoder.encodeCommand(command));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);
        command.set(Command.KEY_DATA, "LBS,1");
        assertEquals("LBS,1", encoder.encodeCommand(command));

    }

    @Test
    public void testHQEncode() throws Exception {

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);
        assertEquals("*HQ,123456789012345,S20,010203,1,1#", encoder.encodeCommand(command, time));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_RESUME);
        assertEquals("*HQ,123456789012345,S20,010203,0,0#", encoder.encodeCommand(command, time));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ALARM_ARM);
        assertEquals("*HQ,123456789012345,SF,010203,0,0#", encoder.encodeCommand(command, time));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ALARM_DISARM);
        assertEquals("*HQ,123456789012345,CF,010203,1,1#", encoder.encodeCommand(command, time));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POSITION_PERIODIC);
        command.set(Command.KEY_FREQUENCY, 30);
        assertEquals("*HQ,123456789012345,D1,010203,30,1#", encoder.encodeCommand(command, time));

        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_REBOOT_DEVICE);
        assertNull(encoder.encodeCommand(command, time));

    }

}
