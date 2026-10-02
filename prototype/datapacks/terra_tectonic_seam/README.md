# Terra Continental × Tectonic — seam prototype

Proves the original promise: **your radial continentalness decides where continents and
oceans are; Tectonic supplies the grand relief** (towering mountains on your land, abyssal
floors in your ocean gaps).

## What it does
Overrides two density functions (this pack loads last, so it wins over both mods):

| File | Override | Effect |
|------|----------|--------|
| `tectonic:noise/raw_continents` | → `terra_continental:continent_base` | **Terrain** height pipeline reads your continents |
| `minecraft:overworld/continents` | → `terra_continental:radial_continent_mask` | **Biome** continentalness stays radial (sim stays valid) |

## Test procedure (fast)
1. **Enable Tectonic**: `config/tectonic.json` → `"general": { "mod_enabled": true }`.
   (Height is already grand: `max_y 1024`, `min_y -256`, `vertical_scale 2.625`.)
2. **Optional but recommended — shrink continents so coasts are near spawn** for a quick look.
   In `config/terra_continental-common.toml` temporarily set:
   ```
   core_radius = 2000
   border_radius = 6000
   transition_width = 1500
   central_land_radius = 700
   continent_spread = 3500
   continent_radius = 900
   ```
   (Restore your real values afterward — this is just so you can fly continent→ocean→continent
   in under a minute instead of tens of thousands of blocks.)
3. **Create a new world** → on the Create World screen open **Data Packs**, drag in
   `terra_tectonic_seam.zip`, move it to *Selected*, then create. (Must be selected at
   creation — worldgen bakes at spawn.)
4. Spawn on the central continent, fly up / spectator out toward a coast. Screenshot:
   - Do continents sit where the simulator's map says?
   - Do they wear Tectonic's mountains?
   - Are the ocean gaps deep and intimidating?

## Calibration knobs (if first pass is off)
- Oceans too uniformly deep → remap `continent_base` ocean range into raw_continents
  `[-0.45, -0.2]` instead of identity (edit `raw_continents.json`).
- Coastlines in the wrong place → add a linear shift/scale in `raw_continents.json`.
- Land not tall enough → that's Tectonic config (`vertical_scale`, `elevation_boost`).
