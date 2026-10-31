"""
Offline reproduction of Terra Continental's biome pipeline.

It reads the density-function JSON the game loads (this tree's mod data and resourcepacks/terrain)
plus terra_continental-common.toml, and evaluates it with mc_dfi, so a config or datapack edit
previews without a build. (The old hand-ported simulator couldn't follow such edits, and silently
kept describing a world from before the terrain was absorbed.)

Biome selection then reuses the existing, already-validated machinery: mc_biomes'
nearest-box lookup plus TerraBlender region routing, fed from `/continental dump_biomes`.

Depth is pinned to 0 (surface band) exactly as BiomeMapCommand.surfaceSampler does in game,
so no terrain column ever has to be solved.
"""

import json as _json
import os as _os
import re as _re

import numpy as np

import mc_biomes
import mc_climate
import mc_dfi
import mc_noise

INSTANCE = _os.path.join(_os.path.expanduser("~"), "curseforge", "minecraft", "Instances",
                         "Terra Incognita")
SRC_RESOURCES = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "..", "src", "main",
                              "resources")

# Router entries that drive biome selection. Depth is supplied as 0.
_AXES = {
    "temperature":     "minecraft:overworld/noise_router/temperature",
    "vegetation":      "minecraft:overworld/noise_router/vegetation",
    "continentalness": "minecraft:overworld/noise_router/continents",
    "erosion":         "minecraft:overworld/noise_router/erosion",
    "weirdness":       "minecraft:overworld/noise_router/ridges",
}

def _read_toml_number(path, key):
    """Minimal `key = value` lookup — enough for the flat configs we read."""
    if not _os.path.exists(path):
        return None
    with open(path, encoding="utf-8-sig") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            if k.strip() == key:
                try:
                    return float(v.split("#")[0].strip())
                except ValueError:
                    return None
    return None


def load_radial_params(toml_path):
    """Read the live [continent_shaping] values so Python matches the running mod."""
    p = mc_climate.RadialParams()
    if not _os.path.exists(toml_path):
        return p
    with open(toml_path, encoding="utf-8-sig") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            key = key.strip()
            if hasattr(p, key):
                try:
                    setattr(p, key, float(val.strip()))
                except ValueError:
                    pass
    return p


class ClimateSim:
    def __init__(self, instance=INSTANCE, biome_dump=None):
        """Reads this worktree's src/main/resources, stacked as the game stacks it: the mod's
        own data, then resourcepacks/terrain on top. Source edits preview without a build. The
        instance supplies terra_continental-common.toml (and a biome dump, if there is one)."""
        self.reg = (mc_dfi.Registry()
                    .add_source(SRC_RESOURCES)
                    .add_source(_os.path.join(SRC_RESOURCES, "resourcepacks", "terrain")))

        # Register every noise definition found in those packs with the noise factory.
        for name, spec in self.reg._noise_json.items():
            if "firstOctave" in spec and "amplitudes" in spec:
                mc_noise.NOISE_PARAMETERS.setdefault(
                    name, (int(spec["firstOctave"]), [float(a) for a in spec["amplitudes"]]))

        common = _os.path.join(instance, "config", "terra_continental-common.toml")
        self.radial = load_radial_params(common)
        self._spawn_climate = [_read_toml_number(common, k) for k in (
            "temperate_spawn_strength", "temperate_spawn_inner_factor", "temperate_spawn_outer_factor")]

        self._custom = {
            "minecraft:shift_x": lambda ctx: ctx.shift()[0],
            "minecraft:shift_z": lambda ctx: ctx.shift()[1],
            "terra_continental:clamp_between": self._clamp_between,
            "terra_continental:radial_falloff": self._radial_falloff,
            "terra_continental:spawn_climate_mask": self._spawn_climate_mask,
        }

        dump = biome_dump or _os.path.join(instance, "terra_biome_dump.json")
        self.table = None
        if not _os.path.exists(dump):
            return      # terrain only (continental_terrain.py); a rig has no biome dump
        entries = mc_biomes.load_entries_from_json(dump)
        tb = mc_biomes.load_terrablender_from_json(dump)
        if tb:
            # The dump snapshots overworld_region_size at dump time, but that value controls
            # how finely TerraBlender re-dices biomes (region_size 3 ~= 512 blocks, 5 ~= 2048)
            # and it is edited far more often than biomes are re-dumped. Prefer the live
            # terrablender.toml so a config change doesn't silently leave this stale.
            live = _read_toml_number(
                _os.path.join(instance, "config", "terrablender.toml"),
                "overworld_region_size")
            self.region_size = int(live) if live is not None else tb["region_size"]
            self.table = mc_biomes.MultiRegionBiomeTable(
                tb["region_entries"], self.region_size, tb["region_weights"])
        else:
            self.region_size = None
            self.table = mc_biomes.BiomeTable(entries=entries)
        self.biome_names = self.table.biome_names

    def set_config(self, tectonic=None, continental=None):
        """Override [continent_shaping] keys for this simulator, as terrain_sweep.py does to a
        rig's files. The terrain hasn't read tectonic.json since 0.10.0: its values are in
        resourcepacks/terrain, so experiment on them with a shape_pack.py pack instead."""
        if tectonic:
            raise ValueError(f"tectonic.json keys {sorted(tectonic)}: the terrain doesn't read tectonic.json "
                             "any more; make a shape_pack.py pack (pack=<dir>) instead")
        for key, value in (continental or {}).items():
            if not hasattr(self.radial, key):
                raise KeyError(f"no continent_shaping key {key!r}")
            setattr(self.radial, key, float(value))

    def _clamp_between(self, ctx, node):
        return np.clip(mc_dfi.evaluate(node["input"], ctx),
                       mc_dfi.evaluate(node["min"], ctx),
                       mc_dfi.evaluate(node["max"], ctx))

    # -- custom node handlers -------------------------------------------------

    def _jitter_table(self, ctx, noise_name):
        """RadialFalloffFunction.jitterSample — fixed-coordinate samples of the seeded
        jitter noise, widened toward [-1, 1]. Per-seed scalars, not per-pixel."""
        key = ("_jitter", noise_name)
        if key not in ctx._named:
            noise = ctx.noise(noise_name)
            idx = np.arange(mc_climate.MAX_ADDITIONAL_CONTINENTS, dtype=np.float64)
            ctx._named[key] = np.stack([
                np.clip(noise.get_value(
                    idx * 9973.0 + ch * 4451.0 + 31.0, 0.0,
                    idx * -6101.0 + ch * 2887.0 - 97.0) * 2.0, -1.0, 1.0)
                for ch in range(3)], axis=1)
        return ctx._named[key]

    def _radial_falloff(self, ctx, node):
        upstream = mc_dfi.evaluate(node["wrapped"], ctx)
        jitter = self._jitter_table(
            ctx, node.get("jitter_noise", "terra_continental:continent_jitter"))
        distance = np.sqrt(ctx.X ** 2 + ctx.Z ** 2)
        return mc_climate.radial_falloff(
            upstream, ctx.X, ctx.Z, distance, self.radial, jitter)

    def _spawn_climate_mask(self, ctx, node=None):
        """terra_continental:spawn_climate_mask (SpawnClimateMask.java): the configured strength
        inside central_land_radius * inner, easing (smoothstep) to 0 by radius * outer."""
        strength, inner_f, outer_f = (v if v is not None else d for v, d in
                                      zip(self._spawn_climate, (1.0, 1.5, 2.0)))
        if strength <= 0.0:
            return ctx.const(0.0)
        inner = self.radial.central_land_radius * inner_f
        outer = max(inner + 1.0, self.radial.central_land_radius * outer_f)
        d = np.sqrt(ctx.X ** 2 + ctx.Z ** 2)
        t = np.clip((outer - d) / (outer - inner), 0.0, 1.0)
        return strength * t * t * (3.0 - 2.0 * t)

    # -- public API -----------------------------------------------------------

    def climate(self, seed, X, Z):
        """Return the five biome axes as raw float arrays over the (X, Z) grid."""
        ctx = mc_dfi.Context(self.reg, seed, X, Z, custom=self._custom)
        return {k: mc_dfi.evaluate(name, ctx) for k, name in _AXES.items()}

    def biome_ids(self, seed, X, Z):
        """Return (ids, names) — ids index into self.biome_names."""
        c = self.climate(seed, X, Z)
        q = mc_biomes.quantize
        qt, qh = q(c["temperature"]), q(c["vegetation"])
        qc, qe = q(c["continentalness"]), q(c["erosion"])
        qw = q(c["weirdness"])
        if isinstance(self.table, mc_biomes.MultiRegionBiomeTable):
            ids = self.table.lookup(seed, qt, qh, qc, qe, qw,
                                    np.asarray(X, dtype=np.int64),
                                    np.asarray(Z, dtype=np.int64))
        else:
            ids = self.table.lookup(qt, qh, qc, qe, qw)
        return np.asarray(ids), self.biome_names

    def grid(self, seed, radius, step, center_x=0, center_z=0):
        """Sample the same grid layout `/continental biome_map` writes (north-up, west-left)."""
        n = (2 * radius) // step + 1
        coords = center_x - radius + np.arange(n, dtype=np.int64) * step
        coords_z = center_z - radius + np.arange(n, dtype=np.int64) * step
        Z, X = np.meshgrid(coords_z, coords, indexing="ij")
        ids, names = self.biome_ids(seed, X, Z)
        return ids.reshape(n, n), names
