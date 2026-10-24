// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.network.packet.CustomPayload;

/** Present for signature compatibility; this client-only port sends no custom payloads. */
public interface PacketSender {
    void sendPacket(CustomPayload payload);
}
