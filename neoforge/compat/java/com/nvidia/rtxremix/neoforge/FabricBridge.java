// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Connects the Fabric API events and registries the mod uses to their NeoForge
 * counterparts. Events that NeoForge has no exact equivalent for (world render
 * stages, client start/stop, ticks, client commands) are fired from this port's
 * own mixins at the same injection points Fabric API uses.
 */
public final class FabricBridge {
    private static final List<KeyBinding> PENDING_KEYS = new ArrayList<>();
    private static boolean keysRegistered;
    private static final List<IdentifiableResourceReloadListener> RELOAD_LISTENERS = new ArrayList<>();
    private static boolean reloadListenersRegistered;
    private static final List<Layer> MODEL_LAYERS = new ArrayList<>();

    private static final PacketSender NO_PAYLOADS = payload -> {
        throw new UnsupportedOperationException("custom payloads are disabled in the client-only NeoForge port");
    };

    private record Layer(EntityModelLayer layer, Supplier<TexturedModelData> data) {
    }

    private FabricBridge() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, FabricBridge::onRegisterKeys);
        modBus.addListener(RegisterClientReloadListenersEvent.class, FabricBridge::onRegisterReloadListeners);
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions.class, event -> {
            synchronized (MODEL_LAYERS) {
                for (Layer l : MODEL_LAYERS) {
                    event.registerLayerDefinition(l.layer(), l.data());
                }
            }
        });
        modBus.addListener(RegisterShadersEvent.class, event -> {
            try {
                CoreShaderRegistrationCallback.EVENT.invoker().registerShaders((id, format, callback) ->
                        event.registerShader(new ShaderProgram(event.getResourceProvider(), id, format), callback));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(ChunkEvent.Load.class, event -> {
            if (event.getChunk() instanceof WorldChunk chunk) {
                if (event.getLevel() instanceof ClientWorld world) {
                    ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world, chunk);
                } else if (event.getLevel() instanceof ServerWorld world) {
                    ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world, chunk);
                }
            }
        });
        game.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getChunk() instanceof WorldChunk chunk) {
                if (event.getLevel() instanceof ClientWorld world) {
                    ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(world, chunk);
                } else if (event.getLevel() instanceof ServerWorld world) {
                    ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(world, chunk);
                }
            }
        });
        game.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(client.getNetworkHandler(), NO_PAYLOADS, client);
        });
        game.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(client.getNetworkHandler(), client);
        });
        game.addListener(ScreenEvent.Init.Post.class, event -> ScreenEvents.AFTER_INIT.invoker().afterInit(
                MinecraftClient.getInstance(), event.getScreen(), event.getScreen().width, event.getScreen().height));
    }

    // --- key bindings ---

    public static void registerKeyBinding(KeyBinding key) {
        synchronized (PENDING_KEYS) {
            if (!keysRegistered) {
                PENDING_KEYS.add(key);
                return;
            }
        }
        // Registered after NeoForge's event: append to the live options, as Fabric does.
        MinecraftClient client = MinecraftClient.getInstance();
        GameOptions options = client.options;
        if (Arrays.asList(options.allKeys).contains(key)) {
            return;
        }
        KeyBinding[] keys = Arrays.copyOf(options.allKeys, options.allKeys.length + 1);
        keys[keys.length - 1] = key;
        options.allKeys = keys;
        options.load();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        synchronized (PENDING_KEYS) {
            for (KeyBinding key : PENDING_KEYS) {
                event.register(key);
            }
            PENDING_KEYS.clear();
            keysRegistered = true;
        }
    }

    // --- resource reload listeners ---

    public static void registerClientReloadListener(IdentifiableResourceReloadListener listener) {
        synchronized (RELOAD_LISTENERS) {
            if (reloadListenersRegistered) {
                throw new IllegalStateException("client reload listener registered too late: " + listener.getFabricId());
            }
            RELOAD_LISTENERS.add(listener);
        }
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        synchronized (RELOAD_LISTENERS) {
            for (IdentifiableResourceReloadListener l : RELOAD_LISTENERS) {
                event.registerReloadListener(l);
            }
            reloadListenersRegistered = true;
        }
    }

    // --- entity model layers ---

    public static void registerModelLayer(EntityModelLayer layer, Supplier<TexturedModelData> data) {
        synchronized (MODEL_LAYERS) {
            MODEL_LAYERS.add(new Layer(layer, data));
        }
    }
}
