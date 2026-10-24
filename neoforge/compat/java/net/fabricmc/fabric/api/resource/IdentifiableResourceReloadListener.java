// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.resource;

import java.util.Collection;
import java.util.Collections;

import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Identifier;

public interface IdentifiableResourceReloadListener extends ResourceReloader {
    Identifier getFabricId();

    default Collection<Identifier> getFabricDependencies() {
        return Collections.emptyList();
    }
}
