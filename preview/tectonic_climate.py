"""
Offline reproduction of the Terra Continental x Tectonic biome pipeline.

This does NOT re-implement Tectonic. It reads the density-function JSON out of the deployed
Tectonic jar, this mod's jar and the seam resourcepack, plus the live tectonic.json and
terra_continental-common.toml, and evaluates them with mc_dfi. Change a config value or a
datapack file and this follows — which is precisely what the old hand-ported simulator
could not do, and why it silently kept describing a pre-Tectonic world.

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

# Router entries that drive biome selection. Depth is supplied as 0.
_AXES = {
    "temperature":     "minecraft:overworld/noise_router/temperature",
    "vegetation":      "minecraft:overworld/noise_router/vegetation",
    "continentalness": "minecraft:overworld/noise_router/continents",
    "erosion":         "minecraft:overworld/noise_router/erosion",
    "weirdness":       "minecraft:overworld/noise_router/ridges",
}

# tectonic:config_noise `key` -> (config section, scale, multiplier, offset).
# Mirrors ConfigNoise: shifted_noise(scale) * multiplier + offset.
_CONFIG_NOISE = {
    "temperature": ("biomes", "temperature_scale", "temperature_multiplier", "temperature_offset"),
    "vegetation":  ("biomes", "vegetation_scale", "vegetation_multiplier", "vegetation_offset"),
    "continents":  ("continents", "continents_scale", None, None),
    "erosion":     ("continents", "erosion_scale", None, None),
    "ridge":       ("continents", "ridge_scale", None, None),
    "island":      ("islands", "noise_scale", "noise_multiplier", "noise_offset"),
}


def _load_jsonc(path):
    """tectonic.json carries // comments (and URLs inside strings), so strip comments
    only outside string literals, then drop any trailing commas."""
    with open(path, encoding="utf-8-sig") as fh:
        src = fh.read()
    out = []
    i, n, in_str = 0, len(src), False
    while i < n:
        ch = src[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
        elif ch == '"':
            in_str = True
            out.append(ch)
            i += 1
        elif ch == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif ch == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        else:
            out.append(ch)
            i += 1
    return _json.loads(_re.sub(r",(\s*[}\]])", r"\1", "".join(out)))


def _find(dirpath, pattern):
    rx = _re.compile(pattern, _re.I)
    for fn in sorted(_os.listdir(dirpath)):
        if rx.search(fn):
            return _os.path.join(dirpath, fn)
    raise FileNotFoundError(f"no file matching {pattern} in {dirpath}")


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


class TectonicSim:
    def __init__(self, instance=INSTANCE, biome_dump=None):
        mods = _os.path.join(instance, "mods")
        tectonic_jar = _find(mods, r"^tectonic-.*\.jar$")
        terra_jar = _find(mods, r"^terra_continental-.*\.jar$")

        # Lowest priority first; the seam pack is registered TOP in game, so it wins here too.
        self.reg = (mc_dfi.Registry()
                    .add_jar(terra_jar, "")
                    .add_jar(tectonic_jar, "resourcepacks/tectonic/")
                    .add_jar(tectonic_jar, "resourcepacks/tectonic/overlay.datapack/")
                    .add_jar(tectonic_jar, "resourcepacks/tectonic/overlay.mod/")
                    .add_jar(terra_jar, "resourcepacks/tectonic_seam/"))

        # Register every noise definition found in those packs with the noise factory.
        for name, spec in self.reg._noise_json.items():
            if "firstOctave" in spec and "amplitudes" in spec:
                mc_noise.NOISE_PARAMETERS.setdefault(
                    name, (int(spec["firstOctave"]), [float(a) for a in spec["amplitudes"]]))

        self.tconf = _load_jsonc(_os.path.join(instance, "config", "tectonic.json"))
        self.radial = load_radial_params(
            _os.path.join(instance, "config", "terra_continental-common.toml"))

        dump = biome_dump or _os.path.join(instance, "terra_biome_dump.json")
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

        # Tectonic's config_* nodes address config values by bare key, so flatten the
        # sectioned JSON into one lookup.
        # Booleans count too — Tectonic gates features like rolling_hills / jungle_pillars
        # through config_constant, where true/false arrive as 1.0/0.0.
        self._flat_conf = {}
        for section in self.tconf.values():
            if isinstance(section, dict):
                for k, v in section.items():
                    if isinstance(v, bool):
                        self._flat_conf[k] = 1.0 if v else 0.0
                    elif isinstance(v, (int, float)):
                        self._flat_conf[k] = float(v)

        self._custom = {
            "minecraft:shift_x": lambda ctx: ctx.shift()[0],
            "minecraft:shift_z": lambda ctx: ctx.shift()[1],
            "tectonic:config_noise": self._config_noise,
            "tectonic:config_constant": self._config_constant,
            "tectonic:config_clamp": self._config_clamp,
            "terra_continental:radial_falloff": self._radial_falloff,
        }

    def _config_constant(self, ctx, node):
        return ctx.const(self._flat_conf[node["key"]])

    def _config_clamp(self, ctx, node):
        return np.clip(mc_dfi.evaluate(node["input"], ctx),
                       mc_dfi.evaluate(node["min"], ctx),
                       mc_dfi.evaluate(node["max"], ctx))

    # -- custom node handlers -------------------------------------------------

    def _config_noise(self, ctx, node):
        section, s_key, m_key, o_key = _CONFIG_NOISE[node["key"]]
        conf = self.tconf.get(section, {})
        scale = float(conf.get(s_key, 1.0))
        mult = float(conf.get(m_key, 1.0)) if m_key else 1.0
        off = float(conf.get(o_key, 0.0)) if o_key else 0.0
        sx, sz = ctx.shift()
        n = ctx.noise(node["noise"])
        v = n.get_value(ctx.X * scale + sx, np.zeros(ctx.shape), ctx.Z * scale + sz)
        return v * mult + off

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
