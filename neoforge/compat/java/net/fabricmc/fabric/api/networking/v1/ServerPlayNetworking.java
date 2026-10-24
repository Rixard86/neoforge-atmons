// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Compile-time stand-in: the client-only port registers no server receivers. */
public final class ServerPlayNetworking {
    private ServerPlayNetworking() {
    }

    public static <T extends CustomPayload> boolean registerGlobalReceiver(CustomPayload.Id<T> type, PlayPayloadHandler<T> handler) {
        throw new UnsupportedOperationException("custom payloads are disabled in the client-only NeoForge port");
    }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        MinecraftServer server();

        ServerPlayerEntity player();

        PacketSender responseSender();
    }
}
