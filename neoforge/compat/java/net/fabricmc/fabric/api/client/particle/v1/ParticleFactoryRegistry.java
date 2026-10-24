// SPDX-License-Identifier: Apache-2.0
// NeoForge port: Fabric API surface used by RTX Remix for Minecraft, implemented
// over NeoForge. Relocated to com.nvidia.rtxremix.neoforge.fabric at packaging.
package net.fabricmc.fabric.api.client.particle.v1;

import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;

/**
 * Particle types live in a registry NeoForge synchronises with the server, so
 * the client-only port registers none; reaching this is a porting bug.
 */
public interface ParticleFactoryRegistry {
    static ParticleFactoryRegistry getInstance() {
        throw new UnsupportedOperationException("particle registration is disabled in the client-only NeoForge port");
    }

    <T extends ParticleEffect> void register(ParticleType<T> type, ParticleFactory<T> factory);

    <T extends ParticleEffect> void register(ParticleType<T> type, PendingParticleFactory<T> factory);

    @FunctionalInterface
    interface PendingParticleFactory<T extends ParticleEffect> {
        ParticleFactory<T> create(FabricSpriteProvider provider);
    }
}
