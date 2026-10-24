// SPDX-License-Identifier: Apache-2.0
//
// NeoForge port of the upstream MultipartBakedModelMixin, on NeoForge's
// ModelData/RenderType getQuads overload. Keeps NeoForge's per-part render
// type filter and per-part model data, and the upstream allocation-free part
// selection (one reused seeded random per thread instead of one per part).
package com.nvidia.rtxremix.neoforge.mixin;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.MultipartBakedModel;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.Random;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.MultipartModelData;

@Mixin(MultipartBakedModel.class)
public abstract class MultipartBakedModelNeoForgeMixin {
    @Shadow
    @Final
    private List<Pair<Predicate<BlockState>, BakedModel>> components;

    @Shadow
    @Final
    private Map<BlockState, BitSet> stateCache;

    @Unique
    private static final ThreadLocal<CheckedRandom> rtxremix$partRandom =
            ThreadLocal.withInitial(() -> new CheckedRandom(0L));

    @Inject(method = "getQuads", at = @At("HEAD"), cancellable = true)
    private void rtxremix$getQuads(
            BlockState state,
            Direction face,
            Random random,
            ModelData data,
            RenderLayer renderType,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        if (state == null) {
            cir.setReturnValue(Collections.emptyList());
            return;
        }
        BitSet selected = stateCache.get(state);
        if (selected == null) {
            selected = new BitSet();
            for (int i = 0, n = components.size(); i < n; i++) {
                if (components.get(i).getLeft().test(state)) {
                    selected.set(i);
                }
            }
            stateCache.put(state, selected);
        }
        long seed = random.nextLong();
        CheckedRandom partRandom = rtxremix$partRandom.get();
        List<BakedQuad> result = null;
        boolean owned = false;
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
            BakedModel part = components.get(i).getRight();
            if (renderType != null) {
                partRandom.setSeed(seed);
                if (!part.getRenderTypes(state, partRandom, data).contains(renderType)) {
                    continue;
                }
            }
            partRandom.setSeed(seed);
            List<BakedQuad> quads = part.getQuads(
                    state, face, partRandom, MultipartModelData.resolve(data, part), renderType);
            if (result == null) {
                result = quads;
            } else {
                if (!owned) {
                    List<BakedQuad> first = result;
                    result = new ArrayList<>(first.size() + quads.size());
                    for (int j = 0, n = first.size(); j < n; j++) {
                        result.add(first.get(j));
                    }
                    owned = true;
                }
                for (int j = 0, n = quads.size(); j < n; j++) {
                    result.add(quads.get(j));
                }
            }
        }
        cir.setReturnValue(result == null ? Collections.emptyList() : result);
    }
}
