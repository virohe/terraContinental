# Compatibility

## Global namespace overrides

Terra Continental overrides four density functions in the **`minecraft` namespace**:

- `data/minecraft/worldgen/density_function/overworld/continents.json`
- `data/minecraft/worldgen/density_function/overworld/erosion.json`
- `data/minecraft/worldgen/density_function/overworld/ridges.json`
- `data/minecraft/worldgen/density_function/overworld/depth.json`

### What this means

These overrides are **intentional** — they are how the mod rewires Minecraft's
noise router without requiring a custom dimension preset.

**All dimension presets are affected.** The vanilla Default, Large Biomes, and
Amplified presets all reference these same density functions, so installing this
mod rewrites terrain for all of them.

**Vanilla Default worlds become Terra Continental worlds.** If a player creates a
new world using the default preset with this mod installed, they will get Terra
Continental terrain, not vanilla terrain.

### Conflicts with other worldgen mods

Other worldgen mods that override the same four files (Tectonic, Terralith,
William Wyther's Expanded Ecosphere, etc.) will **conflict silently** by
datapack load order. Whichever mod's datapack is loaded last wins. There is no
error or warning; the losing mod's overrides are simply ignored.

If you are building a modpack that includes Terra Continental alongside another
worldgen mod, you must decide which mod's continents/erosion/ridges/depth
overrides should win and configure datapack load order accordingly, or use a
datapack that explicitly merges both.
