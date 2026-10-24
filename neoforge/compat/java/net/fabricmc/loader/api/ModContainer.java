// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.metadata.ModMetadata;

public interface ModContainer {
    ModMetadata getMetadata();

    List<Path> getRootPaths();

    default Optional<Path> findPath(String file) {
        for (Path root : getRootPaths()) {
            Path p = root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
            if (java.nio.file.Files.exists(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
