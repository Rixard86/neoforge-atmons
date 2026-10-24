// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.itemgroup.v1;

import net.minecraft.item.ItemConvertible;

public interface FabricItemGroupEntries {
    void add(ItemConvertible item);
}
