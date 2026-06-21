/*
 * Copyright 2024 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by FOGNETX <Drew.Taylor@fognetx.com>, 2026. Modifications licensed under
// AGPL-3.0-or-later (SPDX-License-Identifier: AGPL-3.0-or-later).

package org.traccar.config;

import org.traccar.BaseProtocol;
import org.traccar.protocol.GlobalstarProtocol;
import org.traccar.protocol.Gt06Protocol;
import org.traccar.protocol.Jt808Protocol;
import org.traccar.protocol.Mta6Protocol;
import org.traccar.protocol.NavisProtocol;
import org.traccar.protocol.OsmAndProtocol;
import org.traccar.protocol.SuntechProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PortConfigSuffix extends ConfigSuffix<Integer> {

    private static final Map<String, Integer> PORTS = new HashMap<>();

    private static void put(Class<? extends BaseProtocol> protocolClass, int port) {
        PORTS.put(BaseProtocol.nameFromClass(protocolClass), port);
    }

    static {
        put(SuntechProtocol.class, 5011);
        put(Jt808Protocol.class, 5015);
        put(NavisProtocol.class, 5019);
        put(Gt06Protocol.class, 5023);
        put(Mta6Protocol.class, 5028);
        put(OsmAndProtocol.class, 5055);

        put(GlobalstarProtocol.class, 5185);

    }

    PortConfigSuffix(String key, List<KeyType> types) {
        super(key, types, null);
    }

    @Override
    public ConfigKey<Integer> withPrefix(String protocol) {
        return new IntegerConfigKey(protocol + keySuffix, types, PORTS.get(protocol));
    }
}
