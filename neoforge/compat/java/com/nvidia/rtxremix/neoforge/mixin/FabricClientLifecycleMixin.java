// SPDX-License-Identifier: Apache-2.0
//
// Injection points follow Fabric API's fabric-lifecycle-events-v1
// MinecraftClientMixin (Apache-2.0, FabricMC), so the events fire exactly
// where the upstream mod expects them.
package com.nvidia.rtxremix.neoforge.mixin;

import net.minecraft.client.MinecraftClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

@Mixin(MinecraftClient.class)
abstract class FabricClientLifecycleMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$onStartTick(CallbackInfo info) {
        ClientTickEvents.START_CLIENT_TICK.invoker().onStartTick((MinecraftClient) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$onEndTick(CallbackInfo info) {
        ClientTickEvents.END_CLIENT_TICK.invoker().onEndTick((MinecraftClient) (Object) this);
    }

    @Inject(method = "stop", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V", shift = At.Shift.AFTER, remap = false))
    private void rtxremix$onStopping(CallbackInfo ci) {
        ClientLifecycleEvents.CLIENT_STOPPING.invoker().onClientStopping((MinecraftClient) (Object) this);
    }

    @Inject(method = "run", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;thread:Ljava/lang/Thread;", shift = At.Shift.AFTER, ordinal = 0))
    private void rtxremix$onStart(CallbackInfo ci) {
        ClientLifecycleEvents.CLIENT_STARTED.invoker().onClientStarted((MinecraftClient) (Object) this);
    }
}
