// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.network.packet.CustomPayload;

/**
 * The client-only port registers no payload types, so the server can never
 * receive one: {@link #canSend} is always false and nothing is ever sent.
 */
public final class ClientPlayNetworking {
    private ClientPlayNetworking() {
    }

    public static boolean canSend(CustomPayload.Id<?> type) {
        return false;
    }

    public static void send(CustomPayload payload) {
        throw new UnsupportedOperationException("custom payloads are disabled in the client-only NeoForge port");
    }
}
