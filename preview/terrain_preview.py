"""
Terrain previewer: terrain variants x seeds, offline, as pictures and numbers.

    python preview/terrain_preview.py --out preview/out/run1 --seeds 4 \
        --variant "now:" \
        --variant "calmer: vertical_scale=2.0 erosion_scale=0.15 ridge_scale=0.15" \
        --variant "wider: tc.continent_bump=1.1"

Each --variant is "name: key=value ...": tc.<key> are terra_continental-common.toml
[continent_shaping] keys, pack=<dir> loads a datapack above the terrain pack
(shape experiments from shape_pack.py; repeatable); everything else comes from --instance
(the Terra Incognita instance by default, or a rig folder). Heights come from tectonic_terrain,
which matches the game's getBaseHeight (test_terrain_parity.py), so no server is started.

Writes to --out:
  overview.png  one hill-shaded map per variant (rows) and seed (columns), --radius around the
                origin, every --step blocks: where land is, how high, where the ranges run
  detail.png    terrain_3d.py perspective views of a --size block square per variant/seed, at
                block resolution, centred where the spawn continent's steepest coast is (or --at)
  metrics.json  per run, and a table on stdout averaged over seeds ("sea" is water connected
                to the map's edge; the terrain's sea-level rivers and lakes count as land):
                  land        share of the overview that is land
                  p50/p90/max height above sea on land (overview)
                  coast       median height above sea at 0-128, 128-256, 256-512, 512-1024,
                              1024-2048 blocks from the sea: how fast the land climbs inland
                  wall        share of land within 256 blocks of the sea that stands 100+ above it
                  to100_p10/p50  distance from the sea of ground 100+ above it: how deep the
                              coastal plain is where thinnest (p10) and typically (p50)
                  mountain    share of land 200+ above sea;  over900: overview cells 900+ above
                  steep45/27  share of 8-block steps steeper than 45 / ~27 deg over 40 random
                              256-block land patches per seed (the 3-D view's own numbers,
                              from the steepest coast, are kept as coastview_*),
                              steep45_low/mid/high: the same for land under +150, +150-400, +400
                  gentle      share of steps under ~14 deg (rise of 2 or less over 8 blocks)
                  needles     share of land standing 6+ off its 4 neighbours' mean: spikes and
                              pits score, straight walls don't

Runs go to a process pool (--jobs, default the CPU count). An overview at the defaults takes
about a minute per run on one core; a detail grid about 30 s.
"""

import argparse
import json
import math
import multiprocessing as mp
import os
import random
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SEA = 63
COAST_BANDS = [(0, 128), (128, 256), (256, 512), (512, 1024), (1024, 2048)]
RIG_SEED = 4151832666630476678       # rigs/realgen-test's level-seed, for comparison with rig runs


def parse_variant(text):
    name, _, rest = text.partition(":")
    tect, cont, packs = {}, {}, []
    for item in rest.split():
        k, v = item.split("=", 1)
        if k == "pack":
            packs.append(os.path.abspath(v))
            continue
        val = json.loads(v)
        if k.startswith("tc."):
            cont[k[3:]] = val
        else:
            tect[k] = val
    return {"name": name.strip(), "tectonic": tect, "continental": cont, "packs": packs}


# -- metrics ------------------------------------------------------------------

def distance_to_sea(land, step, limit):
    """Blocks from each land cell to the nearest sea cell, up to limit. Grown one ring of 8
    neighbours at a time (Chebyshev distance): up to ~40% long on diagonals, which is fine for
    bands this wide."""
    dist = np.full(land.shape, np.inf)
    dist[~land] = 0.0
    frontier = ~land
    reached = ~land
    d = 0
    while d * step < limit and frontier.any():
        d += 1
        grow = frontier.copy()
        grow[1:, :] |= frontier[:-1, :]
        grow[:-1, :] |= frontier[1:, :]
        g2 = grow.copy()
        g2[:, 1:] |= grow[:, :-1]
        g2[:, :-1] |= grow[:, 1:]
        new = g2 & ~reached
        dist[new] = d * step
        reached |= new
        frontier = new
    return dist


def ocean_mask(h):
    """Water connected to the map's edge. The terrain's rivers and lakes sit at sea level too;
    counting them as sea made every valley a "coast" (smoke run, 2026-10-23)."""
    water = h <= SEA
    ocean = np.zeros_like(water)
    ocean[0, :], ocean[-1, :], ocean[:, 0], ocean[:, -1] = water[0, :], water[-1, :], water[:, 0], water[:, -1]
    while True:
        grow = ocean.copy()
        grow[1:, :] |= ocean[:-1, :]
        grow[:-1, :] |= ocean[1:, :]
        grow[:, 1:] |= ocean[:, :-1]
        grow[:, :-1] |= ocean[:, 1:]
        grow &= water
        if (grow == ocean).all():
            return ocean
        ocean = grow


def overview_metrics(h, step):
    ocean = ocean_mask(h)
    land = ~ocean            # rivers and lakes count as land here
    above = np.maximum(h - SEA, 0)
    out = {"land": round(float(land.mean()), 3)}
    if land.any():
        a = above[land]
        out["p50"] = int(np.percentile(a, 50))
        out["p90"] = int(np.percentile(a, 90))
        out["max"] = int(a.max())
    if not ocean.any():
        out["coast"] = [None] * len(COAST_BANDS)
        out["wall"] = None
        return out
    dist = distance_to_sea(land, step, 8192)
    coast = []
    for lo, hi in COAST_BANDS:
        sel = land & (dist >= lo) & (dist < hi)
        coast.append(int(np.median(above[sel])) if sel.any() else None)
    out["coast"] = coast
    near = land & (dist <= 256)
    out["wall"] = round(float((above[near] >= 100).mean()), 3) if near.any() else None
    high = land & (above >= 100) & np.isfinite(dist)
    # How close to the sea ground 100+ above it comes: the coastal plain's depth where it is
    # thinnest (p10) and typically (p50).
    out["to100_p10"] = int(np.percentile(dist[high], 10)) if high.any() else None
    out["to100_p50"] = int(np.percentile(dist[high], 50)) if high.any() else None
    out["mountain"] = round(float((above[land] >= 200).mean()), 3) if land.any() else None
    out["over900"] = int((above >= 900).sum())
    return out


def detail_metrics(h):
    land = h > SEA
    steps = []
    for a, b, l in ((h[:, 1:], h[:, :-1], land[:, 1:] | land[:, :-1]),
                    (h[1:, :], h[:-1, :], land[1:, :] | land[:-1, :])):
        steps.append(np.abs(a - b)[l])
    d = np.concatenate(steps) if steps else np.zeros(0)
    if d.size == 0:
        return {"steep45": None, "steep27": None, "gentle": None}
    out = {"steep45": round(float((d > 8).mean()), 3),
           "steep27": round(float((d > 4).mean()), 3),
           "gentle": round(float((d <= 2).mean()), 3)}
    # Steepness by altitude (the target puts steep ground high up): the steeper of each cell's
    # x and z steps, over land in three bands.
    hf = h.astype(np.float64)
    gx = np.abs(np.diff(hf, axis=1))[:-1, :]
    gz = np.abs(np.diff(hf, axis=0))[:, :-1]
    g = np.maximum(gx, gz)
    a = (hf - SEA)[:-1, :-1]
    lm = land[:-1, :-1]
    for band, lo, hi in (("low", 0, 150), ("mid", 150, 400), ("high", 400, 10 ** 6)):
        sel = lm & (a >= lo) & (a < hi)
        out["steep45_" + band] = round(float((g[sel] > 8).mean()), 3) if sel.sum() > 20 else None
    # Needles: cells standing 6+ blocks off the mean of their 4 neighbours (8 blocks away).
    # A wall is steep but straight across, so it scores low; spikes and pits score high.
    lap = hf[1:-1, 1:-1] - (hf[:-2, 1:-1] + hf[2:, 1:-1] + hf[1:-1, :-2] + hf[1:-1, 2:]) / 4
    li = land[1:-1, 1:-1]
    out["needles"] = round(float((np.abs(lap[li]) > 6).mean()), 3) if li.any() else None
    return out


def steepest_coast(h, step, radius, size):
    """Centre for a detail view of the coast with the most rise within ~512 blocks inland.
    terrain_3d.py looks north from the grid's south edge, so only south-facing coasts with open
    sea for size/2 below them qualify, and the centre sits size/4 inland: the camera is then over
    water looking at the coast. (Centring on the coast itself put the camera inside mountains.)"""
    ocean = ocean_mask(h)
    land = ~ocean
    dist = distance_to_sea(land, step, 600)
    reach = max(1, (size // 2) // step)
    sea_south = np.ones_like(ocean)
    for k in range(1, reach + 1):
        shifted = np.zeros_like(ocean)
        shifted[:-k, :] = ocean[k:, :]
        sea_south &= shifted
    coastal = land & (dist <= step * 1.5) & sea_south
    if not coastal.any():
        coastal = land & (dist <= step * 1.5)
    if not coastal.any():
        return 0, 0
    k = max(1, 512 // step)
    pad = np.pad(h, k, mode="edge")
    best = np.zeros_like(h)
    for dz in range(-k, k + 1, max(1, k // 4)):
        for dx in range(-k, k + 1, max(1, k // 4)):
            best = np.maximum(best, pad[k + dz:k + dz + h.shape[0], k + dx:k + dx + h.shape[1]])
    score = np.where(coastal, best, -1e9)
    # Prefer the spawn continent: weight by closeness to the origin.
    n = h.shape[0]
    g = (np.arange(n) - n // 2) * step
    gz, gx = np.meshgrid(g, g, indexing="ij")
    score = score - np.hypot(gx, gz) / radius * 50
    i = int(np.argmax(score))
    z, x = divmod(i, n)
    return int(g[x]), int(g[z]) - size // 4


# -- workers ------------------------------------------------------------------

def _sim(instance, variant):
    import continental_terrain
    # Bare keys were tectonic.json settings; the terrain doesn't read that file since 0.10.0, and
    # set_config says so (use a shape_pack.py pack= instead).
    sim = continental_terrain.TerrainSim(instance, packs=variant.get("packs", ()))
    sim.set_config(tectonic=variant["tectonic"], continental=variant["continental"])
    return sim


def run_overview(args):
    instance, variant, seed, radius, step = args
    t = time.time()
    sim = _sim(instance, variant)
    n = 2 * (radius // step) + 1
    h = sim.grid(seed, 0, 0, n, step)
    return {"variant": variant["name"], "seed": seed, "heights": h,
            "metrics": overview_metrics(h, step), "secs": round(time.time() - t, 1)}


def run_detail(args):
    instance, variant, seed, at, size = args
    t = time.time()
    sim = _sim(instance, variant)
    n = size // 8
    h = sim.grid(seed, at[0], at[1], n, 8)
    return {"variant": variant["name"], "seed": seed, "at": at, "heights": h,
            "metrics": detail_metrics(h), "secs": round(time.time() - t, 1)}


def patch_centres(h, step, radius, seed, count=40):
    """Random land points of an overview (water connected to the edge excluded), multiples of 8."""
    land = ~ocean_mask(h)
    idx = np.flatnonzero(land)
    if idx.size == 0:
        return []
    rng = np.random.default_rng(seed & 0xFFFFFFFF)
    pick = rng.choice(idx, min(count, idx.size), replace=False)
    n = h.shape[0]
    z, x = np.divmod(pick, n)
    return [(int((xi - n // 2) * step) // 8 * 8, int((zi - n // 2) * step) // 8 * 8) for xi, zi in zip(x, z)]


def run_patches(args):
    """Steepness from 40 random 256-block patches of land at block resolution (every 8 blocks),
    one batched solve. The detail view sits on the steepest coast on purpose, so its numbers
    overstated steepness (blend2 scorecard, 2026-10-23); these are the continent's."""
    instance, variant, seed, centres = args
    sim = _sim(instance, variant)
    g = (np.arange(33) - 16) * 8
    cx = np.array([c[0] for c in centres])[:, None, None]
    cz = np.array([c[1] for c in centres])[:, None, None]
    Z, X = np.meshgrid(g, g, indexing="ij")
    h = sim.heights(seed, X[None] + cx, Z[None] + cz)
    ms = [detail_metrics(p) for p in h]
    out = {}
    for k in ms[0]:
        vals = [m[k] for m in ms if m.get(k) is not None]
        out[k] = round(float(np.mean(vals)), 3) if vals else None
    return {"variant": variant["name"], "seed": seed, "metrics": out}


# -- pictures -----------------------------------------------------------------

_RAMP = [(0, (88, 140, 72)), (60, (120, 160, 84)), (200, (160, 150, 100)),
         (400, (140, 120, 100)), (600, (165, 160, 155)), (800, (245, 245, 248))]


def _colour(above):
    a = np.clip(above, 0, _RAMP[-1][0]).astype(np.float64)
    out = np.zeros(a.shape + (3,))
    for (h0, c0), (h1, c1) in zip(_RAMP, _RAMP[1:]):
        m = (a >= h0) & (a <= h1)
        t = ((a - h0) / (h1 - h0))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    return out


def shaded(h, step):
    """Height colours on a fixed scale (sea level to 800 above), shaded by a north-west sun at
    true slope, so a cliff reads dark at any map scale."""
    from PIL import Image
    hf = h.astype(np.float64)
    gz, gx = np.gradient(hf, step)
    # Sun from the north-west, 35 deg up.
    az, alt = math.radians(315), math.radians(35)
    slope = np.arctan(np.hypot(gx, gz))
    aspect = np.arctan2(-gx, gz)
    shade = np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - aspect)
    shade = np.clip(shade / np.sin(alt), 0.35, 1.25)
    rgb = _colour(hf - SEA) * shade[..., None]
    sea = h <= SEA
    rgb[sea] = (54, 96, 156)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))


def contact_sheet(results, variants, seeds, cell, label_fn, path):
    from PIL import Image, ImageDraw
    pad, head = 6, 18
    W = len(seeds) * (cell + pad) + pad + 120
    H = len(variants) * (cell + head + pad) + pad
    sheet = Image.new("RGB", (W, H), (250, 250, 250))
    d = ImageDraw.Draw(sheet)
    by = {(r["variant"], r["seed"]): r for r in results}
    for vi, v in enumerate(variants):
        y = pad + vi * (cell + head + pad)
        d.text((pad, y + head + cell // 2), v["name"], fill=(0, 0, 0))
        for si, s in enumerate(seeds):
            x = 120 + pad + si * (cell + pad)
            r = by.get((v["name"], s))
            if r is None:
                continue
            sheet.paste(r["image"].resize((cell, cell)), (x, y + head))
            d.text((x, y + 2), label_fn(r), fill=(40, 40, 40))
    sheet.save(path)
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--instance", default=None, help="instance or rig folder (default: Terra Incognita)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--seeds", type=int, default=3, help="random seeds to draw (after any --seed)")
    ap.add_argument("--seed", type=int, action="append", default=[], help="a fixed seed (repeatable); 'rig' seed is %d" % RIG_SEED)
    ap.add_argument("--variant", action="append", default=[], help='"name: key=value ..." (repeatable)')
    ap.add_argument("--radius", type=int, default=12288, help="overview half-width in blocks")
    ap.add_argument("--step", type=int, default=96, help="overview sample spacing (a multiple of 4)")
    ap.add_argument("--size", type=int, default=1536, help="detail square side in blocks")
    ap.add_argument("--at", default="coast", help="detail centre: x,z or 'coast' (steepest spawn-continent coast)")
    ap.add_argument("--no-detail", action="store_true")
    # 8 by default: each worker holds 0.5-1 GB, and 16 at once starved the machine until the
    # desktop app froze (2026-10-23).
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count()))
    ap.add_argument("--rng", type=int, default=None, help="seed for drawing --seeds (repeatable runs)")
    a = ap.parse_args()

    import continental_climate
    instance = a.instance or continental_climate.INSTANCE
    variants = [parse_variant(v) for v in (a.variant or ["now:"])]
    rng = random.Random(a.rng)
    seeds = list(a.seed) + [rng.randrange(-2 ** 63, 2 ** 63) for _ in range(a.seeds)]
    os.makedirs(a.out, exist_ok=True)
    if a.step % 4:
        raise SystemExit("--step must be a multiple of 4 (cell corners), or every sample interpolates")

    t0 = time.time()
    ctx = mp.get_context("spawn")
    with ctx.Pool(min(a.jobs, len(variants) * len(seeds))) as pool:
        jobs = [(instance, v, s, a.radius, a.step) for v in variants for s in seeds]
        overview = pool.map(run_overview, jobs)
        print(f"overviews: {len(overview)} in {time.time() - t0:.0f} s", flush=True)
        for r in overview:
            r["image"] = shaded(r["heights"], a.step)
        contact_sheet(overview, variants, seeds, 320,
                      lambda r: f"{r['seed']}  land {r['metrics']['land']:.0%}  max +{r['metrics'].get('max', 0)}",
                      os.path.join(a.out, "overview.png"))

        t1 = time.time()
        firsts = {r["seed"]: r for r in overview if r["variant"] == variants[0]["name"]}
        pcentres = {s: patch_centres(firsts[s]["heights"], a.step, a.radius, s) for s in seeds}
        patches = pool.map(run_patches, [(instance, v, s, pcentres[s]) for v in variants for s in seeds])
        print(f"steepness patches: {len(patches)} in {time.time() - t1:.0f} s", flush=True)

        detail = []
        if not a.no_detail:
            centres = {}
            for r in overview:
                if a.at == "coast":
                    # One place per seed (from the first variant) so variants compare like for like.
                    if r["seed"] not in centres:
                        centres[r["seed"]] = steepest_coast(r["heights"], a.step, a.radius, a.size)
                else:
                    centres[r["seed"]] = tuple(int(v) for v in a.at.split(","))
            jobs = [(instance, v, s, centres[s], a.size) for v in variants for s in seeds]
            t1 = time.time()
            detail = pool.map(run_detail, jobs)
            print(f"details: {len(detail)} in {time.time() - t1:.0f} s", flush=True)

    # 3-D views through terrain_3d.py (same renderer as the rig sweeps), via audit-shaped files.
    if detail:
        files = []
        for r in detail:
            name = f"{r['variant']}@{r['seed'] % 100000:05d}"
            p = os.path.join(a.out, f"detail_{r['variant']}_{r['seed']}.json")
            json.dump({"terrain": {"grid": {"center": f"{r['at'][0]},{r['at'][1]}", "step": 8,
                                            "seaLevel": SEA, "heights": r["heights"].tolist()}}},
                      open(p, "w", encoding="utf-8"))
            files.append(f"{name}={p}")
        tool = os.path.join(HERE, "..", "..", "tools", "python", "terrain_3d.py")
        subprocess.run([sys.executable, tool, os.path.join(a.out, "detail.png")] + files, check=True)

    # Numbers: per run, then per variant averaged over seeds.
    runs = []
    for r in overview:
        m = dict(r["metrics"])
        d = next((x for x in detail if x["variant"] == r["variant"] and x["seed"] == r["seed"]), None)
        if d:
            m.update({"coastview_" + k: v for k, v in d["metrics"].items()})
            m["detail_at"] = d["at"]
        pt = next(x for x in patches if x["variant"] == r["variant"] and x["seed"] == r["seed"])
        m.update(pt["metrics"])
        runs.append({"variant": r["variant"], "seed": r["seed"], **m})
    json.dump({"instance": instance, "variants": variants, "seeds": seeds, "radius": a.radius,
               "step": a.step, "runs": runs}, open(os.path.join(a.out, "metrics.json"), "w"), indent=1)

    cols = ["land", "mountain", "p90", "max", "over900", "wall", "to100_p10", "to100_p50",
            "steep45", "steep45_low", "steep45_mid", "steep45_high", "gentle", "needles"]
    print(f"\n{'variant':<14}" + "".join(f"{c:>9}" for c in cols) + "   coast (height above sea by distance inland: "
          + ", ".join(f"{lo}-{hi}" for lo, hi in COAST_BANDS) + ")")
    for v in variants:
        rs = [r for r in runs if r["variant"] == v["name"]]
        row = f"{v['name']:<14}"
        for c in cols:
            vals = [r[c] for r in rs if r.get(c) is not None]
            row += f"{np.mean(vals):>10.3f}" if vals and isinstance(vals[0], float) else (
                f"{np.mean(vals):>10.0f}" if vals else f"{'-':>10}")
        bands = []
        for i in range(len(COAST_BANDS)):
            vals = [r["coast"][i] for r in rs if r["coast"][i] is not None]
            bands.append(f"{np.mean(vals):.0f}" if vals else "-")
        print(row + "   " + " / ".join(bands))
    print(f"\nwrote {os.path.abspath(a.out)} in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
