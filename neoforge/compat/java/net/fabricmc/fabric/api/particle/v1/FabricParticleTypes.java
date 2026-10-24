// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.particle.v1;

import net.minecraft.particle.SimpleParticleType;

public final class FabricParticleTypes {
    private FabricParticleTypes() {
    }

    public static SimpleParticleType simple() {
        return simple(false);
    }

    public static SimpleParticleType simple(boolean alwaysSpawn) {
        return new SimpleParticleType(alwaysSpawn) {
        };
    }
}
