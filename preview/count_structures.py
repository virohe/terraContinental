"""
Count how many structures of a random_spread set actually land in a mapped area.

Reproduces RandomSpreadStructurePlacement.getPotentialStructureChunk exactly (a
LegacyRandomSource seeded per grid cell), then checks the biome at each candidate
against the structure's eligible biome list. That turns "there are too many villages"
into a number, and lets a target count be solved for instead of guessed at.

Spacing/separation must be given POST-multiplier — RandomSpreadStructurePlacementMixin
scales every random_spread set by structure_spacing_multiplier at runtime.

Usage:
    python count_structures.py <biome_map .json> [spacing] [separation]
"""

import json
import os
import sys

import numpy as np

_MASK48 = (1 << 48) - 1


class JavaRandom:
    """java.util.Random — LegacyRandomSource in Minecraft terms."""

    def __init__(self, seed=0):
        self.set_seed(seed)

    def set_seed(self, seed):
        self._s = (seed ^ 0x5DEECE66D) & _MASK48

    def _next(self, bits):
        self._s = (self._s * 0x5DEECE66D + 0xB) & _MASK48
        return self._s >> (48 - bits)

    def next_int(self, bound):
        if bound & (bound - 1) == 0:                     # power of two
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val

    def set_large_feature_with_salt(self, world_seed, i, j, salt):
        # WorldgenRandom.setLargeFeatureWithSalt
        v = (i * 341873128712 + j * 132897987541 + world_seed + salt)
        v = ((v + (1 << 63)) % (1 << 64)) - (1 << 63)     # wrap to signed long
        self.set_seed(v)


def candidate_chunks(world_seed, spacing, separation, salt, cx0, cx1, cz0, cz1):
    """Every potential structure chunk whose grid cell overlaps the given chunk range."""
    rnd = JavaRandom()
    out = []
    k = spacing - separation
    for j in range(cz0 // spacing - 1, cz1 // spacing + 2):
        for i in range(cx0 // spacing - 1, cx1 // spacing + 2):
            rnd.set_large_feature_with_salt(world_seed, i, j, salt)
            x = i * spacing + rnd.next_int(k)      # RandomSpreadType.LINEAR
            z = j * spacing + rnd.next_int(k)
            if cx0 <= x <= cx1 and cz0 <= z <= cz1:
                out.append((x, z))
    return out


VILLAGE_SETS = [
    # name,                 json spacing, separation, salt
    ("minecraft:villages", 32, 8, 10387312),
    ("biomeswevegone:villages", 34, 8, 437845874),
]


def load_village_biomes(instance):
    """Union of every has_structure/village* tag across the installed mods."""
    import glob
    import zipfile
    names, tagrefs = set(), set()
    for jar in glob.glob(os.path.join(instance, "mods", "*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                for n in zf.namelist():
                    if "/tags/worldgen/biome/has_structure/village" not in n:
                        continue
                    d = json.loads(zf.read(n).decode("utf-8-sig"))
                    for v in d.get("values", []):
                        v = v if isinstance(v, str) else v.get("id", "")
                        (tagrefs if v.startswith("#") else names).add(v.lstrip("#"))
        except Exception:
            pass
    # Vanilla's own lists live in the client jar; add the known defaults.
    names |= {"minecraft:plains", "minecraft:meadow", "minecraft:desert",
              "minecraft:savanna", "minecraft:snowy_plains", "minecraft:taiga"}
    # Resolve referenced biome tags (e.g. #biomeswevegone:savanna).
    for jar in glob.glob(os.path.join(instance, "mods", "*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                for ref in list(tagrefs):
                    ns, _, path = ref.partition(":")
                    entry = f"data/{ns}/tags/worldgen/biome/{path}.json"
                    if entry in zf.namelist():
                        d = json.loads(zf.read(entry).decode("utf-8-sig"))
                        for v in d.get("values", []):
                            v = v if isinstance(v, str) else v.get("id", "")
                            if v and not v.startswith("#"):
                                names.add(v)
        except Exception:
            pass
    return names


def main():
    meta_path = sys.argv[1]
    with open(meta_path, encoding="utf-8-sig") as fh:
        m = json.load(fh)
    n, step, r = m["n"], m["step"], m["radius"]
    ids = np.fromfile(meta_path[:-5] + ".bin", dtype="<u2").reshape(n, n)
    pal = m["palette"]
    instance = os.path.dirname(os.path.dirname(os.path.abspath(meta_path)))

    eligible = load_village_biomes(instance)
    ok = np.array([p in eligible for p in pal])
    print(f"seed {m['seed']}   area {2*r}x{2*r} blocks")
    print(f"village-eligible biomes present in this map: "
          f"{sorted({p for p in pal if p in eligible}).__len__()} of {len(eligible)} known")
    land_ok = ok[ids].mean() * 100
    print(f"eligible area: {land_ok:.1f}% of the map\n")

    mult = float(sys.argv[2]) if len(sys.argv) > 2 else 1.5
    cx0, cx1 = (m["centerX"] - r) // 16, (m["centerX"] + r) // 16
    cz0, cz1 = (m["centerZ"] - r) // 16, (m["centerZ"] + r) // 16

    total = 0
    for name, sp, sep, salt in VILLAGE_SETS:
        S = max(2, int(round(sp * mult)))
        P = min(S - 1, max(1, int(round(sep * mult))))
        hits = 0
        for cx, cz in candidate_chunks(m["seed"], S, P, salt, cx0, cx1, cz0, cz1):
            bx, bz = cx * 16 + 8, cz * 16 + 8
            ix = int(round((bx - (m["centerX"] - r)) / step))
            iz = int(round((bz - (m["centerZ"] - r)) / step))
            if 0 <= ix < n and 0 <= iz < n and ok[ids[iz, ix]]:
                hits += 1
        print(f"  {name:26s} spacing {S:3d} sep {P:2d}  ->  {hits:4d} villages")
        total += hits
    print(f"  {'TOTAL':26s} {'':17s}  ->  {total:4d} villages")


if __name__ == "__main__":
    main()
