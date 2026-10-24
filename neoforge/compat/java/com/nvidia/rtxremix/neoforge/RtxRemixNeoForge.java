// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.WorkerPoolCap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint for RTX Remix for Minecraft, client only.
 *
 * <p>Everything a server would also need (the Copper Beacon blocks, menu and
 * payload, and the rapids particle types) stays unregistered, so a client with
 * this mod joins servers that do not have it, and players without it are not
 * affected. The Fabric entrypoints run unchanged on top of the compat layer.
 */
@Mod(value = RtxRemixNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class RtxRemixNeoForge {
    public static final String MOD_ID = "rtx_remix_renderer";
    /** The upstream (Fabric) mod id, still used for resources and lookups. */
    public static final String FABRIC_MOD_ID = RtxRemixCommonMod.MOD_ID;

    public RtxRemixNeoForge(IEventBus modBus) {
        FabricBridge.init(modBus);
        // Fabric runs this before Minecraft's worker pool exists; on NeoForge the
        // pool is usually up already, so it only takes effect via -Dmax.bg.threads.
        new WorkerPoolCap().onPreLaunch();
        RtxRemixCommonMod.serverContentEnabled = false;
        new RtxRemixMod().onInitializeClient();
    }
}
