// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Fired from NeoForge's client-side {@code ChunkEvent.Load/Unload}. */
public final class ClientChunkEvents {
    public static final Event<Load> CHUNK_LOAD = EventFactory.createArrayBacked(Load.class, cs -> (world, chunk) -> {
        for (Load c : cs) c.onChunkLoad(world, chunk);
    });
    public static final Event<Unload> CHUNK_UNLOAD = EventFactory.createArrayBacked(Unload.class, cs -> (world, chunk) -> {
        for (Unload c : cs) c.onChunkUnload(world, chunk);
    });

    private ClientChunkEvents() {
    }

    @FunctionalInterface
    public interface Load {
        void onChunkLoad(ClientWorld world, WorldChunk chunk);
    }

    @FunctionalInterface
    public interface Unload {
        void onChunkUnload(ClientWorld world, WorldChunk chunk);
    }
}
