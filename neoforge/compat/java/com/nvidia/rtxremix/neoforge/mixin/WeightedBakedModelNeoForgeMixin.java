// SPDX-License-Identifier: Apache-2.0
//
// NeoForge port of the upstream WeightedBakedModelMixin. NeoForge replaces the
// three-argument getQuads with its ModelData/RenderType overload (the vanilla
// signature becomes a default that forwards here), so the allocation-free
// variant selection moves to that overload and passes both through.
package com.nvidia.rtxremix.neoforge.mixin;

import java.util.Collections;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.WeightedBakedModel;
import net.minecraft.util.collection.Weighted;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.neoforged.neoforge.client.model.data.ModelData;

@Mixin(WeightedBakedModel.class)
public abstract class WeightedBakedModelNeoForgeMixin {
    @Shadow
    @Final
    private int totalWeight;

    @Shadow
    @Final
    private List<Weighted.Present<BakedModel>> models;

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            Random random,
            ModelData data,
            RenderLayer renderType,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        int remaining = Math.abs((int) random.nextLong()) % totalWeight;
        List<Weighted.Present<BakedModel>> variants = models;
        for (int i = 0, n = variants.size(); i < n; i++) {
            Weighted.Present<BakedModel> variant = variants.get(i);
            remaining -= variant.getWeight().getValue();
            if (remaining < 0) {
                cir.setReturnValue(variant.data().getQuads(state, face, random, data, renderType));
                return;
            }
        }
        cir.setReturnValue(Collections.emptyList());
    }
}
