// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.MinecraftClient;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Fired from NeoForge's {@code ClientTickEvent.Pre/Post}. */
public final class ClientTickEvents {
    public static final Event<StartTick> START_CLIENT_TICK = EventFactory.createArrayBacked(StartTick.class, cs -> client -> {
        for (StartTick c : cs) c.onStartTick(client);
    });
    public static final Event<EndTick> END_CLIENT_TICK = EventFactory.createArrayBacked(EndTick.class, cs -> client -> {
        for (EndTick c : cs) c.onEndTick(client);
    });

    private ClientTickEvents() {
    }

    @FunctionalInterface
    public interface StartTick {
        void onStartTick(MinecraftClient client);
    }

    @FunctionalInterface
    public interface EndTick {
        void onEndTick(MinecraftClient client);
    }
}
