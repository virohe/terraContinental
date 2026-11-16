"""
Spawn-continent steering, measured over many seeds (steer_core.py is the implementation).

    python preview/spawn_steer.py --out preview/out/steer --seeds 40
    python preview/spawn_steer.py --out ... --seedlist 1,2,3 [--candidates 48]

For each seed: the steered layout's spawn landmass (km2), roundness (a disc is 1.0), coast wiggle
against the neighbours' (reported, not scored: every layout passed it, 2026-11-10) and the
nearest neighbour's gap (km), against the targets in terra_continental-common.toml
[continent_shaping] spawn_*; hit rates; the search's time; and a sheet of the maps (gold: spawn).
The steering was chosen over one-layout rules on 40 seeds, 2026-11-10: all targets on 82% of seeds
against 18% (rules) and 5% (0.13.0's fixed lobes).
"""

import argparse
import json
import math
import os
import sys
import time

import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import continental_climate as cc
import mc_climate
import mc_dfi
import steer_core as sc


def wiggle(mask):
    """Coastline length over a ~400-block-blurred copy's: bays and headlands, not overall shape."""
    def perim(m):
        return (m & ~ndimage.binary_erosion(m)).sum()
    return perim(mask) / max(perim(ndimage.gaussian_filter(mask.astype(float), 3.0) > 0.5), 1)


def report(v, p):
    m = sc.measure(v, p)
    land = v > sc.COAST
    lab, nl = ndimage.label(land)
    c = sc.N // 2
    sp = lab[c, c] if land[c, c] else None
    counts = np.bincount(lab.ravel(), minlength=nl + 1)
    if sp:
        big = [i for i in range(1, nl + 1) if i != sp and counts[i] * sc.PX >= sc.BIG]
        m["coast"] = round(wiggle(lab == sp), 2)
        m["ncoast"] = round(float(np.median([wiggle(lab == i) for i in big])), 2) if big else None
    hits = dict(size=abs(math.log(max(m["area"], 1) / p.spawn_target_area)) <= math.log(1.15),
                shape=0.55 <= m["roundness"] <= 0.82,
                neighbours=m["gap"] is not None and p.spawn_gap_min <= m["gap"] <= p.spawn_gap_max,
                open_sea=m["others"] <= p.spawn_max_other_land)
    hits["all"] = all(hits.values())
    return m, hits


def tile(v, size=200):
    from PIL import Image
    img = np.zeros(v.shape + (3,), np.uint8)
    img[:] = (40, 70, 140)
    img[v > -0.19] = (70, 110, 180)
    land = v > sc.COAST
    img[land] = (90, 160, 70)
    lab, _ = ndimage.label(land)
    c = v.shape[0] // 2
    if land[c, c]:
        img[lab == lab[c, c]] = (200, 170, 80)
    return Image.fromarray(img).resize((size, size), Image.NEAREST)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seeds", type=int, default=40)
    ap.add_argument("--seedlist", default="")
    ap.add_argument("--candidates", type=int, default=None)
    ap.add_argument("--instance", default=None, help="read this instance's terra_continental-common.toml")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    sim = cc.ClimateSim(instance=a.instance or os.path.join(os.environ["TMP"], "emptyinst"))
    p = sim.radial
    seeds = [int(x) for x in a.seedlist.split(",") if x] or \
        [int(x) for x in np.random.default_rng(20261110).integers(-2**62, 2**62, a.seeds)]
    node = sim.reg.df("terra_continental:continent_base")["argument"]["argument1"]
    xs = -sc.R + np.arange(sc.N) * float(sc.STEP)
    X, Z = np.meshgrid(xs, xs)
    rows, tiles = [], []
    for seed in seeds:
        ctx = mc_dfi.Context(sim.reg, seed, X, Z, custom=sim._custom)
        up = mc_dfi.evaluate(node["wrapped"], ctx)
        noise = ctx.noise("terra_continental:continent_jitter")
        jitter = sim._jitter_table(ctx, "terra_continental:continent_jitter")
        t = time.time()

        def upstream(XX, ZZ):
            return mc_dfi.evaluate(node["wrapped"], mc_dfi.Context(sim.reg, seed, XX, ZZ, custom=sim._custom))

        lay, _ = sc.steer(upstream, noise, mc_climate.continent_centers(jitter, p), p, a.candidates)
        ms = round((time.time() - t) * 1000)
        ox, oz = lay["offset"]
        up = up if not (ox or oz) else upstream(X + ox, Z + oz)
        grid = sc.Grid(up, noise)
        wx, wz = grid.warp(lay["wavelength"])
        v = sc.continent_base(lay, up, X, Z, p, wx, wz)
        m, hits = report(v, p)
        rows.append(dict(seed=seed, index=lay["index"], round=lay["round"], score=round(lay["score"], 3), ms=ms,
                         m=m, hits=hits))
        tiles.append((seed, tile(v), m, hits))
        print(seed, f"round {lay['round']} #{lay['index']}", round(lay["score"], 3),
              {k: (round(v, 2) if isinstance(v, float) else v) for k, v in m.items()}, hits["all"], f"{ms} ms",
              flush=True)
    json.dump(rows, open(os.path.join(a.out, "results.json"), "w"), indent=1)
    summary = {k: round(100 * np.mean([r["hits"][k] for r in rows])) for k in ("size", "shape", "neighbours", "open_sea", "all")}
    summary["rounds_mean"] = round(float(np.mean([r["round"] + 1 for r in rows])), 2)
    summary["ms_per_seed"] = round(np.mean([r["ms"] for r in rows]))
    json.dump(summary, open(os.path.join(a.out, "summary.json"), "w"), indent=1)
    print(json.dumps(summary))

    from PIL import Image, ImageDraw
    cols = 6
    W, H = cols * 206, -(-len(tiles) // cols) * 250
    im = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(im)
    for i, (seed, tl, m, h) in enumerate(tiles):
        x, y = (i % cols) * 206, (i // cols) * 250
        im.paste(tl, (x, y))
        col = (0, 120, 0) if h["all"] else (170, 0, 0)
        d.text((x, y + 202), str(seed), fill=(0, 0, 0))
        d.text((x, y + 214), f"{m['area']:.0f} km2  round {m['roundness']:.2f}", fill=col)
        d.text((x, y + 226), f"gap {m['gap'] if m['gap'] is None else round(m['gap'], 1)}  "
                             f"other {m['others']:.0f}%", fill=col)
    im.save(os.path.join(a.out, "sheet.png"))


if __name__ == "__main__":
    main()
