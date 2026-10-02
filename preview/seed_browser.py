"""
Seed browser — pick a scan from the list, hover to identify biomes, star the good ones.

Reads the exports written in game:
    /continental biome_map  <radius> <step> [seed]   climate only (fast)
    /continental detail_map <radius> <step> [seed]   climate + terrain height (topography)
producing <instance>/seedscan/biome_map_<seed>.{json,bin,hgt}

Those come straight out of the live generator, so this is ground truth — it does not depend
on the offline Python pipeline or its parity status.

Everything is clickable: choose a scan in the left-hand list, "Load folder…" to point at a
different instance, "Refresh" to pick up scans generated since launch (no restart needed).
Keyboard still works: arrows switch scans, f favourites, m cycles view, c contours.

Usage
    python seed_browser.py                    # auto-find the newest seedscan folder
    python seed_browser.py <seedscan-or-instance-dir>
"""

import glob
import json
import os
import random
import sys
import threading

import numpy as np

try:
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox
except ImportError:
    raise SystemExit("tkinter is required (ships with the standard python.org installer)")

try:
    import matplotlib
    matplotlib.use("TkAgg")
    from matplotlib.figure import Figure
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
except ImportError:
    raise SystemExit("matplotlib is required:  pip install matplotlib")

from mc_biomes import BIOME_COLORS

_INSTANCES = os.path.join(os.path.expanduser("~"), "curseforge", "minecraft", "Instances")


# ---------------------------------------------------------------------------
# loading
# ---------------------------------------------------------------------------

def find_seedscan(arg=None):
    """Accept a seedscan dir, an instance dir, or nothing (newest scan wins)."""
    if arg:
        cand = [arg, os.path.join(arg, "seedscan")]
    else:
        cand = sorted(glob.glob(os.path.join(_INSTANCES, "*", "seedscan")),
                      key=lambda p: os.path.getmtime(p), reverse=True)
    for c in cand:
        if os.path.isdir(c) and (glob.glob(os.path.join(c, "biome_map_*.json"))
                                 or glob.glob(os.path.join(c, "offline_*.json"))):
            return c
    raise SystemExit(
        "No seedscan exports found.\n"
        "Run  /continental biome_map 50000 128 <seed>  in game, "
        "or pass the folder explicitly.")


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def color_for(name):
    """Match the in-game palette; hash unknowns into land tones so they never read as ocean."""
    short = name[10:] if name.startswith("minecraft:") else name
    for key in (name, short):
        if key in BIOME_COLORS:
            return hex_to_rgb(BIOME_COLORS[key])
    h = hash(short) & 0xFFFFFF
    import colorsys
    return colorsys.hsv_to_rgb(0.05 + (h % 1000) / 1000.0 * 0.40,
                               0.45 + ((h >> 10) % 1000) / 1000.0 * 0.35,
                               0.55 + ((h >> 20) % 1000) / 1000.0 * 0.30)


def is_ocean(name):
    return ("ocean" in name
            or name.endswith(":dead_sea") or name.endswith(":lush_stacks"))


class Scan:
    """One exported seed: the ID grid, its palette, and derived stats."""

    def __init__(self, meta_path):
        self.path = meta_path
        with open(meta_path, encoding="utf-8-sig") as fh:
            self.meta = json.load(fh)
        n = self.meta["n"]
        self.ids = np.fromfile(meta_path[:-5] + ".bin", dtype="<u2").reshape(n, n)
        self.palette = self.meta["palette"]
        self.seed = self.meta["seed"]
        self.n = n
        self.sea_level = self.meta.get("seaLevel", 63)
        self.offline = bool(self.meta.get("offline", False))

        # detail_map also writes terrain height; biome_map doesn't.
        hgt = meta_path[:-5] + ".hgt"
        self.height = (np.fromfile(hgt, dtype="<i2").reshape(n, n).astype(np.float32)
                       if self.meta.get("hasHeight") and os.path.exists(hgt) else None)

        counts = np.bincount(self.ids.ravel(), minlength=len(self.palette))
        total = self.ids.size
        ocean = sum(c for c, nm in zip(counts, self.palette) if is_ocean(nm))
        self.land_pct = 100.0 * (total - ocean) / total
        self.variety = int((counts > 0).sum())
        order = np.argsort(counts)[::-1]
        self.top = [(self.palette[i], 100.0 * counts[i] / total)
                    for i in order[:6] if counts[i]]

        self._rgb = None

    @property
    def rgb(self):
        if self._rgb is None:
            lut = np.array([color_for(nm) for nm in self.palette], dtype=np.float32)
            self._rgb = lut[self.ids]
        return self._rgb

    def hillshade(self):
        """Classic relief shading: illuminate the surface gradient from the north-west.
        Land only — sea is flat, and shading the seabed just adds noise."""
        if self.height is None:
            return None
        h = np.where(self.height < self.sea_level, float(self.sea_level), self.height)
        gz, gx = np.gradient(h, float(self.meta["step"]))
        # Vertical exaggeration keeps gentle valleys legible at continental steps.
        ex = 4.0
        slope = np.arctan(ex * np.hypot(gx, gz))
        aspect = np.arctan2(-gz, gx)
        az, alt = np.radians(315.0), np.radians(45.0)
        shade = (np.sin(alt) * np.cos(slope)
                 + np.cos(alt) * np.sin(slope) * np.cos(az - aspect))
        # Raw shade occupies a narrow band whose width depends on step and vertical scale,
        # which washes relief out. Stretch to the 2-98 percentile so it reads consistently
        # whether it's a 100k overview or a 4k valley.
        lo, hi = np.percentile(shade, (2.0, 98.0))
        if hi - lo < 1e-6:
            return np.clip(shade, 0.0, 1.0)
        return np.clip((shade - lo) / (hi - lo), 0.0, 1.0)

    def shaded_rgb(self):
        """Biome colour modulated by relief — reads as a physical map."""
        s = self.hillshade()
        if s is None:
            return self.rgb
        return np.clip(self.rgb * (0.45 + 0.85 * s)[..., None], 0.0, 1.0)

    def height_rgb(self):
        """Pure topography: bathymetry below sea level, elevation ramp above."""
        if self.height is None:
            return self.rgb
        import matplotlib
        cmap = (lambda n: matplotlib.colormaps[n]) if hasattr(matplotlib, "colormaps") \
            else (lambda n: matplotlib.cm.get_cmap(n))
        h = self.height
        sea = h < self.sea_level
        out = np.zeros(h.shape + (3,), dtype=np.float32)
        if sea.any():
            d = h[sea]
            t = (d - d.min()) / max(1.0, float(np.ptp(d)))
            out[sea] = cmap("Blues_r")(0.15 + 0.55 * t)[:, :3]
        if (~sea).any():
            e = h[~sea]
            t = (e - e.min()) / max(1.0, float(np.ptp(e)))
            out[~sea] = cmap("terrain")(0.25 + 0.75 * t)[:, :3]
        s = self.hillshade()
        return np.clip(out * (0.45 + 0.85 * s)[..., None], 0.0, 1.0)

    def height_at(self, wx, wz):
        if self.height is None:
            return None
        m = self.meta
        ix = int(round((wx - (m["centerX"] - m["radius"])) / m["step"]))
        iz = int(round((wz - (m["centerZ"] - m["radius"])) / m["step"]))
        if 0 <= ix < self.n and 0 <= iz < self.n:
            return int(self.height[iz, ix])
        return None

    def extent(self):
        r, cx, cz = self.meta["radius"], self.meta["centerX"], self.meta["centerZ"]
        return [cx - r, cx + r, cz + r, cz - r]   # north up

    def biome_at(self, wx, wz):
        m = self.meta
        ix = int(round((wx - (m["centerX"] - m["radius"])) / m["step"]))
        iz = int(round((wz - (m["centerZ"] - m["radius"])) / m["step"]))
        if 0 <= ix < self.n and 0 <= iz < self.n:
            return self.palette[self.ids[iz, ix]]
        return None


# ---------------------------------------------------------------------------
# browser
# ---------------------------------------------------------------------------

class App(tk.Tk):
    SORTS = ("seed", "land %", "variety")
    MODES = ("biome", "biome+relief", "topography")

    def __init__(self, folder):
        super().__init__()
        self.title("Terra Continental - seed browser")
        self.geometry("1280x900")
        self.folder = folder
        self.fav_path = None
        self.scans = []
        self.view = []
        self.favorites = set()
        self.cur = None
        self._build_ui()
        self.load_folder(folder)

    # -- ui ------------------------------------------------------------------

    def _build_ui(self):
        top = ttk.Frame(self, padding=6)
        top.pack(side=tk.TOP, fill=tk.X)
        ttk.Button(top, text="Load folder...", command=self.pick_folder).pack(side=tk.LEFT)
        ttk.Button(top, text="Refresh", command=self.refresh).pack(side=tk.LEFT, padx=(4, 12))

        self.mode = tk.StringVar(value=self.MODES[0])
        for m in self.MODES:
            ttk.Radiobutton(top, text=m, value=m, variable=self.mode,
                            command=self.draw).pack(side=tk.LEFT)
        self.contours = tk.BooleanVar(value=False)
        ttk.Checkbutton(top, text="contours", variable=self.contours,
                        command=self.draw).pack(side=tk.LEFT, padx=(12, 0))

        self.folder_lbl = ttk.Label(top, text="", foreground="#666")
        self.folder_lbl.pack(side=tk.RIGHT)

        # Status bar MUST be packed before the expanding body: tkinter hands space out in
        # pack order, so an expand=True sibling packed first leaves this with zero height
        # (which is what silently swallowed the hover readout).
        self.status = ttk.Label(self, text="hover the map to identify a biome",
                                anchor=tk.W, padding=4, font=("Consolas", 10),
                                relief=tk.SUNKEN)
        self.status.pack(side=tk.BOTTOM, fill=tk.X)

        body = ttk.Frame(self)
        body.pack(fill=tk.BOTH, expand=True)

        left = ttk.Frame(body, padding=(6, 0, 6, 6))
        left.pack(side=tk.LEFT, fill=tk.Y)

        srow = ttk.Frame(left)
        srow.pack(fill=tk.X, pady=(0, 4))
        ttk.Label(srow, text="sort").pack(side=tk.LEFT)
        self.sort = tk.StringVar(value=self.SORTS[0])
        ttk.Combobox(srow, textvariable=self.sort, values=self.SORTS, width=9,
                     state="readonly").pack(side=tk.LEFT, padx=4)
        self.sort.trace_add("write", lambda *_: self.repopulate())
        self.only_fav = tk.BooleanVar(value=False)
        ttk.Checkbutton(srow, text="fav only", variable=self.only_fav,
                        command=self.repopulate).pack(side=tk.LEFT)

        self.listbox = tk.Listbox(left, width=36, height=32,
                                  font=("Consolas", 9), exportselection=False)
        self.listbox.pack(fill=tk.Y, expand=True)
        self.listbox.bind("<<ListboxSelect>>", self.on_select)

        brow = ttk.Frame(left)
        brow.pack(fill=tk.X, pady=4)
        ttk.Button(brow, text="favourite", command=self.toggle_fav).pack(side=tk.LEFT)
        ttk.Button(brow, text="copy seed", command=self.copy_seed).pack(side=tk.LEFT, padx=4)
        ttk.Button(brow, text="delete", command=self.delete_scan).pack(side=tk.LEFT)

        # --- offline scanning: generate a scan for any seed without loading a world ------
        gen = ttk.LabelFrame(left, text="scan a seed (offline)", padding=4)
        gen.pack(fill=tk.X, pady=(8, 0))
        srow2 = ttk.Frame(gen)
        srow2.pack(fill=tk.X)
        self.seed_var = tk.StringVar(value="")
        ttk.Entry(srow2, textvariable=self.seed_var, width=22).pack(side=tk.LEFT)
        ttk.Button(srow2, text="random", width=7,
                   command=self.random_seed).pack(side=tk.LEFT, padx=2)
        prow = ttk.Frame(gen)
        prow.pack(fill=tk.X, pady=(4, 0))
        ttk.Label(prow, text="radius").pack(side=tk.LEFT)
        self.gen_radius = tk.StringVar(value="50000")
        ttk.Entry(prow, textvariable=self.gen_radius, width=7).pack(side=tk.LEFT, padx=(2, 6))
        ttk.Label(prow, text="step").pack(side=tk.LEFT)
        self.gen_step = tk.StringVar(value="256")
        ttk.Entry(prow, textvariable=self.gen_step, width=5).pack(side=tk.LEFT, padx=2)
        self.gen_btn = ttk.Button(gen, text="Generate scan", command=self.generate_scan)
        self.gen_btn.pack(fill=tk.X, pady=(4, 0))
        ttk.Label(gen, text="offline sim currently ~88% vs in-game;\nverify a finalist with "
                            "/continental biome_map",
                  font=("Consolas", 7), foreground="#a33", justify=tk.LEFT).pack(anchor=tk.W)

        self.info = ttk.Label(left, text="", justify=tk.LEFT, wraplength=260,
                              font=("Consolas", 8))
        self.info.pack(fill=tk.X)

        # Canvas + matplotlib's own toolbar, which is where zoom/pan/reset live.
        right = ttk.Frame(body)
        right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.fig = Figure(figsize=(9, 9), dpi=100)
        self.ax = self.fig.add_subplot(111)
        self.canvas = FigureCanvasTkAgg(self.fig, master=right)
        tb = NavigationToolbar2Tk(self.canvas, right, pack_toolbar=False)
        tb.update()
        tb.pack(side=tk.BOTTOM, fill=tk.X)
        self.canvas.get_tk_widget().pack(side=tk.TOP, fill=tk.BOTH, expand=True)
        self.canvas.mpl_connect("motion_notify_event", self.on_move)

        for key, d in (("<Left>", -1), ("<Up>", -1), ("<Right>", 1), ("<Down>", 1)):
            self.bind(key, lambda e, dd=d: self.step(dd))
        self.bind("f", lambda e: self.toggle_fav())
        self.bind("c", lambda e: self._toggle_contours())
        self.bind("m", lambda e: self.cycle_mode())

    def _toggle_contours(self):
        self.contours.set(not self.contours.get())
        self.draw()

    # -- data ----------------------------------------------------------------

    def load_folder(self, folder):
        try:
            folder = find_seedscan(folder)
        except SystemExit as exc:
            self.status.config(text=str(exc))
            return
        self.folder = folder
        self.folder_lbl.config(text=folder)
        self.fav_path = os.path.join(folder, "favorites.json")
        self.favorites = set()
        if os.path.exists(self.fav_path):
            try:
                with open(self.fav_path, encoding="utf-8") as fh:
                    self.favorites = set(json.load(fh))
            except Exception:
                pass
        self.scans = []
        self.refresh()

    def refresh(self):
        """Re-read the folder so scans generated since launch appear without a restart."""
        metas = sorted(glob.glob(os.path.join(self.folder, "biome_map_*.json"))
                       + glob.glob(os.path.join(self.folder, "offline_*.json")))
        keep = {s.path: s for s in self.scans}
        self.scans = [keep.get(p) or Scan(p) for p in metas]
        self.repopulate()

    def repopulate(self):
        pool = [s for s in self.scans
                if not self.only_fav.get() or s.seed in self.favorites]
        key = {"seed": lambda s: s.seed,
               "land %": lambda s: -s.land_pct,
               "variety": lambda s: -s.variety}[self.sort.get()]
        self.view = sorted(pool, key=key)
        self.listbox.delete(0, tk.END)
        for s in self.view:
            star = "*" if s.seed in self.favorites else " "
            kind = "~" if s.offline else ("^" if s.height is not None else " ")
            self.listbox.insert(
                tk.END, f"{star}{kind} {s.seed}  {s.land_pct:5.1f}%  {s.variety:3d}")
        if self.view:
            i = self.view.index(self.cur) if self.cur in self.view else 0
            self.listbox.selection_clear(0, tk.END)
            self.listbox.selection_set(i)
            self.listbox.see(i)
            self.cur = self.view[i]
            self.draw()
        else:
            self.cur = None
            self.ax.clear()
            self.canvas.draw_idle()
            self.status.config(text="no scans match the filter")

    # -- actions -------------------------------------------------------------

    def pick_folder(self):
        d = filedialog.askdirectory(
            title="Pick a seedscan folder (or an instance folder)",
            initialdir=self.folder if os.path.isdir(self.folder) else None)
        if d:
            self.load_folder(d)

    def on_select(self, _ev):
        sel = self.listbox.curselection()
        if sel:
            self.cur = self.view[sel[0]]
            self.draw()

    def step(self, d):
        if not self.view:
            return
        i = (self.view.index(self.cur) + d) % len(self.view) if self.cur in self.view else 0
        self.listbox.selection_clear(0, tk.END)
        self.listbox.selection_set(i)
        self.listbox.see(i)
        self.cur = self.view[i]
        self.draw()

    def cycle_mode(self):
        i = (self.MODES.index(self.mode.get()) + 1) % len(self.MODES)
        self.mode.set(self.MODES[i])
        self.draw()

    def toggle_fav(self):
        if not self.cur or not self.fav_path:
            return
        self.favorites.symmetric_difference_update({self.cur.seed})
        with open(self.fav_path, "w", encoding="utf-8") as fh:
            json.dump(sorted(self.favorites), fh, indent=1)
        self.repopulate()

    def copy_seed(self):
        if self.cur:
            self.clipboard_clear()
            self.clipboard_append(str(self.cur.seed))
            self.status.config(text=f"copied seed {self.cur.seed}")

    def delete_scan(self):
        """Remove the selected scan's files. In-game exports are expensive to regenerate,
        so those get a confirmation; offline scans are cheap and go quietly."""
        s = self.cur
        if s is None:
            return
        stem = s.path[:-5]
        files = [stem + ext for ext in (".json", ".bin", ".hgt", ".png")
                 if os.path.exists(stem + ext)]
        if not s.offline:
            if not messagebox.askyesno(
                    "Delete in-game scan?",
                    f"Delete the IN-GAME export for seed {s.seed}?\n\n"
                    f"{len(files)} file(s). This one came from the live generator and can only "
                    f"be recreated by running the command in game again."):
                return
        for f in files:
            try:
                os.remove(f)
            except OSError as exc:
                self.status.config(text=f"could not delete {os.path.basename(f)}: {exc}")
                return
        self.favorites.discard(s.seed)
        self.cur = None
        self.refresh()
        self.status.config(text=f"deleted {len(files)} file(s) for seed {s.seed}")

    def random_seed(self):
        self.seed_var.set(str(random.getrandbits(64) - (1 << 63)))

    def generate_scan(self):
        """Sample a seed with the offline pipeline and drop it into the folder as a scan.

        Runs on a worker thread so the window stays responsive; the offline sim takes
        seconds to a minute depending on radius/step.
        """
        try:
            seed = int(self.seed_var.get().strip())
            radius = int(self.gen_radius.get())
            step = int(self.gen_step.get())
        except ValueError:
            self.status.config(text="seed / radius / step must be integers")
            return
        if not os.path.isdir(self.folder):
            self.status.config(text="load a folder first (scans are written there)")
            return

        self.gen_btn.config(state=tk.DISABLED, text="Generating...")
        self.status.config(text=f"sampling seed {seed} offline...")

        def work():
            try:
                import tectonic_climate as tc
                instance = os.path.dirname(os.path.abspath(self.folder))
                sim = tc.TectonicSim(instance=instance)
                ids, names = sim.grid(seed, radius, step)
                n = ids.shape[0]
                # "offline_" keeps these clear of in-game exports, and radius/step keep
                # different zooms of one seed from overwriting each other.
                base = os.path.join(self.folder, f"offline_{seed}_r{radius}_s{step}")
                ids.astype("<u2").tofile(base + ".bin")
                with open(base + ".json", "w", encoding="utf-8") as fh:
                    json.dump({"seed": seed, "n": n, "radius": radius, "step": step,
                               "centerX": 0, "centerZ": 0, "palette": list(names),
                               "seaLevel": 63, "hasHeight": False, "offline": True}, fh)
                self.after(0, lambda: self._gen_done(seed, None))
            except Exception as exc:                      # surfaced in the status bar
                self.after(0, lambda e=exc: self._gen_done(seed, e))

        threading.Thread(target=work, daemon=True).start()

    def _gen_done(self, seed, err):
        self.gen_btn.config(state=tk.NORMAL, text="Generate scan")
        if err:
            self.status.config(text=f"offline scan failed: {err}")
            return
        self.refresh()
        for i, s in enumerate(self.view):
            if s.seed == seed:
                self.listbox.selection_clear(0, tk.END)
                self.listbox.selection_set(i)
                self.listbox.see(i)
                self.cur = s
                self.draw()
                break
        self.status.config(text=f"offline scan of seed {seed} ready (~88% vs in-game)")

    # -- render --------------------------------------------------------------

    def draw(self):
        s = self.cur
        if s is None:
            return
        mode = self.mode.get() if s.height is not None else "biome"
        img = {"biome": s.rgb,
               "biome+relief": s.shaded_rgb(),
               "topography": s.height_rgb()}[mode]
        self.ax.clear()
        self.ax.imshow(img, extent=s.extent(), interpolation="nearest")
        if self.contours.get() and s.height is not None:
            m = s.meta
            xs = np.linspace(m["centerX"] - m["radius"], m["centerX"] + m["radius"], s.n)
            zs = np.linspace(m["centerZ"] - m["radius"], m["centerZ"] + m["radius"], s.n)
            iv = max(16, int(round(float(np.ptp(s.height)) / 12 / 16)) * 16)
            self.ax.contour(xs, zs, s.height,
                            levels=np.arange(float(s.height.min()),
                                             float(s.height.max()), iv),
                            colors="k", linewidths=0.3, alpha=0.35)
        self.ax.set_xlabel("X (blocks)")
        self.ax.set_ylabel("Z (blocks)")
        self.canvas.draw_idle()

        top = "\n".join(f"  {p:5.1f}%  {nm.split(':')[-1]}" for nm, p in s.top)
        h = (f"\nheight y {int(s.height.min())}..{int(s.height.max())}"
             if s.height is not None
             else "\nno height - use /continental detail_map")
        if s.offline:
            h += "\nOFFLINE SCAN ~88% - verify in game"
        self.info.config(
            text=f"seed {s.seed}\nradius {s.meta['radius']}  step {s.meta['step']}\n"
                 f"land {s.land_pct:.1f}%   {s.variety} biomes{h}\n\ntop biomes:\n{top}")

    def on_move(self, ev):
        if self.cur is None or ev.inaxes is not self.ax or ev.xdata is None:
            return
        nm = self.cur.biome_at(ev.xdata, ev.ydata)
        ht = self.cur.height_at(ev.xdata, ev.ydata)
        self.status.config(
            text=f"x={int(ev.xdata):>8}  z={int(ev.ydata):>8}   {nm or '-'}"
                 + (f"   y={ht}" if ht is not None else ""))


def main():
    start = sys.argv[1] if len(sys.argv) > 1 else None
    try:
        folder = find_seedscan(start)
    except SystemExit as exc:
        print(exc)
        folder = start or "."
    App(folder).mainloop()


if __name__ == "__main__":
    main()
