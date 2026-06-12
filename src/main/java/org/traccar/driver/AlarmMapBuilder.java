// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import java.util.Map;

/**
 * Delegate for the {@code alarms { }} block inside a variant definition.
 * Groovy's {@code >>} operator maps to {@code rightShift}, so driver scripts write:
 * <pre>
 *   alarms {
 *       "TOWED"      >> ALARM_TOW
 *       "DEF" >> { model -> model.startsWith("MT700") ? ALARM_REMOVING : ALARM_POWER_CUT }
 *   }
 * </pre>
 */
public final class AlarmMapBuilder {

    private final Map<String, Object> alarmMap;

    public AlarmMapBuilder(Map<String, Object> alarmMap) {
        this.alarmMap = alarmMap;
    }

    /** Called by Groovy's {@code "EVENT" >> alarmConstant} syntax. */
    public void rightShift(String event, Object alarmOrClosure) {
        if (alarmOrClosure != null) {
            alarmMap.put(event, alarmOrClosure);
        }
    }

    /**
     * Allows pipe-separated multi-event aliases:
     * <pre>"BLP" | "CLP" >> ALARM_LOW_BATTERY</pre>
     * Returns an {@link EventGroup} whose {@code rightShift} maps all events.
     */
    public EventGroup or(String first, String second) {
        return new EventGroup(alarmMap, first, second);
    }

    public static final class EventGroup {
        private final Map<String, Object> map;
        private final String[] events;

        EventGroup(Map<String, Object> map, String... events) {
            this.map = map;
            this.events = events;
        }

        public EventGroup or(String event) {
            String[] next = new String[events.length + 1];
            System.arraycopy(events, 0, next, 0, events.length);
            next[events.length] = event;
            return new EventGroup(map, next);
        }

        public void rightShift(Object alarmOrClosure) {
            for (String event : events) {
                if (alarmOrClosure != null) {
                    map.put(event, alarmOrClosure);
                }
            }
        }
    }
}
