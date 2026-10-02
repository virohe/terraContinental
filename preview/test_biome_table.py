"""
Validates the BiomeTable band-cell lookup against the exact per-point
nearest-box search, over random points spanning (and exceeding) the parameter
space. Any mismatch that is not an exact fitness tie is a bug.

Usage: python preview/test_biome_table.py
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mc_biomes import BiomeTable


def main():
    table = BiomeTable()
    print(f"entries: {len(table.entries)}, distinct biomes: {len(table.biome_names)}")
    print(f"cells per dim: {[len(e) - 1 for e in table.edges]}")

    rng = np.random.default_rng(12345)
    n = 200_000
    t = rng.integers(-16000, 16000, n)
    h = rng.integers(-16000, 16000, n)
    c = rng.integers(-26000, 16000, n)
    e = rng.integers(-16000, 16000, n)
    w = rng.integers(-10000, 10001, n)  # ridges output is clamped to [-1, 1]

    fast = table.lookup(t, h, c, e, w)

    mismatches = 0
    ties = 0
    for k in range(n):
        exact = table.brute_force_biome(t[k], h[k], c[k], e[k], w[k])
        got = table.biome_names[fast[k]]
        if got != exact:
            # check whether it is a tie (both at identical minimal fitness)
            p = np.array([t[k], h[k], c[k], e[k], w[k]], dtype=np.float64)
            d = np.maximum(table._boxes[:, :, 0] - p, p - table._boxes[:, :, 1])
            np.maximum(d, 0.0, out=d)
            fit = np.einsum("nd,nd->n", d, d)
            best = fit.min()
            tied = {table.biome_names[table._entry_biome[i]]
                    for i in np.flatnonzero(fit == best)}
            if got in tied:
                ties += 1
            else:
                mismatches += 1
                if mismatches <= 10:
                    print(f"MISMATCH at t={t[k]} h={h[k]} c={c[k]} e={e[k]} w={w[k]}: "
                          f"table={got} exact={exact}")
    print(f"{n} random points: {mismatches} true mismatches, {ties} resolved-as-tie")
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
