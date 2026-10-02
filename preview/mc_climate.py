"""
Terra Continental's noise router, ported exactly from the datapack JSONs:

  temperature  = shifted_noise(minecraft:temperature,     xz_scale=0.05)
  vegetation   = shifted_noise(minecraft:vegetation,      xz_scale=0.06)
  continents   = continent_base + tectonic_boost + mushroom_dip   (radial_continent_mask)
  erosion      = shifted_noise(minecraft:erosion,         xz_scale=0.10)
  ridges       = flat_cache(cache_2d(scaled_weirdness))
               = clamp(noise(minecraft:ridge, 0.025), -1, 1)
               (scale 1.0 = vanilla frequency; configurable via weirdness_scale in TOML)

with the continent mask components (density_function/*.json):

  continent_base     = max(radial_falloff(shifted_noise(continentalness, 0.05)), -1.02)
  tectonic_potential = shifted_noise(terra_continental:tectonic,       0.05)
  tectonic_segments  = shifted_noise(terra_continental:tectonic_break, 0.05)
  tectonic_ridge     = square(clamp(1 - 6.667 * |tectonic_potential|, 0, 1))
  tectonic_chain     = tectonic_ridge * clamp(2.5 * (tectonic_segments + 0.1), 0, 1)
  tectonic_boost     = 0.85 * tectonic_chain * clamp(-2 * (continent_base + 0.11), 0, 1)
  mushroom_dip       = -1.2 * square(tectonic_ridge
                                     * clamp(-4 * (continent_base + 0.75), 0, 1)
                                     * clamp( 8 * (tectonic_segments - 0.55), 0, 1))

where shift_x = shift_a(minecraft:offset) and shift_z = shift_b(minecraft:offset)
exactly as vanilla (offset noise at (x/4, 0, z/4) * 4 resp. (z/4, x/4, 0) * 4),
and radial_falloff is RadialFalloffFunction.java (central bump + seed-jittered
additional continent centers + radial ocean bias + soft border sigmoid).

Biome selection samples at quart-aligned block coordinates and depth = 0
(overworld surface), matching Climate.Sampler.sample.
"""

import json
import os
from dataclasses import dataclass, fields

import numpy as np

from mc_noise import NormalNoise, Xoroshiro, from_hash_of, NOISE_PARAMETERS

_DEFAULT_MASK_JSON = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
    "data", "terra_continental", "worldgen", "density_function",
    "radial_continent_mask.json")

CONTINENT_XZ_SCALE = 0.05
TEMPERATURE_XZ_SCALE = 0.05
VEGETATION_XZ_SCALE = 0.06
EROSION_XZ_SCALE = 0.10
RIDGE_XZ_SCALE = 0.025
TECTONIC_XZ_SCALE = 0.05

# jitter samples precomputed per sampler for indices < MAX_ADDITIONAL_CONTINENTS
MAX_ADDITIONAL_CONTINENTS = 32


@dataclass
class RadialParams:
    core_radius: float = 50000.0
    border_radius: float = 150000.0
    transition_width: float = 30000.0
    ocean_floor_value: float = -1.0
    ocean_bias: float = 0.65
    radial_bias_strength: float = 0.2
    central_land_bump: float = 1.45
    central_land_radius: float = 9000.0
    additional_continents: float = 5.0   # int in Java; float here for CLI/sliders
    continent_spread: float = 60000.0
    continent_radius: float = 14000.0
    continent_bump: float = 1.45

    @classmethod
    def from_datapack(cls, path=_DEFAULT_MASK_JSON):
        try:
            with open(path) as fh:
                data = json.load(fh)
        except OSError:
            return cls()
        kwargs = {f.name: float(data[f.name]) for f in fields(cls) if f.name in data}
        return cls(**kwargs)


def _smoothstep_falloff(t):
    """1 at t=0 falling smoothly to 0 at t>=1 (RadialFalloffFunction.smoothstepFalloff)."""
    t = np.minimum(t, 1.0)
    return 1.0 - t * t * (3.0 - 2.0 * t)


def continent_centers(jitter, p):
    """
    1:1 with RadialFalloffFunction.buildCenters: additional continent centers
    evenly distributed by angle with per-seed jitter in angle, distance and
    radius. jitter is the (MAX_ADDITIONAL_CONTINENTS, 3) per-seed sample table.
    Returns (cx, cz, radius) float arrays of length additional_continents.
    """
    n = max(0, min(int(round(p.additional_continents)), jitter.shape[0]))
    if n == 0:
        return (np.zeros(0), np.zeros(0), np.zeros(0))
    i = np.arange(n, dtype=np.float64)
    angle = (2.0 * np.pi * i) / n + jitter[:n, 0] * (np.pi / n) * 0.8
    dist = p.continent_spread * (0.40 + 0.275 * (jitter[:n, 1] + 1.0))  # 40%-95%
    radius = p.continent_radius * (1.0 + 0.30 * jitter[:n, 2])          # +/-30%
    return np.cos(angle) * dist, np.sin(angle) * dist, radius


def radial_falloff(upstream, x, z, distance, p, jitter):
    """1:1 with RadialFalloffFunction.compute (multi-continent version)."""
    bumped = upstream + p.central_land_bump * _smoothstep_falloff(
        distance / p.central_land_radius)

    cx, cz, cr = continent_centers(jitter, p)
    for k in range(cx.size):
        d = np.sqrt((x - cx[k]) ** 2 + (z - cz[k]) ** 2)
        bumped = bumped + p.continent_bump * _smoothstep_falloff(d / cr[k])

    distance_factor = distance / p.core_radius
    effective_bias = p.ocean_bias + p.radial_bias_strength * distance_factor
    biased = bumped - effective_bias

    with np.errstate(over="ignore"):
        blend = 1.0 / (1.0 + np.exp((distance - p.border_radius) / p.transition_width))
    return blend * biased + (1.0 - blend) * p.ocean_floor_value


class ClimateSampler:
    """All seed-dependent state. Construction is cheap (~50 small RNG forks)."""

    def __init__(self, world_seed, large_biomes=False):
        self.seed = world_seed
        fork = Xoroshiro.from_seed(world_seed).fork_positional()

        def mk(name):
            first_octave, amplitudes = NOISE_PARAMETERS[name]
            return NormalNoise(from_hash_of(*fork, name), first_octave, amplitudes)

        self.offset = mk("minecraft:offset")
        self.temperature = mk("minecraft:temperature")
        self.vegetation = mk("minecraft:vegetation")
        self.continentalness = mk("minecraft:continentalness")
        self.erosion = mk("minecraft:erosion")
        self.ridge = mk("minecraft:ridge")
        self.tectonic = mk("terra_continental:tectonic")
        self.tectonic_break = mk("terra_continental:tectonic_break")
        self.continent_jitter = mk("terra_continental:continent_jitter")

        # large biomes: halve xz_scale for temperature and vegetation
        # matches temperature_large.json (0.05→0.025) and vegetation_large.json (0.06→0.03)
        self._temp_scale = TEMPERATURE_XZ_SCALE * (0.5 if large_biomes else 1.0)
        self._veg_scale = VEGETATION_XZ_SCALE * (0.5 if large_biomes else 1.0)

        # RadialFalloffFunction.jitterSample: fixed-coordinate samples of the
        # seeded jitter noise, widened toward [-1, 1]. Param-independent, so
        # precompute for every index the Java code could ever use.
        idx = np.arange(MAX_ADDITIONAL_CONTINENTS, dtype=np.float64)
        self.jitter = np.stack([
            np.clip(self.continent_jitter.get_value(
                idx * 9973.0 + ch * 4451.0 + 31.0, 0.0,
                idx * -6101.0 + ch * 2887.0 - 97.0) * 2.0, -1.0, 1.0)
            for ch in range(3)], axis=1)

    def _raw_block(self, x, z):
        """Raw fields for one flat float64 coordinate block."""
        # vanilla shift_x / shift_z (shift_a / shift_b over minecraft:offset)
        sx = self.offset.get_value(x * 0.25, 0.0, z * 0.25) * 4.0
        sz = self.offset.get_value(z * 0.25, x * 0.25, 0.0) * 4.0
        return {
            "x": x,
            "z": z,
            "distance": np.sqrt(x * x + z * z),
            "temperature": self.temperature.get_value(
                x * self._temp_scale + sx, 0.0, z * self._temp_scale + sz),
            "vegetation": self.vegetation.get_value(
                x * self._veg_scale + sx, 0.0, z * self._veg_scale + sz),
            "raw_continents": self.continentalness.get_value(
                x * CONTINENT_XZ_SCALE + sx, 0.0, z * CONTINENT_XZ_SCALE + sz),
            "erosion": self.erosion.get_value(
                x * EROSION_XZ_SCALE + sx, 0.0, z * EROSION_XZ_SCALE + sz),
            "raw_ridge": self.ridge.get_value(
                x * RIDGE_XZ_SCALE + sx, 0.0, z * RIDGE_XZ_SCALE + sz),
            "tectonic_potential": self.tectonic.get_value(
                x * TECTONIC_XZ_SCALE + sx, 0.0, z * TECTONIC_XZ_SCALE + sz),
            "tectonic_segments": self.tectonic_break.get_value(
                x * TECTONIC_XZ_SCALE + sx, 0.0, z * TECTONIC_XZ_SCALE + sz),
        }

    def raw_fields(self, bx, bz, progress=None, workers=None):
        """
        bx, bz: integer block coordinate arrays (quart-aligned here, matching
        Climate.Sampler quart sampling). Returns dict of float64 arrays with
        everything that does NOT depend on the radial-falloff parameters:
        temperature, vegetation, raw continentalness (pre-falloff), erosion,
        raw ridge noise, and distance from origin.

        Work is split into cache-friendly blocks across threads (numpy ufuncs
        release the GIL).
        """
        bx = (np.asarray(bx, dtype=np.int64) >> 2) << 2
        bz = (np.asarray(bz, dtype=np.int64) >> 2) << 2
        shape = bx.shape
        x = bx.astype(np.float64).ravel()
        z = bz.astype(np.float64).ravel()

        n = x.size
        workers = workers or min(16, os.cpu_count() or 4)
        block = max(16384, -(-n // (workers * 4)))
        starts = list(range(0, n, block))
        results = [None] * len(starts)
        done = [0]

        def run(idx):
            s = starts[idx]
            results[idx] = self._raw_block(x[s:s + block], z[s:s + block])
            done[0] += 1
            if progress:
                progress(done[0] / len(starts))

        if len(starts) == 1 or workers == 1:
            for i in range(len(starts)):
                run(i)
        else:
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=workers) as pool:
                list(pool.map(run, range(len(starts))))

        out = {
            key: np.concatenate([r[key] for r in results]).reshape(shape)
            for key in results[0]
        }
        out["jitter"] = self.jitter   # per-seed scalar table, not per-pixel
        return out


def tectonic_ridge(raw):
    """tectonic_ridge.json: 1 at plate boundaries (tectonic_potential = 0), 0 away."""
    return np.square(np.clip(
        1.0 - 6.667 * np.abs(raw["tectonic_potential"]), 0.0, 1.0))


def _inland_stretch(c):
    """Matches continent_inland_stretch.json spline: stretches inland continentalness
    from our compressed range (~-0.11 to +0.68) back to vanilla range (-0.11 to +1.0).
    Ocean/coast values (<= -0.11) are unchanged."""
    # Spline control points: (-1.5,-1.5), (-0.19,-0.19), (-0.11,-0.11), (0.35,0.55), (0.68,1.0)
    # Use numpy piecewise linear interpolation between these points
    xs = np.array([-1.5, -0.19, -0.11, 0.35, 0.68])
    ys = np.array([-1.5, -0.19, -0.11, 0.55, 1.0])
    return np.interp(c, xs, ys)


def finish_fields(raw, params):
    """
    Applies the radial falloff, the tectonic boost / mushroom dip, and the
    ridges formula (the parts that change when the sliders move).
    Returns (continents, erosion, weirdness).
    """
    base = radial_falloff(raw["raw_continents"], raw["x"], raw["z"],
                          raw["distance"], params, raw["jitter"])
    base = np.maximum(base, -1.02)                      # continent_base.json

    ridge = tectonic_ridge(raw)
    segments = raw["tectonic_segments"]
    chain = ridge * np.clip(2.5 * (segments + 0.1), 0.0, 1.0)   # tectonic_chain
    boost = 0.85 * chain * np.clip(-2.0 * (base + 0.11), 0.0, 1.0)
    dip = -1.2 * np.square(ridge
                           * np.clip(-4.0 * (base + 0.75), 0.0, 1.0)
                           * np.clip(8.0 * (segments - 0.55), 0.0, 1.0))
    continents = _inland_stretch(base + boost + dip)    # continent_inland_stretch

    # erosion.json: shifted_noise + (-0.55 * tectonic_ridge), floored at -1.0
    erosion = np.maximum(raw["erosion"] - 0.55 * ridge, -1.0)

    # ridges.json: flat_cache(cache_2d(scaled_weirdness)) = vanilla ridge noise
    weirdness = np.clip(raw["raw_ridge"], -1.0, 1.0)
    return continents, erosion, weirdness
