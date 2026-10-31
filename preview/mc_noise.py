"""
Exact Python/numpy port of Minecraft 1.21.1's worldgen noise stack:

    Xoroshiro128PlusPlus  ->  XoroshiroRandomSource (+ positional fork / md5 hash)
    ImprovedNoise         ->  one octave of Ken Perlin's improved noise
    PerlinNoise           ->  multi-octave sum ("new factory" path used by the noise router)
    NormalNoise           ->  pair of PerlinNoise instances, value-factor normalized

Scalar RNG / seeding is plain Python int math (64-bit masked); noise evaluation is
vectorized over numpy float64 arrays. Verified bit-for-bit against the real
decompiled 1.21.1 classes (see test_parity.py).
"""

import hashlib

import os as _os

import numpy as np

_M64 = (1 << 64) - 1
_SILVER_RATIO_64 = 0x6A09E667F3BCC909   # 7640891576956012809
_GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15   # -7046029254386353131 as unsigned

# Java: nextBits(53) * 1.110223E-16F  (a *float* literal widened to double)
_DOUBLE_UNIT = float(np.float32(1.110223e-16))


def _mix_stafford13(z):
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _M64
    return z ^ (z >> 31)


def upgrade_seed_to_128bit(seed):
    """RandomSupport.upgradeSeedTo128bit: (lo, hi) both Stafford-mixed."""
    seed &= _M64
    lo = seed ^ _SILVER_RATIO_64
    hi = (lo + _GOLDEN_RATIO_64) & _M64
    return _mix_stafford13(lo), _mix_stafford13(hi)


def seed_from_hash(name):
    """RandomSupport.seedFromHashOf: md5(name) -> two big-endian longs."""
    digest = hashlib.md5(name.encode("utf-8")).digest()
    return int.from_bytes(digest[0:8], "big"), int.from_bytes(digest[8:16], "big")


def _rotl(x, k):
    return ((x << k) | (x >> (64 - k))) & _M64


class Xoroshiro:
    """Xoroshiro128PlusPlus + the XoroshiroRandomSource draw methods we need."""

    def __init__(self, lo, hi):
        self.lo = lo & _M64
        self.hi = hi & _M64
        if self.lo == 0 and self.hi == 0:
            self.lo = _GOLDEN_RATIO_64
            self.hi = _SILVER_RATIO_64

    @classmethod
    def from_seed(cls, seed):
        return cls(*upgrade_seed_to_128bit(seed))

    def next_long(self):
        """Returns the next 64-bit value as an *unsigned* Python int."""
        lo, hi = self.lo, self.hi
        result = (_rotl((lo + hi) & _M64, 17) + lo) & _M64
        hi ^= lo
        self.lo = _rotl(lo, 49) ^ hi ^ ((hi << 21) & _M64)
        self.hi = _rotl(hi, 28)
        return result

    def next_long_signed(self):
        v = self.next_long()
        return v - (1 << 64) if v >= (1 << 63) else v

    def next_double(self):
        return (self.next_long() >> 11) * _DOUBLE_UNIT

    def next_int_bounded(self, bound):
        """XoroshiroRandomSource.nextInt(int bound) - Lemire rejection sampling."""
        i = self.next_long() & 0xFFFFFFFF
        j = i * bound
        k = j & 0xFFFFFFFF
        if k < bound:
            l = (1 << 32) % bound
            while k < l:
                i = self.next_long() & 0xFFFFFFFF
                j = i * bound
                k = j & 0xFFFFFFFF
        return j >> 32

    def fork_positional(self):
        """Returns (seedLo, seedHi) of the positional factory."""
        return self.next_long(), self.next_long()


def from_hash_of(fork_lo, fork_hi, name):
    """XoroshiroPositionalRandomFactory.fromHashOf(name)."""
    lo, hi = seed_from_hash(name)
    return Xoroshiro(lo ^ fork_lo, hi ^ fork_hi)


# SimplexNoise.GRADIENT - 16 gradient vectors, indexed by perm & 15
_GRAD = np.array(
    [
        [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
        [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
        [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
        [1, 1, 0], [0, -1, 1], [-1, 1, 0], [0, -1, -1],
    ],
    dtype=np.float64,
)
_GX = _GRAD[:, 0].copy()
_GY = _GRAD[:, 1].copy()
_GZ = _GRAD[:, 2].copy()


# Mth.floor(m / yScale + 1.0E-7F): a float literal widened to double
_SMEAR_EPS = float(np.float32(1.0e-7))


def _smoothstep(t):
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)


class ImprovedNoise:
    """One octave. noise() is vectorized; x/y/z may be arrays or scalars."""

    def __init__(self, rng):
        self.xo = rng.next_double() * 256.0
        self.yo = rng.next_double() * 256.0
        self.zo = rng.next_double() * 256.0
        p = list(range(256))
        for i in range(256):
            j = rng.next_int_bounded(256 - i)
            p[i], p[i + j] = p[i + j], p[i]
        self.p = np.array(p, dtype=np.int32)

    def noise(self, x, y, z, y_scale=0.0, y_max=0.0):
        """y_scale/y_max are the legacy "smear" arguments BlendedNoise passes: the gradient
        dot product uses a y snapped down to multiples of y_scale, the fade curve the true y."""
        d0 = np.asarray(x, dtype=np.float64) + self.xo
        d1 = np.asarray(y, dtype=np.float64) + self.yo
        d2 = np.asarray(z, dtype=np.float64) + self.zo
        xi = np.floor(d0)
        yi = np.floor(d1)
        zi = np.floor(d2)
        fx = d0 - xi
        fy = d1 - yi
        fz = d2 - zi
        # wrap() keeps |coords| < 2^25, so int32 index math is safe and faster
        gx = xi.astype(np.int32)
        gy = yi.astype(np.int32)
        gz = zi.astype(np.int32)

        p = self.p

        def pp(idx):
            return p[idx & 255]

        a = pp(gx)
        b = pp(gx + 1)
        aa = pp(a + gy)       # Java j1
        ab = pp(a + gy + 1)   # Java k1
        ba = pp(b + gy)       # Java l1
        bb = pp(b + gy + 1)   # Java i2

        g000 = pp(aa + gz) & 15
        g100 = pp(ba + gz) & 15
        g010 = pp(ab + gz) & 15
        g110 = pp(bb + gz) & 15
        g001 = pp(aa + gz + 1) & 15
        g101 = pp(ba + gz + 1) & 15
        g011 = pp(ab + gz + 1) & 15
        g111 = pp(bb + gz + 1) & 15

        fx1 = fx - 1.0
        fy1 = fy - 1.0
        fz1 = fz - 1.0
        if np.any(np.asarray(y_scale) != 0.0):
            m = np.where((np.asarray(y_max) >= 0.0) & (np.asarray(y_max) < fy), y_max, fy)
            fy_dot = fy - np.floor(m / y_scale + _SMEAR_EPS) * y_scale
            fy_s = fy
            fy, fy1 = fy_dot, fy_dot - 1.0
        else:
            fy_s = fy
        d000 = _GX[g000] * fx + _GY[g000] * fy + _GZ[g000] * fz
        d100 = _GX[g100] * fx1 + _GY[g100] * fy + _GZ[g100] * fz
        d010 = _GX[g010] * fx + _GY[g010] * fy1 + _GZ[g010] * fz
        d110 = _GX[g110] * fx1 + _GY[g110] * fy1 + _GZ[g110] * fz
        d001 = _GX[g001] * fx + _GY[g001] * fy + _GZ[g001] * fz1
        d101 = _GX[g101] * fx1 + _GY[g101] * fy + _GZ[g101] * fz1
        d011 = _GX[g011] * fx + _GY[g011] * fy1 + _GZ[g011] * fz1
        d111 = _GX[g111] * fx1 + _GY[g111] * fy1 + _GZ[g111] * fz1

        sx = _smoothstep(fx)
        sy = _smoothstep(fy_s)
        sz = _smoothstep(fz)

        # Mth.lerp3 (lerp along x, then y, then z)
        x00 = d000 + sx * (d100 - d000)
        x10 = d010 + sx * (d110 - d010)
        x01 = d001 + sx * (d101 - d001)
        x11 = d011 + sx * (d111 - d011)
        y0 = x00 + sy * (x10 - x00)
        y1 = x01 + sy * (x11 - x01)
        return y0 + sz * (y1 - y0)


def _wrap(value):
    """PerlinNoise.wrap - keeps inputs in +/-2^25 to preserve precision."""
    return value - np.floor(value / 3.3554432e7 + 0.5) * 3.3554432e7


class PerlinNoise:
    """Multi-octave noise, "new factory" path (forkPositional + octave_N hashes)."""

    def __init__(self, rng, first_octave, amplitudes):
        self.first_octave = first_octave
        self.amplitudes = list(amplitudes)
        fork_lo, fork_hi = rng.fork_positional()
        self.levels = []
        for k, amp in enumerate(self.amplitudes):
            if amp != 0.0:
                octave = first_octave + k
                noise = ImprovedNoise(from_hash_of(fork_lo, fork_hi, f"octave_{octave}"))
                self.levels.append((k, amp, noise))
        n = len(self.amplitudes)
        self.lowest_freq_input = 2.0 ** first_octave
        self.lowest_freq_value = 2.0 ** (n - 1) / (2.0 ** n - 1.0)

    def get_value(self, x, y, z):
        result = None
        for k, amp, noise in self.levels:
            # exact: repeated doubling/halving == multiplying by 2**k (powers of two)
            f_in = self.lowest_freq_input * (2.0 ** k)
            f_val = self.lowest_freq_value / (2.0 ** k)
            term = amp * noise.noise(_wrap(x * f_in), _wrap(y * f_in), _wrap(z * f_in)) * f_val
            result = term if result is None else result + term
        return result if result is not None else 0.0


class NormalNoise:
    """Sum of two PerlinNoise samplers, second at INPUT_FACTOR-scaled coords."""

    INPUT_FACTOR = 1.0181268882175227

    def __init__(self, rng, first_octave, amplitudes):
        self.first = PerlinNoise(rng, first_octave, amplitudes)
        self.second = PerlinNoise(rng, first_octave, amplitudes)
        nonzero = [i for i, a in enumerate(amplitudes) if a != 0.0]
        spread = max(nonzero) - min(nonzero)
        expected_deviation = 0.1 * (1.0 + 1.0 / (spread + 1))
        self.value_factor = 0.16666666666666666 / expected_deviation

    def get_value(self, x, y, z):
        f = self.INPUT_FACTOR
        return (self.first.get_value(x, y, z)
                + self.second.get_value(x * f, y * f, z * f)) * self.value_factor


class LegacyPerlinNoise:
    """PerlinNoise.createLegacyForBlendedNoise: octaves drawn in sequence from one random
    (useNewFactory = false), all amplitudes 1. getOctaveNoise(0) is the first one drawn."""

    def __init__(self, rng, first_octave, count):
        j = -first_octave
        levels = [None] * count
        levels[j] = ImprovedNoise(rng)
        for lx in range(j - 1, -1, -1):
            levels[lx] = ImprovedNoise(rng)
        self.levels = levels

    def octave(self, i):
        return self.levels[len(self.levels) - 1 - i]


class BlendedNoise:
    """minecraft:old_blended_noise (BlendedNoise.compute, 1.21.1), vectorised. Seeded from
    the world's positional random at "minecraft:terrain", as RandomState does."""

    def __init__(self, world_seed, xz_scale, y_scale, xz_factor, y_factor, smear):
        fork_lo, fork_hi = Xoroshiro.from_seed(world_seed).fork_positional()
        rng = from_hash_of(fork_lo, fork_hi, "minecraft:terrain")
        self.min_limit = LegacyPerlinNoise(rng, -15, 16)
        self.max_limit = LegacyPerlinNoise(rng, -15, 16)
        self.main = LegacyPerlinNoise(rng, -7, 8)
        self.xz_mul = 684.412 * xz_scale
        self.y_mul = 684.412 * y_scale
        self.xz_factor = xz_factor
        self.y_factor = y_factor
        self.smear = smear

    def compute(self, x, y, z):
        d = np.asarray(x, dtype=np.float64) * self.xz_mul
        e = np.asarray(y, dtype=np.float64) * self.y_mul
        f = np.asarray(z, dtype=np.float64) * self.xz_mul
        g, h, i = d / self.xz_factor, e / self.y_factor, f / self.xz_factor
        j = self.y_mul * self.smear
        k = j / self.y_factor
        n = 0.0
        o = 1.0
        for p in range(8):
            n = n + self.main.octave(p).noise(_wrap(g * o), _wrap(h * o), _wrap(i * o), k * o, h * o) / o
            o /= 2.0
        q = (n / 10.0 + 1.0) / 2.0
        lo = 0.0
        hi = 0.0
        o = 1.0
        for r in range(16):
            s, t, u = _wrap(d * o), _wrap(e * o), _wrap(f * o)
            lo = lo + self.min_limit.octave(r).noise(s, t, u, j * o, e * o) / o
            hi = hi + self.max_limit.octave(r).noise(s, t, u, j * o, e * o) / o
            o /= 2.0
        # Mth.clampedLerp; the game skips the side it won't use, which changes nothing here
        a, b = lo / 512.0, hi / 512.0
        return np.where(q <= 0.0, a, np.where(q >= 1.0, b, a + q * (b - a))) / 128.0


# Vanilla 1.21.1 noise parameters (NoiseData.bootstrap)
NOISE_PARAMETERS = {
    "minecraft:offset": (-3, [1.0, 1.0, 1.0, 0.0]),
    "minecraft:temperature": (-10, [1.5, 0.0, 1.0, 0.0, 0.0, 0.0]),
    "minecraft:vegetation": (-8, [1.0, 1.0, 0.0, 0.0, 0.0, 0.0]),
    "minecraft:continentalness": (-9, [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0]),
    "minecraft:erosion": (-9, [1.0, 1.0, 0.0, 1.0, 1.0]),
    "minecraft:ridge": (-7, [1.0, 2.0, 1.0, 0.0, 0.0, 0.0]),
    # terrain (continental_terrain.py): Tectonic's sloped_cheese reads vanilla's jagged noise
    "minecraft:jagged": (-16, [1.0] * 16),
    # Terra Continental custom noises (data/terra_continental/worldgen/noise/*.json)
    "terra_continental:tectonic": (-10, [1.0, 0.6, 0.3]),
    "terra_continental:tectonic_break": (-8, [1.0, 0.7]),
    "terra_continental:continent_jitter": (-6, [1.0]),
    "terra_continental:river_main": (-7, [1.0, 0.7, 0.35]),
    "terra_continental:river_tributary": (-6, [1.0, 0.8, 0.4]),
    "terra_continental:river_creek": (-6, [1.0, 0.9, 0.5]),
}


def create_router_noise(world_seed, name):
    """
    Reproduces RandomState: world seed -> XoroshiroRandomSource.forkPositional()
    -> fromHashOf(noise id) -> NormalNoise.create(...).
    """
    fork_lo, fork_hi = Xoroshiro.from_seed(world_seed).fork_positional()
    first_octave, amplitudes = NOISE_PARAMETERS[name]
    return NormalNoise(from_hash_of(fork_lo, fork_hi, seed_name(name)), first_octave, amplitudes)


def _renamed_noises():
    path = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "..", "src", "main", "resources",
                         "terra_continental_renamed_noises.txt")
    with open(path, encoding="utf-8") as fh:
        return {ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")}


_RENAMED = None


def seed_name(name):
    """The id a noise is seeded under, as RenamedNoises.seedId in the mod. The terrain's noises
    were tectonic:<path> until 0.10.0 and keep those ids' seeds (parameter/<x> as minecraft:<x>,
    which is how Tectonic seeded them; missing that drew unrelated erosion and ridges, terrain
    parity 2026-10-23)."""
    global _RENAMED
    if _RENAMED is None:
        _RENAMED = _renamed_noises()
    ns, _, path = name.partition(":")
    if ns == "terra_continental" and path in _RENAMED:
        if path.startswith("parameter/"):
            return "minecraft:" + path[len("parameter/"):]
        return "tectonic:" + path
    return name
