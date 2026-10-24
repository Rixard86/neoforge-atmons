// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Fired from NeoForge's server-side {@code ChunkEvent.Load/Unload} (integrated server only here). */
public final class ServerChunkEvents {
    public static final Event<Load> CHUNK_LOAD = EventFactory.createArrayBacked(Load.class, cs -> (world, chunk) -> {
        for (Load c : cs) c.onChunkLoad(world, chunk);
    });
    public static final Event<Unload> CHUNK_UNLOAD = EventFactory.createArrayBacked(Unload.class, cs -> (world, chunk) -> {
        for (Unload c : cs) c.onChunkUnload(world, chunk);
    });

    private ServerChunkEvents() {
    }

    @FunctionalInterface
    public interface Load {
        void onChunkLoad(ServerWorld world, WorldChunk chunk);
    }

    @FunctionalInterface
    public interface Unload {
        void onChunkUnload(ServerWorld world, WorldChunk chunk);
    }
}
