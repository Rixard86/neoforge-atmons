// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.screen.v1;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** {@link #AFTER_INIT} is fired from NeoForge's {@code ScreenEvent.Init.Post}. */
public final class ScreenEvents {
    public static final Event<AfterInit> AFTER_INIT = EventFactory.createArrayBacked(AfterInit.class, cs -> (client, screen, w, h) -> {
        for (AfterInit c : cs) c.afterInit(client, screen, w, h);
    });

    private ScreenEvents() {
    }

    @FunctionalInterface
    public interface AfterInit {
        void afterInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight);
    }
}
