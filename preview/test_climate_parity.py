"""
Parity gate: offline Python pipeline vs the real in-game generator.

This is the measuring stick that the old simulator never had. `/continental biome_map` exports
a biome-ID grid straight out of the live generator; this replays the identical grid through
tectonic_climate and diffs cell by cell. If agreement drops, something drifted and the
offline tool must not be trusted until it's explained.

Usage:
    # in game, on a plain BetterX world:
    #   /continental biome_map 20000 128 <seed>
    python test_climate_parity.py                       # newest export, default instance
    python test_climate_parity.py <seed>
    python test_climate_parity.py <path to .json/.bin>   # any instance

The instance is derived from the export path (<instance>/seedscan/...), so the interpreter
loads the same jars and configs that produced the export — comparing against a different
instance's mods would be meaningless.
"""

import glob
import json
import os
import sys
import time
from collections import Counter

import numpy as np

import continental_climate as tc

SEEDSCAN = os.path.join(tc.INSTANCE, "seedscan")


def load_export(arg=None):
    if arg and not str(arg).lstrip("-").isdigit():
        meta_path = os.path.splitext(str(arg))[0] + ".json"
    elif arg is not None:
        meta_path = os.path.join(SEEDSCAN, f"biome_map_{arg}.json")
    else:
        metas = sorted(glob.glob(os.path.join(SEEDSCAN, "biome_map_*.json")),
                       key=os.path.getmtime)
        if not metas:
            raise SystemExit(
                f"No exports in {SEEDSCAN}.\n"
                "Run  /continental biome_map 20000 128 <seed>  in game first,\n"
                "or pass the path to an export's .json/.bin.")
        meta_path = metas[-1]
    with open(meta_path, encoding="utf-8-sig") as fh:
        meta = json.load(fh)
    grid = np.fromfile(meta_path[:-5] + ".bin", dtype="<u2")
    n = meta["n"]
    # <instance>/seedscan/biome_map_*.json  ->  <instance>
    instance = os.path.dirname(os.path.dirname(os.path.abspath(meta_path)))
    return meta, grid.reshape(n, n), instance


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    meta, ingame_ids, instance = load_export(arg)
    seed = meta["seed"]
    palette = meta["palette"]
    print(f"in-game export: seed={seed} n={meta['n']} radius={meta['radius']} "
          f"step={meta['step']} center=({meta['centerX']},{meta['centerZ']})")
    print(f"instance: {instance}")

    # TerrainSim, not ClimateSim: the terrain pack's biome router reads terra_continental:giant_peaks
    # (the giant's biome pull), which only TerrainSim evaluates.
    import continental_terrain
    sim = continental_terrain.TerrainSim(instance=instance)
    py_ids, py_names = sim.grid(seed, meta["radius"], meta["step"],
                                meta["centerX"], meta["centerZ"])

    # Compare by NAME — the two palettes are independent orderings. mc_biomes strips the
    # "minecraft:" namespace while the in-game export keeps it, so normalise both sides.
    def norm(names):
        return np.array([n[10:] if n.startswith("minecraft:") else n for n in names],
                        dtype=object)

    ingame = norm(palette)[ingame_ids]
    mine = norm(py_names)[py_ids]

    total = ingame.size
    bad = ingame != mine
    same = total - int(bad.sum())
    pct = 100.0 * same / total
    print(f"\nagreement: {same}/{total} = {pct:.2f}%")

    if bad.any():
        # Boundary-adjacent means the python answer appears somewhere in the in-game 3x3
        # neighbourhood: the fields agree, the biome edge just fell in a different cell.
        # A low share here means a field or table is genuinely wrong, not merely imprecise.
        nb = np.zeros_like(bad)
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                nb |= (np.roll(np.roll(ingame, dz, 0), dx, 1) == mine)
        print(f"  boundary-adjacent: {100.0 * nb[bad].mean():.1f}% of mismatches")

        # Splitting by namespace separates a stale biome TABLE (BWG boxes come from
        # dump_biomes) from a wrong climate FIELD (vanilla-only disagreements).
        bwg = (np.char.startswith(ingame.astype(str), "biomeswevegone")
               | np.char.startswith(mine.astype(str), "biomeswevegone"))
        print(f"  involving BWG:  {100.0 * (bad & bwg).mean():.2f}% of all cells"
              f"   vanilla-only: {100.0 * (bad & ~bwg).mean():.2f}%")

        pairs = Counter(zip(ingame[bad].tolist(), mine[bad].tolist()))
        print("\ntop mismatches (in-game -> python):")
        for (a, b), c in pairs.most_common(15):
            print(f"  {100.0 * c / total:5.2f}%  {a}  ->  {b}")

    # The biome table is only as current as the last dump; a stale one shifts every box.
    dump_path = os.path.join(instance, "terra_biome_dump.json")
    if os.path.exists(dump_path):
        dump_age = os.path.getmtime(dump_path)
        newer = [p for p in (os.path.join(instance, "mods"),
                             os.path.join(instance, "config"))
                 if os.path.exists(p) and os.path.getmtime(p) > dump_age]
        if newer:
            print(f"\nWARNING: terra_biome_dump.json ({time.ctime(dump_age)}) is older than "
                  f"{', '.join(os.path.basename(p) for p in newer)}.")
            print("         Re-run  /continental dump_biomes  in this instance and retry.")

    print("\nPASS" if pct >= 99.0 else "\nFAIL — do not trust the offline tool yet")
    return 0 if pct >= 99.0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
