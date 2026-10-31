"""
Terrain shape experiments as datapacks: modified copies of the terrain splines, written the way
the terrain pack would ship them, so a variant that wins can move into resourcepacks/terrain unchanged.

    python preview/shape_pack.py <out dir> key=value ...
    python preview/terrain_preview.py ... --variant "name: pack=<out dir> vertical_scale=2.2"

Why each knob exists (measured 2026-10-23 with the previewer, pack settings):
- The base terrain gives full mountain height from continentalness C = 0, which Terra Continental's
  continent field reaches a median ~300 blocks from the sea (10% within ~100): the coastal wall.
  coast_c0/c1/c2 remap C for the terrain splines only (biomes keep the real C), so mountains
  start further inland: raw C c0 is where the base terrain's C = 0 lands, c1 its 0.1, c2 its 0.8.
- Mountains are the band |E| < 0.3 of folded erosion, i.e. along every erosion zero-line: a
  lattice of chains over 34% of the land. mountain_band (raw |E|) is where the base terrain's 0.3 lands;
  smaller means thinner, fewer mountain areas.
- Inside that band the base terrain cuts no valleys. valley_* add flat-floored, steep-walled valleys
  where |R| (folded ridges, the same lines the base terrain's lowland rivers follow) is small: floor
  below valley_width, wall up to valley_width + valley_wall. The floor follows continentalness:
  fjord_floor (negative: below sea level, a sea arm) where the mountains begin, valley_floor by
  fjord_reach further in, valley_floor_high deep inland. With one floor value per spline point,
  the coast remap left most mountain land near the fjord end, and the valleys flooded into sea
  channels across whole ranges (blend run, 2026-10-23).
- jag scales the base terrain's mountain jaggedness (the needle texture) and keeps it off valley floors;
  rough scales the 3-D roughness every land surface gets.
- giant_height/giant_radius add one massif per continent (terra_continental:giant_peaks). A giant
  already in the terrain pack is replaced, not stacked. Tour fixes (2026-10-24): giant_warp roughens its
  round outline, giant_spurs carves ridges and gullies down its flanks and giant_texture adds
  knolls ~250 blocks across (a smooth round dome showed its block steps as concentric rings); giant_flatten (0-1) squashes the base terrain's own relief
  under the dome toward giant_flatten_to, so its hills and terraced plateaus no longer ride up as
  separate knobs (within giant_flatten_reach of the radius: the user preferred the base terrain's own
  rugged flanks, so only the summit needs it); giant_cap (a share of giant_height) instead bends
  the base terrain's relief down, smoothly, where giant plus terrain would come within reach of the main
  summit, so its mountains near the top stay as varied lesser summits but none outgrows the main
  peak or meets the world ceiling (the original's flat-cut tops on 2 of 4 seeds); giant_biome (above 1 saturates the core) pulls the biome erosion and weirdness inside giant_biome_reach
  of the radius toward vanilla's peaks, so lowland forests stop running up the summit;
  giant_crags adds the base terrain's crag texture (jaggedness) within giant_crags_reach of the radius,
  since flattening the relief under the massif left its summit a smooth dome; giant_summit (the
  summit cone's share of the height), giant_summit_width (its radius as a share of the massif's)
  and giant_summit_power (higher: more concave) shape the top; giant_companions adds that many
  lesser summits round it (the tallest's tip at companion_height of the main summit, the rest a
  little lower; companion_width of the radius wide, companion_dist of the radius out);
  giant_variety (0-1) gives each massif its own height, size, summit shape, companions, spurs
  and stretch (round cone to a long massif with a summit ridge).
- flat_lift raises ground whose offset is near 0 (the base terrain's flats sit ~+1-5 above sea) by up to
  this much, fading out by offset 0.15 and below -0.05 so river channels stay at sea level.
  (The ponds turned out not to come from that: see wetland. Without flat_ends the curve kept
  falling past 0.15 and pulled every mountain down, 609 -> 457.)
- wetland: the base terrain's "diamond" regions carry a wetland noise (xz 0.8) that dips the ground
  below sea level wherever |noise| > 0.3, a lace of ponds ~100 blocks across. It is meant for
  hot, wet climates (temperature index 4), but the spline holds that value below 4, so cold and
  temperate diamond ground got it too: 9% of it water, ~1% elsewhere (pond crop, 2026-10-23).
  "off" removes the dips; a number scales the noise thresholds (1.5: dips only past 0.45).
- wetland_gate keeps the base terrain's pond density but only inside marsh patches ~1 km across: the
  same wetland noise at a tenth of its frequency (xz 0.08) must pass this threshold, with a 0.04
  blend at the edge. Share of ground inside patches, measured on two seeds: 0.21 -> ~25%,
  0.26 -> ~20%, 0.39 -> ~10%. (User, 2026-10-23: same density of lakes, less of the land.)
- hill_rivers=off keeps the base terrain's surface rivers only where the ground around them is low: the
  offset is built twice, as is (wet) and with every river dip filled to its bank (dry, rivers=off's
  rule, in the spline and in terra_continental:region/*), and the wet one is used where the dry ground is
  below hill_rivers_low, the dry one above hill_rivers_high. hill_rivers_keep (0-1) keeps that
  share of each valley, dry: filling channels to the bank also filled real valleys in the hills,
  up to 70 blocks, and the ranges lost their character (the user, 2026-10-24). hill_rivers_keep=dry
  lifts only the river bed out of the water and leaves every valley, cliff and gorge as it was.
  hill_rivers_giant=false leaves the giant's gorges alone (their walls are its cliffs and spurs).
- high_river_biomes=off drops river biomes where the ground is high (offset high_river_low to
  high_river_high), keeping them along real rivers for spawning (the user, 2026-10-24). Rivers stay in the lowlands and fade
  out as the ground rises, instead of cutting sea-level trenches through hills and mountains (the
  user, 2026-10-24). Under a giant the dry offset is used throughout, and giant_terraces (up to 2;
  its mask out to giant_terraces_reach of the radius) squashes its low ground (offset below ~0.2:
  the base terrain's stepped plateaus, tiers and river loops)
  toward giant_flatten_to while its mountains stay: lifted onto the dome they read as topo-map
  contour steps.
- high_coast_biomes=off keeps shore biomes (stony_shore, beaches) at the shore: where the ground's
  offset passes high_coast_low -> high_coast_high, the biome continentalness is lifted to at least
  high_coast_to, out of vanilla's coast band. The coast remap raises mountains on ground whose
  real C is still in that band, and vanilla's coast slice at low erosion is stony_shore (the
  user's flight, 2026-10-25: flat Stony Shore at y ~213 in the south-coast mountains).
- rivers=off removes the base terrain's surface rivers, for the planned-drainage study (RIVERS.md):
  every spline over folded ridges whose first point dips below sea level (offset < 0 at
  |R| <= 0.05, in the offset spline and in terra_continental:region/*) holds its bank value (the first
  point with offset >= 0) from |R| = 0 to the bank, so the ground keeps its local height
  instead of falling to a sea-level channel. the base terrain's river lines close into loops (ring
  lakes); planned rivers would replace them, not run beside them.
- range_scale lays mountains out as a few long chains instead of the base terrain's lattice (user: one
  or two chains per continent plus massifs). M is a low-frequency noise
  (terra_continental:tectonic at xz range_scale): its zero-lines are long and sparse. Within
  range_width of them (|M| < w) the base terrain's |E| is untouched, so its mountains, gaps and passes
  stay; elsewhere |E| gets up to +0.35, tapering to nothing by |E| 0.6, which turns lattice
  mountains (|E| < 0.3) into foothills and plateaus (0.35-0.475) and leaves the base terrain's flats,
  rivers and lakes (|E| > 0.6) alone. range_massif keeps standalone massifs where |E| is below
  it even off the chains.

- warp scales the climate noises' domain warp (shift_x/shift_z on continents, erosion, ridges);
  0.5 puts it back at vanilla's size in the world. the base terrain's doubled warp is what made the ranges
  knobby (range study, 2026-10-30).
- linear_density=true removes the kink the base terrain's sloped_cheese puts at the surface, which made
  contour-line terraces every 8 blocks of height on gentle slopes.
- crest_height (offset units, before vertical_scale) with crest_width (|E|) and crest_power: a
  knife-edged spine along each range's centre line, for Teton/Alps-like crests.

Keys (default = the terrain pack unchanged): warp, linear_density, crest_height, crest_width, crest_power, coast_c0, coast_c1, coast_c2, mountain_band, valley_width,
valley_wall, valley_floor, valley_floor_high, fjord_floor, fjord_reach, flat_lift, wetland, wetland_gate, jag, rough, giant_height, giant_radius, giant_reach, giant_warp, giant_spurs, giant_texture, giant_grain, giant_flatten, giant_flatten_to, giant_flatten_reach, giant_cap, giant_terraces, giant_terraces_reach, giant_biome, giant_biome_reach, giant_crags, giant_crags_reach, giant_summit, giant_summit_width, giant_summit_power, giant_companions, giant_companion_height, giant_companion_width, giant_companion_dist, giant_variety, rivers, hill_rivers, hill_rivers_low, hill_rivers_high, hill_rivers_keep, hill_rivers_giant, high_river_biomes, high_river_low, high_river_high, high_coast_biomes, high_coast_low,
high_coast_high, high_coast_to, range_scale, range_width, range_massif.
"""

import copy
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

C = "terra_continental:noise/raw_continents"
E = "terra_continental:noise/continent/erosion_folded"
R = "terra_continental:noise/continent/ridges_folded"
SHAPE_C = "terra_continental:shape/continents"
SHAPE_E = "terra_continental:shape/erosion_folded"
SHAPE_OFFSET = "terra_continental:shape/offset_base"
TERRAIN = ["terra_continental:terrain_spline/offset/continents", "terra_continental:terrain_spline/factor/continents",
           "terra_continental:terrain_spline/jaggedness/continents", "terra_continental:terrain_spline/offset/depth_additive"]


def linear_spline(coord, pts, flat_ends=False):
    """A monotone spline through (location, value) pairs (Fritsch-Carlson derivatives: the
    harmonic mean of the neighbouring slopes, 0 at a turn or next to a flat run). A plain mean
    overshot: a flat run beside a steep wall swung a 0-1 mask up to 5.6 (shape run, 2026-10-23)."""
    slopes = [(pts[i + 1][1] - pts[i][1]) / (pts[i + 1][0] - pts[i][0]) for i in range(len(pts) - 1)]
    out = []
    for i, (x, y) in enumerate(pts):
        if i == 0:
            d = 0.0 if flat_ends else slopes[0]
        elif i == len(pts) - 1:
            d = 0.0 if flat_ends else slopes[-1]
        elif slopes[i - 1] * slopes[i] <= 0:
            d = 0.0
        else:
            d = 2.0 / (1.0 / slopes[i - 1] + 1.0 / slopes[i])
        out.append({"location": x, "value": y, "derivative": d})
    return {"type": "minecraft:spline", "spline": {"coordinate": coord, "points": out}}


def replace_coord(node, old, new):
    if isinstance(node, dict):
        return {k: (new if k == "coordinate" and v == old else replace_coord(v, old, new))
                for k, v in node.items()}
    if isinstance(node, list):
        return [replace_coord(v, old, new) for v in node]
    return node


def unwrap(node):
    while isinstance(node, dict) and node.get("type") in ("minecraft:flat_cache", "minecraft:cache_2d"):
        node = node["argument"]
    return node


def valley(v, floor, w, wall):
    """A mountain value v cut by a valley along small |R|: flat floor, then a steep wall."""
    return {"coordinate": R, "points": [
        {"location": 0.0, "value": floor, "derivative": 0.0},
        {"location": w, "value": floor, "derivative": 0.0},
        {"location": w + wall, "value": v, "derivative": 0.0}]}


def add_valleys(offset_spline, p, ccoord):
    """Cut valleys into every numeric mountain value (|E| <= 0.3) under inland C points."""
    floor = linear_spline(ccoord, [(0.0, p["fjord_floor"]), (p["fjord_reach"], p["valley_floor"]),
                                   (0.8, p["valley_floor_high"])])["spline"]
    s = copy.deepcopy(offset_spline)
    for cp in s["points"]:
        loc = cp["location"]
        if loc < 0.0 or not isinstance(cp["value"], dict) or cp["value"].get("coordinate") not in (E, SHAPE_E):
            continue
        for ep in cp["value"]["points"]:
            if ep["location"] <= 0.3 and isinstance(ep["value"], (int, float)):
                ep["value"] = valley(ep["value"], floor, p["valley_width"], p["valley_wall"])
    return s


WETLAND = "terra_continental:region/diamond/wetland"


def reshape_wetland(node, how, gate=None):
    """Every spline over the wetland noise: flat (off), with its thresholds pushed out (a
    number), and/or only inside marsh patches (gate threshold on the same noise at xz 0.08)."""
    if isinstance(node, list):
        return [reshape_wetland(v, how, gate) for v in node]
    if not isinstance(node, dict):
        return node
    c = node.get("coordinate")
    if isinstance(c, dict) and c.get("noise") == WETLAND and c.get("xz_scale") != 0.08:
        dry = max(p["value"] for p in node["points"])
        if how == "off":
            return dry
        if how is not None:
            for pt in node["points"]:
                pt["location"] *= float(how)
                pt["derivative"] = pt.get("derivative", 0.0) / float(how)
        if gate is not None:
            return {"coordinate": {"type": "minecraft:noise", "noise": WETLAND, "xz_scale": 0.08, "y_scale": 0},
                    "points": [{"location": gate, "value": dry, "derivative": 0.0},
                               {"location": gate + 0.04, "value": node, "derivative": 0.0}]}
        return node
    return {k: reshape_wetland(v, how, gate) for k, v in node.items()}


REGIONS = ["terra_continental:region/club", "terra_continental:region/club_weak", "terra_continental:region/diamond",
           "terra_continental:region/heart", "terra_continental:region/spade", "terra_continental:region/spade_weak"]


def no_rivers(node, keep=0.0):
    """Every river dip over R (first point below sea level at |R| <= 0.05) flattened to its bank;
    with keep > 0, raised only to (1 - keep) of the bank, so a dry valley that deep remains; with
    keep "dry", only the points below sea level are lifted, to DRY_FLOOR, and nothing else moves."""
    if isinstance(node, list):
        return [no_rivers(v, keep) for v in node]
    if not isinstance(node, dict):
        return node
    node = {k: no_rivers(v, keep) for k, v in node.items()}
    pts = node.get("points")
    if (node.get("coordinate") == R and pts and isinstance(pts[0]["value"], (int, float))
            and pts[0]["value"] < 0 and pts[0]["location"] <= 0.05):
        bank = next((i for i, pt in enumerate(pts)
                     if not isinstance(pt["value"], (int, float)) or pt["value"] >= 0), None)
        if keep == "dry":
            # Lift only the water: half-filled valleys rounded cliffs and subdued the ridges
            # between them (giant preview g24: 9-19% of a giant raised, up to 111 blocks).
            for pt in pts:
                if isinstance(pt["value"], (int, float)) and pt["value"] < DRY_FLOOR:
                    pt["value"] = DRY_FLOOR
                    pt["derivative"] = 0.0
        elif bank is not None and isinstance(pts[bank]["value"], (int, float)):
            floor = pts[bank]["value"] * (1.0 - keep)
            for pt in pts[:bank]:
                pt["value"] = max(pt["value"], floor) if keep else pts[bank]["value"]
                pt["derivative"] = 0.0
            if not keep:
                pts[bank]["derivative"] = 0.0
    return node


DEFAULTS = {"warp": None, "linear_density": None,
            "crest_height": None, "crest_width": 0.15, "crest_power": 1.5,
            "coast_c0": None, "coast_c1": None, "coast_c2": None, "mountain_band": None,
            "valley_width": None, "valley_wall": 0.03, "valley_floor": 0.06, "valley_floor_high": 0.15,
            "fjord_floor": -0.08, "fjord_reach": 0.03,
            "flat_lift": None, "wetland": None, "wetland_gate": None, "jag": 1.0, "rough": 1.0, "giant_height": None, "giant_radius": 2400.0, "giant_reach": 0.35,
            "giant_warp": 0.0, "giant_spurs": 0.0, "giant_texture": 0.0, "giant_grain": 0.0, "giant_flatten": 0.0, "giant_flatten_to": 0.05, "giant_flatten_reach": 1.0, "giant_cap": None, "giant_terraces": 0.0, "giant_terraces_reach": 1.0,
            "hill_rivers": None, "hill_rivers_low": 0.12, "hill_rivers_high": 0.25, "hill_rivers_keep": 0.0, "hill_rivers_giant": True,
            "high_river_biomes": None, "high_river_low": 0.15, "high_river_high": 0.3,
            "high_coast_biomes": None, "high_coast_low": 0.05, "high_coast_high": 0.12, "high_coast_to": -0.1,
            "giant_biome": 0.0, "giant_biome_reach": 0.6, "giant_crags": 0.0, "giant_crags_reach": 0.3,
            "giant_summit": 0.35, "giant_summit_width": 0.3333, "giant_summit_power": 1.5,
            "giant_companions": 0, "giant_companion_height": 0.85, "giant_companion_width": 0.12,
            "giant_companion_dist": 0.22, "giant_variety": 0.0,
            "rivers": None, "range_scale": None, "range_width": 0.12, "range_massif": None}

RANGE_E = "terra_continental:shape/range_erosion"
GIANT = "terra_continental:giant_peaks"
DRY_FLOOR = 0.01    # a lifted river bed's offset: just above sea level
OFFSET_WET = "terra_continental:shape/offset_wet"
OFFSET_DRY = "terra_continental:shape/offset_dry"
OFFSET_BLEND = "terra_continental:shape/offset_blend"
DRY = "terra_continental:dry/"
REGION_SETS = ["terra_continental:terrain_spline/offset/regions", "terra_continental:terrain_spline/offset/regions_weak"]


def replace_refs(node, mapping):
    """Every string that names a function in mapping (a reference or a spline coordinate)."""
    if isinstance(node, str):
        return mapping.get(node, node)
    if isinstance(node, dict):
        return {k: replace_refs(v, mapping) for k, v in node.items()}
    if isinstance(node, list):
        return [replace_refs(v, mapping) for v in node]
    return node


def dry_offset(offset, reg, out, keep=0.0):
    """The offset with every river dip filled to its bank, regions included (under their own
    ids, so the wet regions stay for the lowlands)."""
    mapping = {name: DRY + name.split(":", 1)[1] for name in REGIONS + REGION_SETS}
    for name in REGIONS + REGION_SETS:
        body = copy.deepcopy(out.get(name) or reg.df(name))
        out[mapping[name]] = replace_refs(no_rivers(body, keep), mapping)
    return replace_refs(no_rivers(copy.deepcopy(offset), keep), mapping)
ROUTER_EROSION = "minecraft:overworld/noise_router/erosion"
ROUTER_RIDGES = "minecraft:overworld/noise_router/ridges"
ROUTER_CONTINENTS = "minecraft:overworld/noise_router/continents"
FULL_C = "terra_continental:noise/full_continents"
SHAPE_C_BIOME = "terra_continental:shape/continents_biome"
# Vanilla's peaks: erosion index 0 (below -0.78) and weirdness where peaks-and-valleys is 1
# (|weirdness| = 2/3).
PEAK_EROSION = -0.9
PEAK_WEIRDNESS = 0.667


def strip_giant(node):
    """The offset without a giant the terrain pack already adds."""
    if isinstance(node, dict):
        if node.get("type") == "minecraft:add" and isinstance(node.get("argument2"), dict)                 and node["argument2"].get("type") == GIANT:
            return strip_giant(node["argument1"])
        return {k: strip_giant(v) for k, v in node.items()}
    if isinstance(node, list):
        return [strip_giant(v) for v in node]
    return node


def giant_node(p, **extra):
    n = {"type": GIANT, "height": p["giant_height"], "radius": p["giant_radius"],
         "reach": p["giant_reach"], "jitter_noise": "terra_continental:continent_jitter"}
    if p["giant_warp"] or p["giant_spurs"] or p["giant_texture"] or p["giant_grain"]:
        n.update({"warp": p["giant_warp"], "spurs": p["giant_spurs"], "texture": p["giant_texture"],
                  "detail_noise": "terra_continental:tectonic"})
    if (p["giant_summit"], p["giant_summit_width"], p["giant_summit_power"]) != (0.35, 0.3333, 1.5):
        n.update({"summit": p["giant_summit"], "summit_width": p["giant_summit_width"],
                  "summit_power": p["giant_summit_power"]})
    if p["giant_grain"]:
        n["grain"] = p["giant_grain"]
    if p["giant_variety"]:
        n["variety"] = p["giant_variety"]
    if p["giant_companions"]:
        n.update({"companions": p["giant_companions"], "companion_height": p["giant_companion_height"],
                  "companion_width": p["giant_companion_width"], "companion_dist": p["giant_companion_dist"]})
    n.update(extra)
    return n


def lerp_to(value, target, weight):
    """value * (1 - weight) + target * weight, as density-function JSON."""
    return {"type": "minecraft:add",
            "argument1": {"type": "minecraft:mul", "argument1": value, "argument2": {
                "type": "minecraft:add", "argument1": 1.0, "argument2": {
                    "type": "minecraft:mul", "argument1": -1.0, "argument2": weight}}},
            "argument2": {"type": "minecraft:mul", "argument1": target, "argument2": weight}}


def range_erosion(p):
    """Folded erosion pushed out of the mountain band away from M's zero-lines (range_scale)."""
    m = {"type": "minecraft:abs", "argument": {"type": "minecraft:noise", "noise": "terra_continental:tectonic",
                                               "xz_scale": p["range_scale"], "y_scale": 0.0}}
    w = p["range_width"]
    off_chain = linear_spline(m, [(w, 0.0), (w + 0.1, 1.0)], flat_ends=True)
    taper = {"type": "minecraft:clamp", "input": {"type": "minecraft:add", "argument1": 1.0, "argument2": {
        "type": "minecraft:mul", "argument1": -1.0 / 0.6, "argument2": E}}, "min": 0.0, "max": 1.0}
    push = {"type": "minecraft:mul", "argument1": 0.35, "argument2": {
        "type": "minecraft:mul", "argument1": off_chain, "argument2": taper}}
    if p["range_massif"] is not None:
        mm = p["range_massif"]
        push = {"type": "minecraft:mul", "argument1": push,
                "argument2": linear_spline(E, [(mm, 0.0), (mm + 0.05, 1.0)], flat_ends=True)}
    return {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:add", "argument1": E, "argument2": push}}


def crest(reg, p):
    """A spine along each range's centre: crest_height * (1 - |E| / crest_width) ^ crest_power.
    Ranges are the band of small folded erosion |E| and their middle is E's zero line, where |E|
    turns sharply, so the spine comes out knife-edged along the range (an arete), not rounded."""
    w, pw = p["crest_width"], p["crest_power"]
    pts = [(w * t, (1.0 - t) ** pw) for t in (0.0, 0.1, 0.25, 0.45, 0.7, 1.0)]
    profile = linear_spline("terra_continental:shape/crest_e", pts + [(2.0, 0.0)], flat_ends=False)
    profile["spline"]["points"][-1]["derivative"] = 0.0
    profile["spline"]["points"][-2]["derivative"] = 0.0
    return {"type": "minecraft:mul", "argument1": p["crest_height"], "argument2": profile}


def build(reg, params):
    """{function id: json} for the pack."""
    p = dict(DEFAULTS)
    p.update(params)
    out = {}
    src = {name: copy.deepcopy(reg.df(name)) for name in TERRAIN}
    if p["coast_c0"] is not None:
        c0, c1, c2 = p["coast_c0"], p["coast_c1"] or p["coast_c0"] + 0.2, p["coast_c2"] or 1.2
        out[SHAPE_C] = {"type": "minecraft:flat_cache", "argument": linear_spline(
            C, [(-1.5, -1.5), (-0.155, -0.155), (c0, 0.0), (c1, 0.1), (c2, 0.8), (c2 + 1.0, 1.8)])}
        src = {k: replace_coord(v, C, SHAPE_C) for k, v in src.items()}
    e_in = E
    if p["range_scale"] is not None:
        out[RANGE_E] = range_erosion(p)
        e_in = RANGE_E
    if p["mountain_band"] is not None:
        m = p["mountain_band"]
        out[SHAPE_E] = {"type": "minecraft:flat_cache", "argument": linear_spline(
            e_in, [(0.0, 0.0), (m, 0.3), (0.45, 0.425), (2.0, 1.975)])}
        src = {k: replace_coord(v, E, SHAPE_E) for k, v in src.items()}
    elif e_in != E:
        src = {k: replace_coord(v, E, e_in) for k, v in src.items()}
    offset = unwrap(src[TERRAIN[0]])
    if p["rivers"] == "off":
        offset = no_rivers(offset)
    if p["valley_width"] is not None:
        ccoord = SHAPE_C if p["coast_c0"] is not None else C
        offset = {"type": "minecraft:spline", "spline": add_valleys(offset["spline"], p, ccoord)}
    if p["flat_lift"] is not None:
        # Keep the shaped offset under its own id so the lift can read it without evaluating
        # it twice.
        out[SHAPE_OFFSET] = {"type": "minecraft:flat_cache", "argument": offset}
        lift = p["flat_lift"]
        offset = {"type": "minecraft:add", "argument1": SHAPE_OFFSET, "argument2": linear_spline(
            SHAPE_OFFSET, [(-0.05, 0.0), (0.0, lift), (0.08, lift), (0.15, 0.0)], flat_ends=True)}
    if p["giant_height"] is not None:
        offset = strip_giant(offset)
    if p["high_river_biomes"] == "off":
        # Rivers' biomes stay along real rivers (spawning needs them) but not at altitude: where
        # the ground's offset passes high_river_low -> high_river_high, weirdness near 0 (the river
        # band, |w| < ~0.05) is stretched away from 0, so the band shrinks to slivers under a
        # biome cell. Per column, so a river down in a valley keeps its biome.
        w = "terra_continental:biome_parameter/ridges"
        gap = {"type": "minecraft:spline", "spline": {"coordinate": w, "points": [
            {"location": -2.0, "value": -2.0, "derivative": 1.0},
            {"location": -0.05, "value": -0.12, "derivative": 1.0},
            {"location": -0.004, "value": -0.1, "derivative": 0.0},
            {"location": 0.004, "value": 0.1, "derivative": 0.0},
            {"location": 0.05, "value": 0.12, "derivative": 1.0},
            {"location": 2.0, "value": 2.0, "derivative": 1.0}]}}
        alt = linear_spline(TERRAIN[0], [(p["high_river_low"], 0.0), (p["high_river_high"], 1.0)], flat_ends=True)
        # Built before the giant's pull, which wraps it.
        out[ROUTER_RIDGES] = lerp_to(w, gap, alt)
    if p["high_coast_biomes"] == "off":
        # Shore biomes only at the shore: where the ground's offset passes high_coast_low ->
        # high_coast_high, the biome continentalness is lifted to at least high_coast_to (vanilla's
        # near-inland slice starts at -0.11), so mountains the coast remap raised on ground whose C
        # is still in vanilla's coast band (-0.19 to -0.11) stop drawing stony_shore on their
        # slopes (the user's flight, 2026-10-25: flat Stony Shore at y ~213). Per column; 2-D, so
        # it replaces only the router's surface term and leaves the cave compression as it was.
        alt = linear_spline(TERRAIN[0], [(p["high_coast_low"], 0.0), (p["high_coast_high"], 1.0)], flat_ends=True)
        lifted = {"type": "minecraft:max", "argument1": FULL_C, "argument2": p["high_coast_to"]}
        out[SHAPE_C_BIOME] = {"type": "minecraft:flat_cache", "argument": lerp_to(FULL_C, lifted, alt)}
        router = copy.deepcopy(reg.df(ROUTER_CONTINENTS))
        if router.get("type") != "minecraft:add" or router.get("argument1") != FULL_C:
            raise SystemExit(f"{ROUTER_CONTINENTS} is not add({FULL_C}, ...); update high_coast_biomes")
        router["argument1"] = SHAPE_C_BIOME
        out[ROUTER_CONTINENTS] = router
    if p["hill_rivers"] == "off":
        out[OFFSET_WET] = {"type": "minecraft:flat_cache", "argument": offset}
        out[OFFSET_DRY] = {"type": "minecraft:flat_cache", "argument": dry_offset(offset, reg, out, p["hill_rivers_keep"])}
        weight = linear_spline(OFFSET_DRY, [(p["hill_rivers_low"], 0.0), (p["hill_rivers_high"], 1.0)],
                               flat_ends=True)
        if p["giant_height"] is not None and p["hill_rivers_giant"]:
            weight = {"type": "minecraft:max", "argument1": weight, "argument2": {
                "type": "minecraft:clamp", "min": 0.0, "max": 1.0, "input": {
                    "type": "minecraft:mul", "argument1": 2.0,
                    "argument2": giant_node(p, output="mask", mask_reach=p["giant_terraces_reach"])}}}
        offset = lerp_to(OFFSET_WET, OFFSET_DRY, weight)
    if p["giant_height"] is not None:
        if p["giant_terraces"]:
            # Low ground (offset below 0.2) squashed toward giant_flatten_to with a little relief
            # kept, hills and mountains from 0.6 untouched, and a ramp near identity slope
            # between: with the squash up to 0.3 and a steep ramp to 0.5, mid-height hills were
            # flattened and new sheer walls formed where squashed ground met a kept mountain
            # (giant preview g22, two of four seeds).
            out[OFFSET_BLEND] = {"type": "minecraft:flat_cache", "argument": offset}
            ft = p["giant_flatten_to"]
            squash = linear_spline(OFFSET_BLEND, [(-0.5, ft - 0.075), (0.2, ft + 0.06), (0.6, 0.6),
                                                  (2.5, 2.5)])
            # Clamped, so giant_terraces above 1 holds full strength out to where the mask is
            # 1/giant_terraces; giant_terraces_reach above 1 carries it past the dome's edge (at
            # 0.9 within reach 1, plateau walls on the outer flanks still read as contour lines).
            weight = {"type": "minecraft:clamp", "min": 0.0, "max": 1.0, "input": {
                "type": "minecraft:mul", "argument1": p["giant_terraces"],
                "argument2": giant_node(p, output="mask", mask_reach=p["giant_terraces_reach"])}}
            offset = lerp_to(OFFSET_BLEND, squash, weight)
        if p["giant_flatten"]:
            weight = {"type": "minecraft:mul", "argument1": p["giant_flatten"],
                      "argument2": giant_node(p, output="mask", mask_reach=p["giant_flatten_reach"])}
            offset = lerp_to(offset, p["giant_flatten_to"], weight)
        if p["giant_cap"] is not None:
            # base' = cap + knee(base - cap): unchanged well below the cap, then bending over to
            # at most +0.1 above it, so a capped mountain keeps its shape instead of a flat top.
            # Floored at 0: below that the cap squashed the ground under the main summit too,
            # pulling it under +1,000 on all four test seeds (giant preview g14).
            cap = {"type": "minecraft:max", "argument1": 0.0, "argument2": {
                "type": "minecraft:add", "argument1": p["giant_cap"] * p["giant_height"],
                "argument2": {"type": "minecraft:mul", "argument1": -1.0, "argument2": giant_node(p)}}}
            diff = {"type": "minecraft:add", "argument1": offset,
                    "argument2": {"type": "minecraft:mul", "argument1": -1.0, "argument2": cap}}
            knee = {"type": "minecraft:spline", "spline": {"coordinate": diff, "points": [
                {"location": -2.0, "value": -2.0, "derivative": 1.0},
                {"location": -0.3, "value": -0.3, "derivative": 1.0},
                {"location": 0.0, "value": -0.03, "derivative": 0.4},
                {"location": 0.3, "value": 0.07, "derivative": 0.15},
                {"location": 1.0, "value": 0.1, "derivative": 0.0}]}}
            offset = {"type": "minecraft:add", "argument1": cap, "argument2": knee}
        offset = {"type": "minecraft:add", "argument1": offset, "argument2": giant_node(p)}
        if p["giant_biome"]:
            # Clamped, so giant_biome above 1 saturates the core and steepens the edge.
            weight = {"type": "minecraft:clamp", "min": 0.0, "max": 1.0, "input": {
                "type": "minecraft:mul", "argument1": p["giant_biome"],
                "argument2": giant_node(p, output="mask", mask_reach=p["giant_biome_reach"])}}
            out[ROUTER_EROSION] = lerp_to("terra_continental:biome_parameter/erosion", PEAK_EROSION, weight)
            # Toward +-PEAK_WEIRDNESS by the value's own sign: pulling every value toward +0.667
            # carried negative weirdness through 0, the river band, and drew a ring of river
            # biome round each giant (river diagnosis, 2026-10-24).
            inner = out.get(ROUTER_RIDGES, "terra_continental:biome_parameter/ridges")
            if inner != "terra_continental:biome_parameter/ridges":
                out["terra_continental:shape/ridges_high"] = {"type": "minecraft:flat_cache", "argument": inner}
                inner = "terra_continental:shape/ridges_high"
            target = {"type": "minecraft:spline", "spline": {"coordinate": inner, "points": [
                {"location": -2.0, "value": -PEAK_WEIRDNESS, "derivative": 0.0},
                {"location": -0.02, "value": -PEAK_WEIRDNESS, "derivative": 0.0},
                {"location": 0.02, "value": PEAK_WEIRDNESS, "derivative": 0.0},
                {"location": 2.0, "value": PEAK_WEIRDNESS, "derivative": 0.0}]}}
            out[ROUTER_RIDGES] = lerp_to(inner, target, weight)
    if p["crest_height"] is not None:
        offset = {"type": "minecraft:add", "argument1": offset, "argument2": crest(reg, p)}
    src[TERRAIN[0]] = {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": offset}}
    jag = unwrap(src[TERRAIN[2]])
    factors = []
    if p["jag"] != 1.0:
        factors.append(p["jag"])
    if p["valley_width"] is not None:
        # No needles on valley floors; full texture again at the top of the wall.
        factors.append(linear_spline(R, [(0.0, 0.0), (p["valley_width"], 0.0),
                                         (p["valley_width"] + p["valley_wall"], 1.0), (2.0, 1.0)]))
    for f in factors:
        jag = {"type": "minecraft:mul", "argument1": jag, "argument2": f}
    if p["giant_height"] is not None and p["giant_crags"]:
        jag = {"type": "minecraft:add", "argument1": jag, "argument2": {
            "type": "minecraft:mul", "argument1": p["giant_crags"],
            "argument2": giant_node(p, output="mask", mask_reach=p["giant_crags_reach"])}}
    src[TERRAIN[2]] = {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": jag}}
    if p["wetland"] is not None or p["wetland_gate"] is not None:
        out["terra_continental:region/diamond"] = reshape_wetland(copy.deepcopy(reg.df("terra_continental:region/diamond")),
                                                         p["wetland"], p["wetland_gate"])
    if p["rivers"] == "off":
        for name in REGIONS:
            out[name] = no_rivers(copy.deepcopy(out.get(name) or reg.df(name)))
    if p["rough"] != 1.0:
        out["terra_continental:terrain_spline/roughness"] = {"type": "minecraft:mul", "argument1": p["rough"],
                                                    "argument2": copy.deepcopy(reg.df("terra_continental:terrain_spline/roughness"))}
    if p["warp"] is not None:
        # The climate noises' domain warp, scaled. Vanilla adds shift_x/shift_z after scaling the
        # coordinates, at xz_scale 0.25 (~+-16 blocks in the world); the terrain's 0.12-0.13 doubles
        # that to ~+-30 blocks at a 32-block wavelength, which crumples every steep slope into
        # knobs (range study, 2026-10-30: off, knob relief 11.2 -> 5.4 blocks). The shared
        # constants, so biomes and terrain stay aligned.
        for n in ("continents", "erosion", "ridge"):
            name = f"terra_continental:__constants/noise/{n}"
            body = copy.deepcopy(reg.df(name))
            for axis in ("shift_x", "shift_z"):
                if body.get(axis) != "minecraft:" + axis:
                    raise SystemExit(f"{name} {axis} is {body.get(axis)!r}, not the plain shift")
                body[axis] = {"type": "minecraft:mul", "argument1": p["warp"], "argument2": "minecraft:" + axis}
            out[name] = body
    if p["linear_density"]:
        # Density without the kink at the surface: 4 * quarter_negative(v) is 4v in the ground
        # and v in the air, and interpolating across that kink inside the 8-block noise cells lifts
        # the surface 0-9 blocks by where it falls in the cell: a contour line every 8 blocks on
        # gentle slopes (the striped plateau, the giant's rings; 2026-10-30). 4v everywhere keeps
        # the ground's values; only the air's change, and caves take min() with the ground.
        body = copy.deepcopy(reg.df("terra_continental:sloped_cheese"))
        inner = body["argument"]["argument1"]
        if inner.get("argument1") != 4 or inner["argument2"].get("type") != "minecraft:quarter_negative":
            raise SystemExit("terra_continental:sloped_cheese isn't 4 * quarter_negative(...) any more")
        inner["argument2"] = inner["argument2"]["argument"]
        out["terra_continental:sloped_cheese"] = body
    if p["crest_height"] is not None:
        e = "terra_continental:shape/erosion_folded" if "terra_continental:shape/erosion_folded" in reg._df else E
        out["terra_continental:shape/crest_e"] = {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:abs", "argument": e}}
    out.update(src)
    return out


def write(out_dir, functions, params):
    for name, body in functions.items():
        ns, path = name.split(":", 1)
        f = os.path.join(out_dir, "data", ns, "worldgen", "density_function", *path.split("/")) + ".json"
        os.makedirs(os.path.dirname(f), exist_ok=True)
        with open(f, "w", encoding="utf-8") as fh:
            json.dump(body, fh, indent=1)
    with open(os.path.join(out_dir, "pack.mcmeta"), "w", encoding="utf-8") as fh:
        json.dump({"pack": {"pack_format": 48, "description": "shape experiment " + json.dumps(params)}}, fh)


def main():
    import continental_climate
    out_dir = sys.argv[1]
    params = {}
    for a in sys.argv[2:]:
        k, v = a.split("=", 1)
        if k not in DEFAULTS:
            raise SystemExit(f"unknown key {k}; known: {', '.join(DEFAULTS)}")
        try:
            params[k] = json.loads(v)
        except ValueError:
            params[k] = v           # a bare word: rivers=off, wetland=off
    reg = continental_climate.ClimateSim().reg
    write(out_dir, build(reg, params), params)
    print(out_dir)


if __name__ == "__main__":
    main()
