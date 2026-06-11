package org.traccar.driver;

import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Device;
import org.traccar.session.cache.CacheManager;

/**
 * Per-device attribute accessor passed to driver closures.
 * Wraps {@link CacheManager} so decode/encode closures can look up the
 * device password, model, and custom attributes without direct framework access.
 *
 * <p>Typical usage in a decode closure:
 * <pre>
 * decode { msg, ctx ->
 *     def session = ctx.session(imei)
 *     if (!session) return null
 *     def attrs = ctx.deviceAttrs(session)
 *     def pwd = attrs.password('00000000')
 *     // ...
 * }
 * </pre>
 *
 * <p>Typical usage in an encode closure:
 * <pre>
 * encode { cmd, ctx ->
 *     def pwd = ctx.devicePassword('00000000')
 *     // ...
 * }
 * </pre>
 */
public final class DeviceAttrs {

    private final CacheManager cacheManager;
    private final long deviceId;
    private final String protocol;

    DeviceAttrs(CacheManager cacheManager, long deviceId, String protocol) {
        this.cacheManager = cacheManager;
        this.deviceId = deviceId;
        this.protocol = protocol;
    }

    /**
     * Returns the device password from the {@code password} device attribute,
     * falling back to a protocol-level config entry, then to {@code defaultValue}.
     * Mirrors {@link AttributeUtil#getDevicePassword}.
     */
    public String password(String defaultValue) {
        return AttributeUtil.getDevicePassword(cacheManager, deviceId, protocol, defaultValue);
    }

    /** Returns the device's model string (e.g. {@code "MT700"}), or {@code null}. */
    public String model() {
        Device device = cacheManager.getObject(Device.class, deviceId);
        return device != null ? device.getModel() : null;
    }

    /**
     * Returns a raw device attribute by key, or {@code null} if not set.
     * Does not walk the group/server hierarchy — use this for driver-specific
     * per-device settings stored directly on the device.
     */
    public String get(String key) {
        return get(key, null);
    }

    /**
     * Returns a raw device attribute by key, or {@code defaultValue} if not set.
     */
    public String get(String key, String defaultValue) {
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device != null) {
            Object value = device.getAttributes().get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return defaultValue;
    }
}
