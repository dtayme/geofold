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
import org.traccar.protocol.AplicomProtocol;
import org.traccar.protocol.AtrackProtocol;
import org.traccar.protocol.CastelProtocol;
import org.traccar.protocol.EelinkProtocol;
import org.traccar.protocol.GalileoProtocol;
import org.traccar.protocol.Gl200Protocol;
import org.traccar.protocol.GlobalstarProtocol;
import org.traccar.protocol.Gt06Protocol;
import org.traccar.protocol.Jt600Protocol;
import org.traccar.protocol.Jt808Protocol;
import org.traccar.protocol.MeiligaoProtocol;
import org.traccar.protocol.MeitrackProtocol;
import org.traccar.protocol.Mta6Protocol;
import org.traccar.protocol.NavisProtocol;
import org.traccar.protocol.OsmAndProtocol;
import org.traccar.protocol.RuptelaProtocol;
import org.traccar.protocol.SuntechProtocol;
import org.traccar.protocol.T55Protocol;
import org.traccar.protocol.TeltonikaProtocol;
import org.traccar.protocol.Tk103Protocol;
import org.traccar.protocol.TotemProtocol;
import org.traccar.protocol.TzoneProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PortConfigSuffix extends ConfigSuffix<Integer> {

    private static final Map<String, Integer> PORTS = new HashMap<>();

    private static void put(Class<? extends BaseProtocol> protocolClass, int port) {
        PORTS.put(BaseProtocol.nameFromClass(protocolClass), port);
    }

    static {
        put(Tk103Protocol.class, 5002);
        put(Gl200Protocol.class, 5004);
        put(T55Protocol.class, 5005);

        put(TotemProtocol.class, 5007);
        put(MeiligaoProtocol.class, 5009);
        put(SuntechProtocol.class, 5011);
        put(Jt600Protocol.class, 5014);
        put(Jt808Protocol.class, 5015);
        put(NavisProtocol.class, 5019);
        put(MeitrackProtocol.class, 5020);
        put(Gt06Protocol.class, 5023);
        put(TeltonikaProtocol.class, 5027);
        put(Mta6Protocol.class, 5028);
        put(TzoneProtocol.class, 5029);
        put(GalileoProtocol.class, 5034);
        put(AtrackProtocol.class, 5044);
        put(RuptelaProtocol.class, 5046);
        put(AplicomProtocol.class, 5049);
        put(OsmAndProtocol.class, 5055);
        put(EelinkProtocol.class, 5064);
        put(CastelProtocol.class, 5086);

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
