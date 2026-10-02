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
- `/continental` commands: biome dump and biome map.

## Config

`config/terra_continental-common.toml`: continent shaping, weirdness scale, biomes (ocean
formations, temperate spawn continent), caves. On the first start after the rename, an existing
`terra_incognita-common.toml` is copied to the new name (the old file stays as a backup).

`terra_continental-structures.toml`: spacing multipliers — keep them at 1.0; they never affected
generation, only `/locate` (see the workspace CLAUDE.md).

## Tools

`preview/` is a Python simulator of the density functions (maps without running the game).
World census tools live in the workspace `tools/`.

This project started from the NeoForge MDK template (`TEMPLATE_LICENSE.txt`).
