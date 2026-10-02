"""
Port of net.minecraft.world.level.biome.OverworldBiomeBuilder (1.21.1) and the
Climate nearest-ParameterPoint selection, restricted to surface sampling
(depth = 0).

Biome selection is Minecraft's "closest box" search: each biome entry is a box
in (temperature, humidity, continentalness, erosion, depth, weirdness) space
(quantized to ints at x10000) and the sampled point picks the entry with the
smallest sum of squared per-axis distances-to-box.

For speed we precompute a 5-D band lookup table: the vanilla boxes tile the
parameter space along fixed band edges, so within one band-cell the winning
entry is constant. lookup() is then pure digitize + fancy indexing.
brute_force_biome() does the exact per-point search and is used both for click
inspection and to validate the table (see test_biome_table.py).
"""

import hashlib as _hashlib
import json as _json
import os as _os

import numpy as np

# ---------------------------------------------------------------------------
# TerraBlender uniqueness-noise implementation
#
# TerraBlender assigns each (x, z) position to a "region" (vanilla, BWG, etc.)
# using a seeded layered noise: an InitialLayer picks a region by weighted
# random, then several zoom layers smooth and up-scale it.
#
# The algorithm is a port of:
#   terrablender.worldgen.noise.{AreaContext, LayeredNoiseUtil, WeightedRandomLayer}
#   net.minecraft.util.LinearCongruentialGenerator
# ---------------------------------------------------------------------------

_LCG_A = 6364136223846793005   # from LinearCongruentialGenerator source
_LCG_B = 1442695040888963407
_MOD64 = 1 << 64
_SIGN64 = 1 << 63


def _long(x):
    """Truncate arbitrary-precision int to signed 64-bit (Java long semantics)."""
    x = int(x) & (_MOD64 - 1)
    return x - _MOD64 if x >= _SIGN64 else x


def _lcg(left, right):
    """MC LinearCongruentialGenerator.next(left, right)  — scalar Python ints."""
    return _long(left * (left * _LCG_A + _LCG_B) + right)


def _mix_seed(seed, salt):
    """TerraBlender AreaContext.mixSeed(seed, salt) — from bytecode analysis."""
    s = _lcg(salt, salt)
    s = _lcg(s, salt)
    s = _lcg(s, salt)
    r = _lcg(seed, s)
    r = _lcg(r, s)
    r = _lcg(r, s)
    return r


# numpy uint64 constants for vectorised LCG
_NP_A = np.uint64(_LCG_A)
_NP_B = np.uint64(_LCG_B)


def _lcg_np(left, right):
    """Vectorised LCG for numpy int64 arrays (or scalar left + array right)."""
    if isinstance(left, np.ndarray):
        lu = left.view(np.uint64)
        inner = lu * _NP_A + _NP_B
        ru = (np.uint64(right & (_MOD64 - 1)) if np.isscalar(right)
              else right.view(np.uint64))
        return (lu * inner + ru).view(np.int64)
    else:
        # left is a Python scalar — compute scalar part, add array right
        left_u = left & (_MOD64 - 1)
        scalar_u = np.uint64(
            _long(left_u * (left_u * _LCG_A + _LCG_B)) & (_MOD64 - 1))
        ru = (np.uint64(right & (_MOD64 - 1)) if np.isscalar(right)
              else right.view(np.uint64))
        return (scalar_u + ru).view(np.int64)


def _init_random_np(ctx_seed, x_arr, z_arr):
    """Vectorised AreaContext.initRandom(x, z) for numpy int64 position arrays."""
    s = _lcg_np(ctx_seed, x_arr)   # left=scalar, right=array → array
    s = _lcg_np(s, z_arr)
    s = _lcg_np(s, x_arr)
    s = _lcg_np(s, z_arr)
    return s


# Largest region grid (int8 cells) we will materialise. 150M ~= 150 MB, which covers a
# detail map at full quart resolution; wide overviews fall back to skipping fine layers.
_REGION_CELL_BUDGET = 150_000_000


def _floormod(v, n):
    n = np.int64(n)
    return ((v % n) + n) % n


def _pick_weighted(rval, region_weights):
    """WeightedRandomLayer: one nextRandom(totalWeight), then walk the weight table."""
    total = np.int64(int(sum(region_weights)))
    pick = _floormod(rval >> np.int64(24), total)
    cumulative = np.cumsum(region_weights, dtype=np.int64)
    return np.searchsorted(cumulative, pick, side="right").astype(np.int8)


def _mode_or_random(a, b, c, d, pick4, fuzzy):
    """
    ZoomLayer.modeOrRandom for the centre child.

    FUZZY simply picks one of the four parents at random. NORMAL takes the majority
    where one exists and only falls back to random otherwise — that majority vote is
    what makes real TerraBlender regions coherent instead of speckled, and it is why a
    per-pixel reverse trace cannot reproduce them (you cannot invert a vote).
    """
    stack = np.stack([a, b, c, d])
    rnd = np.take_along_axis(stack, pick4[None].astype(np.intp), 0)[0]
    if fuzzy:
        return rnd
    # Ordered exactly as the bytecode, args (a, b, c, d).
    conds = [
        (b == c) & (c == d),
        (a == b) & (a == c),
        (a == b) & (a == d),
        (a == c) & (a == d),
        (a == b) & (c != d),
        (a == c) & (b != d),
        (a == d) & (b != c),
        (b == c) & (a != d),
        (b == d) & (a != c),
        (c == d) & (a != b),
    ]
    choices = [b, a, a, a, a, a, a, b, b, c]
    return np.select(conds, choices, default=rnd)


def _zoom_once(parent, px0, pz0, ctx_seed, fuzzy):
    """
    One forward zoom. parent is indexed [z, x] and covers x in [px0, px0+W), z in [pz0, pz0+H).
    Returns (child, cx0, cz0) covering twice the resolution, minus the final row/column
    (a parent cell needs its +1 neighbours to produce children).

    Per ZoomLayer.applyPixel each child re-seeds from the block corner (2*px, 2*pz) and
    advances the RNG a fixed number of times depending on which of the four it is, so all
    four children of a parent cell can be produced from three RNG states.
    """
    H, W = parent.shape
    i = parent[:-1, :-1]        # (px,   pz)
    l = parent[1:, :-1]         # (px,   pz+1)
    n = parent[:-1, 1:]         # (px+1, pz)
    p = parent[1:, 1:]          # (px+1, pz+1)

    xs = np.arange(px0, px0 + W - 1, dtype=np.int64)
    zs = np.arange(pz0, pz0 + H - 1, dtype=np.int64)
    ZZ, XX = np.meshgrid(zs, xs, indexing="ij")

    r0 = _init_random_np(ctx_seed, XX * 2, ZZ * 2)
    r1 = _lcg_np(r0, ctx_seed)
    r2 = _lcg_np(r1, ctx_seed)

    child = np.empty(((H - 1) * 2, (W - 1) * 2), dtype=np.int8)
    child[0::2, 0::2] = i                                            # copies the parent
    child[1::2, 0::2] = np.where(_floormod(r0 >> np.int64(24), 2) == 0, i, l)
    child[0::2, 1::2] = np.where(_floormod(r1 >> np.int64(24), 2) == 0, i, n)
    child[1::2, 1::2] = _mode_or_random(
        i, n, l, p, _floormod(r2 >> np.int64(24), 4), fuzzy)
    return child, px0 * 2, pz0 * 2


def get_region_indices(world_seed, region_size, region_weights, block_X, block_Z):
    """
    TerraBlender region index per position, by FORWARD simulation of its layer stack.

    Layers, from terrablender.worldgen.noise.LayeredNoiseUtil.createZoomedArea:
        InitialLayer (weighted random)        salt 1
        ZoomLayer.FUZZY                       salt 2000
        ZoomLayer.NORMAL x3                   salts 2001, 2002, 2003
        ZoomLayer.NORMAL x region_size        salts 1001 .. 1001+region_size-1

    An earlier implementation traced backwards from each pixel, applying FUZZY's random
    rule at every layer. That is wrong for the eight NORMAL layers and unfixable in that
    form, because their majority vote has no pointwise inverse; it showed up as speckle
    and as BWG biomes swapping for their vanilla twins.

    Zoom levels finer than the sample spacing are skipped: they only jitter boundaries
    below one sample, and simulating them would mean materialising the whole map at quart
    resolution. Everything at or above sample spacing is exact.
    """
    qx = np.asarray(block_X, dtype=np.int64) >> np.int64(2)
    qz = np.asarray(block_Z, dtype=np.int64) >> np.int64(2)

    salts = [(2000, True)] + [(s, False) for s in (2001, 2002, 2003)] \
        + [(1001 + k, False) for k in range(int(region_size))]
    total_zooms = len(salts)

    # Simulate every layer if the full-resolution grid fits in memory, and only drop the
    # finest ones when it doesn't. Skipping is not free — the finer zooms redistribute
    # values inside a coarse cell, so a skipped layer costs accuracy at the boundaries —
    # so skip the minimum the budget allows rather than a fixed number.
    span = max(int(qx.max()) - int(qx.min()), int(qz.max()) - int(qz.min())) + 1
    skip = 0
    while skip < total_zooms and ((span >> skip) + 2) ** 2 > _REGION_CELL_BUDGET:
        skip += 1
    used = salts[:total_zooms - skip]

    tx = qx >> np.int64(skip)
    tz = qz >> np.int64(skip)

    # Walk back from the needed output window to the initial-layer window.
    lo_x, hi_x = int(tx.min()), int(tx.max())
    lo_z, hi_z = int(tz.min()), int(tz.max())
    bounds = [(lo_x, hi_x, lo_z, hi_z)]
    for _ in used:
        lo_x, hi_x = lo_x >> 1, (hi_x >> 1) + 1
        lo_z, hi_z = lo_z >> 1, (hi_z >> 1) + 1
        bounds.append((lo_x, hi_x, lo_z, hi_z))

    x0, x1, z0, z1 = bounds[-1]
    xs = np.arange(x0, x1 + 1, dtype=np.int64)
    zs = np.arange(z0, z1 + 1, dtype=np.int64)
    ZZ, XX = np.meshgrid(zs, xs, indexing="ij")
    grid = _pick_weighted(_init_random_np(_mix_seed(world_seed, 1), XX, ZZ), region_weights)
    gx0, gz0 = x0, z0

    for salt, fuzzy in used:
        grid, gx0, gz0 = _zoom_once(grid, gx0, gz0, _mix_seed(world_seed, salt), fuzzy)

    ix = (tx - gx0).astype(np.intp)
    iz = (tz - gz0).astype(np.intp)
    np.clip(ix, 0, grid.shape[1] - 1, out=ix)
    np.clip(iz, 0, grid.shape[0] - 1, out=iz)
    return grid[iz, ix].astype(np.int32)


_F = np.float32


def q(value):
    """Climate.quantizeCoord: (long)(value * 10000.0F) - float math, trunc to zero."""
    return int(_F(value) * _F(10000.0))


def span(lo, hi):
    return (q(lo), q(hi))


FULL_RANGE = span(-1.0, 1.0)

TEMPERATURES = [span(-1.0, -0.45), span(-0.45, -0.15), span(-0.15, 0.2),
                span(0.2, 0.55), span(0.55, 1.0)]
HUMIDITIES = [span(-1.0, -0.35), span(-0.35, -0.1), span(-0.1, 0.1),
              span(0.1, 0.3), span(0.3, 1.0)]
EROSIONS = [span(-1.0, -0.78), span(-0.78, -0.375), span(-0.375, -0.2225),
            span(-0.2225, 0.05), span(0.05, 0.45), span(0.45, 0.55), span(0.55, 1.0)]

FROZEN_RANGE = TEMPERATURES[0]
UNFROZEN_RANGE = (TEMPERATURES[1][0], TEMPERATURES[4][1])

MUSHROOM_CONT = span(-1.2, -1.05)
DEEP_OCEAN_CONT = span(-1.05, -0.455)
OCEAN_CONT = span(-0.455, -0.19)
COAST_CONT = span(-0.19, -0.11)
INLAND_CONT = span(-0.11, 0.55)
NEAR_INLAND_CONT = span(-0.11, 0.03)
MID_INLAND_CONT = span(0.03, 0.3)
FAR_INLAND_CONT = span(0.3, 1.0)

# Weirdness slices (addInlandBiomes order)
W_SLICES = [
    ("mid", span(-1.0, -0.93333334)),
    ("high", span(-0.93333334, -0.7666667)),
    ("peaks", span(-0.7666667, -0.56666666)),
    ("high", span(-0.56666666, -0.4)),
    ("mid", span(-0.4, -0.26666668)),
    ("low", span(-0.26666668, -0.05)),
    ("valleys", span(-0.05, 0.05)),
    ("low", span(0.05, 0.26666668)),
    ("mid", span(0.26666668, 0.4)),
    ("high", span(0.4, 0.56666666)),
    ("peaks", span(0.56666666, 0.7666667)),
    ("high", span(0.7666667, 0.93333334)),
    ("mid", span(0.93333334, 1.0)),
]

OCEANS = [
    ["deep_frozen_ocean", "deep_cold_ocean", "deep_ocean", "deep_lukewarm_ocean", "warm_ocean"],
    ["frozen_ocean", "cold_ocean", "ocean", "lukewarm_ocean", "warm_ocean"],
]
MIDDLE_BIOMES = [
    ["snowy_plains", "snowy_plains", "snowy_plains", "snowy_taiga", "taiga"],
    ["plains", "plains", "forest", "taiga", "old_growth_spruce_taiga"],
    ["flower_forest", "plains", "forest", "birch_forest", "dark_forest"],
    ["savanna", "savanna", "forest", "jungle", "jungle"],
    ["desert", "desert", "desert", "desert", "desert"],
]
MIDDLE_BIOMES_VARIANT = [
    ["ice_spikes", None, "snowy_taiga", None, None],
    [None, None, None, None, "old_growth_pine_taiga"],
    ["sunflower_plains", None, None, "old_growth_birch_forest", None],
    [None, None, "plains", "sparse_jungle", "bamboo_jungle"],
    [None, None, None, None, None],
]
PLATEAU_BIOMES = [
    ["snowy_plains", "snowy_plains", "snowy_plains", "snowy_taiga", "snowy_taiga"],
    ["meadow", "meadow", "forest", "taiga", "old_growth_spruce_taiga"],
    ["meadow", "meadow", "meadow", "meadow", "dark_forest"],
    ["savanna_plateau", "savanna_plateau", "forest", "forest", "jungle"],
    ["badlands", "badlands", "badlands", "wooded_badlands", "wooded_badlands"],
]
PLATEAU_BIOMES_VARIANT = [
    ["ice_spikes", None, None, None, None],
    ["cherry_grove", None, "meadow", "meadow", "old_growth_pine_taiga"],
    ["cherry_grove", "cherry_grove", "forest", "birch_forest", None],
    [None, None, None, None, None],
    ["eroded_badlands", "eroded_badlands", None, None, None],
]
SHATTERED_BIOMES = [
    ["windswept_gravelly_hills", "windswept_gravelly_hills", "windswept_hills",
     "windswept_forest", "windswept_forest"],
    ["windswept_gravelly_hills", "windswept_gravelly_hills", "windswept_hills",
     "windswept_forest", "windswept_forest"],
    ["windswept_hills", "windswept_hills", "windswept_hills",
     "windswept_forest", "windswept_forest"],
    [None, None, None, None, None],
    [None, None, None, None, None],
]


def _pick_middle(i, j, w):
    if w[1] < 0:
        return MIDDLE_BIOMES[i][j]
    variant = MIDDLE_BIOMES_VARIANT[i][j]
    return variant if variant is not None else MIDDLE_BIOMES[i][j]


def _pick_badlands(j, w):
    if j < 2:
        return "badlands" if w[1] < 0 else "eroded_badlands"
    return "badlands" if j < 3 else "wooded_badlands"


def _pick_middle_or_badlands(i, j, w):
    return _pick_badlands(j, w) if i == 4 else _pick_middle(i, j, w)


def _pick_plateau(i, j, w):
    if w[1] >= 0:
        variant = PLATEAU_BIOMES_VARIANT[i][j]
        if variant is not None:
            return variant
    return PLATEAU_BIOMES[i][j]


def _pick_slope(i, j, w):
    if i >= 3:
        return _pick_plateau(i, j, w)
    return "snowy_slopes" if j <= 1 else "grove"


def _pick_middle_or_badlands_or_slope(i, j, w):
    return _pick_slope(i, j, w) if i == 0 else _pick_middle_or_badlands(i, j, w)


def _maybe_windswept_savanna(i, j, w, fallback):
    return "windswept_savanna" if (i > 1 and j < 4 and w[1] >= 0) else fallback


def _pick_shattered(i, j, w):
    biome = SHATTERED_BIOMES[i][j]
    return biome if biome is not None else _pick_middle(i, j, w)


def _pick_beach(i, j):
    if i == 0:
        return "snowy_beach"
    return "desert" if i == 4 else "beach"


def _pick_shattered_coast(i, j, w):
    biome = _pick_middle(i, j, w) if w[1] >= 0 else _pick_beach(i, j)
    return _maybe_windswept_savanna(i, j, w, biome)


def _pspan(a, b):
    """Climate.Parameter.span(paramA, paramB)."""
    return (a[0], b[1])


def build_entries():
    """
    Returns list of (t, h, c, e, w, biome_name) with each of t/h/c/e/w a
    (min, max) quantized tuple. Only the depth=0 surface entries (each
    addSurfaceBiome also emits a depth=1 copy, irrelevant when sampling
    depth=0; underground/bottom biomes likewise can never win at depth 0).
    """
    entries = []

    def add(t, h, c, e, w, biome):
        entries.append((t, h, c, e, w, biome))

    # ---- addOffCoastBiomes ----
    add(FULL_RANGE, FULL_RANGE, MUSHROOM_CONT, FULL_RANGE, FULL_RANGE, "mushroom_fields")
    for i, temp in enumerate(TEMPERATURES):
        add(temp, FULL_RANGE, DEEP_OCEAN_CONT, FULL_RANGE, FULL_RANGE, OCEANS[0][i])
        add(temp, FULL_RANGE, OCEAN_CONT, FULL_RANGE, FULL_RANGE, OCEANS[1][i])

    # ---- addInlandBiomes ----
    for kind, w in W_SLICES:
        if kind == "mid":
            _add_mid_slice(add, w)
        elif kind == "high":
            _add_high_slice(add, w)
        elif kind == "peaks":
            _add_peaks(add, w)
        elif kind == "low":
            _add_low_slice(add, w)
        elif kind == "valleys":
            _add_valleys(add, w)

    return entries


def _add_peaks(add, w):
    for i, t in enumerate(TEMPERATURES):
        for j, h in enumerate(HUMIDITIES):
            middle = _pick_middle(i, j, w)
            middle_badlands = _pick_middle_or_badlands(i, j, w)
            middle_badlands_slope = _pick_middle_or_badlands_or_slope(i, j, w)
            plateau = _pick_plateau(i, j, w)
            shattered = _pick_shattered(i, j, w)
            windswept = _maybe_windswept_savanna(i, j, w, shattered)
            peak = _pick_peak(i, j, w)
            add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[0], w, peak)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), EROSIONS[1], w, middle_badlands_slope)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[1], w, peak)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), _pspan(EROSIONS[2], EROSIONS[3]), w, middle)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[2], w, plateau)
            add(t, h, MID_INLAND_CONT, EROSIONS[3], w, middle_badlands)
            add(t, h, FAR_INLAND_CONT, EROSIONS[3], w, plateau)
            add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[4], w, middle)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), EROSIONS[5], w, windswept)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[5], w, shattered)
            add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[6], w, middle)


def _pick_peak(i, j, w):
    if i <= 2:
        return "jagged_peaks" if w[1] < 0 else "frozen_peaks"
    return "stony_peaks" if i == 3 else _pick_badlands(j, w)


def _add_high_slice(add, w):
    for i, t in enumerate(TEMPERATURES):
        for j, h in enumerate(HUMIDITIES):
            middle = _pick_middle(i, j, w)
            middle_badlands = _pick_middle_or_badlands(i, j, w)
            middle_badlands_slope = _pick_middle_or_badlands_or_slope(i, j, w)
            plateau = _pick_plateau(i, j, w)
            shattered = _pick_shattered(i, j, w)
            windswept = _maybe_windswept_savanna(i, j, w, middle)
            slope = _pick_slope(i, j, w)
            peak = _pick_peak(i, j, w)
            add(t, h, COAST_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w, middle)
            add(t, h, NEAR_INLAND_CONT, EROSIONS[0], w, slope)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[0], w, peak)
            add(t, h, NEAR_INLAND_CONT, EROSIONS[1], w, middle_badlands_slope)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[1], w, slope)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), _pspan(EROSIONS[2], EROSIONS[3]), w, middle)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[2], w, plateau)
            add(t, h, MID_INLAND_CONT, EROSIONS[3], w, middle_badlands)
            add(t, h, FAR_INLAND_CONT, EROSIONS[3], w, plateau)
            add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[4], w, middle)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), EROSIONS[5], w, windswept)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[5], w, shattered)
            add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[6], w, middle)


def _add_mid_slice(add, w):
    add(FULL_RANGE, FULL_RANGE, COAST_CONT, _pspan(EROSIONS[0], EROSIONS[2]), w, "stony_shore")
    add(_pspan(TEMPERATURES[1], TEMPERATURES[2]), FULL_RANGE,
        _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "swamp")
    add(_pspan(TEMPERATURES[3], TEMPERATURES[4]), FULL_RANGE,
        _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "mangrove_swamp")
    for i, t in enumerate(TEMPERATURES):
        for j, h in enumerate(HUMIDITIES):
            middle = _pick_middle(i, j, w)
            middle_badlands = _pick_middle_or_badlands(i, j, w)
            middle_badlands_slope = _pick_middle_or_badlands_or_slope(i, j, w)
            shattered = _pick_shattered(i, j, w)
            plateau = _pick_plateau(i, j, w)
            beach = _pick_beach(i, j)
            windswept = _maybe_windswept_savanna(i, j, w, middle)
            shattered_coast = _pick_shattered_coast(i, j, w)
            slope = _pick_slope(i, j, w)
            add(t, h, _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[0], w, slope)
            add(t, h, _pspan(NEAR_INLAND_CONT, MID_INLAND_CONT), EROSIONS[1], w, middle_badlands_slope)
            add(t, h, FAR_INLAND_CONT, EROSIONS[1], w, slope if i == 0 else plateau)
            add(t, h, NEAR_INLAND_CONT, EROSIONS[2], w, middle)
            add(t, h, MID_INLAND_CONT, EROSIONS[2], w, middle_badlands)
            add(t, h, FAR_INLAND_CONT, EROSIONS[2], w, plateau)
            add(t, h, _pspan(COAST_CONT, NEAR_INLAND_CONT), EROSIONS[3], w, middle)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[3], w, middle_badlands)
            if w[1] < 0:
                add(t, h, COAST_CONT, EROSIONS[4], w, beach)
                add(t, h, _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[4], w, middle)
            else:
                add(t, h, _pspan(COAST_CONT, FAR_INLAND_CONT), EROSIONS[4], w, middle)
            add(t, h, COAST_CONT, EROSIONS[5], w, shattered_coast)
            add(t, h, NEAR_INLAND_CONT, EROSIONS[5], w, windswept)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[5], w, shattered)
            if w[1] < 0:
                add(t, h, COAST_CONT, EROSIONS[6], w, beach)
            else:
                add(t, h, COAST_CONT, EROSIONS[6], w, middle)
            if i == 0:
                add(t, h, _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, middle)


def _add_low_slice(add, w):
    add(FULL_RANGE, FULL_RANGE, COAST_CONT, _pspan(EROSIONS[0], EROSIONS[2]), w, "stony_shore")
    add(_pspan(TEMPERATURES[1], TEMPERATURES[2]), FULL_RANGE,
        _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "swamp")
    add(_pspan(TEMPERATURES[3], TEMPERATURES[4]), FULL_RANGE,
        _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "mangrove_swamp")
    for i, t in enumerate(TEMPERATURES):
        for j, h in enumerate(HUMIDITIES):
            middle = _pick_middle(i, j, w)
            middle_badlands = _pick_middle_or_badlands(i, j, w)
            middle_badlands_slope = _pick_middle_or_badlands_or_slope(i, j, w)
            beach = _pick_beach(i, j)
            windswept = _maybe_windswept_savanna(i, j, w, middle)
            shattered_coast = _pick_shattered_coast(i, j, w)
            add(t, h, NEAR_INLAND_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w, middle_badlands)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), _pspan(EROSIONS[0], EROSIONS[1]), w,
                middle_badlands_slope)
            add(t, h, NEAR_INLAND_CONT, _pspan(EROSIONS[2], EROSIONS[3]), w, middle)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), _pspan(EROSIONS[2], EROSIONS[3]), w,
                middle_badlands)
            add(t, h, COAST_CONT, _pspan(EROSIONS[3], EROSIONS[4]), w, beach)
            add(t, h, _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[4], w, middle)
            add(t, h, COAST_CONT, EROSIONS[5], w, shattered_coast)
            add(t, h, NEAR_INLAND_CONT, EROSIONS[5], w, windswept)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[5], w, middle)
            add(t, h, COAST_CONT, EROSIONS[6], w, beach)
            if i == 0:
                add(t, h, _pspan(NEAR_INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, middle)


def _add_valleys(add, w):
    add(FROZEN_RANGE, FULL_RANGE, COAST_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w,
        "stony_shore" if w[1] < 0 else "frozen_river")
    add(UNFROZEN_RANGE, FULL_RANGE, COAST_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w,
        "stony_shore" if w[1] < 0 else "river")
    add(FROZEN_RANGE, FULL_RANGE, NEAR_INLAND_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w, "frozen_river")
    add(UNFROZEN_RANGE, FULL_RANGE, NEAR_INLAND_CONT, _pspan(EROSIONS[0], EROSIONS[1]), w, "river")
    add(FROZEN_RANGE, FULL_RANGE, _pspan(COAST_CONT, FAR_INLAND_CONT),
        _pspan(EROSIONS[2], EROSIONS[5]), w, "frozen_river")
    add(UNFROZEN_RANGE, FULL_RANGE, _pspan(COAST_CONT, FAR_INLAND_CONT),
        _pspan(EROSIONS[2], EROSIONS[5]), w, "river")
    add(FROZEN_RANGE, FULL_RANGE, COAST_CONT, EROSIONS[6], w, "frozen_river")
    add(UNFROZEN_RANGE, FULL_RANGE, COAST_CONT, EROSIONS[6], w, "river")
    add(_pspan(TEMPERATURES[1], TEMPERATURES[2]), FULL_RANGE,
        _pspan(INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "swamp")
    add(_pspan(TEMPERATURES[3], TEMPERATURES[4]), FULL_RANGE,
        _pspan(INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "mangrove_swamp")
    add(FROZEN_RANGE, FULL_RANGE, _pspan(INLAND_CONT, FAR_INLAND_CONT), EROSIONS[6], w, "frozen_river")
    for i, t in enumerate(TEMPERATURES):
        for j, h in enumerate(HUMIDITIES):
            middle_badlands = _pick_middle_or_badlands(i, j, w)
            add(t, h, _pspan(MID_INLAND_CONT, FAR_INLAND_CONT), _pspan(EROSIONS[0], EROSIONS[1]), w,
                middle_badlands)


# ---------------------------------------------------------------------------
# JSON dump loader  (terra dump_biomes output)
# ---------------------------------------------------------------------------

def load_entries_from_json(path):
    """
    Load biome climate entries from a terra_biome_dump.json produced by the
    /terra dump_biomes command.  Works with any combination of biome mods.

    Filters to surface entries only (depth range contains 0) and normalises
    biome names: minecraft: prefix is stripped so vanilla biomes match the
    existing BIOME_COLORS keys; other namespaces are kept as-is.
    """
    with open(path, encoding="utf-8") as f:
        data = _json.load(f)

    return _parse_entries(data["entries"])


def load_terrablender_from_json(path):
    """Return TerraBlender metadata from a format-2 dump, or None if absent.

    Returns dict with keys:
      region_size   – int (TerraBlenderConfig.overworldRegionSize)
      region_weights – list[int]
      region_entries – list[list[entry]] (one entry-list per region; region 0
                       is None meaning "use the vanilla entries")
    """
    with open(path, encoding="utf-8") as f:
        data = _json.load(f)
    if "terrablender" not in data:
        return None

    tb = data["terrablender"]
    region_size = int(tb["region_size"])
    vanilla_entries = _parse_entries(data["entries"])

    weights = []
    entries_per_region = []
    for reg in tb["regions"]:
        weights.append(int(reg["weight"]))
        raw = reg["entries"]
        if raw == "vanilla":
            entries_per_region.append(vanilla_entries)
        else:
            entries_per_region.append(_parse_entries(raw))

    return {
        "region_size": region_size,
        "region_weights": weights,
        "region_entries": entries_per_region,
    }


def _parse_entries(raw_list):
    """Shared helper: parse a JSON entry list → [(t,h,c,e,w,name), ...]."""
    result = []
    for e in raw_list:
        d_min, d_max = e["d"]
        if d_min > 0 or d_max < 0:
            continue
        raw = e["biome"]
        name = raw[len("minecraft:"):] if raw.startswith("minecraft:") else raw
        result.append((
            tuple(e["t"]), tuple(e["h"]), tuple(e["c"]),
            tuple(e["e"]), tuple(e["w"]), name,
        ))
    return result


# ---------------------------------------------------------------------------
# Fast lookup table
# ---------------------------------------------------------------------------

class BiomeTable:
    def __init__(self, entries=None, cache=True):
        self.entries = entries if entries is not None else build_entries()
        names = []
        seen = {}
        ids = []
        for ent in self.entries:
            name = ent[5]
            if name not in seen:
                seen[name] = len(names)
                names.append(name)
            ids.append(seen[name])
        self.biome_names = names
        self._entry_biome = np.array(ids, dtype=np.int32)
        # (N, 5, 2) box bounds
        self._boxes = np.array([[e[0], e[1], e[2], e[3], e[4]] for e in self.entries],
                               dtype=np.float64)
        self.edges = [self._dim_edges(d) for d in range(5)]
        self._table = (self._load_cached() if cache else None)
        if self._table is None:
            self._table = self._build_table()
            if cache:
                self._save_cache()

    def _cache_path_and_key(self):
        path = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)),
                             ".biome_table_cache.npz")
        key = _hashlib.md5(repr(self.entries).encode()).hexdigest()
        return path, key

    def _load_cached(self):
        path, key = self._cache_path_and_key()
        try:
            data = np.load(path, allow_pickle=False)
            if str(data["key"]) == key:
                return data["table"]
        except Exception:
            pass
        return None

    def _save_cache(self):
        path, key = self._cache_path_and_key()
        try:
            np.savez_compressed(path, key=key, table=self._table)
        except OSError:
            pass

    def _dim_edges(self, dim):
        boxes = self._boxes[:, dim, :]
        edges = sorted(set(boxes[:, 0]).union(boxes[:, 1]))
        # find stretches not covered by any box; the nearest-box winner flips at
        # the midpoint there (competing boxes are flat in the other dims across
        # the gap), so insert an edge at each gap midpoint
        extra = []
        for a, b in zip(edges[:-1], edges[1:]):
            covered = np.any((boxes[:, 0] <= a) & (boxes[:, 1] >= b))
            if not covered:
                extra.append((a + b) / 2.0)
        return np.array(sorted(edges + extra), dtype=np.float64)

    def _build_table(self):
        reps = [
            (edges[:-1] + edges[1:]) / 2.0  # interior representative per band-cell
            for edges in self.edges
        ]
        shape = tuple(len(r) for r in reps)
        grids = np.meshgrid(*reps, indexing="ij")
        pts = np.stack([g.ravel() for g in grids], axis=1)  # (cells, 5)
        n_cells = pts.shape[0]
        lo = self._boxes[:, :, 0]  # (N, 5)
        hi = self._boxes[:, :, 1]
        winner = np.empty(n_cells, dtype=np.int32)
        chunk = 4096
        for start in range(0, n_cells, chunk):
            p = pts[start:start + chunk]  # (c, 5)
            # distance-to-box per dim: max(lo - v, v - hi, 0)
            d = np.maximum(lo[None, :, :] - p[:, None, :], p[:, None, :] - hi[None, :, :])
            np.maximum(d, 0.0, out=d)
            fitness = np.einsum("cnd,cnd->cn", d, d)
            winner[start:start + chunk] = np.argmin(fitness, axis=1)
        return self._entry_biome[winner].reshape(shape).astype(np.int16)

    def lookup(self, t, h, c, e, w):
        """
        t/h/c/e/w: quantized int64 arrays (any shape). Returns int16 biome-id
        array; ids index into self.biome_names.
        """
        idx = []
        for dim, v in enumerate((t, h, c, e, w)):
            edges = self.edges[dim]
            vc = np.clip(v, edges[0], edges[-1])
            idx.append(np.searchsorted(edges[1:-1], vc, side="right"))
        return self._table[tuple(idx)]

    def brute_force_biome(self, t, h, c, e, w):
        """Exact nearest-entry search for one quantized point (insertion-order ties)."""
        p = np.array([t, h, c, e, w], dtype=np.float64)
        d = np.maximum(self._boxes[:, :, 0] - p, p - self._boxes[:, :, 1])
        np.maximum(d, 0.0, out=d)
        fitness = np.einsum("nd,nd->n", d, d)
        return self.biome_names[self._entry_biome[np.argmin(fitness)]]


def quantize(values):
    """Climate.target: double -> (float) cast -> * 10000.0F -> (long) trunc."""
    f = np.asarray(values, dtype=np.float32) * np.float32(10000.0)
    return np.trunc(f).astype(np.int64)


# ---------------------------------------------------------------------------
# Multi-region biome table (TerraBlender support)
# ---------------------------------------------------------------------------

class MultiRegionBiomeTable:
    """Wraps one BiomeTable per TerraBlender region with a shared biome palette.

    Usage mirrors BiomeTable except lookup() and brute_force_biome() also
    take the world seed and block X/Z arrays so the uniqueness noise can
    route each pixel to the correct region's table.
    """

    def __init__(self, region_entries, region_size, region_weights):
        self.region_size = region_size
        self.region_weights = region_weights

        # Unified biome name list across all regions
        all_names = []
        seen = {}
        self._region_tables = []
        self._id_maps = []   # local biome-id → global biome-id per region

        for entries in region_entries:
            tbl = BiomeTable(entries=entries, cache=False)
            self._region_tables.append(tbl)
            mapping = []
            for local_name in tbl.biome_names:
                if local_name not in seen:
                    seen[local_name] = len(all_names)
                    all_names.append(local_name)
                mapping.append(seen[local_name])
            self._id_maps.append(np.array(mapping, dtype=np.int32))

        self.biome_names = all_names
        ph = "terrablender:deferred_placeholder"
        self._placeholder_id = all_names.index(ph) if ph in all_names else None

    def lookup(self, world_seed, qt, qh, qc, qe, qw, block_X, block_Z):
        """Return global int32 biome-ID array for the given climate + position arrays."""
        reg_ids = get_region_indices(
            world_seed, self.region_size, self.region_weights, block_X, block_Z)
        result = np.empty(qt.shape, dtype=np.int32)
        for i, (tbl, id_map) in enumerate(
                zip(self._region_tables, self._id_maps)):
            mask = (reg_ids == i)
            if not mask.any():
                continue
            local = tbl.lookup(qt[mask], qh[mask], qc[mask], qe[mask], qw[mask])
            result[mask] = id_map[local.astype(np.int32)]

        # DEFERRED_PLACEHOLDER means "use vanilla instead" — fall back to region 0
        if self._placeholder_id is not None:
            deferred = (result == self._placeholder_id)
            if deferred.any():
                van_tbl, van_map = self._region_tables[0], self._id_maps[0]
                local = van_tbl.lookup(
                    qt[deferred], qh[deferred], qc[deferred],
                    qe[deferred], qw[deferred])
                result[deferred] = van_map[local.astype(np.int32)]

        return result.astype(np.int16)

    def brute_force_biome(self, world_seed, t, h, c, e, w, block_x, block_z):
        """Single-point exact lookup for map inspection."""
        bx = np.array([[block_x]], dtype=np.int64)
        bz = np.array([[block_z]], dtype=np.int64)
        reg_idx = int(get_region_indices(
            world_seed, self.region_size, self.region_weights, bx, bz).ravel()[0])
        tbl = self._region_tables[reg_idx]
        name = tbl.brute_force_biome(t, h, c, e, w)
        if name == "terrablender:deferred_placeholder":
            name = self._region_tables[0].brute_force_biome(t, h, c, e, w)
        return name


# ---------------------------------------------------------------------------
# Colors (Amidst-style palette)
# ---------------------------------------------------------------------------

BIOME_COLORS = {
    "ocean": "#000070", "deep_ocean": "#000030",
    "frozen_ocean": "#7070D6", "deep_frozen_ocean": "#404090",
    "cold_ocean": "#202070", "deep_cold_ocean": "#202038",
    "lukewarm_ocean": "#000090", "deep_lukewarm_ocean": "#000040",
    "warm_ocean": "#0000AC",
    "river": "#0000FF", "frozen_river": "#A0A0FF",
    "beach": "#FADE55", "snowy_beach": "#FAF0C0", "stony_shore": "#A2A284",
    "plains": "#8DB360", "sunflower_plains": "#B5DB88",
    "snowy_plains": "#FFFFFF", "ice_spikes": "#B4DCDC",
    "desert": "#FA9418",
    "swamp": "#07F9B2", "mangrove_swamp": "#67352B",
    "forest": "#056621", "flower_forest": "#2D8E49",
    "birch_forest": "#307444", "old_growth_birch_forest": "#589C6C",
    "dark_forest": "#40511A",
    "taiga": "#0B6659", "snowy_taiga": "#31554A",
    "old_growth_pine_taiga": "#596651", "old_growth_spruce_taiga": "#818E79",
    "savanna": "#BDB25F", "savanna_plateau": "#A79D64", "windswept_savanna": "#E5DA87",
    "jungle": "#537B09", "sparse_jungle": "#628B17", "bamboo_jungle": "#768E14",
    "badlands": "#D94515", "eroded_badlands": "#FF6D3D", "wooded_badlands": "#B09765",
    "meadow": "#7DA854", "cherry_grove": "#F7B9DC",
    "grove": "#88BB66", "snowy_slopes": "#C4C4C4",
    "jagged_peaks": "#DCDCC8", "frozen_peaks": "#B0B3CE", "stony_peaks": "#888888",
    "windswept_hills": "#597D72", "windswept_gravelly_hills": "#789878",
    "windswept_forest": "#589C6C",
    "mushroom_fields": "#FF00FF",

    # Oh The Biomes We've Gone
    "biomeswevegone:allium_shrubland": "#8880C0",
    "biomeswevegone:amaranth_grassland": "#C060A0",
    "biomeswevegone:araucaria_savanna": "#B89840",
    "biomeswevegone:aspen_boreal": "#A8C460",
    "biomeswevegone:atacama_outback": "#C8A068",
    "biomeswevegone:baobab_savanna": "#BF9A38",
    "biomeswevegone:basalt_barrera": "#3A3A3A",
    "biomeswevegone:bayou": "#1A4A30",
    "biomeswevegone:black_forest": "#1C3312",
    "biomeswevegone:canadian_shield": "#6A7060",
    "biomeswevegone:cika_woods": "#3E6830",
    "biomeswevegone:coconino_meadow": "#B8A870",
    "biomeswevegone:coniferous_forest": "#1E5035",
    "biomeswevegone:crag_gardens": "#5A7850",
    "biomeswevegone:crimson_tundra": "#A02828",
    "biomeswevegone:cypress_swamplands": "#1E4A28",
    "biomeswevegone:cypress_wetlands": "#286850",
    "biomeswevegone:dacite_ridges": "#787070",
    "biomeswevegone:dacite_shore": "#888080",
    "biomeswevegone:dead_sea": "#D0C8A8",
    "biomeswevegone:ebony_woods": "#283018",
    "biomeswevegone:enchanted_tangle": "#4A2870",
    "biomeswevegone:firecracker_chaparral": "#B84828",
    "biomeswevegone:forgotten_forest": "#4A5840",
    "biomeswevegone:fragment_jungle": "#3A7808",
    "biomeswevegone:frosted_coniferous_forest": "#3A6868",
    "biomeswevegone:frosted_taiga": "#3A5858",
    "biomeswevegone:howling_peaks": "#C0C8D8",
    "biomeswevegone:ironwood_gour": "#4A5868",
    "biomeswevegone:jacaranda_jungle": "#7840A8",
    "biomeswevegone:lush_stacks": "#187820",
    "biomeswevegone:maple_taiga": "#A83820",
    "biomeswevegone:mojave_desert": "#D0A868",
    "biomeswevegone:orchard": "#78B850",
    "biomeswevegone:overgrowth_woodlands": "#287828",
    "biomeswevegone:pale_bog": "#8AA888",
    "biomeswevegone:prairie": "#C8A840",
    "biomeswevegone:pumpkin_valley": "#D06020",
    "biomeswevegone:rainbow_beach": "#38D0B8",
    "biomeswevegone:red_rock_peaks": "#C03820",
    "biomeswevegone:red_rock_valley": "#B84830",
    "biomeswevegone:redwood_thicket": "#6A2218",
    "biomeswevegone:rose_fields": "#F07888",
    "biomeswevegone:rugged_badlands": "#C03028",
    "biomeswevegone:sakura_grove": "#F0A0C8",
    "biomeswevegone:shattered_glacier": "#B0C8E8",
    "biomeswevegone:sierra_badlands": "#B87840",
    "biomeswevegone:skyris_vale": "#5880B8",
    "biomeswevegone:temperate_grove": "#5A8840",
    "biomeswevegone:tropical_rainforest": "#186818",
    "biomeswevegone:weeping_witch_forest": "#382840",
    "biomeswevegone:white_mangrove_marshes": "#689878",
    "biomeswevegone:windswept_desert": "#C8A040",
    "biomeswevegone:zelkova_forest": "#88A840",
}
