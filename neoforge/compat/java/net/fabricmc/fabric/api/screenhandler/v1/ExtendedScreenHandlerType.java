// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.screenhandler.v1;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;

/** Compile-time stand-in: menus need the server to have the mod. */
public class ExtendedScreenHandlerType<T extends ScreenHandler, D> extends ScreenHandlerType<T> {
    public ExtendedScreenHandlerType(ExtendedFactory<T, D> factory, PacketCodec<? super RegistryByteBuf, D> packetCodec) {
        super((syncId, inventory) -> {
            throw new UnsupportedOperationException("menus are disabled in the client-only NeoForge port");
        }, FeatureFlags.VANILLA_FEATURES);
    }

    @FunctionalInterface
    public interface ExtendedFactory<T extends ScreenHandler, D> {
        T create(int syncId, PlayerInventory inventory, D data);
    }
}
