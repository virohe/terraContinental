"""
Verifies mc_noise.py bit-for-bit against values dumped from the real decompiled
Minecraft 1.21.1 classes (ParityDump.java, run against the NeoForge dev jars).

Usage: python preview/test_parity.py [path/to/parity.txt]
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mc_noise import (NOISE_PARAMETERS, ImprovedNoise, NormalNoise, Xoroshiro,
                      from_hash_of)

DEFAULT_DUMP = os.path.join(os.environ.get("TEMP", "/tmp"), "mc_parity", "parity.txt")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DUMP
    raw = open(path, "rb").read()
    text = raw.decode("utf-16") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8")
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    failures = 0
    checks = 0

    def check(label, expected, actual, exact=True):
        nonlocal failures, checks
        checks += 1
        ok = (expected == actual) if exact else abs(expected - actual) < 1e-15
        if not ok:
            failures += 1
            print(f"FAIL {label}: java={expected!r} python={actual!r}")

    # --- low-level RNG ---
    r = Xoroshiro.from_seed(21)
    seed = None
    noise = None
    current_noise_rng_fork = None

    it = iter(lines)
    for line in it:
        parts = line.split()
        if parts[0] == "RNG" and parts[1] == "nextLong":
            check("nextLong", int(parts[2]), r.next_long_signed())
        elif parts[0] == "RNG" and parts[1] == "nextDouble":
            check("nextDouble", float(parts[2]), r.next_double())
        elif parts[0] == "RNG" and parts[1] == "nextInt100":
            check("nextInt(100)", int(parts[2]), r.next_int_bounded(100))
        elif parts[0] == "HASH":
            lo, hi = Xoroshiro.from_seed(21).fork_positional()
            h = from_hash_of(lo, hi, "minecraft:temperature")
            check("fromHashOf nextLong", int(parts[2]), h.next_long_signed())
        elif parts[0] == "IMP" and parts[1] == "xo":
            imp = ImprovedNoise(Xoroshiro.from_seed(21))
            check("imp.xo", float(parts[2]), imp.xo)
            check("imp.yo", float(parts[4]), imp.yo)
            check("imp.zo", float(parts[6]), imp.zo)
        elif parts[0] == "IMP":
            x, y, z, v = float(parts[1]), float(parts[2]), float(parts[3]), float(parts[4])
            check(f"imp.noise({x},{y},{z})", v, float(imp.noise(x, y, z)))
        elif parts[0] == "SEED":
            seed = int(parts[1])
            fork = Xoroshiro.from_seed(seed).fork_positional()
            current_noise_rng_fork = fork
            noises = {}
        elif parts[0] == "NOISE":
            name = parts[1]
            if name not in noises:
                fo, amps = NOISE_PARAMETERS[name]
                rng = from_hash_of(*current_noise_rng_fork, name)
                noises[name] = NormalNoise(rng, fo, amps)
            x, y, z, v = float(parts[2]), float(parts[3]), float(parts[4]), float(parts[5])
            got = float(noises[name].get_value(x, y, z))
            check(f"seed={seed} {name} ({x},{y},{z})", v, got)

    print(f"{checks} checks, {failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
