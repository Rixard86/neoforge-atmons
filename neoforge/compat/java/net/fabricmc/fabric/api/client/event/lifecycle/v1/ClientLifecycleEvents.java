// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.MinecraftClient;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Fired from {@code MinecraftClientLifecycleMixin}, at the same points as Fabric API. */
public final class ClientLifecycleEvents {
    public static final Event<ClientStarted> CLIENT_STARTED = EventFactory.createArrayBacked(ClientStarted.class, cs -> client -> {
        for (ClientStarted c : cs) c.onClientStarted(client);
    });
    public static final Event<ClientStopping> CLIENT_STOPPING = EventFactory.createArrayBacked(ClientStopping.class, cs -> client -> {
        for (ClientStopping c : cs) c.onClientStopping(client);
    });

    private ClientLifecycleEvents() {
    }

    @FunctionalInterface
    public interface ClientStarted {
        void onClientStarted(MinecraftClient client);
    }

    @FunctionalInterface
    public interface ClientStopping {
        void onClientStopping(MinecraftClient client);
    }
}
