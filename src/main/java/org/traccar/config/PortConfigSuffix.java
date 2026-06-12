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
import org.traccar.protocol.AdmProtocol;
import org.traccar.protocol.ApelProtocol;
import org.traccar.protocol.AplicomProtocol;
import org.traccar.protocol.AtrackProtocol;
import org.traccar.protocol.AutoFonProtocol;
import org.traccar.protocol.BceProtocol;
import org.traccar.protocol.CastelProtocol;
import org.traccar.protocol.CellocatorProtocol;
import org.traccar.protocol.CityeasyProtocol;
import org.traccar.protocol.EasyTrackProtocol;
import org.traccar.protocol.EelinkProtocol;
import org.traccar.protocol.EnforaProtocol;
import org.traccar.protocol.FifotrackProtocol;
import org.traccar.protocol.GalileoProtocol;
import org.traccar.protocol.GatorProtocol;
import org.traccar.protocol.Gl200Protocol;
import org.traccar.protocol.GlobalSatProtocol;
import org.traccar.protocol.GlobalstarProtocol;
import org.traccar.protocol.Gps103Protocol;
import org.traccar.protocol.GpsGateProtocol;
import org.traccar.protocol.Gt06Protocol;
import org.traccar.protocol.HuaShengProtocol;
import org.traccar.protocol.IntellitracProtocol;
import org.traccar.protocol.JmakProtocol;
import org.traccar.protocol.Jt600Protocol;
import org.traccar.protocol.Jt808Protocol;
import org.traccar.protocol.KhdProtocol;
import org.traccar.protocol.M2mProtocol;
import org.traccar.protocol.MegastekProtocol;
import org.traccar.protocol.MeiligaoProtocol;
import org.traccar.protocol.MeitrackProtocol;
import org.traccar.protocol.Minifinder2Protocol;
import org.traccar.protocol.MiniFinderProtocol;
import org.traccar.protocol.MobilogixProtocol;
import org.traccar.protocol.Mta6Protocol;
import org.traccar.protocol.MxtProtocol;
import org.traccar.protocol.NavigilProtocol;
import org.traccar.protocol.NavisProtocol;
import org.traccar.protocol.NoranProtocol;
import org.traccar.protocol.OrionProtocol;
import org.traccar.protocol.OsmAndProtocol;
import org.traccar.protocol.ProgressProtocol;
import org.traccar.protocol.RamacProtocol;
import org.traccar.protocol.RitiProtocol;
import org.traccar.protocol.RuptelaProtocol;
import org.traccar.protocol.SkypatrolProtocol;
import org.traccar.protocol.StartekProtocol;
import org.traccar.protocol.Stl060Protocol;
import org.traccar.protocol.SuntechProtocol;
import org.traccar.protocol.T55Protocol;
import org.traccar.protocol.T622IridiumProtocol;
import org.traccar.protocol.T800xProtocol;
import org.traccar.protocol.TaipProtocol;
import org.traccar.protocol.TeltonikaProtocol;
import org.traccar.protocol.ThinkPowerProtocol;
import org.traccar.protocol.Tk103Protocol;
import org.traccar.protocol.TopinProtocol;
import org.traccar.protocol.TotemProtocol;
import org.traccar.protocol.TramigoProtocol;
import org.traccar.protocol.TzoneProtocol;
import org.traccar.protocol.UlbotechProtocol;
import org.traccar.protocol.V680Protocol;
import org.traccar.protocol.WatchProtocol;
import org.traccar.protocol.WialonProtocol;
import org.traccar.protocol.Xexun2Protocol;
import org.traccar.protocol.XexunProtocol;
import org.traccar.protocol.XirgoProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PortConfigSuffix extends ConfigSuffix<Integer> {

    private static final Map<String, Integer> PORTS = new HashMap<>();

    private static void put(Class<? extends BaseProtocol> protocolClass, int port) {
        PORTS.put(BaseProtocol.nameFromClass(protocolClass), port);
    }

    static {
        put(Gps103Protocol.class, 5001);
        put(Tk103Protocol.class, 5002);
        put(Gl200Protocol.class, 5004);
        put(T55Protocol.class, 5005);
        put(XexunProtocol.class, 5006);
        put(TotemProtocol.class, 5007);
        put(EnforaProtocol.class, 5008);
        put(MeiligaoProtocol.class, 5009);
        put(SuntechProtocol.class, 5011);
        put(ProgressProtocol.class, 5012);
        put(Jt600Protocol.class, 5014);
        put(Jt808Protocol.class, 5015);
        put(V680Protocol.class, 5016);
        put(NavisProtocol.class, 5019);
        put(MeitrackProtocol.class, 5020);
        put(SkypatrolProtocol.class, 5021);
        put(Gt06Protocol.class, 5023);
        put(MegastekProtocol.class, 5024);
        put(NavigilProtocol.class, 5025);
        put(GpsGateProtocol.class, 5026);
        put(TeltonikaProtocol.class, 5027);
        put(Mta6Protocol.class, 5028);
        put(TzoneProtocol.class, 5029);
        put(TaipProtocol.class, 5031);
        put(CellocatorProtocol.class, 5033);
        put(GalileoProtocol.class, 5034);
        put(IntellitracProtocol.class, 5037);
        put(WialonProtocol.class, 5039);
        put(ApelProtocol.class, 5041);
        put(GlobalSatProtocol.class, 5043);
        put(AtrackProtocol.class, 5044);
        put(RuptelaProtocol.class, 5046);
        put(AplicomProtocol.class, 5049);
        put(GatorProtocol.class, 5052);
        put(NoranProtocol.class, 5053);
        put(M2mProtocol.class, 5054);
        put(OsmAndProtocol.class, 5055);
        put(EasyTrackProtocol.class, 5056);
        put(KhdProtocol.class, 5058);
        put(Stl060Protocol.class, 5060);
        put(MiniFinderProtocol.class, 5062);
        put(EelinkProtocol.class, 5064);
        put(OrionProtocol.class, 5070);
        put(RitiProtocol.class, 5071);
        put(UlbotechProtocol.class, 5072);
        put(TramigoProtocol.class, 5073);
        put(AutoFonProtocol.class, 5077);
        put(BceProtocol.class, 5080);
        put(XirgoProtocol.class, 5081);
        put(CastelProtocol.class, 5086);
        put(MxtProtocol.class, 5087);
        put(CityeasyProtocol.class, 5088);
        put(AdmProtocol.class, 5092);
        put(WatchProtocol.class, 5093);
        put(T800xProtocol.class, 5094);
        put(HuaShengProtocol.class, 5111);
        put(FifotrackProtocol.class, 5124);
        put(GlobalstarProtocol.class, 5185);
        put(Minifinder2Protocol.class, 5187);
        put(TopinProtocol.class, 5199);
        put(MobilogixProtocol.class, 5216);
        put(StartekProtocol.class, 5222);
        put(ThinkPowerProtocol.class, 5228);
        put(Xexun2Protocol.class, 5233);
        put(T622IridiumProtocol.class, 5248);
        put(RamacProtocol.class, 5251);
        put(JmakProtocol.class, 5259);
    }

    PortConfigSuffix(String key, List<KeyType> types) {
        super(key, types, null);
    }

    @Override
    public ConfigKey<Integer> withPrefix(String protocol) {
        return new IntegerConfigKey(protocol + keySuffix, types, PORTS.get(protocol));
    }
}
