# River study: planned drainage on the blend2 terrain

> Ids before 0.10.0 were `tectonic:<path>`; they are `terra_continental:<path>` now (same paths), and the seam is part of `resourcepacks/terrain`. The notes below keep the names of their day.

Investigation only (2026-10-23): no mod source or seam file changed. The question was whether
routed rivers (decisions.md, 2026-10-23: 3-5 major rivers per continent with tributaries, down to
the sea, boat-navigable far inland, rapids and waterfalls in the mountains) can replace Tectonic's
surface rivers, and what that would cost.

**Short answer: yes, the terrain drains into believable networks, but on today's continents
"major" means a 5-12 km river with an 8-30 km² basin, and the long rivers rise in mountain-ringed basins that must become either lakes
or gorges.** Planning is cheap if it reads
Tectonic's 2-D offset instead of full heights. The carving and the water above sea level are two
new pieces of Java, and each needs an in-game prototype.

## How it was run

```bash
# packs (recipe in SHAPES.md); rivers=off flattens Tectonic's surface river channels
python preview/shape_pack.py preview/out/packs/blend2 coast_c0=0.2 coast_c1=0.4 coast_c2=1.0 mountain_band=0.22 jag=0.6 rough=0.6 giant_height=1.3 wetland_gate=0.39
python preview/shape_pack.py preview/out/packs/blend2_norivers <same> rivers=off
# heightfield at 64 blocks per cell, priority-flood + D8, maps (~3-7 min per seed on 8 workers)
python preview/rivers.py --seed 4151832666630476678 --pack preview/out/packs/blend2_norivers
python preview/rivers.py --seed 12345 --pack preview/out/packs/blend2_norivers --radius 14336
python preview/rivers.py --seed 8675309 --pack preview/out/packs/blend2_norivers --radius 14336
#   ... and each again with --lake-depth -1 --tag _fill (fill every basin, no breaching)
```

Outputs in `preview/out/rivers/` (not in git, like every pack and map):
`<seed>_rivers.png` (the network, 12-block lake cap), `<seed>_rivers_fill.png` (no breaching),
`<seed>_tectonic.png` (Tectonic's own river lines over the same ground), `<seed>_stats*.json`, and
the cached heights and ridges `.npy` files.

`rivers.py`'s steps: ocean = sea-level cells joined to the grid edge; the continent = the land
component holding the origin; a priority-flood (Barnes 2014, +epsilon) from the coast; D8 flow on
the result; accumulation with uniform rain. **Hybrid fill and breach** (`--lake-depth`, default 12):
a basin is filled at most 12 blocks deep, and the rest of its spill path is carved down so it
falls steadily to the sea. Rivers are cells draining >= 1 km² (`--trib`); a major river is a basin
of >= 8 km² (`--major`). Rapids are a water-surface drop of 4-20 blocks per 64-block cell; falls
are 20 or more.

### `rivers=off` (shape_pack.py)

Tectonic's river channel is the first one or two points of every spline over
`ridges_folded` (|R| <= 0.05, offset -0.06 to -0.15, below sea level). There are 14 such splines in
`terrain_spline/offset/continents`, plus more in `region/club`, `club_weak`, `diamond`, `heart`,
`spade` and `spade_weak`. The option sets those points to the bank value (the first point with
offset >= 0, at |R| 0.1-0.15) with a zero slope, so the ground keeps its local height. On the
maps, the old river lines no longer leave any channel. `depth_additive`'s R spline (0 at
|R| 0.125) is not a river and is left alone.

## Results (blend2 + rivers=off, 64-block cells)

| seed | continent | basins >= 8 km² | top basins (km²) | river network | lakes kept (cap 12) | gorges | lakes if only filled |
|---|---|---|---|---|---|---|---|
| 4151832666630476678 | 174 km² | 4 | 28.8, 20.0, 13.4, 11.2, 5.6 | 69 km | 18.7 km², largest 4.5 | 2.4 km², cut p50 10 / p90 67 / max 180 | 33.9 km², p50 depth 40, max 211 |
| 12345 | 222 km² (\*) | 3 | 30.3, 25.9, 9.2, 7.9, 7.3 | 86 km | 27.8 km², largest 8.8 | 2.5 km², cut 12 / 60 / 139 | 41.7 km², p90 depth 105 |
| 8675309 | 96 km² | 2 | 18.4, 11.8, 7.4, 4.5, 4.4 | 42 km | 7.6 km², largest 1.3 | 1.5 km², cut 12 / 62 / 185 | 18.7 km², p50 depth 67 |

(\*) The continent runs off the 28 km grid, so both its area and its west basins are cut short.

The major rivers' main stems (mouth to the 1 km² source; "sea-level reach" = how far from the
mouth the water surface stays within 8 blocks of sea level):

| seed | basin | stem | sea-level reach | rapids / falls cells in basin |
|---|---|---|---|---|
| 4151832666630476678 | 28.8 / 20.0 / 13.4 / 11.2 | 10.6 / 9.5 / 6.5 / 2.8 km | 10.6 / 9.5 / 4.8 / 1.2 km | 25/2, 6/1, 1/0, 8/1 |
| 12345 | 30.3 / 25.9 / 9.2 | 10.5 / 12.4 / 4.3 km | 10.0 / 8.5 / 4.3 km | 0/0, 9/5, 0/0 |
| 8675309 | 18.4 / 11.8 | 9.7 / 4.2 km | 6.2 / 4.2 km | 9/0, 0/0 |

What the maps show:

1. **Continents are small for a "3-5 major rivers" brief.** The spawn continents measure 96-222
   km², roughly 10-17 km across. At 8 km², the counts are 4, 3 and 2. At 5 km² they would be 7, 9
   and 3. A river system that spans a continent is about 10 km long, the scale of a short coastal
   river in Norway or the Waipiʻo stream, not a Rhine. Larger or longer rivers need larger
   continents (`continent_radius`, `central_land_radius`) rather than different routing.
2. **The blend2 ranges enclose basins.** On every seed the long rivers start in interior valleys
   ringed by the mountain bands (the erosion zero-lines form a lattice). When only filled, those
   basins become lakes of 5-9 km², 40-200 blocks deep, with surfaces at y 110-190: 10-20% of
   the continent under water. Breaching with a 12-block cap keeps the Como-like lakes (shallow,
   1-9 km²) and cuts the outlet as a gorge through the rim. The cuts are usually short (median
   10-12 blocks, p90 60-67), and the deepest per continent is 139-185 blocks
   (Lauterbrunnen-like, which matches the brief). The lake cap is the main design control: deeper caps give more and higher lakes and
   fewer canyons.
3. **The rivers are boat-friendly in the lowlands.** Most main stems stay within 8 blocks of sea
   level for most of their length (sea-level reach 4-10 km). Rapids and falls cluster where a
   stem breaches a rim or a tributary drops off a range. Only 37-79 river cells per continent
   (2-5 km) lie more than 100 blocks above the sea: high ground drains less than 1 km² before it
   reaches a valley, so mountain streams fall below this threshold and would be tributaries.
4. **Tectonic's lines don't drain.** `<seed>_tectonic.png` shows |ridges| < 0.03 on land as
   dashes over the whole continent, uphill as well as down, with no sense of a network. That is
   why its rivers form loops and ring lakes. Planned rivers replace those lines; they don't
   coexist with them.
5. **Artifacts in this prototype:** D8 across filled lakes and flats draws straight 45°/90°
   runs, and 64-block cells smooth away anything narrower than about 150 blocks. A game version
   would smooth and meander the polylines (spline plus noise offset) and route on 32-block cells
   in the mountains.

## What a mod would need

### The plan: a per-world raster

At world creation, for each continent (the central one plus `additional_continents`; their centres
and radii come from `RadialFalloffFunction`'s jitter, the same values `giant_peaks` and the
previewer use): build a ground-height raster at 64 blocks, then run the same priority-flood,
breach, D8 and accumulation, and keep the output:

- **river polylines** (cells draining >= 1 km²) with the drainage area per vertex, smoothed and
  meandered. Width ~ k·sqrt(area), for example 6 blocks at 1 km² and 20-30 at 25 km²; depth 3-6.
  A boat needs >= 2 deep.
- **water surface per vertex**: the filled/breached surface, quantised to whole blocks. Each step
  is a weir, rapid or fall.
- **lake polygons** (the filled cells, level per lake) and **gorge segments** (bed below ground).

Height source: in-game `getBaseHeight` costs ~5 ms per call in this pack (CLAUDE.md). A
continent of 174 km² is 42k cells, which comes to 3.5 min per continent on one thread and ~20 min
for six. **Reading Tectonic's 2-D `tectonic:terrain_spline/offset/final` instead is enough:** on
seed 8675309 a monotone fit from offset to height is off by a median of 1 block (p90 9), and the
basins come out the same (18.2/8.4/8.0 vs 18.3/8.4/7.7 km² at 128 blocks). It costs 91 µs per
column even in numpy, 50 times less than a height search, so planning should take seconds per
continent in Java. The plan is deterministic from the seed, so a cache in the world folder
(`data/terra_continental_rivers.dat`, well under 1 MB) only speeds up the first load. Reopening a
world reads that file. This fits the decision record ("unconcerned about creation time as long as
reopening stays quick").

Like `SpawnVillagePlanner`, the plan must exist before the first chunk of the spawn continent
generates, and it must not run inside a server tick (60 s watchdog). Compute it on a worker thread
started at server start. Chunk generation for a continent waits on that continent's future: in
practice the spawn continent at creation and the others on demand.

**Seed access:** density functions never see the world seed (RadialFalloffFunction's comment).
The plan DF should get it the way `RadialFalloffFunction` does, through a seeded noise holder
filled in during `mapAll`/`visitNoise`. It can then key a static plan cache by that noise's
instance, so two worlds open in one JVM (single-player, then a new world) don't share a plan.

### The carve term: a density function over the raster

`terra_continental:river_carve` (name illustrative), 3-D but y-linear:

```
carve(x, y, z) = k * (bed(x, z) - y)        // positive = solid
bed(x, z)      = surface - depth           in the channel (distance d < w/2)
               = surface + (d - w/2) * bank  outside, rising to +inf by the valley width
```

`d` is the distance to the nearest river segment. Store segments in a spatial hash (64-block
buckets, a few segments per lookup), wrapped in `flat_cache` so each 4x4 column pays once. Lakes
use their own polygons, with the bed held at the ground (no cut) and only water added.

Put it inside the `interpolated` node of `final_density`, where Tectonic already writes
`min(base_terrain, caves)`, as `min(base_terrain, caves, river_carve)`. A `min` only removes
ground, so a gorge is that same cut through a rim. Working in blocks (`y`) avoids converting into
Tectonic's offset units, which change with `vertical_scale`. The cost: cell interpolation (4
across, 8 up) turns banks into Tectonic's familiar staircase, and beds narrower than ~8 blocks
round off. The seam owns `final_density` for Tectonic. Vanilla, Terralith and others would each
need one override, which is where generator independence costs something.

The mixing should also lower `preliminary_surface_level` along channels (same raster). Vanilla's
aquifer uses that value to decide where the surface fluid rules apply, and a channel it doesn't
know about may be treated as deep underground and filled from random aquifer levels.

### Water above sea level: an Aquifer fluid picker

Tectonic's `NoiseBasedChunkGeneratorMixin` (`tectonic$fixLavaLevel`) shows the hook. It runs in the
`NoiseBasedChunkGenerator` constructor and replaces the private `globalFluidPicker` (a memoized
`Supplier<Aquifer.FluidPicker>`) with
`(x, y, z) -> y < min(lavaLevel, ...) ? LAVA : y < seaLevel ? defaultFluid : AIR`. A river mod would
wrap whatever picker is installed (Tectonic's included) and return
`FluidStatus(riverSurface(x, z), WATER)` where the plan has a river or lake, otherwise the wrapped
status. Wrapping rather than replacing keeps Tectonic's lava fix and the underground rivers'
floodedness rule (`fluid_level_floodedness`, a range_choice on `underground_river/parameters`).

What to expect (to verify in game):
- The noise aquifer reads the global picker per aquifer cell (16 x 12 x 16) near the surface, so
  the water level is piecewise constant over 16-block cells. A level step therefore lands on a
  cell boundary, not exactly at the planned weir. Snapping steps to aquifer-cell edges when
  building the plan avoids half-steps.
- Where two levels meet, vanilla either builds a barrier (stone between pressures, via the barrier
  noise) or leaves an open face and schedules a fluid tick (`Aquifer.shouldScheduleFluidUpdate`).
  The open face then spills as a short waterfall when the chunk first ticks, which is the look we
  want for falls. A barrier across a channel would read as a dam, so check whether the carve wins
  there.
- Caves under a lake or river fill to its level, as vanilla's sea does. That is acceptable, but
  it will flood the occasional cave entrance.

Fallback if the aquifer path proves unruly: a worldgen feature (in the `fluid_springs` or `lakes`
step) that writes source blocks below the planned surface within its own chunk, and flowing water
down each step. That is simpler to reason about but blind to caves, and it needs care with
neighbour updates (the CLAUDE.md drop-through gotcha applies to fluids too).

### Replacing Tectonic's rivers and river biomes

- **Terrain:** the `rivers=off` spline edit, shipped in the seam the way a shape candidate is
  (offset/continents plus the six region splines). It is datapack-only.
- **Biomes:** biome weirdness is the noise router's `ridges` field,
  `minecraft:overworld/noise_router/ridges` = `tectonic:biome_parameter/ridges` + 0. That entry is
  separate from the terrain's `ridges_folded`, so it can change without moving any ground.
  Override it with a DF that returns the original value with |w| pushed out of the river band
  (vanilla's valley PV, |w| < 0.05; sign kept so variants survive), and exactly 0 on planned
  channels. River biomes then follow the planned rivers, at the biome grid's 4-block resolution.
  `ScaledWeirdnessFunction` already sits on this path for Terra Continental, and the two should be
  merged rather than stacked. Check TerraBlender's regions for their own river picks.
- **Underground rivers stay** (decision). Tectonic routes them where its surface rivers "reach
  mountain ranges" (`underground_rivers` comment in tectonic.json). Without surface rivers they
  stay as caves with water and lose their entrances. The planned breaches take over the role of
  getting a river through a range.
- **Spawn villages / LandMask:** they treat rivers and lakes as land (biome at sea level not
  ocean), so nothing breaks. The 40% river villages (decision) would read the plan: mouths and
  fords are the polyline's first vertex and its narrow crossings.

## Where it belongs

**A separate module, not Terra Continental's core.** Reasons:
- Terra Continental's stated job (decisions 2026-10-23, "generator independence") is continents,
  humidity, and village spacing and placement. Rivers need a per-generator carve seam
  (`final_density`) and a chunk-generator mixin (fluid picker). That is generator-specific work of
  the kind TC is trying to keep thin.
- It is the riskiest piece (aquifer behaviour, interpolation, performance) and should be able to
  ship, be disabled and be iterated on without blocking TC releases.
- It depends on TC only for continent geometry (centres and radii, or LandMask) and the seed
  holder. TC would depend on it only softly: village placement asks "is there a plan?" and falls
  back to today's behaviour.

A sibling mod in this workspace (for example `terra_rivers`, with its own seam pack layered over
Tectonic's and TC's) fits that best. A package inside TC behind a config flag would be the
lighter alternative if a second jar is unwanted. The datapack half (`rivers=off`, the biome
`ridges` override) belongs with whichever ships the carve, because without the carve the world has
no rivers at all.

## Before committing to it

1. Decide the continent scale: are 10 km rivers enough, or should continents grow?
2. Decide the lake/gorge balance (the lake cap): the `_fill` maps show the all-lakes extreme, and
   the default maps show cap 12.
3. Prototype the carve DF plus the fluid picker on one hand-made polyline in the realgen rig
   before building the planner: water-step behaviour is the unknown that decides the design.
