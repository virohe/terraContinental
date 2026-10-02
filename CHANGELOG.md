# Terra Continental changelog

Tags: `tc-vX.Y.Z` from 0.8.0 (`ti-vX.Y.Z` before the rename). Entries before 0.7.3 were
reconstructed on 2026-09-27; everything from
0.0.1 to 0.7.3 landed in one catch-up commit (`15597b7`) because it had not been committed as
it happened. Earlier history is in `git log`.

## 0.8.0 — 2026-10-01
- **Renamed `terra_incognita` → `terra_continental`** (Terra Continental), so the mod no longer
  shares a name with the modpack. Everything moved with it: mod id, jar name, Java package
  (`com.terracontinental`), the `terra_continental:` worldgen namespace, the built-in pack ids
  (`terra_continental:resourcepacks/*`), the world presets ("Terra Continental — …") and the
  debug commands (`/continental biome_map`, `/continental dump_biomes`). Tags are now `tc-v*`.
- **Existing worlds** load unchanged: they only remember the old built-in pack ids, which
  Minecraft drops as missing while the renamed always-active packs take their place.
  Terrain is identical; no worldgen values changed.
- **Config**: on first start, `config/terra_incognita-common.toml` is copied to
  `terra_continental-common.toml` so tuning carries over (the old file stays as a backup).
  The per-world structures config starts from defaults; its only setting is inert (TI-102).
- Terra Towns 0.4.9 depends on `terra_continental`; older Terra Towns versions need the old mod.

## 0.7.4 — 2026-09-29
- **End biome variety** built in (`end_biomes` pack). On a world made with a non-BetterX world
  type, WorldWeaver picks BetterEnd biomes from the End router's temperature, which vanilla
  leaves at a constant 0 -- so the whole outer End was one highland biome
  (`glowing_grasslands`) plus `flower_islets`. The pack gives the End a real 2D temperature
  noise. Only newly generated End chunks change. Check: `tools/python/end_biome_census.py`.

## 0.7.3 — 2026-09-26
- **Temperate spawn continent**: the Tectonic seam's `noise_router/temperature` blends the
  spawn continent toward Terra Incognita's temperate climate via the new
  `terra_incognita:spawn_climate_mask`. Config: `[biomes] temperate_spawn_*`. A seed whose
  spawn continent was 99.5% frozen went from 1 hamlet site to 8.
- **Distant towns** built in: village spacing 129/30 for the vanilla and BWG grids (~8-12
  villages per continent), as an always-active built-in pack.

## 0.7.2 — 2026-09-26
- **Big trees** built in (raised canopies for horse clearance), previously only a world
  datapack in one save. Lang file encoding fixed.

## 0.7.1
- Goofball Haven's version (that instance stays on it).

## 0.6.x
- **Tectonic seam** as an always-active built-in pack: radial continentalness drives Tectonic's
  shorelines; biome-only overrides of `noise_router/{continents,depth,vegetation}`.
- Cave depth rescaling (`cave_depth_scale`) so vanilla's underground biome bands exist again.
- BWG mixins: rarer ocean formations; the pumpkin-patch worldgen deadlock fix.
- `/incognita` biome dump and biome map commands.

## 0.0.1 — 2026-06
- Radial continent shaping with multiple continents, tectonic ridges, three-tier rivers,
  structure spacing (later found to be dead code), Python preview simulator.
