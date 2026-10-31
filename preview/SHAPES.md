# Terrain shape candidates

> Ids before 0.10.0 were `tectonic:<path>`; they are `terra_continental:<path>` now (same paths), and the seam is part of `resourcepacks/terrain`. The notes below keep the names of their day.

Recipes for `shape_pack.py`. Packs are written under `preview/out/` (not in git), so this file is
what makes a candidate reproducible. Targets: `docs/decisions.md`, 2026-10-23.

## blend2 (current candidate, 2026-10-23)

```bash
python preview/shape_pack.py preview/out/packs/blend2 coast_c0=0.2 coast_c1=0.4 coast_c2=1.0 mountain_band=0.22 jag=0.6 rough=0.6 giant_height=1.3 wetland_gate=0.39
```

With the pack's own `tectonic.json` (vertical_scale 3.005):
- **coast**: mountains reach full height from raw continentalness 0.2 instead of 0, about 600-900
  blocks inland instead of ~300, leaving a buildable coastal shelf.
- **mountain_band 0.22**: slightly fewer, thinner chains.
- **jag/rough 0.6**: fewer needles.
- **giant_height 1.3**: one massif per continent at about +1,100 (needs a Java
  `terra_continental:giant_peaks` before it can run in game).
- **wetland_gate 0.39**: Tectonic's wetland ponds at their original density, but only inside marsh
  patches covering ~10% of eligible ground (user: same lake density, less of the land). 0.21 gives
  ~25%. The earlier `wetland=1.5` thinned the ponds everywhere instead.

Variant for the river study (`RIVERS.md`): the same command into `preview/out/packs/blend2_norivers`
with `rivers=off` added, which flattens Tectonic's surface river channels to their banks.

Scorecard (4 seeds, steepness from 40 random land patches each): 11.9% of land over 45° (16% of
ground under +150, 37% between +150 and +400, 63% above); current pack 19.8%. With
`vertical_scale` 2.5 on top: 8.1% (target 5-8%), coastal walls 3%. Roughness doesn't move
steepness at all (tested 0.3 and 0).

## Knobby ranges and contour lines (2026-10-30, in the seam)

The user: ranges should read like the Tetons, Alps and Dolomites, not knobby, bumpy ridges; and
contour-line stripes on gentle ground (a plateau east of the main range, the giant's flanks).
Measured on the main range (1760,1920, 2 km, 4 blocks/px) with knob relief = std of height minus
its 68-block mean on ground 200+ above sea:

- **Knobs: the climate noises' domain warp.** Vanilla adds shift_x/shift_z after scaling the
  coordinates, at xz_scale 0.25: about +-16 blocks in the world. The terrain's continents, erosion
  and ridges run at 0.12-0.13, so the same shift moves samples about +-30 blocks at a 32-block
  wavelength and crumples every steep slope. Warp off: knob relief 11.2 -> 5.4, knobs 1,718 -> 377
  per km2. Ruled out (each <15%): jaggedness, roughness (no 3-D noise in this terrain at all, so the
  factor curve can't calm anything), the weathering and mountain-ridge detail noises, erosion's
  fine octaves.
- **Contour lines: a kink at the surface.** sloped_cheese was 4 * quarter_negative(v): 4v in the
  ground, v in the air. Interpolating across that kink inside the 8-block noise cells lifted the
  surface 0-9 blocks by where it fell in the cell, a sawtooth with an 8-block period that shows as
  a contour line every 8 blocks of height on gentle slopes. 4v on both sides keeps the ground's
  values; on the striped plateau the surface's gap to the height curve went from 1.7 +- 1.0 blocks to
  0.2 +- 0.4.

The user picked warp 0.5 (vanilla's size in the world) with the linear density, no crest:

```bash
python preview/shape_pack.py preview/out/packs/fix warp=0.5 linear_density=true
```

Four seeds (4151832666630476678, 12345, 8675309, 8261657684473197624), before -> after: land
over 45 deg 7.0% -> 5.9% (target 5-8%), needles 3.7% -> 1.7%, gentle ground 74.0% -> 75.7%;
mountain share, max height and ground over +900 unchanged; land 27.8% -> 27.2% (coasts wobble
less). Main range: knob relief 11.2 -> 7.5, knobs 1,718 -> 637 per km2. The giant: flank texture
7.6 -> 6.5 blocks and its outer flanks' contour stripes gone; summit unchanged (+1,127).
In game (rig, seed 4151832666630476678, 36,864 heights round the spawn coast): 98.0% of land
exact against the previewer, 99.1% within 1 block, 100% within 16; the misses cluster where the
game is lower (underground rivers and caves opening, which the previewer doesn't model).
Prototyped and not taken: a crest (crest_height, a knife-edged spine along each range's centre),
and warp 0.25.

## Range layouts (prototyped 2026-10-23; user picked A, erosion 0.12)

All on blend2 with `vertical_scale=2.5` and `giant_height=1.9` (1.3 left the giant short of
+1,000 at that scale). HD maps of seed 8261657684473197624 in `preview/out/ranges_hd/`.

| option | how | look | land over 45° |
|---|---|---|---|
| **A, erosion 0.09** | `erosion_scale=0.09` in tectonic.json | 2-3 long continuous ranges per continent, open lowlands | 6.6% |
| **A, erosion 0.12** | `erosion_scale=0.12` | the same, with slightly more, shorter ranges | 5.6-8.6% |
| B1-wide | `range_scale=0.07 range_width=0.32` in the pack | a planned-chain noise; too wide, back to a lattice | 8.1% |
| B1 / B1b | `range_width` 0.2 / 0.12 | chains too thin (4% of land mountain) | 5-6% |
| A + B1-wide | both | no different from A alone | 8.2% |

Erosion also feeds biome placement, so A shifts the biome layout too. Open issue for every
option: the giant's flanks show contour rings (Tectonic's terraced lowlands lifted onto the dome).

## In-game port (branch `wip/terrain-blend2`, 2026-10-23)

blend2 with `giant_height=1.9` in the seam plus Java `terra_continental:giant_peaks`, on the rig with
`vertical_scale` 2.5 and `deep_ocean_depth` -3.1 (terrain-only sweep, 627 s): heights around the
spawn giant (-3144,-208, 36,864 samples) match the previewer 99.3% exactly and 100% within 1 block,
summit y 1,151 in both. The rig audit's spawn-continent steepness (its own, stricter sampling):
27.5% of land over 45° with the current pack, 16% with the port.

## The giant, after the tour (2026-10-24, in the seam)

The user's tour found three faults on the giant: contour rings on its flanks, lowland biomes
running up it, and a lumpy cluster rather than one massif. On four seeds (4151832666630476678,
12345, 8675309, 8261657684473197624) the multi-seed check found a fourth: where the giant landed on
a Tectonic range, its top was cut flat by the world ceiling (+1,181 on 2 of 4).

The seam now carries this recipe, applied over the blend2 seam (`shape_pack.py` replaces the giant
already in it; the coast/band/jag/wetland keys are already in the seam):

```bash
python preview/shape_pack.py preview/out/packs/g27 giant_height=2.0 giant_cap=0.82 giant_warp=0.12 giant_spurs=0.35 giant_texture=0.04 giant_crags=0.6 giant_crags_reach=0.3 giant_biome=2.0 giant_biome_reach=0.8 giant_variety=1.0 hill_rivers=off hill_rivers_low=0.3 hill_rivers_high=0.45 hill_rivers_giant=false high_river_biomes=off
```

- **Rings**: a perfectly round, smooth dome shows its block steps as concentric rings. `warp`
  roughens the outline, `spurs` carves curving ridges and gullies down the flanks (polar noise,
  faded in below the summit so they don't meet in a starburst), `texture` adds knolls ~250 across.
- **Biomes**: `giant_biome` pulls the biome erosion and weirdness (the noise router's, which
  terrain doesn't read) toward vanilla's peaks within 0.8 of the radius: frozen/jagged/howling
  peaks on top, a grove or snowy-slopes ring, and no river biomes on the upper flanks, on all four
  seeds.
- **One massif, but varied**: flattening Tectonic's relief under the giant (`giant_flatten`) made
  one clean mountain, but the user preferred the original's rugged, varied companions and only
  wanted its summit. So `giant_cap` leaves Tectonic's relief alone and bends it down, smoothly,
  only where giant + terrain would come within reach of the main summit (0.82 of the height, with
  a knee so capped mountains keep their shape). Main summits +1,120 to +1,155 on the four seeds,
  companions at most +951, none flat (1-8 grid cells within 3 blocks of the top). `giant_height`
  2.05 with cap 0.8 touched the ceiling (+1,185) on one seed.
- **Character per massif** (`giant_variety`, the user: worlds and continents need more character):
  each massif draws its own height (down to 0.94), radius (±20%), summit shape, spur strength and
  stretch (1 to 1.6 times along a seeded axis), from the jitter noise at fixed coordinates. With
  `companions` unset, variety can still add up to two lesser summit cones.
- `crags` adds Tectonic's crag texture within 0.3 of the radius, so the summit isn't smooth.
- Tried and dropped: summit horns/needles (`giant_summit` 0.5-0.6; the user kept the broad peak),
  fixed companion cones without variety (too alike from seed to seed).

**Rivers through ranges, river biomes at altitude** (2026-10-24/25, the last four keys above). The
user: only the cuts that pass through a range should go, and river biomes only at altitude, since
real rivers need theirs for spawning.
- `hill_rivers=off` with 0.3/0.45: a river channel is filled to its bank only where the ground
  around it (the offset with every channel filled) is mountain, so cuts through ranges close and
  everything else stays: 0.1-1% of each 8 km test area changes. `hill_rivers_giant=false` leaves
  the giant's own gorges: on the giant small offset dips are multiplied into gorges up to ~110
  deep whose walls are its cliffs and spurs, and filling them (any share, even only below sea
  level) rounded the cliffs and subdued the ridges (previews g19-g25, rejected by the user).
- `high_river_biomes=off`: where the ground's offset passes 0.15 -> 0.3 (about +60 -> +120), the
  biome weirdness band around 0 (rivers) is stretched to slivers, per column, so a river down in a
  valley keeps its biome. River biome share above +120: 0.5-1.6% -> 0% on the four seeds; below
  +20 unchanged (3.7-8.8%).
- A bug it found: the giant's biome pull took weirdness toward +0.667 and carried negative values
  through 0, painting a ring of river biome round every giant. Now it pulls toward +-0.667 by sign.
- Also tried and dropped for the giant's step lines: squashing its low ground (`giant_terraces`) and
  finer texture (`giant_grain`): both removed Tectonic's plateaus and valleys, which the user wants
  kept.

- Port check (rig, same grid as above): 99.3% exact, 100% within 1 block.

**Shore biomes only at the shore** (2026-10-25, `high_coast_biomes=off`, defaults 0.05 / 0.12 /
-0.1; the seam's `noise_router/continents` and `shape/continents_biome`). The user's flight found
large flat Stony Shore at y ~213 in the mountains behind the spawn continent's south coast (around
2300-2600, 5300-5900, seed 4151832666630476678). Not the rivers branch (13.6% of that window with
planned rivers off, 13.7% on): the coast remap raises mountains on ground whose biome
continentalness is still in vanilla's coast band (-0.19 to -0.11), where low erosion picks
stony_shore. Those cells sat at offset ~0.22 (p50), C -0.18 to -0.12, erosion -0.5 to -0.3. Now
the biome C is lifted to at least -0.1 where the ground's offset passes 0.05 -> 0.12 (about +20 ->
+50), per column; terrain untouched.

| window | stony_shore | shore biomes | > 128 from sea | at offset > 0.12 |
|---|---|---|---|---|
| user's 3 km (2400, 5500), step 16 | 13.4 -> 4.2% | 13.4 -> 4.2% | 4.95 -> 0.19% | 8.8 -> 0% |
| 12 km round spawn, 4151832666630476678 | 1.56 -> 0.64% | 2.66 -> 1.74% | 0.37 -> 0.05% | 0.87 -> 0% |
| 12345 | 1.29 -> 0.63% | 2.30 -> 1.64% | 0.18 -> 0.03% | 0.61 -> 0% |
| 8675309 | 0.92 -> 0.34% | 1.84 -> 1.26% | 0.11 -> 0.01% | 0.54 -> 0% |
| 8261657684473197624 | 0.32 -> 0.17% | 1.82 -> 1.67% | 0.05 -> 0.01% | 0.14 -> 0% |

Shore biomes within 64 blocks of the sea (offset < 0) keep 80-98% of their share on the 12 km
windows (1.82 -> 1.49% etc.; 64% in the user's window, where mountains come down to the sea), and no ocean biome lands on raised ground either way. A steeper ramp (0.02 -> 0.08) took
another 0.6% off the user's window but ate more of the real shore (3.94 -> 3.36% within 64).
Pictures: `preview/out/giant/seeds/summits17.png` (not in git; regenerate with the command above,
`terrain_map.py` and `preview/biome_patch.py`).

Port check (rig, terrain-only sweep, 569 s, 36,864 heights around the spawn giant -3144,-208 with
the pack's settings): the Java matches the previewer 99.3% exactly and 100% within 1 block, summit
y 1,185 (+1,122) in both.

## Seas, world height, plateaus (2026-10-23)

- **Deep seas**: `deep_ocean_depth` -3.1 (from -2.0) puts the open-ocean floor at y ~-326 (p50),
  deepest -361, about 400 below sea level as the user asked; `min_y` -512 leaves ~150 blocks under it.
- **World height**: at `giant_height` 1.9 the giant's summit is cut flat at +1,181 (y 1,244):
  Tectonic's top slide fades the terrain out in the 30 blocks below `max_y` 1,264. Proposal:
  `max_y` 1,408 and `min_y` -576 (a couple hundred blocks above the tallest peak and below the
  deepest sea, as the user asked). Not applied anywhere yet; changing world height also changes
  TC's world-type height modifiers and costs disk and generation time.
- **Plateaus** (flat ground 80+ above sea, over 80 blocks): 0.8% of land with today's settings,
  4.8-5.9% with range option A, 2.2% with B1-wide: "uncommon" under A.
- **Giant bearing**: the massif's bearing from its continent's centre was clamped, which sent many
  seeds' giants due west; now spread around the circle (Python and Java).

Tried and dropped:
- **valleys** (`valley_*`): they follow the ridge lines, which cross every range, so they cut ranges
  into pieces (user, 2026-10-23). Valleys that run along ranges need planned drainage (river study).
- **flat_lift**: didn't reduce ponds; the ponds come from the wetland noise.
