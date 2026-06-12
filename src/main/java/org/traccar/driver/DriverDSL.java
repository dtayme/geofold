// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;
import groovy.lang.Script;
import org.traccar.helper.Checksum;
import org.traccar.model.Command;
import org.traccar.model.Position;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Base class for all driver scripts. Every .groovy file in the drivers/ directory
 * is compiled with this as its script base class, giving it access to all DSL
 * methods and alarm constants without any imports.
 *
 * <p>A minimal driver script looks like:
 * <pre>
 * protocol("mydevice") {
 *     port 5000
 *     variant("v1") {
 *         matches { msg -> msg.startsWith("*HQ,") }
 *         decode { msg, pos -> ... }
 *         encode { cmd -> ... }
 *     }
 * }
 * </pre>
 */
public abstract class DriverDSL extends Script {

    private DriverDefinition definition;

    // -------------------------------------------------------------------------
    // Alarm constants — re-exported so scripts need no imports
    // -------------------------------------------------------------------------
    public static final String ALARM_GENERAL        = Position.ALARM_GENERAL;
    public static final String ALARM_SOS            = Position.ALARM_SOS;
    public static final String ALARM_VIBRATION      = Position.ALARM_VIBRATION;
    public static final String ALARM_MOVEMENT       = Position.ALARM_MOVEMENT;
    public static final String ALARM_LOW_SPEED      = Position.ALARM_LOW_SPEED;
    public static final String ALARM_OVERSPEED      = Position.ALARM_OVERSPEED;
    public static final String ALARM_FALL_DOWN      = Position.ALARM_FALL_DOWN;
    public static final String ALARM_LOW_POWER      = Position.ALARM_LOW_POWER;
    public static final String ALARM_LOW_BATTERY    = Position.ALARM_LOW_BATTERY;
    public static final String ALARM_FAULT          = Position.ALARM_FAULT;
    public static final String ALARM_POWER_OFF      = Position.ALARM_POWER_OFF;
    public static final String ALARM_POWER_ON       = Position.ALARM_POWER_ON;
    public static final String ALARM_DOOR           = Position.ALARM_DOOR;
    public static final String ALARM_LOCK           = Position.ALARM_LOCK;
    public static final String ALARM_UNLOCK         = Position.ALARM_UNLOCK;
    public static final String ALARM_GEOFENCE       = Position.ALARM_GEOFENCE;
    public static final String ALARM_GEOFENCE_ENTER = Position.ALARM_GEOFENCE_ENTER;
    public static final String ALARM_GEOFENCE_EXIT  = Position.ALARM_GEOFENCE_EXIT;
    public static final String ALARM_GPS_ANTENNA_CUT = Position.ALARM_GPS_ANTENNA_CUT;
    public static final String ALARM_ACCIDENT       = Position.ALARM_ACCIDENT;
    public static final String ALARM_TOW            = Position.ALARM_TOW;
    public static final String ALARM_IDLE           = Position.ALARM_IDLE;
    public static final String ALARM_HIGH_RPM       = Position.ALARM_HIGH_RPM;
    public static final String ALARM_ACCELERATION   = Position.ALARM_ACCELERATION;
    public static final String ALARM_BRAKING        = Position.ALARM_BRAKING;
    public static final String ALARM_CORNERING      = Position.ALARM_CORNERING;
    public static final String ALARM_LANE_CHANGE    = Position.ALARM_LANE_CHANGE;
    public static final String ALARM_FATIGUE_DRIVING = Position.ALARM_FATIGUE_DRIVING;
    public static final String ALARM_POWER_CUT      = Position.ALARM_POWER_CUT;
    public static final String ALARM_POWER_RESTORED = Position.ALARM_POWER_RESTORED;
    public static final String ALARM_JAMMING        = Position.ALARM_JAMMING;
    public static final String ALARM_TEMPERATURE    = Position.ALARM_TEMPERATURE;
    public static final String ALARM_PARKING        = Position.ALARM_PARKING;
    public static final String ALARM_BONNET         = Position.ALARM_BONNET;
    public static final String ALARM_FOOT_BRAKE     = Position.ALARM_FOOT_BRAKE;
    public static final String ALARM_FUEL_LEAK      = Position.ALARM_FUEL_LEAK;
    public static final String ALARM_TAMPERING      = Position.ALARM_TAMPERING;
    public static final String ALARM_REMOVING       = Position.ALARM_REMOVING;

    // -------------------------------------------------------------------------
    // Command type constants — re-exported so scripts need no imports
    // -------------------------------------------------------------------------
    public static final String TYPE_CUSTOM             = Command.TYPE_CUSTOM;
    public static final String TYPE_IDENTIFICATION     = Command.TYPE_IDENTIFICATION;
    public static final String TYPE_POSITION_SINGLE    = Command.TYPE_POSITION_SINGLE;
    public static final String TYPE_POSITION_PERIODIC  = Command.TYPE_POSITION_PERIODIC;
    public static final String TYPE_POSITION_STOP      = Command.TYPE_POSITION_STOP;
    public static final String TYPE_ENGINE_STOP        = Command.TYPE_ENGINE_STOP;
    public static final String TYPE_ENGINE_RESUME      = Command.TYPE_ENGINE_RESUME;
    public static final String TYPE_ALARM_ARM          = Command.TYPE_ALARM_ARM;
    public static final String TYPE_ALARM_DISARM       = Command.TYPE_ALARM_DISARM;
    public static final String TYPE_ALARM_DISMISS      = Command.TYPE_ALARM_DISMISS;
    public static final String TYPE_SET_TIMEZONE       = Command.TYPE_SET_TIMEZONE;
    public static final String TYPE_REQUEST_PHOTO      = Command.TYPE_REQUEST_PHOTO;
    public static final String TYPE_POWER_OFF          = Command.TYPE_POWER_OFF;
    public static final String TYPE_REBOOT_DEVICE      = Command.TYPE_REBOOT_DEVICE;
    public static final String TYPE_FACTORY_RESET      = Command.TYPE_FACTORY_RESET;
    public static final String TYPE_SEND_SMS           = Command.TYPE_SEND_SMS;
    public static final String TYPE_SEND_USSD          = Command.TYPE_SEND_USSD;
    public static final String TYPE_SOS_NUMBER         = Command.TYPE_SOS_NUMBER;
    public static final String TYPE_SILENCE_TIME       = Command.TYPE_SILENCE_TIME;
    public static final String TYPE_SET_PHONEBOOK      = Command.TYPE_SET_PHONEBOOK;
    public static final String TYPE_MESSAGE            = Command.TYPE_MESSAGE;
    public static final String TYPE_VOICE_MESSAGE      = Command.TYPE_VOICE_MESSAGE;
    public static final String TYPE_OUTPUT_CONTROL     = Command.TYPE_OUTPUT_CONTROL;
    public static final String TYPE_VOICE_MONITORING   = Command.TYPE_VOICE_MONITORING;
    public static final String TYPE_SET_AGPS           = Command.TYPE_SET_AGPS;
    public static final String TYPE_SET_INDICATOR      = Command.TYPE_SET_INDICATOR;
    public static final String TYPE_CONFIGURATION      = Command.TYPE_CONFIGURATION;
    public static final String TYPE_GET_VERSION        = Command.TYPE_GET_VERSION;
    public static final String TYPE_FIRMWARE_UPDATE    = Command.TYPE_FIRMWARE_UPDATE;
    public static final String TYPE_SET_CONNECTION     = Command.TYPE_SET_CONNECTION;
    public static final String TYPE_SET_ODOMETER       = Command.TYPE_SET_ODOMETER;
    public static final String TYPE_GET_MODEM_STATUS   = Command.TYPE_GET_MODEM_STATUS;
    public static final String TYPE_GET_DEVICE_STATUS  = Command.TYPE_GET_DEVICE_STATUS;
    public static final String TYPE_SET_SPEED_LIMIT    = Command.TYPE_SET_SPEED_LIMIT;
    public static final String TYPE_MODE_POWER_SAVING  = Command.TYPE_MODE_POWER_SAVING;
    public static final String TYPE_MODE_DEEP_SLEEP    = Command.TYPE_MODE_DEEP_SLEEP;
    public static final String TYPE_VIDEO_START        = Command.TYPE_VIDEO_START;
    public static final String TYPE_VIDEO_STOP         = Command.TYPE_VIDEO_STOP;
    public static final String TYPE_ALARM_GEOFENCE     = Command.TYPE_ALARM_GEOFENCE;
    public static final String TYPE_ALARM_BATTERY      = Command.TYPE_ALARM_BATTERY;
    public static final String TYPE_ALARM_SOS          = Command.TYPE_ALARM_SOS;
    public static final String TYPE_ALARM_REMOVE       = Command.TYPE_ALARM_REMOVE;
    public static final String TYPE_ALARM_CLOCK        = Command.TYPE_ALARM_CLOCK;
    public static final String TYPE_ALARM_SPEED        = Command.TYPE_ALARM_SPEED;
    public static final String TYPE_ALARM_FALL         = Command.TYPE_ALARM_FALL;
    public static final String TYPE_ALARM_VIBRATION    = Command.TYPE_ALARM_VIBRATION;

    // -------------------------------------------------------------------------
    // Binary helper — used inside decode { buf, ctx -> } closures
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if bit {@code bit} is set in {@code value} (bit 0 = LSB).
     * Re-exported from {@link BufReader} so binary decode closures need no import.
     *
     * <p>Example: {@code if (checkBit(flags, 2)) pos.valid = true}
     */
    public static boolean checkBit(int value, int bit) {
        return BufReader.checkBit(value, bit);
    }

    /** Builds a binary packet using a {@link BufWriter} and returns its bytes. */
    public static byte[] bytes(Closure<?> body) {
        BufWriter writer = new BufWriter();
        body.setDelegate(writer);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        if (body.getMaximumNumberOfParameters() == 0) {
            body.call();
        } else {
            body.call(writer);
        }
        return writer.toByteArray();
    }

    /** Returns a scripted frame result that keeps the consumed raw bytes. */
    public static FrameResult frameRaw(int length) {
        return FrameResult.raw(length);
    }

    /** Returns a scripted frame result with replacement payload bytes. */
    public static FrameResult frameResult(int length, byte[] payload) {
        return FrameResult.transformed(length, payload);
    }

    public static int xor(byte[] bytes) {
        return Checksum.xor(ByteBuffer.wrap(bytes)) & 0xff;
    }

    public static int xor(String value) {
        return Checksum.xor(value) & 0xff;
    }

    public static String nmea(String value) {
        return Checksum.nmea(value);
    }

    public static int sum(byte[] bytes) {
        return Checksum.modulo256(ByteBuffer.wrap(bytes));
    }

    public static int sum(String value) {
        return Checksum.modulo256(ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII)));
    }

    public static int crc16X25(byte[] bytes) {
        return Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(bytes));
    }

    public static int crc16Modbus(byte[] bytes) {
        return Checksum.crc16(Checksum.CRC16_MODBUS, ByteBuffer.wrap(bytes));
    }

    public static int crc16CcittFalse(byte[] bytes) {
        return Checksum.crc16(Checksum.CRC16_CCITT_FALSE, ByteBuffer.wrap(bytes));
    }

    public static long crc32(byte[] bytes) {
        return Checksum.crc32(Checksum.CRC32_STANDARD, ByteBuffer.wrap(bytes)) & 0xffffffffL;
    }

    // -------------------------------------------------------------------------
    // FrameSpec factory helpers — used inside variant { frame ... } blocks
    // -------------------------------------------------------------------------

    /** Text: scan for {@code terminator} bytes (e.g. {@code readUntil("##")}). */
    public static FrameSpec readUntil(String terminator) {
        return FrameSpec.readUntil(terminator);
    }

    /** Text: scan for {@code terminator} bytes and include them in the frame passed to decode. */
    public static FrameSpec readUntilKeep(String terminator) {
        return FrameSpec.readUntilKeep(terminator);
    }

    /** Text: scan for newline, strip trailing CR. */
    public static FrameSpec readLine() {
        return FrameSpec.readLine();
    }

    /**
     * Binary: always read exactly {@code size} bytes per frame.
     *
     * <p>Example: {@code frame 0x78 as byte, readFixed(32)}
     */
    public static FrameSpec readFixed(int size) {
        return FrameSpec.readFixed(size);
    }

    /**
     * Binary: read one of several fixed frame sizes.
     *
     * <p>Example: {@code frame 0x24 as byte, readFixedAny(32, 45)}
     */
    public static FrameSpec readFixedAny(int... sizes) {
        return FrameSpec.readFixedAny(sizes);
    }

    /**
     * Binary: read a length field at {@code offset} bytes in, {@code length}
     * bytes wide (1, 2, or 4). Total frame =
     * {@code offset + length + fieldValue}.
     *
     * <p>Example: {@code frame 0x7e as byte, readLengthField(3, 2)}
     */
    public static FrameSpec readLengthField(int offset, int length) {
        return FrameSpec.readLengthField(offset, length);
    }

    /**
     * Binary: like {@link #readLengthField(int, int)} but adds
     * {@code adjustment} extra bytes after the field value.
     * Total frame = {@code offset + length + fieldValue + adjustment}.
     *
     * <p>Example — 2-byte header, 2-byte length, 1-byte checksum:
     * {@code frame 0x78 as byte, readLengthField(2, 2, 1)}
     */
    public static FrameSpec readLengthField(int offset, int length, int adjustment) {
        return FrameSpec.readLengthField(offset, length, adjustment);
    }

    public static FrameSpec readLengthFieldLE(int offset, int length) {
        return FrameSpec.readLengthFieldLE(offset, length);
    }

    public static FrameSpec readLengthFieldLE(int offset, int length, int adjustment) {
        return FrameSpec.readLengthFieldLE(offset, length, adjustment);
    }

    public static FrameSpec readEscaped(byte delimiter, byte escape, Map<?, ?> replacements) {
        return FrameSpec.readEscaped(delimiter, escape, replacements);
    }

    public static FrameSpec readEscaped(char delimiter, char escape, Map<?, ?> replacements) {
        return FrameSpec.readEscaped((byte) delimiter, (byte) escape, replacements);
    }

    public static FrameSpec scriptedFrame(Closure<?> body) {
        return FrameSpec.readScripted(body);
    }

    // -------------------------------------------------------------------------
    // DSL entry point
    // -------------------------------------------------------------------------

    /**
     * Top-level DSL method. Called once per driver script:
     * <pre>protocol("mictrack") { ... }</pre>
     */
    public void protocol(String name, Closure<?> body) {
        DriverDefinition def = new DriverDefinition(name);
        ProtocolBuilder builder = new ProtocolBuilder(def);
        body.setDelegate(builder);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.call();
        this.definition = def;
    }

    /**
     * Returns the {@link DriverDefinition} built by running this script.
     * Called by {@link DriverRegistry} after {@code script.run()}.
     */
    public DriverDefinition getDefinition() {
        return definition;
    }
}
