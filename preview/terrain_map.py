"""
One high-resolution hill-shaded map per variant, for looking at closely (terrain_preview.py's
sheets are thumbnails: one sample per 96 blocks).

    python preview/terrain_map.py --out preview/out/maps --seed 4151832666630476678 \
        --radius 8192 --step 16 --variant "now:" --variant "all: pack=preview/out/packs/all"

Variants and the instance work as in terrain_preview.py. The grid is cut into row bands that run
in a process pool. Writes <name>.png per variant (one pixel per sample, north up, with a
1 km scale bar and the height scale) and <name>.npy (heights) to --out. At --step 16 and
--radius 8192 that is 1025 x 1025 pixels, about 2-4 minutes on 16 cores.
"""

import argparse
import multiprocessing as mp
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import terrain_preview as tp


def run_band(args):
    instance, variant, seed, xs, zs = args
    sim = tp._sim(instance, variant)
    Z, X = np.meshgrid(zs, xs, indexing="ij")
    return sim.heights(seed, X, Z)


def annotate(img, step, title):
    from PIL import Image, ImageDraw
    pad = 28
    out = Image.new("RGB", (img.width, img.height + pad * 2), (250, 250, 250))
    out.paste(img, (0, pad))
    d = ImageDraw.Draw(out)
    d.text((6, 8), title, fill=(0, 0, 0))
    # 1 km scale bar
    px = int(round(1000 / step))
    y = img.height + pad + 12
    d.rectangle([6, y, 6 + px, y + 4], fill=(0, 0, 0))
    d.text((10 + px, y - 4), "1 km", fill=(0, 0, 0))
    # height scale (the fixed colour ramp, sea level to +800)
    ramp = np.linspace(0, tp._RAMP[-1][0], 200)
    sw = tp._colour(ramp[None, :]).astype(np.uint8)
    strip = Image.fromarray(np.repeat(sw, 8, axis=0))
    x0 = 90 + px
    out.paste(strip, (x0, y - 2))
    d.text((x0 + 205, y - 4), "sea .. +800", fill=(0, 0, 0))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--instance", default=None)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--variant", action="append", default=[])
    ap.add_argument("--radius", type=int, default=8192)
    ap.add_argument("--step", type=int, default=16, help="a multiple of 4")
    ap.add_argument("--center", default="0,0")
    # 8 by default: each worker holds 0.5-1 GB, and 16 at once starved the machine until the
    # desktop app froze (2026-10-23).
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count()))
    ap.add_argument("--closeups", action="store_true",
                    help="also 3D views (terrain_3d.py) of each variant's highest peak and of the "
                         "steepest south-facing coast: peak_3d.png, coast_3d.png")
    a = ap.parse_args()
    if a.step % 4:
        raise SystemExit("--step must be a multiple of 4")

    import continental_climate
    instance = a.instance or continental_climate.INSTANCE
    variants = [tp.parse_variant(v) for v in (a.variant or ["now:"])]
    cx, cz = (int(v) for v in a.center.split(","))
    n = 2 * (a.radius // a.step) + 1
    xs = cx + (np.arange(n) - n // 2) * a.step
    zs = cz + (np.arange(n) - n // 2) * a.step
    bands = np.array_split(zs, max(1, a.jobs * 3))
    os.makedirs(a.out, exist_ok=True)
    maps = {}
    with mp.get_context("spawn").Pool(a.jobs) as pool:
        for v in variants:
            t = time.time()
            parts = pool.map(run_band, [(instance, v, a.seed, xs, b) for b in bands if b.size])
            h = np.concatenate(parts, axis=0)
            maps[v["name"]] = h
            np.save(os.path.join(a.out, v["name"] + ".npy"), h)
            img = tp.shaded(h, a.step)
            title = (f"{v['name']}   seed {a.seed}   {2 * a.radius // 1000} km across, "
                     f"1 px = {a.step} blocks   highest +{int(h.max()) - tp.SEA}")
            annotate(img, a.step, title).save(os.path.join(a.out, v["name"] + ".png"))
            print(f"{v['name']}: {n}x{n} in {time.time() - t:.0f} s", flush=True)
        if a.closeups:
            closeups(a, instance, variants, maps, xs, zs, pool)


def closeups(a, instance, variants, maps, xs, zs, pool):
    """terrain_3d.py looks north from a camera 220 blocks south of the grid. The peak view puts
    each variant's highest point a quarter of the way in from the far edge, camera 450 up; the
    coast view frames the first variant's steepest south-facing coast for every variant."""
    import json
    import subprocess
    tool = os.path.join(HERE, "..", "..", "tools", "python", "terrain_3d.py")
    first = maps[variants[0]["name"]]
    coast = tp.steepest_coast(first, a.step, a.radius, 1536)
    coast = (int(coast[0]) + int(xs[len(xs) // 2]), int(coast[1]) + int(zs[len(zs) // 2]))
    for view, size, step, height in (("peak", 4096, 16, 450), ("coast", 1536, 8, 260)):
        files = []
        for v in variants:
            h = maps[v["name"]]
            if view == "peak":
                iz, ix = np.unravel_index(int(np.argmax(h)), h.shape)
                cx, cz = int(xs[ix]), int(zs[iz]) + size // 4
            else:
                cx, cz = coast
            m = size // step
            gx = cx + (np.arange(m) - m // 2) * step
            gz = cz + (np.arange(m) - m // 2) * step
            parts = pool.map(run_band, [(instance, v, a.seed, gx, b)
                                        for b in np.array_split(gz, a.jobs * 2) if b.size])
            g = np.concatenate(parts, axis=0)
            p = os.path.join(a.out, f"{view}_{v['name']}.json")
            json.dump({"terrain": {"grid": {"center": f"{cx},{cz}", "step": step, "seaLevel": tp.SEA,
                                            "heights": g.tolist()}}}, open(p, "w", encoding="utf-8"))
            files.append(f"{v['name']}={p}")
        subprocess.run([sys.executable, tool, os.path.join(a.out, f"{view}_3d.png"), "--height",
                        str(height)] + files, check=True)
        print(f"{view} views written", flush=True)


if __name__ == "__main__":
    main()
