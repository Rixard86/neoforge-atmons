// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

/** The parts of Fabric Loader the mod uses, answered by FML. */
public final class NeoForgeFabricLoader implements FabricLoader {
    public static final NeoForgeFabricLoader INSTANCE = new NeoForgeFabricLoader();

    private NeoForgeFabricLoader() {
    }

    /** Fabric ids may contain '-', NeoForge ids may not. */
    static String toNeoForgeId(String fabricId) {
        if (RtxRemixNeoForge.FABRIC_MOD_ID.equals(fabricId)) {
            return RtxRemixNeoForge.MOD_ID;
        }
        return fabricId.replace('-', '_');
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        String fabricId = id;
        return ModList.get().getModContainerById(toNeoForgeId(id)).map(c -> new Container(fabricId, c));
    }

    @Override
    public boolean isModLoaded(String id) {
        return ModList.get().isLoaded(toNeoForgeId(id));
    }

    @Override
    public EnvType getEnvironmentType() {
        return EnvType.CLIENT;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    private record Container(String fabricId, net.neoforged.fml.ModContainer mod) implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            String version = mod.getModInfo().getVersion().toString();
            String name = mod.getModInfo().getDisplayName();
            return new ModMetadata() {
                @Override
                public String getId() {
                    return fabricId;
                }

                @Override
                public String getName() {
                    return name;
                }

                @Override
                public Version getVersion() {
                    return () -> version;
                }
            };
        }

        @Override
        public List<Path> getRootPaths() {
            return List.of(mod.getModInfo().getOwningFile().getFile().getSecureJar().getRootPath());
        }
    }
}
