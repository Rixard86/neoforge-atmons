// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.itemgroup.v1;

import net.minecraft.item.ItemGroup;
import net.minecraft.registry.RegistryKey;

import net.fabricmc.fabric.api.event.Event;

/** Compile-time stand-in: the client-only port adds no items. */
public final class ItemGroupEvents {
    private ItemGroupEvents() {
    }

    public static Event<ModifyEntries> modifyEntriesEvent(RegistryKey<ItemGroup> registryKey) {
        throw new UnsupportedOperationException("items are disabled in the client-only NeoForge port");
    }

    @FunctionalInterface
    public interface ModifyEntries {
        void modifyEntries(FabricItemGroupEntries entries);
    }
}
