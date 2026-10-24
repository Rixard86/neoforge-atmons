// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

public interface ModMetadata {
    String getId();

    String getName();

    Version getVersion();
}
