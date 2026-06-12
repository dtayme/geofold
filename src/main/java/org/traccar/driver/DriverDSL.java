// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;
import groovy.lang.Script;
import org.traccar.model.Command;
import org.traccar.model.Position;

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
    public static final String TYPE_POSITION_SINGLE    = Command.TYPE_POSITION_SINGLE;
    public static final String TYPE_POSITION_PERIODIC  = Command.TYPE_POSITION_PERIODIC;
    public static final String TYPE_ENGINE_STOP        = Command.TYPE_ENGINE_STOP;
    public static final String TYPE_ENGINE_RESUME      = Command.TYPE_ENGINE_RESUME;
    public static final String TYPE_ALARM_ARM          = Command.TYPE_ALARM_ARM;
    public static final String TYPE_ALARM_DISARM       = Command.TYPE_ALARM_DISARM;
    public static final String TYPE_REBOOT_DEVICE      = Command.TYPE_REBOOT_DEVICE;
    public static final String TYPE_MODE_DEEP_SLEEP    = Command.TYPE_MODE_DEEP_SLEEP;
    public static final String TYPE_SET_CONNECTION     = Command.TYPE_SET_CONNECTION;
    public static final String TYPE_GET_DEVICE_STATUS  = Command.TYPE_GET_DEVICE_STATUS;
    public static final String TYPE_POWER_OFF          = Command.TYPE_POWER_OFF;
    public static final String TYPE_OUTPUT_CONTROL     = Command.TYPE_OUTPUT_CONTROL;
    public static final String TYPE_IDENTIFICATION     = Command.TYPE_IDENTIFICATION;

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

    // -------------------------------------------------------------------------
    // FrameSpec factory helpers — used inside variant { frame ... } blocks
    // -------------------------------------------------------------------------

    /** Text: scan for {@code terminator} bytes (e.g. {@code readUntil("##")}). */
    public static FrameSpec readUntil(String terminator) {
        return FrameSpec.readUntil(terminator);
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
