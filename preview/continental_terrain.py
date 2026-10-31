"""
Offline terrain heights for Terra Continental's terrain, without running the game.

Why: a rig run (tools/python/terrain_sweep.py) takes minutes per setting and shows one place on
one seed. Tuning the look of mountains and coasts needs many seeds and many settings, so this
evaluates the same density-function JSON the game loads (this tree's mod data and
resourcepacks/terrain) plus terra_continental-common.toml, and solves each column's surface the
way the game does:

- the terrain density is the `interpolated` argument of the router's final_density, sampled on
  the noise cells' corners (every 4 blocks across, every 8 up from min_y) and interpolated
  in between, which is where the terrain's staircasing comes from;
- a column's surface is the top of its highest solid run, found by a binary search over the
  cell corners plus a short scan above for overhangs;
- getBaseHeight(WORLD_SURFACE_WG) is that top + 1, or the sea level where water covers it.

Not modelled: caves, noodles, underground rivers and lava tunnels (all carve below the surface;
a cave mouth that breaks the surface is missed), aquifer lakes above sea level, chunk blending.
test_terrain_parity.py measures how close this comes to a rig's TerrainAudit grid.

    sim = TerrainSim(instance_or_rig_dir, packs=["preview/out/packs/experiment"])
    H = sim.heights(seed, X, Z)          # block coordinates, any shape
"""

import json as _json
import os as _os

import numpy as np

import mc_climate
import mc_dfi
import continental_climate

CELL_W = 4        # size_horizontal 1
CELL_H = 8        # size_vertical 2
SEA_LEVEL = 63

# Vanilla's built-in entry (not in any jar's data): the 3-D roughness the terrain adds on land.
_BASE_3D_NOISE = {"type": "minecraft:old_blended_noise", "xz_scale": 0.25, "y_scale": 0.125,
                  "xz_factor": 80.0, "y_factor": 160.0, "smear_scale_multiplier": 8.0}

# Cave terms inside the interpolated density: held open-air-free so min(base_terrain, caves)
# is the terrain itself.
_NO_CAVES = {"terra_continental:caves": 64.0}

_OVERHANG_SCAN = 4     # cell corners scanned above a found surface for a higher solid run
# giant_peaks spurs, in the detail noise's input units (terra_continental:tectonic, wavelength
# ~1,024): the angle on a circle of this radius gives ~10 spurs around a massif, and the radius
# is stretched so a spur meanders about once per 650 blocks. GiantPeaksFunction has the same.
_SPUR_ARC = 1630.0
_SPUR_RADIAL = 1.6
_SPUR_BEND = 0.6                        # radians of angle bend at noise 1
_SPUR_START, _SPUR_FADE = 0.12, 0.35    # spurs fade in from t 0.12 to 0.47 of the radius
_TEXTURE_SCALE = 4.0                    # flank knolls: wavelength ~256 blocks
_GRAIN_SCALE = 16.0                     # finer flank bumps: wavelength ~64 blocks


def _find_interpolated(node):
    if isinstance(node, dict):
        if node.get("type") == "minecraft:interpolated":
            return node["argument"]
        for v in node.values():
            r = _find_interpolated(v)
            if r is not None:
                return r
    return None


class TerrainSim(continental_climate.ClimateSim):
    def __init__(self, instance=continental_climate.INSTANCE, packs=()):
        """packs: extra datapack roots loaded above the terrain pack (shape experiments,
        shape_pack.py), the way a later version of the pack would override it."""
        super().__init__(instance)
        for root in packs:
            # A pack that isn't there used to be skipped silently, and its variant came out
            # identical to the baseline (a Git Bash /c/... path under Windows Python, 2026-10-30).
            if not _os.path.isdir(_os.path.join(root, "data")):
                raise FileNotFoundError(f"pack has no data/ folder: {root}")
            self.reg.add_source(root)
        # Noises a pack defines (or redefines) reach the noise factory too, as the base packs' do.
        for name, spec in self.reg._noise_json.items():
            if "firstOctave" in spec and "amplitudes" in spec:
                continental_climate.mc_noise.NOISE_PARAMETERS[name] = (
                    int(spec["firstOctave"]), [float(a) for a in spec["amplitudes"]])
        self.reg._df.setdefault("minecraft:overworld/base_3d_noise", _BASE_3D_NOISE)
        self._custom["terra_continental:giant_peaks"] = self._giant_peaks
        self._custom["minecraft:y"] = lambda ctx: ctx.Y
        for name, value in _NO_CAVES.items():
            self._custom[name] = (lambda v: lambda ctx: ctx.const(v))(value)
        self.density = _find_interpolated(
            self.reg.df("minecraft:overworld/noise_router/final_density"))
        if self.density is None:
            raise mc_dfi.DensityFunctionError("final_density has no interpolated node")

    def _giant_peaks(self, ctx, node):
        """terra_continental:giant_peaks (experiment; Python only until it earns a Java port):
        one massif per continent, an offset bump (offset units, before
        vertical_scale) added to the terrain's height. Each sits `reach` of the way from its
        continent's centre toward the coast at a seed-drawn bearing. Profile: a broad dome
        (radius) plus a summit cone (summit of the height, summit_width of the radius, default a
        third, summit_power concave), so it reads as one mountain, not a hill; variety gives
        each massif its own height, size, summit and companions."""
        height = float(node.get("height", 3.0))
        radius = float(node.get("radius", 2400.0))
        reach = float(node.get("reach", 0.35))
        output = node.get("output", "height")
        mask_reach = float(node.get("mask_reach", 1.0))
        warp = float(node.get("warp", 0.0))
        spurs = float(node.get("spurs", 0.0))
        texture = float(node.get("texture", 0.0))
        grain = float(node.get("grain", 0.0))
        summit = float(node.get("summit", 0.35))
        summit_width = float(node.get("summit_width", 1.0 / 3.0))
        summit_power = float(node.get("summit_power", 1.5))
        companions = int(node.get("companions", 0))
        comp_height = float(node.get("companion_height", 0.85))
        comp_width = float(node.get("companion_width", 0.12))
        comp_dist = float(node.get("companion_dist", 0.22))
        variety = float(node.get("variety", 0.0))
        detail = (ctx.noise(node.get("detail_noise", "terra_continental:tectonic"))
                  if warp or spurs or texture or grain else None)
        jitter = self._jitter_table(ctx, node.get("jitter_noise", "terra_continental:continent_jitter"))
        cx, cz, cr = mc_climate.continent_centers(jitter, self.radial)
        cx = np.concatenate([[0.0], cx])
        cz = np.concatenate([[0.0], cz])
        cr = np.concatenate([[self.radial.central_land_radius], cr])
        noise = ctx.noise(node.get("jitter_noise", "terra_continental:continent_jitter"))
        out = np.zeros(ctx.shape)
        for k in range(cx.size):
            # Bearing from fixed-coordinate samples of the seeded jitter noise, as
            # RadialFalloffFunction draws its continents (different constants).
            # Unclamped: clamping (noise * 2) to +-1 sent many seeds' giants due west (rig seed:
            # exactly 180 degrees, 2026-10-23).
            ang = float(noise.get_value(k * 7919.0 + 211.0, 0.0, k * -3571.0 - 59.0)) * 2.0 * np.pi
            px = cx[k] + np.cos(ang) * cr[k] * reach
            pz = cz[k] + np.sin(ang) * cr[k] * reach

            # Each massif's own character (variety, 0-1): with one recipe, every giant on every
            # seed came out alike (the user, 2026-10-24: worlds and continents should have more
            # character). Height only goes down, so none reaches the world ceiling.
            # Draws in -1..1: the noise at fixed coordinates, stretched (its raw values cluster
            # within about +-0.4, which gave every giant a near-average character).
            def u(i):
                return min(1.0, max(-1.0, 2.5 * float(noise.get_value(k * 577.0 + i * 97.0 + 13.0, 0.0,
                                                                      k * -389.0 - i * 61.0 - 29.0))))
            h_k = height * (1.0 - 0.03 * variety * (u(0) + 1.0))
            rad = radius * (1.0 + 0.2 * variety * u(1))
            sm = min(0.6, max(0.15, summit + 0.1 * variety * u(2)))
            sw = summit_width * (1.0 + 0.25 * variety * u(3))
            sp = summit_power * (1.0 + 0.3 * variety * u(4))
            n_c = int(np.floor(companions + 2.0 * variety * u(5) + 0.5))
            n_c = min(companions + 2, max(1 if companions > 0 else 0, n_c))
            c_h = min(0.92, comp_height * (1.0 + 0.06 * variety * u(6)))
            c_d = comp_dist * (1.0 + 0.25 * variety * u(7))
            sp_k = spurs * (1.0 + 0.4 * variety * u(8))
            # Stretched along a seeded axis (1 to 1.6 times, the same area): some giants are
            # round cones, others long massifs with a summit ridge.
            el = 1.0 + 0.3 * variety * (u(9) + 1.0)
            # Unclamped, as the massif's bearing: clamped draws pile up at +-1, which is one axis.
            axis = 2.5 * np.pi * float(noise.get_value(k * 577.0 + 10 * 97.0 + 13.0, 0.0,
                                                       k * -389.0 - 10 * 61.0 - 29.0))
            ca, sa, se = np.cos(axis), np.sin(axis), np.sqrt(el)

            dx, dz = ctx.X - px, ctx.Z - pz
            along, across = dx * ca + dz * sa, -dx * sa + dz * ca
            d = np.sqrt(along * along / el + across * across * el)
            r = rad
            if warp:
                # A ragged outline: a perfectly round dome showed its block steps as concentric
                # rings on the flanks (tour, 2026-10-24).
                r = rad * (1.0 + warp * detail.get_value(ctx.X, 4111.0 + k, ctx.Z))
            t = d / r
            if output == "mask":
                tm = np.clip(t / mask_reach, 0.0, 1.0)
                out = np.maximum(out, 1 - tm * tm * (3 - 2 * tm))
                continue
            dome = np.where(t < 1, 1 - t * t * (3 - 2 * t), 0.0)
            # summit: the cone's share of the height. A narrower, more concave cone gives a
            # sharper top (the user, 2026-10-24, after flattening left a rounded one).
            cone = np.clip(1 - t / sw, 0.0, None) ** sp
            bump = (1.0 - sm) * dome + sm * cone
            if sp_k:
                # Ridges and gullies running down the flanks: noise in polar coordinates, the
                # angle on a circle (no seam) and stretched along the radius, so features
                # converge on the summit like a real massif's spurs. Faded in below the summit
                # cone: at full strength there they met in a starburst of knife edges.
                # The angle bends with a low-frequency noise, so spurs curve and fork instead of
                # running ruler-straight (pie-slice facets, giant preview g2).
                ang_k = np.arctan2(dz, dx) + _SPUR_BEND * detail.get_value(ctx.X, 6007.0 + k, ctx.Z)
                n = detail.get_value(np.cos(ang_k) * _SPUR_ARC, d * _SPUR_RADIAL + 977.0 * (k + 1),
                                     np.sin(ang_k) * _SPUR_ARC)
                ridge = 1.0 - 2.0 * np.minimum(np.abs(n), 1.0)
                f = np.clip((t - _SPUR_START) / _SPUR_FADE, 0.0, 1.0)
                bump = bump * (1.0 + sp_k * ridge * f * f * (3 - 2 * f))
            for j in range(n_c):
                # Lesser summits round the main one (the user liked the original's, which were
                # Tectonic hills riding the dome until giant_flatten removed them). Bearings and
                # distances come from the jitter noise at fixed coordinates, like the massif's own.
                # Each tip is a set share of the main summit: the cone makes up the difference to
                # the profile under it, so none outgrows the main peak (one did by 26 blocks when
                # the cones were simply added before the spurs).
                a_j = (2.0 * np.pi * j / n_c
                       + 1.2 * float(noise.get_value(k * 131.0 + j * 17.0 + 5.0, 0.0, j * -29.0 - 7.0)))
                r_j = c_d * rad * (0.85 + 0.3 * float(noise.get_value(
                    k * -83.0 + j * 41.0 - 3.0, 0.0, j * 23.0 + 11.0)))
                t_j = min(r_j / rad, 1.0)
                under = ((1.0 - sm) * (1 - t_j * t_j * (3 - 2 * t_j))
                         + sm * max(0.0, 1 - t_j / sw) ** sp)
                tip = c_h * (1.0 - 0.15 * j / max(1, n_c - 1))   # the first is tallest
                # Placed in the massif's stretched frame, so they follow its long axis.
                la, lc = np.cos(a_j) * r_j * se, np.sin(a_j) * r_j / se
                tc = np.sqrt((ctx.X - px - (la * ca - lc * sa)) ** 2
                             + (ctx.Z - pz - (la * sa + lc * ca)) ** 2) / (comp_width * rad)
                bump = bump + max(0.0, tip - under) * np.clip(1 - tc, 0.0, None) ** sp
            if texture:
                # Knolls a few hundred blocks across: on an untextured slope every block step
                # ran evenly round the mountain as a contour ring.
                n = detail.get_value(ctx.X * _TEXTURE_SCALE, 9001.0 + k, ctx.Z * _TEXTURE_SCALE)
                bump = bump + texture * n * np.where(t < 1, np.sin(np.pi * np.clip(t, 0.0, 1.0)), 0.0)
            if grain:
                # Finer bumps (~64 blocks): on the dome's smooth flanks each block step still ran
                # evenly for hundreds of blocks and read as topo-map lines (the user, 2026-10-24).
                n = detail.get_value(ctx.X * _GRAIN_SCALE, 12007.0 + k, ctx.Z * _GRAIN_SCALE)
                bump = bump + grain * n * np.where(t < 1, np.sin(np.pi * np.clip(t, 0.0, 1.0)), 0.0)
            out += h_k * bump
        return out

    @property
    def min_y(self):
        return self._base_noise["min_y"]

    @property
    def max_y(self):
        return self._base_noise["min_y"] + self._base_noise["height"]

    @property
    def _base_noise(self):
        # The world's height limits, from the terrain pack's overworld noise settings.
        path = _os.path.join(continental_climate.SRC_RESOURCES, "resourcepacks", "terrain", "data",
                            "minecraft", "worldgen", "noise_settings", "overworld.json")
        with open(path, encoding="utf-8") as fh:
            return _json.load(fh)["noise"]

    @property
    def nodes(self):
        """Cell-corner levels: min_y, min_y + 8, ... up to the top of the world."""
        return (self.max_y - self.min_y) // CELL_H + 1

    # -- cell corners ---------------------------------------------------------

    def _eval(self, full, sel, node):
        ctx = full.at(sel, self.min_y + CELL_H * node)
        return np.asarray(mc_dfi.evaluate(self.density, ctx), dtype=np.float64)

    def _search(self, full, V):
        """Per corner column: the highest cell-corner level with positive density whose next
        level up is not. Fills V[column, level] with every value evaluated."""
        n = full.shape[0]
        idx = np.arange(n)
        lo = np.zeros(n, dtype=np.int64)              # bottom: the slide makes it solid
        hi = np.full(n, self.nodes - 1, dtype=np.int64)   # top: the slide makes it air
        active = hi - lo > 1
        while np.any(active):
            sel = idx[active]
            mid = (lo[sel] + hi[sel]) // 2
            v = self._eval(full, sel, mid)
            V[sel, mid] = v
            pos = v > 0
            lo[sel[pos]] = mid[pos]
            hi[sel[~pos]] = mid[~pos]
            active = hi - lo > 1
        top = lo
        # Overhangs: a solid run above the one the search landed in.
        pending = idx
        while pending.size:
            found = np.zeros(pending.size, dtype=bool)
            for step in range(2, 2 + _OVERHANG_SCAN):
                level = np.minimum(top[pending] + step, self.nodes - 1)
                need = np.isnan(V[pending, level])
                if np.any(need):
                    V[pending[need], level[need]] = self._eval(full, pending[need], level[need])
                v = V[pending, level]
                hit = (v > 0) & (level > top[pending])
                top[pending[hit]] = level[hit]
                found |= hit
            pending = pending[found]
            if pending.size:
                above = np.minimum(top[pending] + 1, self.nodes - 1)
                need = np.isnan(V[pending, above])
                if np.any(need):
                    V[pending[need], above[need]] = self._eval(full, pending[need], above[need])
        return top

    def _fill(self, full, V, cols, levels):
        need = np.isnan(V[cols, levels])
        if np.any(need):
            c, l = cols[need], levels[need]
            key = np.unique(c * self.nodes + l)
            c, l = key // self.nodes, key % self.nodes
            V[c, l] = self._eval(full, c, l)

    # -- public API -----------------------------------------------------------

    def heights(self, seed, X, Z, floor=False):
        """getBaseHeight(WORLD_SURFACE_WG) at block columns X, Z (int arrays, same shape).
        floor=True: the top of the solid ground instead (ocean and lake floors, +1)."""
        X = np.asarray(X, dtype=np.int64)
        Z = np.asarray(Z, dtype=np.int64)
        shape = X.shape
        X, Z = X.ravel(), Z.ravel()
        x0, z0 = np.floor_divide(X, CELL_W) * CELL_W, np.floor_divide(Z, CELL_W) * CELL_W
        fx, fz = (X - x0) / CELL_W, (Z - z0) / CELL_W

        # Every corner any column needs, once.
        corners = np.concatenate([np.stack([x0 + dx, z0 + dz], 1)
                                  for dz in (0, CELL_W) for dx in (0, CELL_W)])
        uniq, inv = np.unique(corners, axis=0, return_inverse=True)
        inv = inv.reshape(4, -1)                       # [corner 00,10,01,11][column]
        weights = np.stack([(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz])
        used = weights > 0
        # Corners with zero weight in every column are never needed (aligned samples).
        need = np.zeros(len(uniq), dtype=bool)
        need[inv[used]] = True

        full = mc_dfi.Context(self.reg, seed, uniq[:, 0].astype(np.float64),
                              uniq[:, 1].astype(np.float64), custom=self._custom)
        V = np.full((len(uniq), self.nodes), np.nan, dtype=np.float32)
        top = np.zeros(len(uniq), dtype=np.int64)
        sub = np.flatnonzero(need)
        top_sub = self._search(_Sub(full, sub), _SubV(V, sub))
        top[sub] = top_sub

        # Each column: the band of levels its corners' surfaces span, interpolated.
        t = np.where(used, top[inv], np.iinfo(np.int64).max)
        lo = np.maximum(np.min(t, axis=0) - 2, 0)
        hi = np.minimum(np.max(np.where(used, top[inv], -1), axis=0) + 1, self.nodes - 1)
        best = np.full(X.size, -1, dtype=np.int64)
        v_at = np.zeros(X.size)
        v_above = np.zeros(X.size)
        span = int(np.max(hi - lo))
        prev = None
        for o in range(0, span + 1):                   # from the top of the band down
            level = hi - o
            cols = np.flatnonzero((level >= lo) & (best < 0))
            if cols.size == 0:
                break
            lv = level[cols]
            for k in range(4):
                u = used[k, cols]
                self._fill(full, V, inv[k, cols[u]], lv[u])
            v = np.zeros(cols.size)
            for k in range(4):
                v += np.where(used[k, cols], weights[k, cols] *
                              np.nan_to_num(V[inv[k, cols], lv].astype(np.float64)), 0.0)
            if prev is not None:
                hit = cols[(v > 0) & (best[cols] < 0)]
                hv = v[(v > 0) & (best[cols] < 0)]
                best[hit] = level[hit]
                v_at[hit] = hv
                v_above[hit] = prev[hit]
            p = np.zeros(X.size)
            p[cols] = v
            prev = p
        # Within the cell: density falls linearly from v_at (solid) to v_above; the last
        # block below the crossing is the top.
        with np.errstate(divide="ignore", invalid="ignore"):
            r = CELL_H * v_at / (v_at - v_above)
        t_in = np.clip(np.ceil(r) - 1, 0, CELL_H - 1)
        top_y = self.min_y + CELL_H * best + t_in
        h = np.where(best >= 0, top_y + 1, self.min_y)
        if floor:
            return h.astype(np.int64).reshape(shape)
        return np.maximum(h, SEA_LEVEL).astype(np.int64).reshape(shape)

    def grid(self, seed, center_x, center_z, size, step):
        """A size x size height grid `step` blocks apart, rows north to south — the layout of
        TerrainAudit's grid (index [gz][gx] at center + (g - size/2) * step)."""
        g = (np.arange(size) - size // 2) * step
        Z, X = np.meshgrid(center_z + g, center_x + g, indexing="ij")
        return self.heights(seed, X, Z)


class _Sub:
    """A view of the full corner context restricted to the corners a request needs."""

    def __init__(self, full, sub):
        self._full = full
        self._sub = sub
        self.shape = (sub.size,)

    def at(self, sel, Y):
        return self._full.at(self._sub[sel], Y)


class _SubV:
    """V rows for `_Sub`'s corners, indexed the way `_search` indexes them."""

    def __init__(self, V, sub):
        self._V = V
        self._sub = sub

    def __getitem__(self, key):
        r, c = key
        return self._V[self._sub[r], c]

    def __setitem__(self, key, value):
        r, c = key
        self._V[self._sub[r], c] = value
