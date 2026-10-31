# Terra Continental (`terra_continental`)

Continent-scale worldgen for the Terra Incognita modpack (NeoForge 1.21.1): a guaranteed spawn
continent plus seed-placed continents across deep ocean, tectonic ridges and a river hierarchy,
layered on top of Tectonic through an always-active "seam" datapack.

Called `terra_incognita` until 0.8.0, when it was renamed so the mod and the modpack no longer
share a name.

- **Changes**: [CHANGELOG.md](CHANGELOG.md)
- **Compatibility**: [COMPATIBILITY.md](COMPATIBILITY.md) — which vanilla density functions are
  overridden, and what that means for other worldgen mods.

## Build

```bash
./gradlew build -x test     # jar in build/libs/terra_continental-<version>.jar
```

The version lives only in `gradle.properties` (mods.toml is template-expanded). Releases go
through `../scripts/release.sh terra-continental <version>`.

## What ships inside the jar

- Custom density-function types: `radial_falloff`, `scaled_weirdness`, `cave_depth_scale`,
  `spawn_climate_mask`.
- Built-in, always-active datapacks (`src/main/resources/resourcepacks/`, registered in
  `TerraContinental.addPackFinders`):
  - `tectonic_seam`: biome-only overrides of Tectonic's noise router (continents, depth,
    vegetation, temperature).
  - `big_trees`: raised tree canopies (built by `tools/pregen/build-bigtrees-datapack.ps1`).
  - `distant_towns`: village spacing (built by `tools/pregen/build-village-spacing-datapack.ps1`).
  - `end_biomes`: a temperature noise for the End router, so WorldWeaver spreads BetterEnd's
    biomes on worlds that keep vanilla's End biome source.
- BWG compatibility mixins (ocean formations, pumpkin-patch deadlock).
- `/continental` commands: biome dump, biome map, and `villages` (list and teleport to the villages
  on your continent).

## Config

`config/terra_continental-common.toml`: continent shaping, weirdness scale, biomes (ocean
formations, temperate spawn continent), caves. On the first start after the rename, an existing
`terra_incognita-common.toml` is copied to the new name (the old file stays as a backup).

`terra_continental-structures.toml`:
- `[global] structure_spacing_multiplier` (1.5) and `[villages] village_spacing_multiplier` (1.0):
  every random-spread structure's spacing, and the village grids on top. They only shape the
  continents other than spawn now.
- `[spawn_villages]`: the spawn continent's planned villages. `enabled` (true), `count` (10; keep
  at least 9: up to 8 players plus the central village), `min_gap` (3000 blocks), `max_relief`
  (14: steepest ground a village may start on), `themed_max` (0: up to this many desert, savanna
  or snowy villages on temperate ground).

## World types

**TC: Large Biomes** (what a new world starts on), **TC: Temperate** and **TC: Climates**, listed
first, then vanilla's Default and the rest; see CHANGELOG 0.9.0. Dedicated servers: set
`level-type=terra_continental:large_biomes`. Large Biomes and Climates use copies of Tectonic's overworld
noise settings: regenerate them with `tools/make_world_types.py <tectonic.jar>` after a Tectonic
update. Dedicated servers need WorldWeaver's `force_default_world_preset` off
(`config/wover/main.json`), or WorldWeaver replaces the world type.

## Tools

`preview/` is a Python simulator of the density functions (maps without running the game).
`preview/terrain_preview.py` previews terrain heights for Tectonic settings × seeds, matching the
game's heights (parity in `docs/testing.md` §2a'').
World census tools live in the workspace `tools/`.

This project started from the NeoForge MDK template (`TEMPLATE_LICENSE.txt`).
