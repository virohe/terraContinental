"""
Vectorised interpreter for Minecraft 1.21.1 density-function JSON.

Why an interpreter instead of hand-porting the maths: the previous simulator hard-coded
Terra Continental's pipeline, and when Tectonic took over continentalness/erosion/ridges the
Python model silently kept reporting the old world. Executing the *actual datapack JSON*
that the game executes removes that whole class of drift — change a Tectonic config value or
a datapack file and this follows automatically.

Two uses:
- the biome path (temperature, humidity, continentalness, erosion, weirdness) over an (X, Z)
  grid at y=0, depth pinned to 0 as BiomeMapCommand.surfaceSampler does;
- terrain (continental_terrain.py): the same graph at 3-D points. A Context then carries Y and a
  `full` context over every column, and any y-independent node is computed once there and
  gathered, so a column search re-evaluates only the parts that change with y.
Caves, aquifers and ore veins are unsupported: they carve below the surface.

Values are numpy float64 arrays shaped like the input grid, except inside spline evaluation
which is done in float32 to match Mth/CubicSpline exactly.
"""

import json as _json
import os as _os

import numpy as np

import mc_noise


class DensityFunctionError(RuntimeError):
    """Raised for an unsupported node type, so gaps fail loudly instead of silently wrong."""


# ---------------------------------------------------------------------------
# CubicSpline — port of net.minecraft.util.CubicSpline.Multipoint.apply
# ---------------------------------------------------------------------------

def _lerp32(t, a, b):
    return a + t * (b - a)


def eval_spline(coord, locations, values, derivatives):
    """
    coord       : float32 array (the spline's coordinate, already evaluated)
    locations   : float32 array, ascending (duplicates allowed)
    values      : list of float32 arrays, one per location (nested splines pre-evaluated)
    derivatives : float32 array

    Mirrors MC exactly: below the first location and at/after the last it extends
    linearly using that endpoint's derivative (a zero derivative means "hold flat");
    in between it is the cubic Hermite-style blend MC uses.
    """
    loc = np.asarray(locations, dtype=np.float32)
    der = np.asarray(derivatives, dtype=np.float32)
    f = np.asarray(coord, dtype=np.float32)
    last = len(loc) - 1

    # findIntervalStart: binarySearch for the first index with f < locations[i], minus 1.
    idx = np.searchsorted(loc, f, side="right") - 1

    out = np.empty(f.shape, dtype=np.float32)

    # values[] are full-grid arrays; flatten once so an interval index can gather per cell.
    stacked = np.stack([np.broadcast_to(v, f.shape).ravel() for v in values])

    def linear_extend(mask, i):
        if not np.any(mask):
            return
        d = der[i]
        v = stacked[i][mask.ravel()]
        out[mask] = v if d == 0.0 else v + d * (f[mask] - loc[i])

    linear_extend(idx < 0, 0)
    linear_extend(idx == last, last)

    mid = (idx >= 0) & (idx < last)
    if np.any(mid):
        i = idx[mid]
        flat_mid = np.flatnonzero(mid.ravel())
        f1 = loc[i]
        f2 = loc[i + 1]
        t = (f[mid] - f1) / (f2 - f1)
        v0 = stacked[i, flat_mid]
        v1 = stacked[i + 1, flat_mid]
        a = der[i] * (f2 - f1) - (v1 - v0)
        b = -der[i + 1] * (f2 - f1) + (v1 - v0)
        out[mid] = _lerp32(t, v0, v1) + t * (1.0 - t) * _lerp32(t, a, b)

    return out


# ---------------------------------------------------------------------------
# Evaluation context
# ---------------------------------------------------------------------------

class Context:
    """Holds the sample grid, the world seed, and caches noise + named functions."""

    def __init__(self, registry, seed, X, Z, custom=None, Y=0.0, full=None, sel=None):
        self.reg = registry
        self.seed = seed
        self.X = np.asarray(X, dtype=np.float64)
        self.Z = np.asarray(Z, dtype=np.float64)
        self.shape = self.X.shape
        self.Y = np.broadcast_to(np.asarray(Y, dtype=np.float64), self.shape)
        self.custom = custom or {}
        # 3-D evaluation: `full` is a y=0 context over every column, `sel` picks this
        # context's columns out of it. y-independent nodes are evaluated there once.
        self.full = full
        self.sel = sel
        self._noise = full._noise if full is not None else {}
        self._named = {}
        self._memo = {}
        self._shift = None

    def noise(self, name):
        if name not in self._noise:
            self._noise[name] = mc_noise.create_router_noise(self.seed, name)
        return self._noise[name]

    def shift(self):
        """minecraft:shift_x / shift_z — the shared offset noise applied to climate samples."""
        if self._shift is None:
            # ShiftA / ShiftB: offset.getValue(x * 0.25, 0, z * 0.25) * 4 and (z, x, 0). The
            # 0.25 was missing until 2026-10-23 (terrain parity found it; mc_climate had it).
            off = self.noise("minecraft:offset")
            zeros = np.zeros(self.shape)
            sx = off.get_value(self.X * 0.25, zeros, self.Z * 0.25) * 4.0
            sz = off.get_value(self.Z * 0.25, self.X * 0.25, zeros) * 4.0
            self._shift = (sx, sz)
        return self._shift

    def const(self, v):
        return np.full(self.shape, float(v), dtype=np.float64)

    def at(self, sel, Y):
        """A context over columns `sel` of this (full, y=0) context at heights Y."""
        return Context(self.reg, self.seed, self.X[sel], self.Z[sel], self.custom, Y,
                       full=self, sel=sel)

    def full_value(self, node):
        """A y-independent node's value on this context's columns, computed once."""
        f = self.full
        key = node if isinstance(node, str) else id(node)
        v = f._memo.get(key)
        if v is None:
            v = np.broadcast_to(evaluate(node, f), f.shape)
            f._memo[key] = v
        return v[self.sel]

    def blended(self, node):
        key = ("_blended", id(node))
        if key not in self._noise:
            self._noise[key] = mc_noise.BlendedNoise(
                self.seed, float(node["xz_scale"]), float(node["y_scale"]),
                float(node["xz_factor"]), float(node["y_factor"]),
                float(node["smear_scale_multiplier"]))
        return self._noise[key]


# ---------------------------------------------------------------------------
# Registry: datapack sources, later sources override earlier ones
# ---------------------------------------------------------------------------

class Registry:
    def __init__(self):
        self._df = {}      # "namespace:path" -> parsed json
        self._noise_json = {}

    def add_jar(self, jar_path, prefix=""):
        """
        Add a datapack rooted at `prefix` inside a jar/zip, e.g. a deployed mod jar's built-in
        pack, so the interpreter sees exactly the data that build loads.
        """
        import zipfile
        with zipfile.ZipFile(jar_path) as zf:
            for info in zf.infolist():
                name = info.filename
                if not name.startswith(prefix) or not name.endswith(".json"):
                    continue
                rel = name[len(prefix):]
                parts = rel.split("/")
                # data/<ns>/worldgen/<kind>/<path>.json
                if len(parts) < 5 or parts[0] != "data" or parts[2] != "worldgen":
                    continue
                kind = parts[3]
                table = {"density_function": self._df, "noise": self._noise_json}.get(kind)
                if table is None:
                    continue
                key = f"{parts[1]}:" + "/".join(parts[4:])[:-5]
                try:
                    table[key] = _json.loads(zf.read(name).decode("utf-8-sig"))
                except Exception:
                    pass
        return self

    def add_source(self, root):
        """Add a datapack root (a directory containing data/<ns>/worldgen/...)."""
        data = _os.path.join(root, "data")
        if not _os.path.isdir(data):
            return self
        for ns in sorted(_os.listdir(data)):
            for kind, table in (("density_function", self._df), ("noise", self._noise_json)):
                base = _os.path.join(data, ns, "worldgen", kind)
                if not _os.path.isdir(base):
                    continue
                for dirpath, _dirs, files in _os.walk(base):
                    for fn in files:
                        if not fn.endswith(".json"):
                            continue
                        rel = _os.path.relpath(_os.path.join(dirpath, fn), base)
                        key = f"{ns}:" + rel[:-5].replace(_os.sep, "/")
                        with open(_os.path.join(dirpath, fn), encoding="utf-8-sig") as fh:
                            try:
                                table[key] = _json.load(fh)
                            except Exception:
                                pass  # non-worldgen JSON in tree; ignore
        return self

    def df(self, name):
        if name not in self._df:
            raise DensityFunctionError(f"density function not found: {name}")
        return self._df[name]

    def noise_params(self, name):
        return self._noise_json.get(name)


# ---------------------------------------------------------------------------
# Evaluation
# ---------------------------------------------------------------------------

def _invert(a):
    with np.errstate(divide="ignore", invalid="ignore"):
        return 1.0 / a


_UNARY = {
    # Tectonic ships an `invert` node (1/x); it appears under both namespaces in its data.
    "minecraft:invert": _invert,
    "terra_continental:invert": _invert,
    "terra_continental:invert": _invert,      # tectonic:invert, absorbed into terrain_base
    "minecraft:abs": np.abs,
    "minecraft:square": lambda a: a * a,
    "minecraft:cube": lambda a: a * a * a,
    "minecraft:half_negative": lambda a: np.where(a > 0, a, a * 0.5),
    "minecraft:quarter_negative": lambda a: np.where(a > 0, a, a * 0.25),
}


def _squeeze(a):
    c = np.clip(a, -1.0, 1.0)
    return c / 2.0 - c * c * c / 24.0


# Named functions whose value depends on y even though no y-node appears in their JSON.
Y_NAMES = {"minecraft:y"}

_Y_TYPES = {"minecraft:y_clamped_gradient", "minecraft:old_blended_noise"}

_flat_memo = {}


def is_flat(node, reg, custom):
    """True when the node's value does not depend on y (so one value per column).
    flat_cache always qualifies: the game fills it at y=0 on quart positions."""
    if isinstance(node, (int, float)) or node is None:
        return True
    if isinstance(node, str):
        key = (id(reg), node)
        if key in _flat_memo:
            return _flat_memo[key]
        if node in custom:
            r = node not in Y_NAMES
        elif node in reg._df:
            _flat_memo[key] = True       # cycle guard
            r = is_flat(reg._df[node], reg, custom)
        else:
            r = True                     # a noise id or config key, not a function
        _flat_memo[key] = r
        return r
    if isinstance(node, list):
        return all(is_flat(v, reg, custom) for v in node)
    if not isinstance(node, dict):
        return True
    key = (id(reg), id(node))
    if key in _flat_memo:
        return _flat_memo[key]
    t = node.get("type")
    if t in custom:
        r = all(is_flat(v, reg, custom) for k, v in node.items()
                if k not in ("type", "noise", "key", "jitter_noise"))
    elif t == "minecraft:flat_cache":
        r = True
    elif t in _Y_TYPES:
        r = False
    elif t in ("minecraft:noise", "minecraft:shifted_noise") and float(node.get("y_scale", 0.0)) != 0.0:
        r = False
    elif "coordinate" in node and "points" in node:      # a bare spline object
        r = is_flat(node["coordinate"], reg, custom) and all(
            is_flat(p["value"], reg, custom) for p in node["points"])
    else:
        r = all(is_flat(v, reg, custom) for k, v in node.items()
                if k not in ("type", "noise", "key", "jitter_noise"))
    _flat_memo[key] = r
    return r


def evaluate(node, ctx):
    """Evaluate a density-function node (json dict, number, or 'ns:name' reference)."""
    if isinstance(node, (int, float)):
        return ctx.const(node)

    if ctx.full is not None and is_flat(node, ctx.reg, ctx.custom):
        return ctx.full_value(node)

    if isinstance(node, str):
        if node in ctx._named:
            return ctx._named[node]
        if node in ctx.custom:
            val = ctx.custom[node](ctx)
        else:
            val = evaluate(ctx.reg.df(node), ctx)
        # A context's points are fixed, so caching by name is safe; on the biome path it is
        # what flat_cache/cache_2d mean.
        ctx._named[node] = val
        return val

    t = node.get("type")

    if t in ctx.custom:
        return ctx.custom[t](ctx, node)

    if t == "minecraft:flat_cache" and ctx.full is not None:
        return ctx.full_value(node["argument"])          # filled at y=0 in game

    # Wrappers that are pure caching / interpolation in-game: identity at given points (the
    # terrain solver does the cell interpolation itself).
    if t in ("minecraft:flat_cache", "minecraft:cache_2d", "minecraft:cache_once",
             "minecraft:cache_all_in_cell", "minecraft:interpolated"):
        return evaluate(node["argument"], ctx)

    if t == "minecraft:constant":
        return ctx.const(node["argument"])

    if t in _UNARY:
        return _UNARY[t](evaluate(node["argument"], ctx))

    if t == "minecraft:squeeze":
        return _squeeze(evaluate(node["argument"], ctx))

    if t == "minecraft:add":
        return evaluate(node["argument1"], ctx) + evaluate(node["argument2"], ctx)
    if t == "minecraft:mul":
        return evaluate(node["argument1"], ctx) * evaluate(node["argument2"], ctx)
    if t == "minecraft:min":
        return np.minimum(evaluate(node["argument1"], ctx), evaluate(node["argument2"], ctx))
    if t == "minecraft:max":
        return np.maximum(evaluate(node["argument1"], ctx), evaluate(node["argument2"], ctx))

    if t == "minecraft:clamp":
        return np.clip(evaluate(node["input"], ctx),
                       float(node["min"]), float(node["max"]))

    if t == "minecraft:range_choice":
        inp = evaluate(node["input"], ctx)
        lo = float(node["min_inclusive"])
        hi = float(node["max_exclusive"])
        return np.where((inp >= lo) & (inp < hi),
                        evaluate(node["when_in_range"], ctx),
                        evaluate(node["when_out_of_range"], ctx))

    if t == "minecraft:y_clamped_gradient":
        # Y is 0 on the biome path (depth pinned to 0 by the caller).
        y0, y1 = float(node["from_y"]), float(node["to_y"])
        v0, v1 = float(node["from_value"]), float(node["to_value"])
        tt = np.clip((ctx.Y - y0) / (y1 - y0), 0.0, 1.0)
        return v0 + tt * (v1 - v0)

    if t == "minecraft:noise":
        return _noise_node(ctx, node["noise"], float(node.get("xz_scale", 1.0)),
                           float(node.get("y_scale", 0.0)), None, None, None)

    if t == "minecraft:shifted_noise":
        sx = evaluate(node["shift_x"], ctx)
        sz = evaluate(node["shift_z"], ctx)
        sy = evaluate(node["shift_y"], ctx) if "shift_y" in node else None
        return _noise_node(ctx, node["noise"], float(node.get("xz_scale", 1.0)),
                           float(node.get("y_scale", 0.0)), sx, sy, sz)

    if t == "minecraft:old_blended_noise":
        return ctx.blended(node).compute(ctx.X, ctx.Y, ctx.Z)

    if t in ("minecraft:shift_a", "minecraft:shift_b", "minecraft:shift"):
        sx, sz = ctx.shift()
        return sx if t != "minecraft:shift_b" else sz

    if t == "minecraft:spline":
        return _spline_node(ctx, node["spline"]).astype(np.float64)

    if t in ("minecraft:blend_alpha",):
        return ctx.const(1.0)          # no blending outside chunk-edge stitching
    if t == "minecraft:blend_density":
        return evaluate(node["argument"], ctx)        # identity away from old-chunk seams
    if t in ("minecraft:blend_offset", "minecraft:beardifier"):
        return ctx.const(0.0)

    raise DensityFunctionError(f"unsupported density function type: {t}")


def _noise_node(ctx, noise_name, xz_scale, y_scale, shift_x, shift_y, shift_z):
    n = ctx.noise(noise_name)
    x = ctx.X * xz_scale + (shift_x if shift_x is not None else 0.0)
    y = ctx.Y * y_scale + (shift_y if shift_y is not None else 0.0)
    z = ctx.Z * xz_scale + (shift_z if shift_z is not None else 0.0)
    return np.broadcast_to(n.get_value(x, y, z), ctx.shape)


def _spline_node(ctx, spline):
    """A spline node's `value` entries may themselves be splines or plain numbers."""
    if isinstance(spline, (int, float)):
        return np.full(ctx.shape, np.float32(spline), dtype=np.float32)

    coord = evaluate(spline["coordinate"], ctx).astype(np.float32)
    pts = spline["points"]
    locations = np.array([float(p["location"]) for p in pts], dtype=np.float32)
    derivatives = np.array([float(p.get("derivative", 0.0)) for p in pts], dtype=np.float32)
    values = [_spline_node(ctx, p["value"]) for p in pts]
    values = [np.broadcast_to(v, ctx.shape).astype(np.float32) for v in values]
    return eval_spline(coord, locations, values, derivatives)
