# RTX Remix for Minecraft on NeoForge (All the Mons)

Unofficial NeoForge 21.1 port of NVIDIA's
[RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft),
built for the *All the Mons - ATMons* modpack (Minecraft 1.21.1).

**Client only.** The NeoForge build registers no blocks, items, particle types,
menus or network payloads, so a client with it joins the normal ATMons server
and players without it see nothing different. The Copper Beacon, its menu and
the water-rapids particles are left out for that reason.

## Layout

| Path | What it is |
|---|---|
| `../src`, `../rtxremix-java` | Upstream sources. Three small edits, all still valid on Fabric: the `RtxRemixCommonMod.serverContentEnabled` switch (checked in `RtxRemixMod`, `WaterRapidParticleSpawner` and `LightManager`, so nothing touches the unregistered Copper Beacon classes), vanilla's `build(null)` in `CopperBeaconBlockEntity`, and RTX rendering switching itself off when the renderer does not start, so the world falls back to vanilla instead of drawing nothing |
| `patches/` | NeoForge-only changes to upstream files, applied at build time (`git apply`), so the tree stays mergeable with upstream |
| `compat/java/com/nvidia/rtxremix/neoforge` | NeoForge entrypoint, event bridge, NeoForge-specific mixins |
| `compat/java/net/fabricmc` | The Fabric API surface the mod uses: vendored from Fabric API where it is pure API (Apache-2.0, FabricMC), implemented over NeoForge where it is not. Relocated to `com.nvidia.rtxremix.neoforge.fabric` in the jar |
| `compat/neoforge.accesswidener` | Access the port needs beyond upstream's widener |
| `neoforge.mods.toml` | Mod metadata template |
| `build.py`, `tools/` | Offline build and verification |

### What changed for NeoForge

* `SectionBuilderMixin` follows NeoForge's `compile(..., List<AdditionalSectionRenderer>)`
  overload, which is where section meshing happens on NeoForge.
* Terrain capture passes NeoForge model data to models, so connected-texture and
  other data-driven modded models capture with their real geometry.
* Terrain capture takes each block's render types from its model, per render
  type as NeoForge's section compiler does. Modded blocks name their render
  type in the model JSON, which vanilla's layer map never sees, so classifying
  by that map drew modded cutouts (flowers, bushes) as opaque quads.
* `WorldRendererMixin` wraps NeoForge's `ModelData` breaking-texture call and its
  frustum-aware particle draws.
* Weighted and multipart model mixins are ported to NeoForge's `getQuads` overload
  (`compat/.../mixin/*NeoForgeMixin.java`), keeping NeoForge's render-type filter.
* `WindowMixin`'s GL 4.4 hint goes in before `ImmediateWindowHandler.setupMinecraftWindow`,
  where NeoForge creates the window.
* Remix presents into a child window of its own over Minecraft's
  (`RemixPresentWindow`) instead of Minecraft's window itself. Where the NVIDIA
  driver's OpenGL holds Minecraft's window, it refuses Remix's Vulkan swapchain
  (`VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`) and Remix does not start. The child
  window is disabled and transparent to clicks, so input still goes to
  Minecraft, and Remix's menus take input through the runtime's own overlay
  window. `-Drtxremix.neoforge.presentWindow=main` restores upstream's behaviour.
* Opening a Remix menu points raw mouse input back at the runtime's menu input
  window (`RemixOverlayInput`). With Minecraft's "Raw Input" on, GLFW takes the
  process's raw mouse registration while the cursor is locked and removes it on
  unlock, which left the menus without a mouse after the first time in a world.
* `ChunkRendererRegionMixin` retains snapshots in NeoForge's region constructor,
  which `RenderRegionCache` calls directly. Retaining only in vanilla's
  constructor let a finished section release snapshots that neighbouring
  sections were still reading, which left holes and wrong blocks in the
  terrain even with RTX off.
* Fluids come from NeoForge's `IClientFluidTypeExtensions`, so modded fluids work.

## Building

There is no Maven access in the build environment, so the build uses what the
CurseForge launcher installed (Minecraft 1.21.1, NeoForge 21.1.249, Mojang's
mappings), Yarn and Intermediary from GitHub, and NVIDIA's release jar for the
native RTX Remix runtime (not built here). Linux shell, Python 3 and JDK 21:

```sh
export RTX_INSTALL=~/curseforge/minecraft/Install       # CurseForge's Install folder
export RTX_RELEASE_JAR=.../rtx-remix-renderer-0.1.0-...jar  # from NVIDIA's v0.1.0 release
export RTX_JDK=... RTX_YARN=.../yarn/mappings RTX_INTERMEDIARY=.../1.21.1.tiny
export RTX_NEOFORGE=21.1.249 RTX_WORK=~/rtx/work      # defaults shown
python3 neoforge/build.py prepare compile package verify
# -> $RTX_WORK/dist/rtx-remix-renderer-<version>-neoforge.<n>.jar
```

`prepare` builds a Yarn-named copy of NeoForge's Minecraft and NeoForge, so the
upstream sources compile unchanged; `package` remaps the result back to Mojang's
names, which NeoForge runs with. Mojang's mappings are only read locally and
never written to the repository.

## Verification

`build.py verify` checks the finished jar against the real NeoForge-patched game
without starting it: every class, field and method reference resolves and is
accessible after the access transformer, and every mixin's target class,
target method, injection point (with ordinals), `@Shadow`, `@Accessor` and
handler signature matches the bytecode it will be applied to. With
`RTX_INSTANCE_MODS` set it also lists other mods that inject into the same
methods. It warns when NeoForge calls an overload of a mixin's target method
that never goes through the target.

What it cannot check: rendering itself, the native runtime, and how other mods'
mixins behave at runtime. That needs the game.

## Instance setup

The *All the Mons - ATMons RTX* instance is a copy of the ATMons instance with:

* Sodium, Iris, ImmediatelyFast, Colorwheel, Colorwheel Patcher and Euphoria
  Patcher disabled (`.disabled`): they replace or batch the rendering RTX Remix
  captures from, or depend on Iris.
* Flywheel's backend set to `flywheel:off`, so Create's machines draw through
  vanilla block-entity rendering, which RTX Remix captures.
* No logs, backups or regenerable caches. Singleplayer worlds were copied over
  afterwards; the ATMons instance keeps its own.
* Unlinked from the CurseForge modpack (a custom profile, like any hand-built
  instance), so CurseForge never offers an ATMons update for it. A pack update
  would replace the mod list and turn Sodium and Iris back on. The linked
  profile is kept as `minecraftinstance.modpack-link-backup.json`.

To move to a new ATMons version: update the normal instance, rebuild the jar
against its NeoForge version (`RTX_NEOFORGE`), run `verify` with
`RTX_INSTANCE_MODS` pointing at the updated mods, and set the RTX instance up
again from the updated one.

Requirements: GeForce RTX 30-series or newer, NVIDIA driver 616.92 or newer.
