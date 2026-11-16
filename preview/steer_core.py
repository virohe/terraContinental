"""
Spawn-continent steering: the reference implementation, 1:1 with SpawnSteer.java.

For each world, try `candidates` spawn layouts (a branching chain of lobes, scaled to the target
area, plus the two nearest continents moved to sit a target gap away), build each one's continent
field on a 513 x 513 grid (128 blocks a cell, 65.5 km across, the loading map's extent) from the
seed's own upstream noise, measure the spawn landmass, and keep the layout closest to the targets
(the user, 2026-11-10: about 275 km2, not round, the nearest neighbour 3-6 km across the water).

Written so Java reproduces it exactly: SplitMix64 for every draw, sums taken in row-major order
one value at a time (numpy's own .mean() sums pairwise), stable sorts, 4-connected labelling and
an exact integer Euclidean distance transform. Sin, cos and exp may differ from Java's in the
last bit, which can move a coast cell on a knife edge; tests/parity compares the picks.
"""

import math
import struct

import numpy as np

MASK = (1 << 64) - 1
GOLDEN = 0x9E3779B97F4A7C15

R, STEP = 32768, 128
N = 2 * (R // STEP) + 1          # 513
PX = STEP * STEP / 1e6           # km2 per cell
COAST = -0.19                    # the loading map's line: vanilla's ocean biomes end at -0.19
OFFSET = 400000.0                # retry rounds shift the noise by up to this many blocks
BIG = 50.0                       # km2: smaller land doesn't count as a neighbour
LAND_FRAC = 0.555                # land reaches about this fraction of a lobe's softened radius
FLOOR = -1.02                    # continent_base's floor


class SplitMix:
    def __init__(self, seed):
        self.s = seed & MASK

    def next(self):
        self.s = (self.s + GOLDEN) & MASK
        z = self.s
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
        return z ^ (z >> 31)

    def unit(self):
        return (self.next() >> 11) * (2.0 ** -53)

    def uniform(self, a, b):
        return a + (b - a) * self.unit()

    def below(self, n):
        return int(n * self.unit())


def _bits(v):
    return struct.unpack("<q", struct.pack("<d", float(v)))[0] & MASK


def _jn(noise, x, z):
    return float(noise.get_value(np.array([x], dtype=np.float64), 0.0, np.array([z], dtype=np.float64))[0])


def steer_seed(noise):
    """The steering's RNG seed: two raw jitter-noise samples (indices 50, 51), so it follows the world seed."""
    a = _jn(noise, 50 * 9973.0 + 31.0, 50 * -6101.0 - 97.0)
    b = _jn(noise, 51 * 9973.0 + 31.0, 51 * -6101.0 - 97.0)
    return ((_bits(a) * GOLDEN) & MASK) ^ _bits(b)


def smooth(t):
    t = np.minimum(t, 1.0)
    return 1.0 - t * t * (3.0 - 2.0 * t)


def seq_sum(a):
    """Row-major, one value at a time (Java's loop), not numpy's pairwise sum."""
    a = np.ravel(a)
    return float(np.cumsum(a)[-1]) if a.size else 0.0


def draw(rng, radius):
    """One candidate layout, before scaling."""
    lobes = [(0.0, 0.0, radius * rng.uniform(0.42, 0.55))]
    k = 3 + rng.below(4)
    for _ in range(k):
        # Branching chain: mostly grow from the newest lobe, sometimes from an older one.
        parent = lobes[-1] if rng.unit() < 0.65 else lobes[rng.below(len(lobes))]
        r = radius * rng.uniform(0.28, 0.5)
        a = rng.uniform(0.0, 2.0 * math.pi)
        d = (parent[2] + r) * rng.uniform(0.5, 0.75)
        lobes.append((parent[0] + math.cos(a) * d, parent[1] + math.sin(a) * d, r))
    soft = rng.uniform(1.05, 1.35)
    warp = rng.uniform(0.15, 0.35) * radius
    wavelength = (0.5 if rng.unit() < 0.5 else 0.7) * radius
    level = rng.uniform(-0.05, 0.1)
    n_angle = rng.uniform(0.0, 2.0 * math.pi)
    n_spread = rng.uniform(math.pi * 0.55, math.pi * 1.45)
    gap = rng.uniform(0.0, 1.0)   # where in [gap_min, gap_max]
    return dict(lobes=lobes, soft=soft, warp=warp, wavelength=wavelength, level=level,
                n_angle=n_angle, n_spread=n_spread, gap=gap)


def shape_area(lobes, soft):
    """km2 of the union of the lobes' land discs, on a 200-block grid (no noise)."""
    ext = max(math.sqrt(x * x + z * z) + r * soft for x, z, r in lobes)
    n = int(math.ceil((2.0 * ext + 1.0) / 200.0))
    g = -ext + np.arange(n) * 200.0
    GX, GZ = np.meshgrid(g, g)
    m = np.zeros_like(GX, dtype=bool)
    for x, z, r in lobes:
        lim = (LAND_FRAC * r * soft) * (LAND_FRAC * r * soft)
        m |= (GX - x) * (GX - x) + (GZ - z) * (GZ - z) < lim
    return int(m.sum()) * 0.04


def scale(L, target):
    k = math.sqrt(target / max(shape_area(L["lobes"], L["soft"]), 1.0))
    L["lobes"] = [(x * k, z * k, r * k) for x, z, r in L["lobes"]]
    L["warp"] = L["warp"] * k
    return L


class Grid:
    """The seed's fixed inputs on the 513 x 513 grid."""

    def __init__(self, up, noise):
        xs = -R + np.arange(N) * float(STEP)
        self.X, self.Z = np.meshgrid(xs, xs)        # [z][x], row-major like Java
        self.D = np.sqrt(self.X * self.X + self.Z * self.Z)
        self.up = up
        self.noise = noise
        self._warp = {}

    def warp(self, wavelength):
        if wavelength not in self._warp:
            self._warp[wavelength] = warp_at(self.noise, self.X, self.Z, wavelength)
        return self._warp[wavelength]


def warp_at(noise, X, Z, wavelength):
    f = 64.0 / wavelength
    return (np.clip(noise.get_value(X * f + 5.0e5, 0.0, Z * f) * 2.0, -1.0, 1.0),
            np.clip(noise.get_value(X * f - 5.0e5, 0.0, Z * f + 3.0e5) * 2.0, -1.0, 1.0))


def finish(L, grid, centers, p):
    """Fix a scaled layout to the seed: lobe heights from the noise under them, neighbours placed."""
    lobes = []
    for x, z, r in L["lobes"]:
        dx, dz = grid.X - x, grid.Z - z
        under = grid.up[np.sqrt(dx * dx + dz * dz) < r]
        mean = seq_sum(under) / under.size if under.size else 0.0
        lobes.append((x, z, r, p.central_land_bump / L["soft"] + L["level"] - mean))
    cx, cz, cr = (np.array(c, dtype=np.float64) for c in centers)
    order = sorted(range(cx.size), key=lambda i: math.sqrt(cx[i] * cx[i] + cz[i] * cz[i]))  # stable
    gap = (p.spawn_gap_min + (p.spawn_gap_max - p.spawn_gap_min) * L["gap"]) * 1000.0
    angles = [L["n_angle"], L["n_angle"] + L["n_spread"]]
    for slot, i in enumerate(order[:2]):
        ux, uz = math.cos(angles[slot]), math.sin(angles[slot])
        reach = max(x * ux + z * uz + 0.7 * r * L["soft"] for x, z, r, _ in lobes)
        dist = reach + gap + 0.8 * cr[i]
        cx[i], cz[i] = ux * dist, uz * dist
    spawn_reach = max(math.sqrt(x * x + z * z) + r * L["soft"] for x, z, r, _ in lobes)
    for i in order[2:]:
        d = math.sqrt(cx[i] * cx[i] + cz[i] * cz[i])
        need = spawn_reach + cr[i] + 6000.0
        if d < need:
            cx[i], cz[i] = cx[i] / d * need, cz[i] / d * need
    return dict(lobes=lobes, soft=L["soft"], warp=L["warp"], wavelength=L["wavelength"],
                core=lobes[0][2], centers=[list(map(float, cx)), list(map(float, cz)), list(map(float, cr))])


def spawn_bump(lay, X, Z, D, wx, wz):
    core = lay["core"]
    t = np.minimum(1.0, D / core)
    amp = lay["warp"] * (t * t * (3.0 - 2.0 * t))
    Xw = X + amp * wx
    Zw = Z + amp * wz
    out = np.zeros(np.shape(X))
    for x, z, r, h in lay["lobes"]:
        dx, dz = Xw - x, Zw - z
        out = np.maximum(out, h * smooth(np.sqrt(dx * dx + dz * dz) / (r * lay["soft"])))
    return out


def falloff(lay, up, X, Z, p, wx, wz):
    """RadialFalloffFunction.compute with a steered layout (continent_base before its floor)."""
    D = np.sqrt(X * X + Z * Z)
    bumped = up + spawn_bump(lay, X, Z, D, wx, wz)
    cx, cz, cr = lay["centers"]
    for i in range(len(cx)):
        dx, dz = X - cx[i], Z - cz[i]
        bumped = bumped + p.continent_bump * smooth(np.sqrt(dx * dx + dz * dz) / cr[i])
    biased = bumped - (p.ocean_bias + p.radial_bias_strength * (D / p.core_radius))
    with np.errstate(over="ignore"):
        blend = 1.0 / (1.0 + np.exp((D - p.border_radius) / p.transition_width))
    return blend * biased + (1.0 - blend) * p.ocean_floor_value


def continent_base(lay, up, X, Z, p, wx, wz):
    return np.maximum(falloff(lay, up, X, Z, p, wx, wz), FLOOR)


# -- measuring ---------------------------------------------------------------------------------

def label4(mask):
    """4-connected components, numbered in row-major order of first cell (as Java's flood fill)."""
    from scipy import ndimage
    lab, n = ndimage.label(mask)
    return lab, n


def edt_sq(feature):
    """Exact squared Euclidean distance (in cells) to the nearest True cell; scipy's EDT squared."""
    from scipy import ndimage
    if not feature.any():
        return None
    d = ndimage.distance_transform_edt(~feature, return_distances=True)
    return np.rint(d * d)


def measure(v, p):
    land = v > COAST
    lab, nl = label4(land)
    c = N // 2
    if land[c, c]:
        sp = lab[c, c]
    else:
        ys, xs = np.nonzero(land)
        if ys.size == 0:
            return dict(area=0.0, roundness=1.0, gap=None, others=0.0)
        d2 = (ys - c) * (ys - c) + (xs - c) * (xs - c)
        j = int(np.argmin(d2))
        sp = lab[ys[j], xs[j]]
    counts = np.bincount(lab.ravel(), minlength=nl + 1)
    spm = lab == sp
    area = int(counts[sp]) * PX
    yy, xx = np.nonzero(spm)
    ym = seq_sum(yy.astype(np.float64)) / yy.size
    xm = seq_sum(xx.astype(np.float64)) / xx.size
    dy, dx = yy - ym, xx - xm
    rg2 = seq_sum(dy * dy + dx * dx) / yy.size * (STEP / 1000.0) * (STEP / 1000.0)
    roundness = area / (2.0 * math.pi * rg2) if rg2 > 0 else 1.0
    big = [i for i in range(1, nl + 1) if i != sp and counts[i] * PX >= BIG]
    gap = None
    if big:
        d2 = edt_sq(np.isin(lab, big))
        gap = math.sqrt(float(d2[spm].min())) * STEP / 1000.0
    others = (int(land.sum()) - int(counts[sp])) * 100.0 / (N * N)
    return dict(area=area, roundness=roundness, gap=gap, others=others)


def score(m, p):
    s = 0.0
    da = abs(math.log(max(m["area"], 1.0) / p.spawn_target_area)) / math.log(1.15)
    s += max(0.0, da - 0.3) * 2.0
    s += max(0.55 - m["roundness"], m["roundness"] - 0.82, 0.0) / 0.1
    if m["gap"] is None:
        s += 5.0
    else:
        s += max(p.spawn_gap_min - m["gap"], m["gap"] - p.spawn_gap_max, 0.0) / 1.5 * 1.5
    s += max(0.0, m["others"] - p.spawn_max_other_land) / 2.0
    return s


def steer(upstream_fn, noise, centers, p, candidates=None):
    """The best layout for this seed. Round 0 reads the seed's noise where it is; while no layout
    hits every target, each further round (up to spawn_rounds) reads it shifted by a drawn offset,
    which the continent field then keeps (RadialFalloffFunction samples its noise there): a new
    continent shape without a new seed. upstream_fn(X, Z) is the noise before the falloff."""
    n = int(candidates if candidates is not None else p.spawn_candidates)
    rng = SplitMix(steer_seed(noise))
    offsets = SplitMix(steer_seed(noise) ^ 0x0FF5E7)
    xs = -R + np.arange(N) * float(STEP)
    X, Z = np.meshgrid(xs, xs)
    best = None
    for rnd in range(max(1, int(p.spawn_rounds))):
        if rnd == 0:
            ox = oz = 0
        else:
            ox = int(offsets.uniform(-OFFSET, OFFSET))
            oz = int(offsets.uniform(-OFFSET, OFFSET))
        grid = Grid(upstream_fn(X + ox, Z + oz), noise)
        drawn = [scale(draw(rng, p.central_land_radius), p.spawn_target_area) for _ in range(n)]
        for i, L in enumerate(drawn):
            lay = finish(L, grid, centers, p)
            wx, wz = grid.warp(lay["wavelength"])
            v = continent_base(lay, grid.up, grid.X, grid.Z, p, wx, wz)
            m = measure(v, p)
            sc = score(m, p)
            if best is None or sc < best["score"]:
                best = dict(lay, index=i, round=rnd, offset=[ox, oz], score=sc, metrics=m)
        if best["score"] <= 0.0:
            break
    return best, None


# -- the previewer's cache ---------------------------------------------------------------------

_MEMO = {}
_PARAMS = ("ocean_bias", "radial_bias_strength", "core_radius", "border_radius", "transition_width",
           "ocean_floor_value", "central_land_bump", "central_land_radius", "additional_continents",
           "continent_spread", "continent_radius", "continent_bump", "spawn_target_area",
           "spawn_gap_min", "spawn_gap_max", "spawn_candidates", "spawn_max_other_land", "spawn_rounds")


def layout_for(key, upstream_fn, noise, centers, p):
    """steer() once per seed and settings: kept in memory and in preview/out/.steer_cache, since
    each previewer worker process would otherwise search again (~10 s)."""
    import hashlib
    import json
    import os
    sig = json.dumps([str(key), steer_seed(noise)] + [getattr(p, k) for k in _PARAMS])
    h = hashlib.sha1(sig.encode()).hexdigest()[:20]
    if h in _MEMO:
        return _MEMO[h]
    folder = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", ".steer_cache")
    path = os.path.join(folder, h + ".json")
    if os.path.exists(path):
        lay = json.load(open(path))
        lay["lobes"] = [tuple(l) for l in lay["lobes"]]
    else:
        lay, _ = steer(upstream_fn, noise, centers, p)
        os.makedirs(folder, exist_ok=True)
        json.dump(lay, open(path, "w"))
    _MEMO[h] = lay
    return lay
