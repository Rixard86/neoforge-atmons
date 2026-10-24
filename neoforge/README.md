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
| `../src`, `../rtxremix-java` | Upstream sources. Two small edits, both still valid on Fabric: the `RtxRemixCommonMod.serverContentEnabled` switch (checked in `RtxRemixMod` and `WaterRapidParticleSpawner`) and vanilla's `build(null)` in `CopperBeaconBlockEntity` |
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
* `WorldRendererMixin` wraps NeoForge's `ModelData` breaking-texture call and its
  frustum-aware particle draws.
* Weighted and multipart model mixins are ported to NeoForge's `getQuads` overload
  (`compat/.../mixin/*NeoForgeMixin.java`), keeping NeoForge's render-type filter.
* `WindowMixin`'s GL 4.4 hint is optional: FML's early window creates the context
  and already takes the highest core profile (4.6 on RTX cards).
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
python3 neoforge/build.py prepare compile package verify
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
methods.

What it cannot check: rendering itself, the native runtime, and how other mods'
mixins behave at runtime. That needs the game.

## Instance setup

The *All the Mons - ATMons RTX* instance is a copy of the ATMons instance with:

* Sodium, Iris, ImmediatelyFast, Colorwheel, Colorwheel Patcher and Euphoria
  Patcher disabled (`.disabled`): they replace or batch the rendering RTX Remix
  captures from, or depend on Iris.
* Flywheel's backend set to `flywheel:off`, so Create's machines draw through
  vanilla block-entity rendering, which RTX Remix captures.
* No singleplayer worlds, logs, backups or regenerable caches.

Requirements: GeForce RTX 30-series or newer, NVIDIA driver 616.92 or newer,
FML's early window left on (`earlyWindowControl = true` in `config/fml.toml`).
