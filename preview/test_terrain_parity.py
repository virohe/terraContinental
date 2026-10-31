"""
Parity gate for tectonic_terrain: offline heights vs a rig's TerrainAudit height grid.

    python test_terrain_parity.py <rig dir> <audit.json> [key=value ...]

The audit must come from a terrain_sweep.py run on that rig (its grid is getBaseHeight at
WORLD_SURFACE_WG). key=value pairs are the tectonic.json changes that run made (the sweep's
"set"), e.g. vertical_scale=2.0. The seed is the rig's level-seed. Reports how many samples
match exactly, within 1, 4 and 16 blocks, and where the worst ones are.
"""

import json
import os
import re
import sys
import time

import numpy as np

import continental_terrain


def main():
    rig, audit = sys.argv[1], sys.argv[2]
    overrides = {}
    for a in sys.argv[3:]:
        k, v = a.split("=", 1)
        overrides[k] = json.loads(v)
    props = open(os.path.join(rig, "server.properties"), encoding="utf-8").read()
    seed = int(re.search(r"(?m)^level-seed=(-?\d+)", props).group(1))
    grid = json.load(open(audit, encoding="utf-8"))["terrain"]["grid"]
    cx, cz = (int(v) for v in grid["center"].split(","))
    want = np.array(grid["heights"])
    sim = continental_terrain.TerrainSim(rig)
    sim.set_config(tectonic=overrides)
    t = time.time()
    got = sim.grid(seed, cx, cz, want.shape[0], int(grid["step"]))
    secs = time.time() - t
    d = np.abs(got - want)
    land = want > continental_terrain.SEA_LEVEL
    print(f"{want.size} samples ({land.sum()} land) in {secs:.1f} s, seed {seed}, {overrides or 'rig config'}")
    for tol in (0, 1, 4, 16):
        print(f"  within {tol:>2}: all {np.mean(d <= tol):6.1%}   land {np.mean(d[land] <= tol):6.1%}")
    print(f"  heights: want {want.min()}..{want.max()} median {int(np.median(want[land]))}, "
          f"got {got.min()}..{got.max()} median {int(np.median(got[land])) if land.any() else '-'}")
    worst = np.argsort(d.ravel())[::-1][:8]
    step = int(grid["step"])
    for i in worst:
        gz, gx = divmod(int(i), want.shape[1])
        x = cx + (gx - want.shape[1] // 2) * step
        z = cz + (gz - want.shape[0] // 2) * step
        print(f"    {x},{z}: want {want[gz, gx]} got {got[gz, gx]}")
    return got, want


if __name__ == "__main__":
    main()
