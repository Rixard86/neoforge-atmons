// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.keybinding.v1;

import net.minecraft.client.option.KeyBinding;

import com.nvidia.rtxremix.neoforge.FabricBridge;

public final class KeyBindingHelper {
    private KeyBindingHelper() {
    }

    /** Registered through NeoForge's {@code RegisterKeyMappingsEvent}, so it shows in Controls and is saved. */
    public static KeyBinding registerKeyBinding(KeyBinding keyBinding) {
        FabricBridge.registerKeyBinding(keyBinding);
        return keyBinding;
    }
}
