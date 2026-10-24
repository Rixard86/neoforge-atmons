// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.render.fluid.v1;

import org.jetbrains.annotations.Nullable;

import net.minecraft.fluid.Fluid;

import com.nvidia.rtxremix.neoforge.NeoForgeFluidRenderHandlers;

/** Backed by NeoForge's {@code IClientFluidTypeExtensions}, which covers every fluid. */
public interface FluidRenderHandlerRegistry {
    FluidRenderHandlerRegistry INSTANCE = NeoForgeFluidRenderHandlers.INSTANCE;

    @Nullable
    FluidRenderHandler get(Fluid fluid);
}
