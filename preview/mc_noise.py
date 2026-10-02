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

    def noise(self, x, y, z):
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
        d000 = _GX[g000] * fx + _GY[g000] * fy + _GZ[g000] * fz
        d100 = _GX[g100] * fx1 + _GY[g100] * fy + _GZ[g100] * fz
        d010 = _GX[g010] * fx + _GY[g010] * fy1 + _GZ[g010] * fz
        d110 = _GX[g110] * fx1 + _GY[g110] * fy1 + _GZ[g110] * fz
        d001 = _GX[g001] * fx + _GY[g001] * fy + _GZ[g001] * fz1
        d101 = _GX[g101] * fx1 + _GY[g101] * fy + _GZ[g101] * fz1
        d011 = _GX[g011] * fx + _GY[g011] * fy1 + _GZ[g011] * fz1
        d111 = _GX[g111] * fx1 + _GY[g111] * fy1 + _GZ[g111] * fz1

        sx = _smoothstep(fx)
        sy = _smoothstep(fy)
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


# Vanilla 1.21.1 noise parameters (NoiseData.bootstrap)
NOISE_PARAMETERS = {
    "minecraft:offset": (-3, [1.0, 1.0, 1.0, 0.0]),
    "minecraft:temperature": (-10, [1.5, 0.0, 1.0, 0.0, 0.0, 0.0]),
    "minecraft:vegetation": (-8, [1.0, 1.0, 0.0, 0.0, 0.0, 0.0]),
    "minecraft:continentalness": (-9, [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0]),
    "minecraft:erosion": (-9, [1.0, 1.0, 0.0, 1.0, 1.0]),
    "minecraft:ridge": (-7, [1.0, 2.0, 1.0, 0.0, 0.0, 0.0]),
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
    return NormalNoise(from_hash_of(fork_lo, fork_hi, name), first_octave, amplitudes)
