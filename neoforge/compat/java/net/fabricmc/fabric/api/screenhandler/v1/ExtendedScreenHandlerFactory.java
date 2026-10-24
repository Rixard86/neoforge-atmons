// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.screenhandler.v1;

import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;

public interface ExtendedScreenHandlerFactory<D> extends NamedScreenHandlerFactory {
    D getScreenOpeningData(ServerPlayerEntity player);
}
