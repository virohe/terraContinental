"""
Planned drainage on a coarse heightfield: would routed rivers fit Terra Incognita's terrain?
(Investigation for RIVERS.md; no mod code depends on it.)

    python preview/rivers.py --seed 4151832666630476678 --pack preview/out/packs/blend2_norivers

1. Heights of the spawn continent at --step blocks per cell (TerrainSim, process pool, cached
   as <out>/<seed>_heights.npy so re-runs only redo the analysis).
2. Ocean = sea-level cells connected to the grid edge. The continent = the land component
   holding the origin; the grid edge counts as an outlet if land reaches it.
3. Priority-flood depression filling (+epsilon, Barnes et al. 2014) from the continent's coast:
   every inland cell gets a path downhill to the sea, and filled-minus-ground is lake depth.
4. D8 flow on the filled surface, flow accumulation in descending-height order (uniform rain).
5. River cells: drainage area >= --trib km^2. A basin is a major river when its outlet drains
   >= --major km^2. Along each river cell, the ground drop to its receiver marks rapids
   (>= --rapids blocks per cell) and falls (>= --falls).
6. Maps, cropped to the continent: <seed>_rivers.png (network over hill shade, majors coloured per basin, lakes, rapids,
   falls) and <seed>_tectonic.png (Tectonic's own river lines, |ridges_folded| < 0.03 on land,
   for comparison), plus <seed>_stats.json.

Jobs default to 8: 16 workers froze the user's machine (2026-10-23).
"""

import argparse
import heapq
import json
import multiprocessing as mp
import os
import sys
import time
from collections import deque

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SEA = 63
RIDGES = "terra_continental:noise/continent/ridges_folded"
# D8 neighbours: (dz, dx), distance in cells.
NB = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
DIST = np.array([1, 1, 1, 1, 2 ** 0.5, 2 ** 0.5, 2 ** 0.5, 2 ** 0.5])


def _band(args):
    import continental_terrain
    pack, seed, xs, zs, ridges = args
    sim = continental_terrain.TerrainSim(packs=[pack] if pack else ())
    Z, X = np.meshgrid(zs, xs, indexing="ij")
    if ridges:
        import mc_dfi
        ctx = mc_dfi.Context(sim.reg, seed, X.astype(np.float64), Z.astype(np.float64), custom=sim._custom)
        return np.asarray(mc_dfi.evaluate(sim.reg.df(RIDGES), ctx), dtype=np.float32)
    return sim.heights(seed, X, Z)


def sample(pack, seed, xs, zs, jobs, ridges=False):
    bands = [b for b in np.array_split(zs, jobs * 4) if b.size]
    with mp.get_context("spawn").Pool(jobs) as pool:
        return np.concatenate(pool.map(_band, [(pack, seed, xs, b, ridges) for b in bands]), axis=0)


def flood(mask, start):
    """4-connected component of `mask` holding the cells in `start` (list of flat indices)."""
    n, m = mask.shape
    seen = np.zeros(mask.size, dtype=bool)
    flat = mask.ravel()
    q = deque(i for i in start if flat[i])
    for i in q:
        seen[i] = True
    while q:
        i = q.popleft()
        z, x = divmod(i, m)
        for dz, dx in NB[:4]:
            zz, xx = z + dz, x + dx
            if 0 <= zz < n and 0 <= xx < m:
                j = zz * m + xx
                if flat[j] and not seen[j]:
                    seen[j] = True
                    q.append(j)
    return seen.reshape(mask.shape)


def priority_flood(h, land, outlet, lake_depth=None, eps=1e-3):
    """Fill depressions in h over `land`, draining to `outlet` cells (ocean next to land and,
    where the continent reaches it, the grid edge). Returns the water/ground surface the rivers
    follow (outlets keep h), and each cell's flood parent (the neighbour it was reached from).

    lake_depth=None fills every depression to its spill point (a lake as deep as the basin).
    A number fills at most that deep and breaches the rest: the cell's spill path (the flood's
    parent chain back to the sea) is carved down so it falls monotonically, a gorge through
    the rim (Lindsay 2016's breaching, limited the way a hybrid fill-and-breach is)."""
    n, m = h.shape
    surf = np.where(land, np.inf, h).astype(np.float64)
    done = ~land
    parent = np.full(h.size, -1, dtype=np.int64)
    heap = []
    for i in np.flatnonzero(outlet):
        heapq.heappush(heap, (float(h.flat[i]), int(i)))
    hf, sf, df = h.ravel().astype(np.float64), surf.ravel(), done.ravel()
    while heap:
        z0, i = heapq.heappop(heap)
        if z0 > sf[i]:
            z0 = sf[i]          # carved since it was pushed
        r, c = divmod(i, m)
        for dz, dx in NB:
            rr, cc = r + dz, c + dx
            if 0 <= rr < n and 0 <= cc < m:
                j = rr * m + cc
                if df[j]:
                    continue
                df[j] = True
                parent[j] = i
                if lake_depth is None or hf[j] >= z0 + eps - lake_depth:
                    sf[j] = max(hf[j], z0 + eps)
                else:
                    sf[j] = z0 + eps - lake_depth
                    level, k = sf[j], i
                    while k >= 0 and land.flat[k] and sf[k] >= level:
                        level -= eps
                        sf[k] = level
                        k = parent[k]
                heapq.heappush(heap, (sf[j], j))
    return surf, parent


def d8(filled, land):
    """Receiver (flat index) of each land cell: steepest descent on the filled surface; -1
    off land, or where no neighbour is lower (see analyse)."""
    n, m = filled.shape
    pad = np.pad(filled, 1, constant_values=np.inf)
    best = np.zeros((n, m))
    rec = np.full((n, m), -1, dtype=np.int64)
    rows, cols = np.mgrid[0:n, 0:m]
    for k, (dz, dx) in enumerate(NB):
        nb = pad[1 + dz:1 + dz + n, 1 + dx:1 + dx + m]
        s = (filled - nb) / DIST[k]
        better = (s > best) & land
        best[better] = s[better]
        rec[better] = ((rows + dz) * m + cols + dx)[better]
    return rec


def accumulate(filled, land, rec):
    order = np.flatnonzero(land.ravel())
    order = order[np.argsort(-filled.ravel()[order], kind="stable")]
    acc = np.where(land.ravel(), 1.0, 0.0)
    r = rec.ravel()
    for i in order:
        j = r[i]
        if j >= 0:
            acc[j] += acc[i]
    return acc.reshape(land.shape)


def basins(land, rec, order_hint):
    """Outlet (flat index, a non-land receiver or self) of every land cell."""
    r = rec.ravel()
    out = np.full(r.size, -1, dtype=np.int64)
    lf = land.ravel()
    for i in order_hint[::-1]:          # downstream first: a receiver is always lower
        j = r[i]
        out[i] = j if not lf[j] else out[j]
    return out.reshape(land.shape)


def strahler(land, rec, order):
    r = rec.ravel()
    s = np.zeros(r.size, dtype=np.int32)
    top = np.zeros(r.size, dtype=np.int32)      # highest order among inflows
    cnt = np.zeros(r.size, dtype=np.int32)      # how many inflows have that order
    for i in order:                              # upstream first
        s[i] = 1 if top[i] == 0 else (top[i] + 1 if cnt[i] >= 2 else top[i])
        j = r[i]
        if j >= 0 and land.flat[j]:
            if s[i] > top[j]:
                top[j], cnt[j] = s[i], 1
            elif s[i] == top[j]:
                cnt[j] += 1
    return s.reshape(land.shape)


def analyse(h, step, a):
    n, m = h.shape
    sea = h <= SEA
    edge = [i for i in range(h.size) if (i // m) in (0, n - 1) or (i % m) in (0, m - 1)]
    ocean = flood(sea, edge)
    notocean = ~ocean
    centre = (n // 2) * m + m // 2
    land = flood(notocean, [centre])
    if not land.any():
        raise SystemExit("origin is in the ocean")
    pad = np.pad(land, 1)
    near_land = np.zeros_like(land)
    for dz, dx in NB:
        near_land |= pad[1 + dz:1 + dz + n, 1 + dx:1 + dx + m]
    border = np.zeros_like(land)
    border[[0, -1], :] = True
    border[:, [0, -1]] = True
    outlet = (ocean & near_land)
    edge_land = land & border
    # Land on the grid edge drains off the map (the continent is cut there): outlet too.
    outlet |= edge_land
    land_in = land & ~edge_land

    filled, parent = priority_flood(h.astype(np.float64), land_in, outlet, a.lake_depth)
    rec = d8(filled, land_in)
    # A breach path carved to just below the sea can leave its last cell lower than the ocean
    # beside it; that cell drains to the cell the flood reached it from.
    stuck = land_in & (rec < 0)
    rec[stuck] = parent.reshape(rec.shape)[stuck]
    acc = accumulate(filled, land_in, rec)
    order = np.flatnonzero(land_in.ravel())
    order = order[np.argsort(-filled.ravel()[order], kind="stable")]
    bas = basins(land_in, rec, order)
    order_s = strahler(land_in, rec, order)

    km2 = (step / 1000.0) ** 2
    area = acc * km2
    river = land_in & (area >= a.trib)
    # Basins by outlet; size = accumulation flowing into the outlet.
    outs, inv = np.unique(bas[land_in], return_inverse=True)
    sizes = np.bincount(inv) * km2
    rank = np.argsort(-sizes)
    major_out = [int(outs[k]) for k in rank if sizes[k] >= a.major]
    lake = land_in & (filled - h > 1.0)
    gorge = land_in & (h - filled > 1.0)
    cut = np.where(gorge, h - filled, 0.0)
    # Lakes as connected water bodies: count, total area, largest.
    lake_ids = label(lake)
    lake_sizes = np.bincount(lake_ids[lake_ids > 0]) * km2 if lake.any() else np.zeros(1)
    drop = np.zeros_like(h, dtype=np.float64)
    rr = rec.ravel()
    ok = river.ravel() & (rr >= 0)
    dflat = drop.ravel()
    # The water surface: the filled/carved surface, sea level at the mouth.
    sfl = np.maximum(filled.ravel(), SEA)
    dflat[ok] = sfl[ok] - sfl[rr[ok]]
    drop = dflat.reshape(h.shape)
    rapids = river & (drop >= a.rapids) & (drop < a.falls)
    falls = river & (drop >= a.falls)

    land_area = land_in.sum() * km2
    majors = []
    for o in major_out:
        sel = land_in & (bas == o)
        trunk = trace_trunk(o, rec, acc, land_in, river)
        hs = [int(h.flat[i]) for i in trunk]
        # Boat reach: from the mouth while the river surface stays within 8 blocks of the sea,
        # i.e. a channel cut to sea level is at most 8 deep (no steps for a boat to climb).
        reach = next((k for k, i in enumerate(trunk) if filled.flat[i] > SEA + 8), len(trunk))
        majors.append({
            "basin_km2": round(float(sel.sum() * km2), 1),
            "length_km": round(len(trunk) * step / 1000.0 * 1.15, 1),     # ~D8 diagonal share
            "source_y": max(hs) if hs else SEA,
            "sea_level_reach_km": round(reach * step / 1000.0 * 1.15, 1),
            "max_strahler": int(order_s[sel].max()),
            "rapids_cells": int((rapids & sel).sum()), "falls_cells": int((falls & sel).sum()),
            "outlet_xz": [int((o % m - m // 2) * step), int((o // m - n // 2) * step)],
            "trunk": trunk,
        })
    stats = {
        "step": step, "grid": [n, m], "continent_km2": round(float(land_area), 1),
        "land_on_grid_edge": int(edge_land.sum()),
        "trib_km2": a.trib, "major_km2": a.major,
        "river_cells": int(river.sum()), "river_km": round(float(river.sum() * step / 1000 * 1.1), 1),
        "basins_over_major": len(majors),
        "basin_sizes_top10_km2": [round(float(sizes[k]), 1) for k in rank[:10]],
        "lakes": int(lake_ids.max()), "lake_km2": round(float(lake.sum() * km2), 1),
        "largest_lakes_km2": [round(float(x), 2) for x in sorted(lake_sizes, reverse=True)[:5]],
        "lake_depth_p50_p90_max": [round(float(np.percentile((filled - h)[lake], q)), 1)
                                   for q in (50, 90)] + [round(float((filled - h)[lake].max()), 1)]
        if lake.any() else [0, 0, 0],
        "rapids_cells": int(rapids.sum()), "falls_cells": int(falls.sum()),
        "lake_depth_cap": a.lake_depth,
        "gorge_cells": int(gorge.sum()), "gorge_km2": round(float(gorge.sum() * km2), 2),
        "gorge_depth_p50_p90_max": [round(float(np.percentile(cut[gorge], q)), 1) for q in (50, 90)]
        + [round(float(cut.max()), 1)] if gorge.any() else [0, 0, 0],
        "river_cells_over_100": int((river & (h > SEA + 100)).sum()),
        "majors": [{k: v for k, v in mj.items() if k != "trunk"} for mj in majors],
    }
    return dict(h=h, ocean=ocean, land=land_in, filled=filled, gorge=gorge, rec=rec, acc=acc, area=area,
                bas=bas, river=river, lake=lake, rapids=rapids, falls=falls, strahler=order_s,
                majors=majors, stats=stats)


def trace_trunk(outlet, rec, acc, land, river):
    """The main stem of a basin: from the coast cell draining into `outlet`, always up the
    biggest inflow. Returns flat indices from mouth to source."""
    m = rec.shape[1]
    r = rec.ravel()
    a = acc.ravel()
    inflow = {}
    for i in np.flatnonzero(land.ravel() & river.ravel()):
        inflow.setdefault(int(r[i]), []).append(int(i))
    starts = inflow.get(int(outlet), [])
    if not starts:
        return []
    cur = max(starts, key=lambda i: a[i])
    path = [cur]
    while cur in inflow:
        cur = max(inflow[cur], key=lambda i: a[i])
        path.append(cur)
    return path


def label(mask):
    """8-connected labels (no scipy here)."""
    n, m = mask.shape
    lab = np.zeros(mask.size, dtype=np.int32)
    flat = mask.ravel()
    k = 0
    for s in np.flatnonzero(flat):
        if lab[s]:
            continue
        k += 1
        lab[s] = k
        q = deque([s])
        while q:
            i = q.popleft()
            z, x = divmod(int(i), m)
            for dz, dx in NB:
                zz, xx = z + dz, x + dx
                if 0 <= zz < n and 0 <= xx < m:
                    j = zz * m + xx
                    if flat[j] and not lab[j]:
                        lab[j] = k
                        q.append(j)
    return lab.reshape(mask.shape)


MAJOR_COLOURS = [(200, 30, 40), (230, 120, 0), (130, 40, 170), (0, 120, 60), (200, 0, 150),
                 (0, 110, 190), (120, 80, 20), (90, 90, 90)]


def crop_box(land, scale, margin=12):
    zs, xs = np.nonzero(land)
    n, m = land.shape
    return (max(xs.min() - margin, 0) * scale, max(zs.min() - margin, 0) * scale,
            min(xs.max() + margin + 1, m) * scale, min(zs.max() + margin + 1, n) * scale)


def render(res, step, scale, title, path):
    import terrain_preview as tp
    from PIL import Image, ImageDraw
    h = res["h"]
    n, m = h.shape
    base = tp.shaded(h, step).convert("RGB")
    rgb = np.asarray(base).astype(np.float64)
    off = ~res["land"] & ~res["ocean"]
    rgb[off & (h > SEA)] = rgb[off & (h > SEA)] * 0.55 + 255 * 0.45       # other land, faded
    rgb[res["lake"]] = (70, 140, 210)
    rgb[res["gorge"]] = rgb[res["gorge"]] * 0.4 + np.array([220, 40, 40]) * 0.6
    img = Image.fromarray(rgb.astype(np.uint8)).resize((m * scale, n * scale), Image.NEAREST)
    d = ImageDraw.Draw(img)
    area = res["area"].ravel()
    rec = res["rec"].ravel()
    major_of = {}
    for k, mj in enumerate(res["majors"]):
        major_of[k] = MAJOR_COLOURS[k % len(MAJOR_COLOURS)]
    bas_major = {}
    for k, mj in enumerate(res["majors"]):
        o = mj["outlet_xz"]
        bas_major[(o[1] // step + n // 2) * m + o[0] // step + m // 2] = major_of[k]
    bas = res["bas"].ravel()
    amax = max(area.max(), 1e-9)

    def xy(i):
        z, x = divmod(int(i), m)
        return (x * scale + scale // 2, z * scale + scale // 2)

    for i in np.flatnonzero(res["river"].ravel()):
        j = rec[i]
        if j < 0:
            continue
        w = 1 + int(round(3.5 * np.sqrt(area[i] / amax) * scale / 2))
        col = bas_major.get(int(bas[i]), (25, 60, 160))
        d.line([xy(i), xy(j)], fill=col, width=w)
    for mask, col, rad in ((res["rapids"], (255, 255, 255), 1), (res["falls"], (255, 230, 0), 3)):
        for i in np.flatnonzero(mask.ravel()):
            x, y = xy(i)
            d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=col, outline=(0, 0, 0))
    img = img.crop(crop_box(res["land"], scale))
    pad = 30
    out = Image.new("RGB", (img.width, img.height + 3 * pad), (250, 250, 250))
    out.paste(img, (0, pad))
    dd = ImageDraw.Draw(out)
    dd.text((6, 8), title, fill=(0, 0, 0))
    px = int(round(5000 / step * scale))
    y = img.height + pad + 12
    dd.rectangle([6, y, 6 + px, y + 4], fill=(0, 0, 0))
    dd.text((10 + px, y - 4), "5 km", fill=(0, 0, 0))
    dd.text((6, y + 12), "majors coloured per basin; blue = tributaries; light blue = lakes",
            fill=(0, 0, 0))
    dd.text((6, y + 26), "red tint = carved gorge; white dots = rapids; yellow = falls", fill=(0, 0, 0))
    out.save(path)


def render_tectonic(h, ridges, land, step, scale, title, path):
    import terrain_preview as tp
    from PIL import Image, ImageDraw
    n, m = h.shape
    rgb = np.asarray(tp.shaded(h, step).convert("RGB")).astype(np.float64)
    line = land & (np.abs(ridges) < 0.03)
    rgb[line] = (20, 50, 200)
    img = Image.fromarray(rgb.astype(np.uint8)).resize((m * scale, n * scale), Image.NEAREST)
    img = img.crop(crop_box(land, scale))
    out = Image.new("RGB", (img.width, img.height + 30), (250, 250, 250))
    out.paste(img, (0, 30))
    ImageDraw.Draw(out).text((6, 8), title, fill=(0, 0, 0))
    out.save(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--pack", required=True)
    ap.add_argument("--out", default=os.path.join(HERE, "out", "rivers"))
    ap.add_argument("--radius", type=int, default=20480)
    ap.add_argument("--step", type=int, default=64)
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count()))
    ap.add_argument("--trib", type=float, default=1.0, help="km^2 drained to count as a river")
    ap.add_argument("--major", type=float, default=8.0, help="km^2 basin for a major river")
    ap.add_argument("--rapids", type=float, default=4.0, help="blocks dropped per cell")
    ap.add_argument("--falls", type=float, default=20.0, help="blocks dropped per cell")
    ap.add_argument("--lake-depth", type=float, default=12.0,
                    help="deepest lake kept; deeper basins are breached (a negative value fills all)")
    ap.add_argument("--tag", default="", help="suffix for the output names")
    ap.add_argument("--scale", type=int, default=3, help="map pixels per cell")
    a = ap.parse_args()
    a.jobs = min(a.jobs, 8)
    os.makedirs(a.out, exist_ok=True)
    nc = 2 * (a.radius // a.step) + 1
    xs = (np.arange(nc) - nc // 2) * a.step
    zs = xs.copy()
    hp = os.path.join(a.out, f"{a.seed}_heights.npy")
    rp = os.path.join(a.out, f"{a.seed}_ridges.npy")
    t = time.time()
    h = np.load(hp) if os.path.exists(hp) else None
    if h is None or h.shape != (nc, nc):        # cached at another radius/step
        h = sample(os.path.abspath(a.pack), a.seed, xs, zs, a.jobs)
        np.save(hp, h)
        print(f"heights {nc}x{nc} in {time.time() - t:.0f} s", flush=True)
    ridges = np.load(rp) if os.path.exists(rp) else None
    if ridges is None or ridges.shape != (nc, nc):
        ridges = sample(os.path.abspath(a.pack), a.seed, xs, zs, a.jobs, ridges=True)
        np.save(rp, ridges)
    t = time.time()
    if a.lake_depth is not None and a.lake_depth < 0:
        a.lake_depth = None
    res = analyse(h, a.step, a)
    print(f"analysis in {time.time() - t:.0f} s", flush=True)
    st = res["stats"]
    title = (f"seed {a.seed}  1 cell = {a.step} blocks  "
             f"continent {st['continent_km2']:.0f} km2  rivers >= {a.trib} km2, majors >= {a.major} km2: "
             f"{st['basins_over_major']}")
    render(res, a.step, a.scale, title, os.path.join(a.out, f"{a.seed}_rivers{a.tag}.png"))
    render_tectonic(h, ridges, res["land"], a.step, a.scale,
                    f"seed {a.seed}: Tectonic's own river lines (|ridges_folded| < 0.03 on land), "
                    "same terrain with them flattened", os.path.join(a.out, f"{a.seed}_tectonic.png"))
    with open(os.path.join(a.out, f"{a.seed}_stats{a.tag}.json"), "w", encoding="utf-8") as fh:
        json.dump(st, fh, indent=1)
    print(json.dumps(st, indent=1))


if __name__ == "__main__":
    main()
