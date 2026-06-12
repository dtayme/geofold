// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;

/**
 * Delegate for the {@code variant(name) { }} block.
 */
public final class VariantBuilder {

    private final VariantDefinition variant;

    public VariantBuilder(VariantDefinition variant) {
        this.variant = variant;
    }

    /** Set the frame spec without a first-byte hint (used as the default/fallback framing). */
    public void frame(FrameSpec spec) {
        variant.setFrameSpec(spec);
    }

    /** Set a scripted binary frame extractor without a first-byte hint. */
    public void frame(Closure<?> closure) {
        variant.setFrameSpec(FrameSpec.readScripted(closure));
    }

    /** Set the frame spec with a first-byte hint (ASCII char) so the frame decoder can dispatch by byte. */
    public void frame(char hint, FrameSpec spec) {
        variant.setFrameByteHint((byte) hint);
        variant.setFrameSpec(spec);
    }

    /** Set a scripted binary frame extractor with an ASCII first-byte hint. */
    public void frame(char hint, Closure<?> closure) {
        variant.setFrameByteHint((byte) hint);
        variant.setFrameSpec(FrameSpec.readScripted(closure));
    }

    /**
     * Set the frame spec with a raw-byte first-byte hint for binary protocols.
     * Use {@code 0x78 as byte} in Groovy to pass a literal byte value.
     *
     * <p>Example:
     * <pre>
     * frame 0x78 as byte, readLengthField(2, 2, 1)
     * </pre>
     */
    public void frame(byte hint, FrameSpec spec) {
        variant.setFrameByteHint(hint);
        variant.setFrameSpec(spec);
    }

    /** Set a scripted binary frame extractor with a raw-byte first-byte hint. */
    public void frame(byte hint, Closure<?> closure) {
        variant.setFrameByteHint(hint);
        variant.setFrameSpec(FrameSpec.readScripted(closure));
    }

    /** Override the configured default maximum frame length for this variant. */
    public void maxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("Maximum frame length must be positive");
        }
        variant.setMaxFrameLength(maxFrameLength);
    }

    @SuppressWarnings("unchecked")
    public void matches(Closure<?> closure) {
        variant.setMatchClosure((Closure<Boolean>) closure);
    }

    @SuppressWarnings("unchecked")
    public void model(Closure<?> closure) {
        variant.setModelClosure((Closure<String>) closure);
    }

    public void alarms(Closure<?> body) {
        AlarmMapBuilder builder = new AlarmMapBuilder(variant.getAlarmMap());
        body.setDelegate(builder);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.call();
    }

    @SuppressWarnings("unchecked")
    public void decode(Closure<?> closure) {
        variant.setDecodeClosure((Closure<Object>) closure);
    }

    @SuppressWarnings("unchecked")
    public void encode(Closure<?> closure) {
        variant.setEncodeClosure((Closure<Object>) closure);
    }
}
