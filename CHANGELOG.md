# Terra Continental changelog

Tags: `tc-vX.Y.Z` from 0.8.0 (`ti-vX.Y.Z` before the rename). Entries before 0.7.3 were
reconstructed on 2026-09-27; everything from
0.0.1 to 0.7.3 landed in one catch-up commit (`15597b7`) because it had not been committed as
it happened. Earlier history is in `git log`.

## 0.13.0 — 2026-11-15
0.12.0 was never released on its own; its changes are part of this release and listed at the end
of this section.

- **The menu music keeps playing while a world loads** (user, 2026-11-11: the load runs for minutes). Vanilla
  stopped all sound when the load began and never ticked music during it; `LoadingMusicMixin` skips that stop
  for a load started from the menu and ticks music and sound in the wait loop.
- **Crossfade into the world** (user, 2026-11-11): from 100% to the world showing takes about 6.5 s. Over that gap
  the menu music fades out (6 s from 100%) and game sounds fade in (5 s from the join), equal-power curves
  (`LoadingFade`, volumes scaled in `SoundEngine.calculateVolume`). In-game music follows at vanilla's usual delay.
- **Dripstone and lush caves in big pockets** (TI-101, known since 0.6.9 as dripstone and lush caves at 0%).
  Vanilla gives a cave the NEAREST underground box, and the surface biomes sit at depth 0 and 1, so mid-band
  they are about 0.45 away: dripstone took caves wherever continentalness passed about 0.35 (not 0.8) and lush
  wherever humidity passed about 0.25 (not 0.7). That made dripstone 20-45% of cave land round some spawns and
  lush 0% on a dry one (the user's playtest, 2026-11-11, seed 1742398067288660832), and the first fix (small
  lush pockets, ~50 blocks across) was too fine (second playtest). Now an underground biome is kept only
  where the climate lies inside its box (`CaveBiomeContainmentMixin`, wrapping TerraBlender's lookup too);
  elsewhere the cave takes the biome its climate picks at the surface. Underground, `noise_router/continents`
  and `noise_router/vegetation` cap continentalness near 0.6 and humidity near 0.6, and two 3-D noises
  (`terra_continental:cave_pocket_dripstone`, `cave_pocket_lush`, first octave -10) pull them to 1.0 and 0.85
  inside pockets. Round spawn (6 km square, y 0 / -64 / -160, 3 seeds, real-pack rig): dripstone 3.6% and lush
  3.0% of cave area, pockets about 500-550 m across (area-weighted) and 1-1.2 km apart, plain caves between;
  deep dark unchanged. Chosen over pockets of 300-380 m 0.5-0.6 km apart, and of 500 m 1.2-1.7 km apart at
  1.6-2%. Below the surface only: both terms blend in from depth 0.1, so surface biomes and terrain are
  unchanged. Existing worlds change cave biomes in chunks generated after the update.
- **`/continental cave_census <radius> <step> [seed] [noise settings]`**: underground biome shares at y 48 to
  -440 through the generator's own climate sampler, with continentalness, humidity and depth histograms, to
  `seedscan/cave_census_*.json`. The optional noise settings id compares climate variants in one boot.
  It also writes the biome grid next to the JSON (`.bin`: little-endian uint16 palette indices by height, then z,
  then x; the JSON gains `palette`, `grid_heights` and `n`) for patch-size and spacing maps.
- **Land structures don't start under water** (playtest 2026-11-11: a village house standing in a
  shallow channel between low islands, biome "Prairie"). Biomes follow the smoothed
  continentalness, while the ground also takes the ocean noise, the wetland regions and the 3-D
  noise, so low land near a coast can be a land biome over a few blocks of sea. A surface structure
  whose biome list has no ocean, beach, river or swamp biome is now refused where its start
  column's ground is more than `dry_start_max_water` (1; -1 = off) blocks under sea level
  (`DryStarts`, `StructureDryStartMixin`). It reads the plan's height estimate and asks the
  generator for the exact ocean floor only near the water line. Village pieces built out from a
  dry start can still reach over water.
- **TC: Large Biomes takes Temperate's vegetation** (the user, 2026-11-11, after the biome size study). Large's
  vegetation noise was 4x vanilla (xz scale 0.0625); it is now the pack's own (0.03), as in TC: Temperate, while
  temperature stays 4x vanilla with the temperate spawn continent. Biome patches on land 1.86 -> 3.01 km²
  (length-weighted walk 772 -> 909 m, 204 -> 163 patches per 100 km²; TC: Temperate 2.98 km²), 5 seeds, 32 km
  square, rivers filled, real-pack rig. TC: Climates keeps the 4x vanilla vegetation. Existing Large worlds
  change biomes in chunks generated after the update.
- **`/continental biome_map_settings <noise settings> <radius> <step> <seed> <centerX> <centerZ>`**: biome_map
  with the climate read from another noise-settings entry (a world datapack's variant), so climate variants
  compare in one server boot. The biome source stays the world's. Used by the biome size study (2026-11-11).
- **A steered spawn continent** (the user, 2026-11-10: on the loading map the spawn continent read as
  a small round island while bigger, rougher continents lay out at sea; later, many retries to find
  an acceptable one: too big, too small, too round, coasts smoother than the neighbours', neighbours
  touching or missing). Its land bump was one round dome of radius 9,000 against the other
  continents' 14,000 +-30%, at a fixed height whatever the noise under it, with the others placed
  without regard to it. Now `SpawnSteer` tries `spawn_candidates` (48) layouts per world, each a
  branching chain of 4-7 lobes in noise-bent coordinates, each lobe's height set from the noise
  under it, scaled to `spawn_target_area`, with the seed's two nearest continents moved to sit a
  drawn gap from its coast and the rest kept clear; builds each one's continent field on the
  loading map's grid from the seed's own noise; and keeps the one closest to the targets: about
  290 km2 (+-15%), roundness 0.55-0.82, the nearest neighbour's coast `spawn_gap_min`-`spawn_gap_max`
  (3-6) km away, and no more than `spawn_max_other_land` (15%) of the map land besides it. Land is
  measured as the loading map draws it (continentalness above -0.19, where vanilla's ocean biomes
  end; at first -0.11, which put a neighbour the user saw 2 km away at 3). While no layout hits
  every target, up to `spawn_rounds` (8) more rounds try again on the seed's noise shifted by a drawn
  offset, which the continent field then keeps: a new shape, same seed (playtest 2026-11-10: two of
  five steered worlds bad, one too much land around, one neighbour too close). Same seed, same world. Chosen over one-layout rules on 40 previewed seeds: every
  target on 82% of seeds, against 18% for rules and 5% for fixed lobes; the targets were set from the
  user's own session (they pass the one world kept, fail all five discarded). The search takes about
  0.6 s a round at world creation (real-pack rig). The giant massifs follow the moved continents, and the
  spawn continent's sits in its core lobe. New `[continent_shaping]` keys: `spawn_steering`,
  `spawn_target_area`, `spawn_gap_min`, `spawn_gap_max`, `spawn_candidates`, `spawn_max_other_land`,
  `spawn_rounds`; `spawn_steering =
  false` gives the old dome of `central_land_radius` (default 14,500). The temperate spawn climate's
  factors default to 1.1 / 1.5 (were 1.5 / 2.0). Previewer: `preview/steer_core.py` is the reference
  implementation; the rig picked the same layout with the same measurements on two seeds. New
  chunks only; old worlds get seams.
- **BWG's sea formations count as sea** on the loading map and for the village planner (the user,
  2026-11-10): `lush_stacks` and `dead_sea` have no "ocean" in their names, so their narrow bands drew
  as light-green squiggles of land out at sea. The biomes themselves are unchanged.
- **The `[caves]` settings work.** `cave_depth_floor_value` and `cave_depth_reference_y` were never
  read: the terrain's `noise_router/depth.json` multiplied the depth by a literal 0.42 and nothing
  referenced `terra_continental:cave_depth_scale`. It now does. The floor default becomes 1.88671875
  (was 1.3), the value that gives exactly 0.42, so cave biomes are unchanged (previewer: identical
  biome depth over 8 heights x 2,601 columns). An existing config still says 1.3, which would now
  mean less deep_dark and deeper dripstone and lush caves: set it to 1.88671875 to keep them as they
  were.

- **Map statistics beside the loading map** (the user, 2026-11-10): a box to the map's left with
  the spawn continent's land area and highest point, the gap to the nearest other continent, how
  much of the map is land, and the villages and hamlets placed so far. Read from the finished world
  plan, so it counts land as the map draws it. Left out when the window is too narrow.

- **Every counted village has villagers in it; the empty ones are rare** (the user, 2026-11-10: a
  planned spawn village in test world 87 generated as a vanilla zombie village, and
  `/continental villages` listed it). Vanilla's five village town-centre pools lose their zombie
  variants (about 2% of rolls), so every vanilla village, planned or on a grid, is inhabited.
  Abandoned villages live on as their own structures, `terra_continental:abandoned_village/<type>`
  (vanilla's zombie town centres, same biomes), and with BWG's Forgotten villages and Pumpkin
  Patches they are placed by the new `terra_continental:per_continent` placement: one or two of
  each per continent, on their biomes, on fairly level ground, at least 1,200 blocks from a planned
  village (until now Pumpkin Patches were on a grid about as dense as villages). None of the three
  is in the village tag, so the loading map, the planner and `/continental villages` never count
  them; `/locate` finds them. Every start logs what the server actually loaded
  (`[village-pools] village town centres: N weighted entries, 0 zombie`) and warns if a mod or
  datapack has put zombie centres back.

- **Villages sit on whole ground** (the user, 2026-11-11, world 91: a planned plains village on flat
  ground had a ravine 4-6 blocks wide and up to 13 deep along a street, and a pit 22 deep inside it;
  "it just kinda breaks immersion"). Three changes:
  - **No carving under villages.** Cave and ravine carvers run after a village is laid out and never
    looked at it. Now, while a chunk is carved, every village touching it (the village tag, the
    abandoned villages, BWG's villages) keeps its area, 8 blocks wider, from 16 blocks under its lowest
    piece up: a ravine heading for a village ends at a wall short of it, deeper caves pass underneath.
  - **One ground rule for every village** (`VillageFootprint`): the planned spawn villages, the random
    village grids on the other continents (vanilla checked only the biome) and the rare per-continent
    villages. Within 48 blocks `max_relief` as before; out to 128 blocks now 1.3x `max_relief` across
    the whole spread (was 2x) and at most 8 between rings 32 blocks apart (was 24).
  - **Gentler ground round village houses**: the terrain fit for `beard_thin` pieces (every vanilla
    and BWG village house) reaches twice as far, so a house on a slope sits in a graded bank rather
    than beside a cut wall. Works over C2ME's replacement of the same code; logs
    `[village-ground] wide terrain fit round village houses is active` once per start.
  Rig, world 91's seed, all ten planned villages generated: columns sunk 3+ below a village's floor
  1,198 -> 0; bare ground steps of 4+ blocks next to houses 164 -> 7. The planner still placed 10
  villages, swapping its three hilliest sites (y 140 and y 203 among them) for level ones.
- **"Try a New Seed" on the loading map** (the user, 2026-11-10: a poor spawn continent should be
  caught while the world loads, not after). Under the map are a status line, a warning line and the
  button, shown only while a new world is being created. A click asks first: the button turns into
  "Keep This World" in the same place, and "Discard and Retry" appears beside it, so a double click
  keeps the world. On confirm the integrated server is stopped (its running flag cleared; the
  village planner and Terra Towns' hamlet search see `isRunning()` go false and give up), vanilla's
  load loop is let out once the server thread ends (`MinecraftWorldLoadMixin`), and the world is
  deleted and created again through the Create World screen's own path with the same name, game
  mode, rules, world type and data packs on a random seed.
- **Village spread check**: once the spawn villages are planned, the planner judges their spread
  and the loading map warns "Village placement appears to be non-optimal" when not all villages
  fit, or over 25% of the land lies farther than
  0.75 x sqrt(land per village) from every village. Logged as `[spawn-villages] spread: ...`.
  The user's 2026-11-10 world (58 km2, 8 of 10 villages, 42% far) fails both. A shrunken gap
  alone isn't counted: a small continent with all ten villages well spread is fine.
- **The loading map shows the work live** (the user, 2026-11-10: "I want to watch and see how the
  program runs"). The map paints as the world plan learns each cell, instead of waiting on vanilla's
  spawn-area percentage, which only moved in the last few seconds after the plan and villages were
  done. The village planner shows its candidate spots (faint dots), the spot being checked (a ring),
  the spots that failed (dark red) and each village the moment it is placed, with its progress on the
  status line. Real time for now; a slowed replay is the fallback if it's too fast to follow.
- **One percentage for the whole load**: the map, the villages, the spawn area, the hamlet search and
  the hamlet builds each get a share (15/5/5/15/60 on a new world with Terra Towns, roughly their
  share of the time; 60/20/20 otherwise), and it never goes backwards.
- **Fix: the loading map starts blank.** Its texture was never uploaded until the first cells were
  painted, so it showed whatever the graphics driver handed back; after a retry that was the
  discarded world's map, at 0% (the user, 2026-11-10).
- `LoadingStatus.warn` (warning line, reached by Terra Towns by reflection) and `requestRetry`.

## 0.11.0 — 2026-11-01
Snow and ice as a feature, with tree and structure lines (playtest 2026-10-31: snow came with the
peak biomes as a palette from y ~250, cliffs included; ice everywhere on the peaks; trees to y ~1,000).
One snow line drives it all: `terra_continental:alpine/snow_line` in the terrain pack, y 500 wandering
+-40 over ~1.5 km (picked from 400-650 previews).
- **Biomes by altitude**: the biome router's erosion is the peaks value above the snow line and kept
  out of the peaks and slopes bands below it, so frozen/jagged/howling peaks cover only ground above
  the line (new density function `terra_continental:at_y` reads the height estimate). Replaces the
  giant-only pull toward peaks.
- **Snowfall** cools from the snow line, not a fixed y 336 (`[terrain] snow_start_offset` is gone).
- **Alpine surface pass** (`terra_continental:alpine_surface`, top-layer step, every overworld biome):
  snow pack on ground gentler than ~50 degrees, bare stone on steeper faces, the line 25 lower on north
  faces and 25 higher on south faces; glacier ice (packed, blue at the core) in high hollows and on high
  flats, tongues down valley floors to 120 below the line; below the line no snow except in biomes
  snowy at sea level. Plants go with the ground the pass changes (playtest 2026-11-01: grass and
  ferns stood on snow, stone and ice, since the pass runs after vegetation), except a quarter of the
  flowers on gentle ground in the 40 blocks above the line, which keep their grass: alpine flowers.
  Main range crest: plants on snow 5,530 to 31, on ice 690 to 2, on rock above y 450 2,480 to 108.
  Debris too (same playtest): leaf piles, clover, mushrooms, succulents, bushes and berry and mint
  plants, which the heightmap stops on; and BWG's own ground (overgrown and podzol dacite, lush grass,
  mossy and rocky stone) now counts as ground, so it turns to snow or rock instead of staying green.
  Crest: mushrooms on snow 423 to 8; leaf piles, succulents, bushes, mints 0.
- **Tree line** and **structure line**: no worldgen trees (vanilla, BWG/Oh The Trees You'll Grow and Cobblemon's apricorns)
  and no surface structures start above `[alpine] tree_line_gap` / `structure_line_gap` (60) below the
  snow line; the tree line thins out over 20 blocks. Saplings and underground structures unaffected.
- Test seed, giant and main range regenerated: peak biomes from the y 500 band up (were from ~250); no
  snow below 450; above 500, 44-67% snow and 22-78% bare rock (was 3-8% rock); trees 0% from 500 up
  (were 13-16% at ~900); ice 2-8% (was 10-27%); structure starts on high ground 6 -> 1 (that one below
  its local line).

- **Loading-map markers are small, one shape per meaning** (the user, 2026-10-31: "small
  indicators, not map-blockers"). They're drawn in screen pixels, not GUI pixels: at GUI scale 4
  a hamlet square was 28 pixels and covered the spawn continent. Spawn is a white dot, the spot
  being checked a thin pulsing ring, a found hamlet site a hollow gold square that fills in once
  the hamlet is built (`LoadingStatus.built`, reported by Terra Towns' next release), and the spawn continent's
  planned villages are terracotta diamonds. The map's shading is unchanged (user's call).
- **The loading map plays in order, with a zoom per hamlet** (the user, 2026-10-31): the map fills
  in, then the villages pop in one by one ("Placing villages"), then Terra Towns' hamlet search
  shows. For each hamlet being built the map zooms in about 4x on it (`LoadingStatus.building`),
  holds until it's built, zooms back out to the whole map, then in on the next. The animation
  trails the server and never holds it up.
- **The loading screen opens on the map's frame**, not vanilla's small chunk grid that snapped to
  the map a few frames later: a TC world is recognised from the server's registries before the
  overworld exists (`PlanPreviews.expected`).

## 0.10.2 — 2026-11-01
- **The spawn continent's coast, for Terra Towns' hamlets**: the spawn-village plan traces the
  continent's outer coast as a closed loop on its 128-block land lattice (`Plan.coast()`), and
  `spawn/SpawnContinent` hands that loop and the planned villages to Terra Towns in plain types
  (it reaches them by reflection). Terra Towns 0.5.10 spreads its starting hamlets along it, away
  from the villages, instead of tracing the biome coast itself (the user, 2026-10-31). On the
  playtest seed: a loop of 798 cells.

## 0.10.1 — 2026-10-31
- **Fewer Cobblemon structures**: new `[cobblemon] cobblemon_spacing_multiplier` (default 2.0, on top of
  the global 1.5) spaces Cobblemon's habitats, ruins, gimmi towers, fishing boats and shipwreck coves
  further apart: about a quarter as many. Their own spacing is 15-30 chunks, so ruins and habitats
  turned up every few hundred blocks (playtest 2026-10-31). Matched by structure-set salt (Cobblemon 1.8.4).
- **Spawn villages stay off crags**: the planner checks a village's whole spread, out to 128 blocks (rings
  every 32 blocks), not just 48: at most twice `max_relief` across it and no rise steeper than 12 in 16.
  The test seed's central village had been built across stone crags 100-128 blocks out (relief 62); it
  now sits on flat ground (relief 13), and Skyris moved off a 77-block slope.
- **Faster village planning**: the footprint checks read the world plan's height estimate (within ~1
  block, microseconds) instead of getBaseHeight (50-65 ms a call). Test seed: 97 s -> 29 s, the same
  sites from run to run. The planner logs how many spots each check rejected.

## 0.10.0 — 2026-10-30
Terra Continental generates its own terrain. Tectonic's terrain is absorbed into the mod (MIT,
credited in `resourcepacks/terrain/NOTICE.txt`), reshaped, and no longer needs Tectonic installed.
**Tectonic is now incompatible; Lithostitched is required.** New worlds generate differently from
0.9.0 worlds of the same seed; existing worlds keep their generated chunks.

The land (user's targets: `docs/decisions.md`, 2026-10-23; measurements: `preview/SHAPES.md`):
- **A buildable coast**: mountains reach full height ~600-900 blocks inland instead of ~300, so
  coasts open onto a shelf, not a wall (coastal walls 18% -> ~3%).
- **Fewer, longer ranges** (a few per continent instead of a lattice), slightly narrower, with
  **smooth flanks instead of knobs**: the climate noises' domain warp is back at vanilla's size in
  the world (at the terrain's larger noise scales it had doubled and crumpled every steep slope).
  On four seeds: 5.9% of land over 45 degrees (target 5-8%), needles 3.7% -> 1.7%.
- **No contour lines** on gentle ground: the terrain density no longer kinks at the surface, which
  made interpolation draw a terrace every 8 blocks of height (plateau stripes, rings on the giant).
- **The giant**: one massif per continent, ~+1,100, with a main summit that always stands highest
  and is never cut flat by the world ceiling, ragged flanks with spurs and gullies, alpine biomes on
  top, and each massif its own shape.
- **Rivers stay out of ranges**: surface river channels are filled where the ground around them is
  mountain, and river biomes stay along real rivers, not on high ground.
- **Shore biomes only at the shore**: no Stony Shore on mountain ground inland (3 km window:
  13.4% -> 4.2%).
- Wetland ponds only in marsh patches on ~10% of eligible ground.
- Deep oceans (floor ~400 below sea level) and a world from y -512 to 1,264, as before.

Loading a world:
- **The world-loading screen draws a map** of the world plan filling in from spawn, in step with the
  percentage (at 50%, half the map), with a status line. While Terra Towns (0.5.8+) looks for the
  starting hamlets, the spot being checked is marked and chosen sites stay as gold dots.
  Single player with a TC world type; other worlds keep vanilla's grid.
- **World plan** (`plan/WorldPlan`): one coarse scan per world (128-block points out to 32 km from
  spawn, ~9 s in the background as the overworld loads): ground height and sea or land. The
  spawn-village planner reads its land from it.

Behaviour carried over from Tectonic, now ours: the ore fix, underground rivers, lava tunnels, the
snow line (now `[terrain] snow_start_offset`, 256 as before), ocean monuments 30 blocks lower, and a
guard for chunks saved with a taller world. Changed on purpose: the lava level sits 10 above the
world floor in the TC Large Biomes and Climates types too (Tectonic matched only noise settings named
`overworld`, so caves below y -54 there could flood with lava). Not carried over: Tectonic's config
screen and `/tectonic`, its Terralith/Clifftree overlays and old-chunk blending.

Config and compatibility:
- `config/tectonic.json` is no longer read; the terrain's values are in the mod.
- `[caves] cave_depth_fallback_min_y` is gone. `/continental biome_map` no longer warns on the TC
  world types.
- Every terrain id is in the `terra_continental` namespace (`resourcepacks/terrain`, one built-in
  pack). Noises renamed from `tectonic:` keep their old seeds (`terra_continental_renamed_noises.txt`).

For developers: the offline previewer (`preview/continental_terrain.py`, `continental_climate.py`)
reads this tree's terrain pack directly and matches the game (rig, seed 4151832666630476678: 98.0%
of land heights exact, 99.1% within 1 block). New `shape_pack.py` keys `warp`, `linear_density`
and `crest_*`; `tools/python/surface_map.py` renders a generated world from its region files.

## 0.9.0 — 2026-10-23
- **Boots without BWG.** The built-in `big_trees` pack overrides 50 BWG tree features and
  `distant_towns` overrides BWG's village set, with no load condition, so without BWG the datapack
  failed to load and the server could not start. Each now carries a `mod_loaded` condition. (Village
  audit, 2026-10-17.)
- The structure spacing multiplier is rounded to 6 decimals before use: 1.5 × 0.7 was
  1.0499999999999998, so separation rounded to 31 instead of 32 and every village moved.
- **BWG grasslands accept plains villages**: coconino meadow, prairie, allium shrubland and amaranth
  grassland (built-in pack `village_biomes`). They cover 10-20% of most spawn continents and took
  no village before; village-grid success rose from 14% to 21% per attempt.
- **Spawn-village planner** (user, 2026-10-18: up to 8 players start on the spawn continent and
  each needs a village, plus a central village everyone visits). The spawn continent's villages are
  planned instead of left to the random village grids, which gave 0-7 (mean 3), clumped and mostly
  plains. Once per world, from the generator alone (no chunks): the spawn continent's land; a
  central village on the valid spot nearest the land's centroid; the rest farthest-first so they
  fill the land; each the village type used least so far that its spot allows (BWG villages
  included); steep ground refused (`max_relief` over a 7×7 footprint), no water, and every spot
  checked with the game's own structure test. The village grids stay off the spawn continent
  (and within `min_gap` of a planned village); every other continent keeps them.
  New `[spawn_villages]` config: `enabled` (true), `count` (10), `min_gap` (3000), `max_relief` (14),
  `themed_max` (0: up to this many desert, savanna or snowy villages on temperate ground).
  Measured on 8 seeds: 10 villages every time, all generated, most common type 46% (was 67%),
  relief ≤ 11 blocks (live-pack villages: up to 177). Planning takes ~15 s per world and also runs
  in the background at server start.
- **`/continental villages` searches the land you stand on**, not a radius circle: a flood fill of
  the landmass (from the generator, no chunks), out to 25 km, and lists the planned spawn villages.
  The circles counted villages on other islands (5 of 8 test seeds) and missed some on long
  continents.
- **TC world types** (user, 2026-10-22) on the creation screen, built on Tectonic:
  **TC: Large Biomes** (climate zones 4× vanilla, temperate spawn continent), **TC: Temperate** (the
  pack's climate as before) and **TC: Climates** (climate zones 4× vanilla, no temperate override).
  The world type list starts TC: Large Biomes, TC: Temperate, TC: Climates, Default, then the rest
  (other mods' types included), and a fresh Create World screen starts on TC: Large Biomes (user,
  2026-10-23: no cycling for a new world). Vanilla hard-codes that starting selection to Default, so
  client-side hooks switch it (through the same call as the World Type button) and order the list.
  "Re-Create" names TC: Large Biomes and TC: Climates worlds by their own type; a TC: Temperate world
  shows as Default (same settings, same world). Dedicated
  servers: set `level-type=terra_continental:large_biomes` in `server.properties`. The End stays vanilla in each, so WorldWeaver's patched End (BetterEnd biomes and
  ground, stable across restarts) applies. Large Biomes and Climates use copies of Tectonic's
  overworld noise settings (MIT), generated by `tools/make_world_types.py` and differing only in
  temperature and humidity; a warning is logged at start if a copy no longer matches Tectonic's.
  The five old presets, which predate Tectonic (vanilla height, no Tectonic terrain), are removed.
  Requires WorldWeaver's `force_default_world_preset` off on dedicated servers, or WorldWeaver
  replaces every server world's type with its own.
- **`/locate` finds the planned spawn villages.** Vanilla's search only walks grid placements, so it
  skipped them and pointed at a village on another continent; so did anything else using it (Terra
  Towns' `VillageAudit`). The nearest planned village now wins when it is nearer.
- The world-type drift check compares the data files, not the loaded settings: TerraBlender adds
  BWG's surface rules only to the settings a world uses, so it warned on every TC world.
- BWG swamp villages count as villages (`#minecraft:village`, optional entry). BWG's own tag leaves them
  out, so `/continental villages` and `/locate` skipped them, and Terra Towns gave them a gym but never
  registered the settlement (client check, 2026-10-23).
- `/continental villages` on the spawn continent lists the planned villages first, as the spawn
  continent's, then any others under "further along this landmass" (a land bridge can carry grid
  villages far past the planner's area). Teleport numbers run on across both lists.
- `/continental villages` on the spawn continent lists every planned village, even one the land
  fill cut off at a narrow neck.

## 0.8.2 — 2026-10-16
- **Structure spacing config now works** (TI-102, TI-103). Until now the spacing mixin only changed
  what `spacing()` reported, which generation never reads, so `structure_spacing_multiplier` (1.5)
  and `village_spacing_multiplier` never applied and only made `/locate` wrong; a continent meant
  to hold ~8-12 villages held 33. The fields themselves are now scaled as each placement is built.
  The structure config (`terra_continental-structures.toml`) is now a **common** config, read from
  `config/`: as a per-world server config it loaded after the world's placements were built, so
  even scaled at build time it read "not loaded" and every multiplier was 1.0. Per-world copies in
  `saves/<world>/serverconfig/` are no longer read.
  **Every random-spread structure** follows the global multiplier in chunks generated from now on.
- `village_spacing_multiplier` default 4.3 → **1.0** (and in the Terra Incognita instance's config):
  4.3 was measured against vanilla's 34-chunk village grid, before the built-in `distant_towns`
  grid (129) existed; on top of it, 1.5 × 4.3 would leave about one village per continent. It is
  not vanilla's spacing: 1.0 with the global 1.5 puts villages ~193 chunks apart (vanilla: 34).
  1.5 × 1.1 measured 7 villages on both a 15.7 km continent and the 9 km spawn continent.
- **BWG Pumpkin Patches and Forgotten villages** (user, 2026-10-16) are taken out of BWG's village
  grid and given their own: Pumpkin Patches about as common as villages but at least ~150 chunks
  (2,400 blocks) apart, so rarely two in one biome; Forgotten villages rare (~750 chunks apart).
  Neither is listed by `/continental villages`: Pumpkin Patches are home to pumpkin wardens, and
  Forgotten villages are ruins with no villagers.
- `/locate` is honest again, and `/continental villages` reads `spacing()` directly (the 0.8.1
  `TrueSpacing` workaround is gone).

## 0.8.1 — 2026-10-16
- **`/continental villages`** (permission 2; user request, 2026-10-16): every village on the
  continent you're standing on, nearest first, each line clickable to teleport there
  (`/continental villages tp <n>`), the way `/terraTowns hamlets` hops between hamlets. Your
  continent is the seed-placed land circle you stand in, searched 25% past its edge for its coasts;
  each village structure set's grid is walked over it and every candidate spot asked, as /locate
  does, whether a village starts there. Run from the console, it lists the spawn continent.
  (`commands.VillagesCommand`, `RadialFalloffFunction.continents`)

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
