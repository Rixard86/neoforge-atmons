// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Fabric's fluid render handlers answered from NeoForge's client fluid type
 * extensions, which every fluid (vanilla and modded) has. Called from section
 * build worker threads, so it only reads immutable state.
 */
public final class NeoForgeFluidRenderHandlers implements FluidRenderHandlerRegistry {
    public static final NeoForgeFluidRenderHandlers INSTANCE = new NeoForgeFluidRenderHandlers();

    private final Map<Fluid, FluidRenderHandler> handlers = new ConcurrentHashMap<>();

    private NeoForgeFluidRenderHandlers() {
    }

    @Override
    public @Nullable FluidRenderHandler get(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return null;
        }
        return handlers.computeIfAbsent(fluid, f -> new Handler());
    }

    private static final class Handler implements FluidRenderHandler {
        @Override
        public Sprite[] getFluidSprites(@Nullable BlockRenderView view, @Nullable BlockPos pos, FluidState state) {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(state);
            Function<Identifier, Sprite> atlas =
                    MinecraftClient.getInstance().getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
            Identifier still;
            Identifier flowing;
            Identifier overlay;
            if (view != null && pos != null) {
                still = ext.getStillTexture(state, view, pos);
                flowing = ext.getFlowingTexture(state, view, pos);
                overlay = ext.getOverlayTexture(state, view, pos);
            } else {
                still = ext.getStillTexture();
                flowing = ext.getFlowingTexture();
                overlay = ext.getOverlayTexture();
            }
            if (still == null || flowing == null) {
                return null;
            }
            if (overlay != null) {
                return new Sprite[] {atlas.apply(still), atlas.apply(flowing), atlas.apply(overlay)};
            }
            return new Sprite[] {atlas.apply(still), atlas.apply(flowing)};
        }

        @Override
        public int getFluidColor(@Nullable BlockRenderView view, @Nullable BlockPos pos, FluidState state) {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(state);
            if (view != null && pos != null) {
                return ext.getTintColor(state, view, pos);
            }
            return ext.getTintColor();
        }
    }
}
