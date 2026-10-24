// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge.mixin;

import java.util.List;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Backs Fabric's {@code Screens.getButtons} view (NeoForge already makes {@code drawables} public). */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Accessor("selectables")
    List<Selectable> rtxremix$getSelectables();

    @Accessor("children")
    List<Element> rtxremix$getChildren();
}
