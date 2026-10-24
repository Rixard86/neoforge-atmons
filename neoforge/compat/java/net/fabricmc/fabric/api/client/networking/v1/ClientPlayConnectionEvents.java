// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.networking.v1.PacketSender;

/** Fired from NeoForge's {@code ClientPlayerNetworkEvent.LoggingIn/LoggingOut}. */
public final class ClientPlayConnectionEvents {
    public static final Event<Join> JOIN = EventFactory.createArrayBacked(Join.class, cs -> (handler, sender, client) -> {
        for (Join c : cs) c.onPlayReady(handler, sender, client);
    });
    public static final Event<Disconnect> DISCONNECT = EventFactory.createArrayBacked(Disconnect.class, cs -> (handler, client) -> {
        for (Disconnect c : cs) c.onPlayDisconnect(handler, client);
    });

    private ClientPlayConnectionEvents() {
    }

    @FunctionalInterface
    public interface Join {
        void onPlayReady(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client);
    }

    @FunctionalInterface
    public interface Disconnect {
        void onPlayDisconnect(ClientPlayNetworkHandler handler, MinecraftClient client);
    }
}
