"""
Ground-truth validation: reads biomes straight out of a real save's region
files and compares them with the simulator's prediction at the same quart
coordinates.

We read each chunk's TOP section biome palette (y = 304..319). Up there the
depth parameter is far below 0 for every column, which penalizes all surface
entries identically and underground entries more, so the stored biome equals
the depth=0 surface selection that the simulator computes. Chunk biome storage
is filled directly from the noise biome source at quart positions (no fuzzy
zoom at generation time), so agreement should be essentially exact.

Usage:
    python preview/validate_against_save.py [save_dir] [n_regions]
"""

import gzip
import os
import random
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mc_biomes import BiomeTable, quantize
from mc_climate import ClimateSampler, RadialParams, finish_fields

DEFAULT_SAVE = os.path.join(os.path.expanduser("~"), "curseforge", "minecraft", "Instances",
                            "Terra Incognita", "saves", "21")


# ---------------------------------------------------------------------------
# Minimal NBT
# ---------------------------------------------------------------------------

class NBT:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def take(self, n):
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v

    def name(self):
        n = struct.unpack(">H", self.take(2))[0]
        return self.take(n).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == 1:
            return struct.unpack(">b", self.take(1))[0]
        if tag == 2:
            return struct.unpack(">h", self.take(2))[0]
        if tag == 3:
            return struct.unpack(">i", self.take(4))[0]
        if tag == 4:
            return struct.unpack(">q", self.take(8))[0]
        if tag == 5:
            return struct.unpack(">f", self.take(4))[0]
        if tag == 6:
            return struct.unpack(">d", self.take(8))[0]
        if tag == 7:
            n = struct.unpack(">i", self.take(4))[0]
            return np.frombuffer(self.take(n), dtype=np.int8)
        if tag == 8:
            n = struct.unpack(">H", self.take(2))[0]
            return self.take(n).decode("utf-8", "replace")
        if tag == 9:
            etag = self.u1()
            n = struct.unpack(">i", self.take(4))[0]
            return [self.payload(etag) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = self.u1()
                if t == 0:
                    return out
                key = self.name()
                out[key] = self.payload(t)
        if tag == 11:
            n = struct.unpack(">i", self.take(4))[0]
            return np.frombuffer(self.take(4 * n), dtype=">i4")
        if tag == 12:
            n = struct.unpack(">i", self.take(4))[0]
            return np.frombuffer(self.take(8 * n), dtype=">i8")
        raise ValueError(f"bad tag {tag}")

    def root(self):
        tag = self.u1()
        self.name()
        return self.payload(tag)


def read_level_seed(save_dir):
    raw = gzip.open(os.path.join(save_dir, "level.dat"), "rb").read()
    root = NBT(raw).root()
    return root["Data"]["WorldGenSettings"]["seed"]


def region_chunks(path):
    """Yields (cx, cz, nbt_dict) for chunks present in a region file."""
    with open(path, "rb") as fh:
        data = fh.read()
    if len(data) < 8192:
        return
    for idx in range(1024):
        entry = struct.unpack(">I", data[idx * 4:idx * 4 + 4])[0]
        offset = (entry >> 8) * 4096
        if offset == 0:
            continue
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        comp = data[offset + 4]
        payload = data[offset + 5:offset + 4 + length]
        try:
            if comp == 2:
                raw = zlib.decompress(payload)
            elif comp == 1:
                raw = gzip.decompress(payload)
            else:
                continue
            chunk = NBT(raw).root()
        except Exception:
            continue
        yield chunk


def top_section_biomes(chunk):
    """Returns (xPos, zPos, biome_at_quart[4][4]) from the topmost section."""
    if chunk.get("Status") not in ("minecraft:full", "full",
                                   "minecraft:initialize_light",
                                   "minecraft:spawn", "minecraft:light"):
        return None
    sections = chunk.get("sections")
    if not sections:
        return None
    top = max((s for s in sections if "biomes" in s), key=lambda s: s["Y"],
              default=None)
    if top is None or top["Y"] < 16:
        return None
    biomes = top["biomes"]
    palette = [p.removeprefix("minecraft:") for p in biomes["palette"]]
    grid = np.zeros((4, 4, 4), dtype=np.int32)  # [y][z][x]
    if "data" in biomes and len(palette) > 1:
        bits = max(1, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        data = biomes["data"].astype(np.uint64)
        vals = []
        mask = np.uint64((1 << bits) - 1)
        for i in range(64):
            word = data[i // per_long]
            shift = np.uint64((i % per_long) * bits)
            vals.append(int((word >> shift) & mask))
        grid = np.array(vals, dtype=np.int32).reshape(4, 4, 4)
    return chunk["xPos"], chunk["zPos"], palette, grid


def main():
    save_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SAVE
    n_regions = int(sys.argv[2]) if len(sys.argv) > 2 else 12

    seed = read_level_seed(save_dir)
    print(f"world seed: {seed}")

    table = BiomeTable()
    sampler = ClimateSampler(seed)
    params = RadialParams.from_datapack()
    print(f"params: {params}")

    region_dir = os.path.join(save_dir, "region")
    files = sorted(os.listdir(region_dir))
    random.seed(7)
    picks = random.sample(files, min(n_regions, len(files)))

    total = 0
    matches = 0
    mismatch_examples = []
    for fname in picks:
        qx_all, qz_all, truth = [], [], []
        for chunk in region_chunks(os.path.join(region_dir, fname)):
            got = top_section_biomes(chunk)
            if got is None:
                continue
            cx, cz, palette, grid = got
            # quart coords of this chunk: 4x4 columns; take y=0 layer of section
            for dz in range(4):
                for dx in range(4):
                    qx_all.append(cx * 4 + dx)
                    qz_all.append(cz * 4 + dz)
                    truth.append(palette[grid[0][dz][dx]])
        if not truth:
            continue
        bx = np.array(qx_all, dtype=np.int64) * 4
        bz = np.array(qz_all, dtype=np.int64) * 4
        raw = sampler.raw_fields(bx, bz)
        c, w = finish_fields(raw, params)
        ids = table.lookup(quantize(raw["temperature"]), quantize(raw["vegetation"]),
                           quantize(c), quantize(raw["erosion"]), quantize(w))
        pred = [table.biome_names[i] for i in np.asarray(ids).ravel()]
        for k, (a, b) in enumerate(zip(pred, truth)):
            total += 1
            if a == b:
                matches += 1
            elif len(mismatch_examples) < 15:
                mismatch_examples.append(
                    (int(bx[k]), int(bz[k]), b, a))
        print(f"{fname}: cumulative {matches}/{total} "
              f"({100.0 * matches / max(total, 1):.2f}%)")

    print(f"\nTOTAL: {matches}/{total} = {100.0 * matches / max(total, 1):.3f}% match")
    if mismatch_examples:
        print("sample mismatches (x, z, save, simulator):")
        for x, z, b, a in mismatch_examples:
            print(f"  ({x:>8}, {z:>8})  save={b:<24} sim={a}")


if __name__ == "__main__":
    main()
