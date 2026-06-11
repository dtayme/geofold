package org.traccar.driver;

import groovy.lang.Closure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One format variant within a driver (e.g. "hq" or "mt700" inside mictrack).
 * All closures are Groovy closures set by the DSL.
 */
public final class VariantDefinition {

    private final String name;
    private FrameSpec frameSpec;
    private Byte frameByteHint;     // first byte that selects this variant's frame spec (null = default/fallback)
    private Integer maxFrameLength;
    private Closure<Boolean> matchClosure;
    private Closure<String> modelClosure;
    private Closure<Object> decodeClosure;
    private Closure<Object> encodeClosure;

    // ordered map: event string -> either a String alarm constant or a Closure<String> for model-aware mapping
    private final Map<String, Object> alarmMap = new LinkedHashMap<>();

    public VariantDefinition(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public FrameSpec getFrameSpec() {
        return frameSpec;
    }

    public void setFrameSpec(FrameSpec frameSpec) {
        this.frameSpec = frameSpec;
    }

    public Byte getFrameByteHint() {
        return frameByteHint;
    }

    public void setFrameByteHint(Byte frameByteHint) {
        this.frameByteHint = frameByteHint;
    }

    public Integer getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(Integer maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    public Closure<Boolean> getMatchClosure() {
        return matchClosure;
    }

    public void setMatchClosure(Closure<Boolean> matchClosure) {
        this.matchClosure = matchClosure;
    }

    public Closure<String> getModelClosure() {
        return modelClosure;
    }

    public void setModelClosure(Closure<String> modelClosure) {
        this.modelClosure = modelClosure;
    }

    public Closure<Object> getDecodeClosure() {
        return decodeClosure;
    }

    public void setDecodeClosure(Closure<Object> decodeClosure) {
        this.decodeClosure = decodeClosure;
    }

    public Closure<Object> getEncodeClosure() {
        return encodeClosure;
    }

    public void setEncodeClosure(Closure<Object> encodeClosure) {
        this.encodeClosure = encodeClosure;
    }

    public Map<String, Object> getAlarmMap() {
        return alarmMap;
    }

    /**
     * Returns {@code true} if this variant uses a binary framing mode and
     * expects a {@link BufReader} rather than a {@code String} in its decode closure.
     */
    public boolean isBinary() {
        return frameSpec != null && frameSpec.isBinary();
    }

    /** Returns true if this variant handles the given raw message string. */
    public boolean matches(String message) {
        if (matchClosure == null) {
            return false;
        }
        Boolean result = matchClosure.call(message);
        return Boolean.TRUE.equals(result);
    }

    /** Extracts the device model string from the message (e.g. "MT700", "MT600"). */
    public String extractModel(String message) {
        if (modelClosure == null) {
            return null;
        }
        return modelClosure.call(message);
    }

    /**
     * Resolves an event string to a Traccar alarm constant, optionally
     * using the device model for model-aware mappings.
     */
    @SuppressWarnings("unchecked")
    public String resolveAlarm(String event, String model) {
        Object mapping = alarmMap.get(event);
        if (mapping == null) {
            return null;
        }
        if (mapping instanceof String) {
            return (String) mapping;
        }
        if (mapping instanceof Closure) {
            return (String) ((Closure<String>) mapping).call(model);
        }
        return null;
    }
}
