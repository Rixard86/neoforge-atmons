// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.screen.v1;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;

import com.nvidia.rtxremix.neoforge.mixin.ScreenAccessor;
import net.fabricmc.fabric.impl.client.screen.ButtonList;

public final class Screens {
    private Screens() {
    }

    /** A live view of the screen's clickable widgets; adding or removing updates the screen. */
    public static List<ClickableWidget> getButtons(Screen screen) {
        ScreenAccessor a = (ScreenAccessor) screen;
        return new ButtonList(screen.drawables, a.rtxremix$getSelectables(), a.rtxremix$getChildren());
    }

    public static MinecraftClient getClient(Screen screen) {
        return MinecraftClient.getInstance();
    }
}
