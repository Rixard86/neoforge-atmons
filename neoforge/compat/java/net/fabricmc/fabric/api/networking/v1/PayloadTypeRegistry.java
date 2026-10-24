// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Compile-time stand-in: payload types would need the server to have the mod. */
public interface PayloadTypeRegistry<B> {
    <T extends CustomPayload> CustomPayload.Type<? super B, T> register(CustomPayload.Id<T> id, PacketCodec<? super B, T> codec);

    static PayloadTypeRegistry<RegistryByteBuf> playC2S() {
        throw new UnsupportedOperationException("custom payloads are disabled in the client-only NeoForge port");
    }

    static PayloadTypeRegistry<RegistryByteBuf> playS2C() {
        throw new UnsupportedOperationException("custom payloads are disabled in the client-only NeoForge port");
    }
}
