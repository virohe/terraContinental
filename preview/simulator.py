"""
Terra Continental world-generation simulator.

Reproduces Minecraft 1.21.1 overworld biome placement exactly (bit-for-bit
noise parity with the decompiled game classes - see test_parity.py) with the
mod's noise router applied: radial-falloff continentalness, rescaled climate
noises, the custom tectonic ridges formula, and the narrowed mushroom-fields
range. Surface biomes are sampled at depth = 0 on quart-aligned coordinates.

Workflow: parameters are what get tuned; seeds are just random draws. Render a
grid of random seeds with one parameter set to check the parameters behave
across seeds, adjust, repeat.

MODES

1. Static PNG (default - no display needed; the final stdout line is always
   the absolute path of the written file):

     python preview/simulator.py --seed 2117724069315525696 --out preview/out/sim.png
     python preview/simulator.py --seeds 12 --out preview/out/grid.png
     python preview/simulator.py --seeds 12 --ocean-bias 0.2 --central-land-radius 8000
     python preview/simulator.py --seed 12345 --large-biomes --out preview/out/lb.png

   --seeds N renders N fresh random seeds as a thumbnail grid, all with the
   same parameters. Any mod parameter can be overridden from the CLI:
   --ocean-bias, --radial-bias-strength, --central-land-bump,
   --central-land-radius, --border-radius, --core-radius, --transition-width,
   --ocean-floor-value.

   --large-biomes halves the xz_scale for temperature and vegetation, matching
   the overworld_large_biomes noise settings preset.

2. Interactive GUI (requires a display):

     python preview/simulator.py --interactive [--seed S]
     python preview/simulator.py --interactive --seeds 6

   Sliders re-apply parameters instantly. "Shuffle seeds" regenerates the
   display with new random seeds using the current slider values - the point
   is to catch parameter choices that only work on lucky seeds.

First-time setup: pip install numpy matplotlib
"""

import argparse
import os
import random
import sys
import time
from dataclasses import fields

import numpy as np

from mc_biomes import (BIOME_COLORS, BiomeTable, MultiRegionBiomeTable,
                       load_entries_from_json, load_terrablender_from_json, quantize)
from mc_climate import ClimateSampler, RadialParams, finish_fields

LAND_THRESHOLD = -0.19          # coast band starts here (continentalness)

PARAM_NAMES = [f.name for f in fields(RadialParams)]


def hex_to_rgb(color):
    color = color.lstrip("#")
    return tuple(int(color[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def random_seed():
    return random.getrandbits(64) - (1 << 63)


class WorldView:
    """One seed's cached noise fields + fast param re-application."""

    _table = None
    _palette = None
    _biome_entries = None   # vanilla entries (set from CLI)
    _tb_data = None         # TerraBlender region data, or None

    @classmethod
    def set_biome_entries(cls, entries):
        """Legacy: set vanilla-only biome entries (no TerraBlender)."""
        cls._biome_entries = entries
        cls._tb_data = None
        cls._table = None
        cls._palette = None

    @classmethod
    def set_biome_data(cls, entries, tb_data):
        """Set biome entries and optional TerraBlender region data."""
        cls._biome_entries = entries
        cls._tb_data = tb_data
        cls._table = None
        cls._palette = None

    @classmethod
    def shared_table(cls):
        if cls._table is None:
            if cls._tb_data is not None:
                cls._table = MultiRegionBiomeTable(
                    cls._tb_data["region_entries"],
                    cls._tb_data["region_size"],
                    cls._tb_data["region_weights"],
                )
            else:
                cls._table = BiomeTable(entries=cls._biome_entries)
            cls._palette = np.array(
                [hex_to_rgb(BIOME_COLORS.get(n, "#808080"))
                 for n in cls._table.biome_names])
        return cls._table, cls._palette

    def __init__(self, seed, extent, res, params, workers=None, verbose=True,
                 large_biomes=False):
        self.seed = seed
        self.extent = extent
        self.res = res
        self.params = params
        self.workers = workers
        self.large_biomes = large_biomes
        self.table, self.palette = self.shared_table()
        coords = np.linspace(-extent, extent, res).astype(np.int64)
        self.X, self.Z = np.meshgrid(coords, coords)
        t0 = time.perf_counter()
        self.raw = ClimateSampler(seed, large_biomes=large_biomes).raw_fields(
            self.X, self.Z, workers=workers)
        if verbose:
            print(f"seed {seed}: noise fields in {time.perf_counter() - t0:.1f}s")
        self.apply_params()

    def reseed(self, seed, verbose=True):
        self.seed = seed
        t0 = time.perf_counter()
        self.raw = ClimateSampler(seed, large_biomes=self.large_biomes).raw_fields(
            self.X, self.Z, workers=self.workers)
        if verbose:
            print(f"seed {seed}: noise fields in {time.perf_counter() - t0:.1f}s")
        self.apply_params()

    def apply_params(self):
        """Fast path: radial falloff + ridges + biome lookup only."""
        self.continents, self.erosion, self.weirdness = finish_fields(self.raw, self.params)
        self.qt = quantize(self.raw["temperature"])
        self.qh = quantize(self.raw["vegetation"])
        self.qc = quantize(self.continents)
        self.qe = quantize(self.erosion)
        self.qw = quantize(self.weirdness)
        if isinstance(self.table, MultiRegionBiomeTable):
            self.biome_ids = self.table.lookup(
                self.seed, self.qt, self.qh, self.qc, self.qe, self.qw,
                self.X, self.Z)
        else:
            self.biome_ids = self.table.lookup(self.qt, self.qh, self.qc, self.qe, self.qw)
        self.image = self.palette[self.biome_ids]
        self.land_fraction = float((self.continents > LAND_THRESHOLD).mean())
        return self.image

    def biome_at_index(self, iz, ix):
        if isinstance(self.table, MultiRegionBiomeTable):
            return self.table.brute_force_biome(
                self.seed,
                int(self.qt[iz, ix]), int(self.qh[iz, ix]), int(self.qc[iz, ix]),
                int(self.qe[iz, ix]), int(self.qw[iz, ix]),
                int(self.X[iz, ix]), int(self.Z[iz, ix]))
        return self.table.brute_force_biome(
            int(self.qt[iz, ix]), int(self.qh[iz, ix]), int(self.qc[iz, ix]),
            int(self.qe[iz, ix]), int(self.qw[iz, ix]))

    def index_of(self, x, z):
        ix = int(round((x + self.extent) / (2 * self.extent) * (self.res - 1)))
        iz = int(round((z + self.extent) / (2 * self.extent) * (self.res - 1)))
        if 0 <= ix < self.res and 0 <= iz < self.res:
            return iz, ix
        return None

    def describe(self, x, z):
        idx = self.index_of(x, z)
        if idx is None:
            return "outside view"
        iz, ix = idx
        biome = self.biome_at_index(iz, ix)
        return (f"({self.X[iz, ix]}, {self.Z[iz, ix]})  {biome}\n"
                f"T {self.raw['temperature'][iz, ix]:+.3f}   "
                f"H {self.raw['vegetation'][iz, ix]:+.3f}   "
                f"C {self.continents[iz, ix]:+.3f}\n"
                f"E {self.erosion[iz, ix]:+.3f}   "
                f"W {self.weirdness[iz, ix]:+.3f}   "
                f"dist {self.raw['distance'][iz, ix]:,.0f}")


# ---------------------------------------------------------------------------
# Drawing
# ---------------------------------------------------------------------------

def draw_map(ax, view, tectonics=True, circles=True, lw_scale=1.0):
    """(Re)draws image + overlays on ax; returns artists for later removal."""
    e = view.extent
    artists = []
    im = ax.imshow(view.image, extent=(-e, e, e, -e), origin="upper",
                   interpolation="nearest", zorder=0)
    artists.append(im)
    if tectonics:
        # plate boundaries / island-chain paths: tectonic_potential = 0
        cs = ax.contour(view.X, view.Z, view.raw["tectonic_potential"],
                        levels=[0.0], colors="#E0E0E0",
                        linewidths=0.8 * lw_scale, alpha=0.55,
                        linestyles="solid", zorder=2)
        artists.append(cs)
    if circles:
        artists.extend(draw_markers(ax, e, linewidth=lw_scale))
    ax.set_xlim(-e, e)
    ax.set_ylim(e, -e)
    return artists


def draw_markers(ax, extent, linewidth=1.0):
    artists = []
    theta = np.linspace(0, 2 * np.pi, 256)
    for radius, style in ((10_000, "--"), (50_000, ":")):
        if radius <= extent * 1.45:
            (ln,) = ax.plot(radius * np.cos(theta), radius * np.sin(theta),
                            style, color="white", linewidth=linewidth,
                            alpha=0.7, zorder=3)
            artists.append(ln)
    (spawn,) = ax.plot(0, 0, "+", color="red", markersize=10,
                       markeredgewidth=1.5, zorder=4)
    artists.append(spawn)
    return artists


def grid_shape(n):
    cols = int(np.ceil(np.sqrt(n * 4.0 / 3.0)))
    rows = int(np.ceil(n / cols))
    return rows, cols


def params_summary(params):
    return (f"ocean_bias {params.ocean_bias:g}  "
            f"radial_bias {params.radial_bias_strength:g}  "
            f"bump {params.central_land_bump:g}@{params.central_land_radius:g}  "
            f"border {params.border_radius:g}")


# ---------------------------------------------------------------------------
# Static PNG mode
# ---------------------------------------------------------------------------

def render_single_png(args, params, out_path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    seed = args.seed if args.seed is not None else random_seed()
    view = WorldView(seed, args.extent, args.res, params, workers=args.workers,
                     large_biomes=args.large_biomes)
    fig, ax = plt.subplots(figsize=(11, 11))
    draw_map(ax, view)
    ax.set_xlabel("X (blocks)")
    ax.set_ylabel("Z (blocks)")
    ax.set_title(f"seed {seed}   land {view.land_fraction * 100:.1f}%\n"
                 f"{params_summary(params)}", fontsize=10)
    fig.savefig(out_path, dpi=140, bbox_inches="tight")
    plt.close(fig)


def render_grid_png(args, params, out_path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    n = args.seeds
    if args.seed is not None:
        random.seed(args.seed)  # reproducible seed *set* if --seed is given
    seeds = [random_seed() for _ in range(n)]
    print("seeds:", " ".join(str(s) for s in seeds))

    rows, cols = grid_shape(n)
    fig, axes = plt.subplots(rows, cols, figsize=(cols * 3.4, rows * 3.6))
    axes = np.atleast_1d(axes).ravel()
    land = []
    for k, seed in enumerate(seeds):
        view = WorldView(seed, args.extent, args.grid_res, params,
                         workers=args.workers, large_biomes=args.large_biomes)
        land.append(view.land_fraction)
        ax = axes[k]
        draw_map(ax, view, lw_scale=0.5)
        ax.set_title(f"{seed}\nland {view.land_fraction * 100:.1f}%", fontsize=7)
        ax.set_xticks([])
        ax.set_yticks([])
    for ax in axes[n:]:
        ax.set_visible(False)
    fig.suptitle(f"{n} random seeds   land {np.mean(land) * 100:.1f}% "
                 f"+/- {np.std(land) * 100:.1f}%\n{params_summary(params)}",
                 fontsize=11)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    print(f"land fraction per seed: "
          + " ".join(f"{lf * 100:.1f}%" for lf in land))


# ---------------------------------------------------------------------------
# Interactive mode
# ---------------------------------------------------------------------------

def make_busy_guard():
    busy = {"flag": False}

    def with_busy(fn):
        def wrapped(*fargs):
            if busy["flag"]:
                return
            busy["flag"] = True
            try:
                fn(*fargs)
            finally:
                busy["flag"] = False
        return wrapped

    return with_busy


def add_param_sliders(fig, params, panel_x, panel_w, top, on_change):
    from matplotlib.widgets import Slider

    slider_defs = [
        ("ocean_bias", -0.5, 1.0),
        ("radial_bias_strength", 0.0, 1.0),
        ("central_land_bump", 0.0, 3.0),
        ("central_land_radius", 1_000, 50_000),
        ("border_radius", 20_000, 400_000),
    ]
    sliders = {}
    for i, (name, lo, hi) in enumerate(slider_defs):
        sax = fig.add_axes((panel_x + 0.045, top - i * 0.055, panel_w - 0.05, 0.03))
        sliders[name] = Slider(sax, name.replace("_", "\n", 1) + " ", lo, hi,
                               valinit=getattr(params, name))
        sliders[name].label.set_fontsize(7)
        sliders[name].valtext.set_fontsize(7)
        sliders[name].on_changed(on_change)
    return sliders


def run_interactive_single(args, params):
    import matplotlib.pyplot as plt
    from matplotlib.widgets import Button, CheckButtons, RadioButtons, TextBox

    seed = args.seed if args.seed is not None else random_seed()
    view = WorldView(seed, args.extent, args.res, params, workers=args.workers,
                     large_biomes=args.large_biomes)

    fig = plt.figure(figsize=(14, 9))
    fig.canvas.manager.set_window_title("Terra Continental world simulator")
    ax = fig.add_axes((0.05, 0.06, 0.62, 0.88))

    state = {"artists": [], "tectonics": True, "circles": True}

    def redraw():
        for artist in state["artists"]:
            try:
                artist.remove()
            except (ValueError, AttributeError):
                pass
        state["artists"] = draw_map(ax, view, state["tectonics"], state["circles"])
        ax.set_xlabel("X (blocks)")
        ax.set_ylabel("Z (blocks)")
        ax.set_title(f"seed {view.seed}    land {view.land_fraction * 100:.1f}%",
                     fontsize=11)
        fig.canvas.draw_idle()

    panel_x, panel_w = 0.74, 0.21
    seed_box = TextBox(fig.add_axes((panel_x, 0.90, panel_w, 0.04)), "seed ",
                       initial=str(view.seed))
    shuffle_btn = Button(fig.add_axes((panel_x, 0.84, panel_w, 0.04)),
                         "Shuffle seed")
    info = fig.text(panel_x, 0.28, "click the map to inspect a point",
                    fontsize=9, family="monospace", va="top", wrap=True)
    fig.text(panel_x, 0.14,
             "sliders: instant re-apply\nseed change: ~5-10 s recompute\n"
             "white dashed: 10 k radius\nwhite dotted: 50 k radius",
             fontsize=8, color="0.4", va="top")

    with_busy = make_busy_guard()

    @with_busy
    def on_slider(_value):
        for name, slider in sliders.items():
            setattr(params, name, slider.val)
        view.apply_params()
        redraw()

    sliders = add_param_sliders(fig, params, panel_x, panel_w, 0.74, on_slider)

    @with_busy
    def on_seed(text):
        try:
            new_seed = int(text.strip())
        except ValueError:
            info.set_text(f"invalid seed: {text!r}")
            fig.canvas.draw_idle()
            return
        if new_seed == view.seed:
            return
        info.set_text("computing noise fields...")
        fig.canvas.draw()
        view.reseed(new_seed)
        info.set_text("done")
        redraw()

    @with_busy
    def on_shuffle(_event):
        new_seed = random_seed()
        seed_box.set_val(str(new_seed))
        info.set_text("computing noise fields...")
        fig.canvas.draw()
        view.reseed(new_seed)
        info.set_text("done")
        redraw()

    checks = CheckButtons(fig.add_axes((panel_x, 0.34, panel_w, 0.07)),
                          ["plate boundaries", "circles"],
                          [True, True])

    def on_check(_label):
        (state["tectonics"],
         state["circles"]) = checks.get_status()
        redraw()

    _GEN_LABELS = ["Normal", "Large Biomes"]
    fig.text(panel_x, 0.515, "Generation type", fontsize=8, color="0.3")
    gen_radio = RadioButtons(
        fig.add_axes((panel_x, 0.42, panel_w, 0.09)),
        _GEN_LABELS, active=1 if args.large_biomes else 0)
    for lbl in gen_radio.labels:
        lbl.set_fontsize(8)

    @with_busy
    def on_gen_type(label):
        view.large_biomes = (label == "Large Biomes")
        info.set_text("computing noise fields...")
        fig.canvas.draw()
        view.reseed(view.seed)
        info.set_text("done")
        redraw()

    def on_click(event):
        if event.inaxes is not ax or event.xdata is None:
            return
        if fig.canvas.toolbar is not None and fig.canvas.toolbar.mode:
            return
        info.set_text(view.describe(event.xdata, event.ydata))
        fig.canvas.draw_idle()

    def format_coord(x, z):
        idx = view.index_of(x, z)
        if idx is None:
            return f"X={x:.0f} Z={z:.0f}"
        biome = view.table.biome_names[view.biome_ids[idx]]
        return f"X={x:.0f} Z={z:.0f}  {biome}"

    seed_box.on_submit(on_seed)
    shuffle_btn.on_clicked(on_shuffle)
    checks.on_clicked(on_check)
    gen_radio.on_clicked(on_gen_type)
    fig.canvas.mpl_connect("button_press_event", on_click)
    ax.format_coord = format_coord

    redraw()
    plt.show()


def run_interactive_grid(args, params):
    import matplotlib.pyplot as plt
    from matplotlib.widgets import Button, RadioButtons

    n = args.seeds
    rows, cols = grid_shape(n)

    fig = plt.figure(figsize=(min(4 + cols * 3, 16), min(2 + rows * 3.2, 10)))
    fig.canvas.manager.set_window_title(
        "Terra Continental - parameter robustness across seeds")

    panel_x, panel_w = 0.80, 0.16
    grid_right = panel_x - 0.03
    axes = []
    for k in range(n):
        r, c = divmod(k, cols)
        w = grid_right / cols
        h = 0.86 / rows
        ax = fig.add_axes((0.02 + c * w, 0.04 + (rows - 1 - r) * h,
                           w * 0.94, h * 0.86))
        ax.set_xticks([])
        ax.set_yticks([])
        axes.append(ax)

    print(f"computing {n} seeds...")
    views = [WorldView(random_seed(), args.extent, args.grid_res, params,
                       workers=args.workers, large_biomes=args.large_biomes)
             for _ in range(n)]

    status = fig.text(panel_x, 0.30, "", fontsize=8, family="monospace",
                      va="top")

    def redraw():
        land = []
        for ax, view in zip(axes, views):
            ax.clear()
            ax.set_xticks([])
            ax.set_yticks([])
            draw_map(ax, view, lw_scale=0.5)
            ax.set_title(f"{view.seed}\nland {view.land_fraction * 100:.1f}%",
                         fontsize=6)
            land.append(view.land_fraction)
        fig.suptitle(f"land {np.mean(land) * 100:.1f}% "
                     f"+/- {np.std(land) * 100:.1f}%   {params_summary(params)}",
                     fontsize=10)
        fig.canvas.draw_idle()

    with_busy = make_busy_guard()

    @with_busy
    def on_slider(_value):
        for name, slider in sliders.items():
            setattr(params, name, slider.val)
        for view in views:
            view.apply_params()
        redraw()

    sliders = add_param_sliders(fig, params, panel_x, panel_w, 0.80, on_slider)

    @with_busy
    def on_shuffle(_event):
        status.set_text("recomputing...")
        fig.canvas.draw()
        for i, view in enumerate(views):
            view.reseed(random_seed())
            status.set_text(f"recomputing... {i + 1}/{n}")
            fig.canvas.draw()
        status.set_text("done")
        redraw()

    shuffle_btn = Button(fig.add_axes((panel_x, 0.88, panel_w, 0.05)),
                         "Shuffle seeds")
    shuffle_btn.on_clicked(on_shuffle)

    _GEN_LABELS = ["Normal", "Large Biomes"]
    fig.text(panel_x, 0.50, "Generation type", fontsize=8, color="0.3")
    gen_radio = RadioButtons(
        fig.add_axes((panel_x, 0.40, panel_w, 0.09)),
        _GEN_LABELS, active=1 if args.large_biomes else 0)
    for lbl in gen_radio.labels:
        lbl.set_fontsize(8)

    @with_busy
    def on_gen_type(label):
        new_lb = (label == "Large Biomes")
        status.set_text("recomputing...")
        fig.canvas.draw()
        for i, view in enumerate(views):
            view.large_biomes = new_lb
            view.reseed(view.seed)
            status.set_text(f"recomputing... {i + 1}/{n}")
            fig.canvas.draw()
        status.set_text("done")
        redraw()

    gen_radio.on_clicked(on_gen_type)

    def on_click(event):
        for view, ax in zip(views, axes):
            if event.inaxes is ax:
                status.set_text(f"seed {view.seed}\n"
                                f"land {view.land_fraction * 100:.1f}%")
                fig.canvas.draw_idle()
                return

    fig.canvas.mpl_connect("button_press_event", on_click)

    redraw()
    plt.show()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(
        description="Terra Continental worldgen simulator "
                    "(static PNG by default; --interactive for the GUI)")
    ap.add_argument("--seed", type=int, default=None,
                    help="world seed (single map). With --seeds N it seeds the "
                         "random seed *set* for reproducibility.")
    ap.add_argument("--seeds", type=int, default=None, metavar="N",
                    help="render N random seeds (same parameters) as a grid")
    ap.add_argument("--extent", "--radius", type=int, default=50_000, dest="extent",
                    help="half-size of the view in blocks (default 50000)")
    ap.add_argument("--res", type=int, default=1000,
                    help="samples per side for single maps (default 1000)")
    ap.add_argument("--grid-res", type=int, default=360,
                    help="samples per side for each grid thumbnail (default 360)")
    ap.add_argument("--out", "--save", dest="out", type=str, default=None,
                    help="output PNG path (static mode)")
    ap.add_argument("--interactive", action="store_true",
                    help="open the GUI with sliders (needs a display)")
    ap.add_argument("--large-biomes", action="store_true", dest="large_biomes",
                    help="halve xz_scale for temperature and vegetation, matching "
                         "the overworld_large_biomes noise settings preset")
    ap.add_argument("--biome-data", dest="biome_data", type=str, default=None,
                    metavar="PATH",
                    help="path to terra_biome_dump.json from /terra dump_biomes; "
                         "replaces the built-in vanilla biome table with the full "
                         "modded biome list (works with any biome mod)")
    ap.add_argument("--workers", type=int, default=None,
                    help="thread count for noise computation")
    for name in PARAM_NAMES:
        ap.add_argument(f"--{name.replace('_', '-')}", type=float, default=None,
                        help=f"override {name} (default: from datapack JSON)")
    args = ap.parse_args()

    params = RadialParams.from_datapack()
    overridden = []
    for name in PARAM_NAMES:
        value = getattr(args, name)
        if value is not None:
            setattr(params, name, value)
            overridden.append(f"{name}={value:g}")
    if overridden:
        print("parameter overrides:", " ".join(overridden))

    if args.biome_data:
        print(f"Loading biome data from {args.biome_data} ...")
        entries = load_entries_from_json(args.biome_data)
        tb_data = load_terrablender_from_json(args.biome_data)
        WorldView.set_biome_data(entries, tb_data)
        print(f"  {len(entries)} vanilla surface entries loaded")
        if tb_data:
            n_mod = sum(1 for r in tb_data["region_entries"][1:] if r)
            total_mod = sum(len(r) for r in tb_data["region_entries"][1:] if r)
            print(f"  TerraBlender: {len(tb_data['region_weights'])} regions, "
                  f"{total_mod} mod biome entries across {n_mod} mod region(s)")

    print("Building biome lookup table (cached after first run)...")
    WorldView.shared_table()

    if args.interactive:
        if args.seeds and args.seeds > 1:
            run_interactive_grid(args, params)
        else:
            run_interactive_single(args, params)
        return

    # static PNG mode
    out = args.out
    if out is None:
        tag = (f"grid{args.seeds}" if args.seeds and args.seeds > 1
               else f"seed{args.seed}" if args.seed is not None else "random")
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out",
                           f"sim_{tag}.png")
    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)

    if args.seeds and args.seeds > 1:
        render_grid_png(args, params, out)
    else:
        render_single_png(args, params, out)

    # dispatch contract: last stdout line is the absolute output path
    print(os.path.abspath(out))


if __name__ == "__main__":
    main()
