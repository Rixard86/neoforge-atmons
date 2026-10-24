// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;

import com.nvidia.rtxremix.neoforge.FabricBridge;

public final class EntityModelLayerRegistry {
    private EntityModelLayerRegistry() {
    }

    /** Registered through NeoForge's {@code EntityRenderersEvent.RegisterLayerDefinitions}. */
    public static void registerModelLayer(EntityModelLayer modelLayer, TexturedModelDataProvider provider) {
        FabricBridge.registerModelLayer(modelLayer, provider::createModelData);
    }

    @FunctionalInterface
    public interface TexturedModelDataProvider {
        TexturedModelData createModelData();
    }
}
