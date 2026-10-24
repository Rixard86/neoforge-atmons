// SPDX-License-Identifier: Apache-2.0
//
// NeoForge port: there is no Fabric Renderer API implementation on NeoForge, so
// nothing can supply a Renderer for vanilla model encoding. No model on NeoForge
// implements FabricBakedModel, so these defaults are never reached; the mod's
// terrain capture takes its vanilla path instead.
package net.fabricmc.fabric.impl.renderer;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.util.math.random.Random;

import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;

public final class VanillaModelEncoder {
	private VanillaModelEncoder() {
	}

	public static void emitBlockQuads(BakedModel model, @Nullable BlockState state, Supplier<Random> randomSupplier, RenderContext context) {
		throw new UnsupportedOperationException("Fabric Renderer API is not available on NeoForge");
	}

	public static void emitItemQuads(BakedModel model, @Nullable BlockState state, Supplier<Random> randomSupplier, RenderContext context) {
		throw new UnsupportedOperationException("Fabric Renderer API is not available on NeoForge");
	}
}
