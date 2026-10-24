// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.resource;

import net.minecraft.resource.ResourceType;

import com.nvidia.rtxremix.neoforge.FabricBridge;

public interface ResourceManagerHelper {
    void registerReloadListener(IdentifiableResourceReloadListener listener);

    /** Client resources go through NeoForge's {@code RegisterClientReloadListenersEvent}. */
    static ResourceManagerHelper get(ResourceType type) {
        if (type != ResourceType.CLIENT_RESOURCES) {
            throw new UnsupportedOperationException("only client resource reload listeners are supported");
        }
        return FabricBridge::registerClientReloadListener;
    }
}
