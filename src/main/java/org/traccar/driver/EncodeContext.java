package org.traccar.driver;

import org.traccar.model.Command;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Passed as the second argument to a variant's {@code encode} closure.
 * Provides formatting helpers so driver scripts don't need boilerplate.
 *
 * <p>Typical usage inside a driver script:
 * <pre>
 * encode { cmd, ctx ->
 *     switch (cmd.type) {
 *         case TYPE_ENGINE_STOP: return "*HQ,${ctx.deviceId()},S20,${ctx.utcTime()},1,1#"
 *         case TYPE_REBOOT_DEVICE: return "REBOOT"
 *     }
 * }
 * </pre>
 */
public final class EncodeContext {

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter
            .ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    private final DriverProtocolEncoder encoder;
    private final Command command;

    EncodeContext(DriverProtocolEncoder encoder, Command command) {
        this.encoder = encoder;
        this.command = command;
    }

    /** Returns the device's unique ID (IMEI) for the current command. */
    public String deviceId() {
        return encoder.uniqueId(command.getDeviceId());
    }

    /** Returns the current UTC time formatted as {@code HHmmss}. */
    public String utcTime() {
        return UTC_TIME.format(new Date().toInstant());
    }

    /** Returns the frequency attribute from the command, or 0 if absent. */
    public int freq() {
        Object v = command.getAttributes().get(Command.KEY_FREQUENCY);
        return v != null ? ((Number) v).intValue() : 0;
    }

    /** Returns the server attribute from the command. */
    public String server() {
        Object v = command.getAttributes().get(Command.KEY_SERVER);
        return v != null ? v.toString() : "";
    }

    /** Returns the port attribute from the command. */
    public String port() {
        Object v = command.getAttributes().get(Command.KEY_PORT);
        return v != null ? v.toString() : "";
    }

    /** Returns a custom data attribute from the command. */
    public String data() {
        Object v = command.getAttributes().get(Command.KEY_DATA);
        return v != null ? v.toString() : "";
    }

    /** Clamps {@code value} to {@code [min, max]}. */
    public long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
