"""
A biome map of one patch of a world, with a legend of its commonest biomes, per variant
(packs and settings as in terrain_preview.py). Made for the giant's biomes (tour, 2026-10-24:
lowland forests ran up a 1,000-block peak), usable anywhere.

    python preview/biome_patch.py --out preview/out/giant/biomes --seed 4151832666630476678 \
        --center -3144,-208 --radius 3072 --step 16 --variant "now:" --variant "g1: pack=..."

Writes <name>.png per variant, north up, and <name>.npy (biome names on the grid).
"""

import argparse
import multiprocessing as mp
import os
import sys
from collections import Counter

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import terrain_preview as tp
from mc_biomes import BIOME_COLORS


def _rgb(name):
    c = BIOME_COLORS.get(name) or BIOME_COLORS.get(name.split(":")[-1]) or "#808080"
    c = c.lstrip("#")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def run_band(args):
    instance, variant, seed, xs, zs = args
    sim = tp._sim(instance, variant)
    Z, X = np.meshgrid(zs, xs, indexing="ij")
    ids, names = sim.biome_ids(seed, X.astype(np.int64), Z.astype(np.int64))
    return np.array([names[i] for i in np.asarray(ids).ravel()], dtype=object).reshape(X.shape)


def render(names, title, path, legend_rows=14):
    from PIL import Image, ImageDraw
    palette = {n: _rgb(n) for n in set(names.ravel())}
    rgb = np.zeros(names.shape + (3,), dtype=np.uint8)
    for n, c in palette.items():
        rgb[names == n] = c
    img = Image.fromarray(rgb).resize((names.shape[1] * 2, names.shape[0] * 2), Image.NEAREST)
    counts = Counter(names.ravel()).most_common(legend_rows)
    out = Image.new("RGB", (img.width + 260, max(img.height, 20 * legend_rows + 40) + 28), (250, 250, 250))
    out.paste(img, (0, 28))
    d = ImageDraw.Draw(out)
    d.text((6, 8), title, fill=(0, 0, 0))
    total = names.size
    for i, (n, c) in enumerate(counts):
        y = 36 + i * 20
        d.rectangle([img.width + 8, y, img.width + 22, y + 14], fill=palette[n])
        d.text((img.width + 28, y + 1), f"{n.split(':')[-1]} {100 * c / total:.0f}%", fill=(0, 0, 0))
    out.save(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--instance", default=None)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--variant", action="append", default=[])
    ap.add_argument("--center", default="0,0")
    ap.add_argument("--radius", type=int, default=3072)
    ap.add_argument("--step", type=int, default=16)
    ap.add_argument("--jobs", type=int, default=min(8, os.cpu_count()))
    a = ap.parse_args()

    import continental_climate
    instance = a.instance or continental_climate.INSTANCE
    variants = [tp.parse_variant(v) for v in (a.variant or ["now:"])]
    cx, cz = (int(v) for v in a.center.split(","))
    n = 2 * (a.radius // a.step) + 1
    xs = cx + (np.arange(n) - n // 2) * a.step
    zs = cz + (np.arange(n) - n // 2) * a.step
    os.makedirs(a.out, exist_ok=True)
    with mp.get_context("spawn").Pool(a.jobs) as pool:
        for v in variants:
            parts = pool.map(run_band, [(instance, v, a.seed, xs, b)
                                        for b in np.array_split(zs, a.jobs * 2) if b.size])
            names = np.concatenate(parts, axis=0)
            title = f"{v['name']}   seed {a.seed}   {2 * a.radius // 1000} km across, 1 px = {a.step // 2} blocks"
            render(names, title, os.path.join(a.out, v["name"] + ".png"))
            np.save(os.path.join(a.out, v["name"] + ".npy"), names.astype(str))
            print(v["name"], "done", flush=True)


if __name__ == "__main__":
    main()
