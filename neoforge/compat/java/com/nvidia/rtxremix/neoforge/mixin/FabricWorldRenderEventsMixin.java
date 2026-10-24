/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.rtxremix.neoforge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.impl.client.rendering.WorldRenderContextImpl;

/*
 * NeoForge port: Fabric API's WorldRenderEvents hooks (fabric-rendering-v1
 * WorldRendererMixin, Apache-2.0, FabricMC), without the dimension-rendering
 * and invalidate-state hooks the mod does not use.
 */
@Mixin(WorldRenderer.class)
abstract class FabricWorldRenderEventsMixin {
	@Final
	@Shadow
	private BufferBuilderStorage bufferBuilders;
	@Shadow private ClientWorld world;
	@Shadow private PostEffectProcessor transparencyPostProcessor;
	@Final
	@Shadow
	private MinecraftClient client;
	@Unique private final WorldRenderContextImpl rtxremix$fabricContext = new WorldRenderContextImpl();

	@Inject(method = "render", at = @At("HEAD"))
	private void beforeRender(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		rtxremix$fabricContext.prepare((WorldRenderer) (Object) this, tickCounter, renderBlockOutline, camera, gameRenderer, lightmapTextureManager, projectionMatrix, positionMatrix, bufferBuilders.getEntityVertexConsumers(), world.getProfiler(), transparencyPostProcessor != null, world);
		WorldRenderEvents.START.invoker().onStart(rtxremix$fabricContext);
	}

	@Inject(method = "setupTerrain", at = @At("RETURN"))
	private void afterTerrainSetup(Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator, CallbackInfo ci) {
		rtxremix$fabricContext.setFrustum(frustum);
		WorldRenderEvents.AFTER_SETUP.invoker().afterSetup(rtxremix$fabricContext);
	}

	@Inject(
			method = "render",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/client/render/WorldRenderer;renderLayer(Lnet/minecraft/client/render/RenderLayer;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
				ordinal = 2,
				shift = Shift.AFTER
			)
	)
	private void afterTerrainSolid(CallbackInfo ci) {
		WorldRenderEvents.BEFORE_ENTITIES.invoker().beforeEntities(rtxremix$fabricContext);
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "NEW", target = "net/minecraft/client/util/math/MatrixStack"))
	private MatrixStack setMatrixStack(MatrixStack matrixStack) {
		rtxremix$fabricContext.setMatrixStack(matrixStack);
		return matrixStack;
	}

	@Inject(method = "render", at = @At(value = "CONSTANT", args = "stringValue=blockentities", ordinal = 0))
	private void afterEntities(CallbackInfo ci) {
		WorldRenderEvents.AFTER_ENTITIES.invoker().afterEntities(rtxremix$fabricContext);
	}

	@Inject(
			method = "render",
			at = @At(
				value = "FIELD",
				target = "Lnet/minecraft/client/MinecraftClient;crosshairTarget:Lnet/minecraft/util/hit/HitResult;",
				shift = At.Shift.AFTER,
				ordinal = 1
			)
	)
	private void beforeRenderOutline(CallbackInfo ci) {
		rtxremix$fabricContext.renderBlockOutline = WorldRenderEvents.BEFORE_BLOCK_OUTLINE.invoker().beforeBlockOutline(rtxremix$fabricContext, client.crosshairTarget);
	}

	@SuppressWarnings("ConstantConditions")
	@Inject(method = "drawBlockOutline", at = @At("HEAD"), cancellable = true)
	private void onDrawBlockOutline(MatrixStack matrixStack, VertexConsumer vertexConsumer, Entity entity, double cameraX, double cameraY, double cameraZ, BlockPos blockPos, BlockState blockState, CallbackInfo ci) {
		if (!rtxremix$fabricContext.renderBlockOutline) {
			// Was cancelled before we got here, so do not
			// fire the BLOCK_OUTLINE event per contract of the API.
			ci.cancel();
		} else {
			rtxremix$fabricContext.prepareBlockOutline(entity, cameraX, cameraY, cameraZ, blockPos, blockState);

			if (!WorldRenderEvents.BLOCK_OUTLINE.invoker().onBlockOutline(rtxremix$fabricContext, rtxremix$fabricContext)) {
				ci.cancel();
			}
		}
	}

	@SuppressWarnings("ConstantConditions")
	@ModifyVariable(method = "drawBlockOutline", at = @At("HEAD"))
	private VertexConsumer resetBlockOutlineBuffer(VertexConsumer vertexConsumer) {
		// The original VertexConsumer may have been ended during the block outlines event, so we
		// have to re-request it to prevent a crash when the vanilla block overlay is submitted.
		return rtxremix$fabricContext.consumers().getBuffer(RenderLayer.getLines());
	}

	@Inject(
			method = "render",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/client/render/debug/DebugRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;DDD)V",
				ordinal = 0
			)
	)
	private void beforeDebugRender(CallbackInfo ci) {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.invoker().beforeDebugRender(rtxremix$fabricContext);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/GameOptions;getCloudRenderModeValue()Lnet/minecraft/client/option/CloudRenderMode;"))
	private void beforeClouds(CallbackInfo ci) {
		WorldRenderEvents.AFTER_TRANSLUCENT.invoker().afterTranslucent(rtxremix$fabricContext);
	}

	@Inject(
			method = "render",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/client/render/WorldRenderer;renderChunkDebugInfo(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/render/Camera;)V"
			)
	)
	private void onChunkDebugRender(CallbackInfo ci) {
		WorldRenderEvents.LAST.invoker().onLast(rtxremix$fabricContext);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void afterRender(CallbackInfo ci) {
		WorldRenderEvents.END.invoker().onEnd(rtxremix$fabricContext);
	}
}
