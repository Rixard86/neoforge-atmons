// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.Optional;

import net.fabricmc.api.EnvType;

public interface FabricLoader {
    static FabricLoader getInstance() {
        return com.nvidia.rtxremix.neoforge.NeoForgeFabricLoader.INSTANCE;
    }

    Path getGameDir();

    Path getConfigDir();

    Optional<ModContainer> getModContainer(String id);

    boolean isModLoaded(String id);

    EnvType getEnvironmentType();

    boolean isDevelopmentEnvironment();
}
