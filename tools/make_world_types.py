"""Generate Terra Continental's world types from the installed Tectonic jar.

    python tools/make_world_types.py <tectonic.jar>

Writes (src/main/resources):
  data/terra_continental/worldgen/noise_settings/overworld_large.json
  data/terra_continental/worldgen/noise_settings/overworld_climates.json
      Copies of Tectonic's minecraft:overworld noise settings (MIT, (c) the Tectonic authors), with
      only noise_router.temperature and .vegetation pointed at Terra Continental's climate functions.
      Everything else is referenced by id, so the seam and Tectonic's terrain apply unchanged.
      Re-run after every Tectonic update; TerraContinental logs a warning at startup when a copy
      no longer matches minecraft:overworld outside those two entries.

World types (the creation screen's list):
  TC: Large Biomes  terra_continental:large_biomes (a fresh Create World screen starts on it: client mixin)
                    climate zones 4x vanilla, temperate spawn continent
  TC: Temperate     terra_continental:temperate - minecraft:overworld itself: the pack's climate
                    (tectonic.json scales), temperate spawn continent
  TC: Climates      terra_continental:climates - climate zones 4x vanilla, no temperate override
"""
import copy
import json
import os
import sys
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources')
DATA = os.path.join(ROOT, 'data')


def write(rel, obj, base=DATA):
    p = os.path.join(base, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def large_noise(noise):
    # Tectonic's config_noise is noise(x * scale + shift) * multiplier + offset; this is the same with
    # scale 0.0625 (vanilla Large Biomes: the climate noises 4x larger than vanilla's 0.25) and the
    # pack's multiplier 1.1.
    return {"type": "minecraft:flat_cache", "argument": {
        "type": "minecraft:mul", "argument1": 1.1,
        "argument2": {"type": "minecraft:shifted_noise", "noise": noise, "xz_scale": 0.0625, "y_scale": 0.0,
                      "shift_x": "minecraft:shift_x", "shift_y": 0.0, "shift_z": "minecraft:shift_z"}}}


def temperate_spawn(t):
    # Same blend as the seam's noise_router/temperature: t + mask * (temperate - t).
    return {"type": "minecraft:add", "argument1": t, "argument2": {
        "type": "minecraft:mul", "argument1": {"type": "terra_continental:spawn_climate_mask"},
        "argument2": {"type": "minecraft:add", "argument1": "terra_continental:temperature_temperate",
                      "argument2": {"type": "minecraft:mul", "argument1": -1.0, "argument2": t}}}}


def lush_caves(v, seam_vegetation):
    # The seam's vegetation router with its input swapped: cave humidity stretch below depth 0.1.
    s = json.dumps(seam_vegetation)
    return json.loads(s.replace('"minecraft:overworld/vegetation"', json.dumps(v)))


def main(jar):
    z = zipfile.ZipFile(jar)
    base = json.loads(z.read('resourcepacks/tectonic/data/minecraft/worldgen/noise_settings/overworld.json'))
    seam_veg = json.load(open(os.path.join(ROOT, 'resourcepacks/tectonic_seam/data/minecraft/worldgen/density_function/overworld/noise_router/vegetation.json'), encoding='utf-8'))
    seam_veg.pop('_comment', None)

    write('terra_continental/worldgen/density_function/climate/temperature_large.json', large_noise('minecraft:temperature'))
    write('terra_continental/worldgen/density_function/climate/vegetation_large.json', large_noise('minecraft:vegetation'))
    write('terra_continental/worldgen/density_function/climate/large/temperature.json', temperate_spawn('terra_continental:climate/temperature_large'))
    write('terra_continental/worldgen/density_function/climate/large/vegetation.json', lush_caves('terra_continental:climate/vegetation_large', seam_veg))
    write('terra_continental/worldgen/density_function/climate/climates/temperature.json', {
        "type": "minecraft:add", "argument1": 0.0, "argument2": "terra_continental:climate/temperature_large"})

    for name, temp, veg in (('overworld_large', 'terra_continental:climate/large/temperature', 'terra_continental:climate/large/vegetation'),
                            ('overworld_climates', 'terra_continental:climate/climates/temperature', 'terra_continental:climate/large/vegetation')):
        s = copy.deepcopy(base)
        s['noise_router']['temperature'] = temp
        s['noise_router']['vegetation'] = veg
        write(f'terra_continental/worldgen/noise_settings/{name}.json', s)
        # Tectonic stretches world height only for minecraft:overworld; give the copies the same.
        write(f'terra_continental/lithostitched/worldgen_modifier/height_{name}.json', {
            "neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "tectonic"}],
            "type": "tectonic:set_height_limits", "dimension_type": "minecraft:overworld", "noise_settings": f"terra_continental:{name}"})

    nether = {"type": "minecraft:the_nether", "generator": {"type": "minecraft:noise", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}, "settings": "minecraft:nether"}}
    vanilla_end = {"type": "minecraft:the_end", "generator": {"type": "minecraft:noise", "biome_source": {"type": "minecraft:the_end"}, "settings": "minecraft:end"}}
    # The End stays vanilla in every TC type. WorldWeaver patches its biome source with BetterEnd's
    # biomes and ground, and that End keeps its layout across restarts. WorldWeaver's own BetterX End
    # (wover:betterx) re-rolls its biome map on every restart (audit 2026-10-22: 3-12 of 75 spots
    # matched chunks generated before it), so new End chunks would not join old ones.
    def preset(settings, end):
        return {"dimensions": {
            "minecraft:overworld": {"type": "minecraft:overworld", "generator": {"type": "minecraft:noise", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}, "settings": settings}},
            "minecraft:the_nether": nether, "minecraft:the_end": end}}

    presets = {'terra_continental/worldgen/world_preset/large_biomes.json': 'terra_continental:overworld_large',
               'terra_continental/worldgen/world_preset/temperate.json': 'minecraft:overworld',
               'terra_continental/worldgen/world_preset/climates.json': 'terra_continental:overworld_climates'}
    for rel, settings in presets.items():
        write(rel, preset(settings, vanilla_end))

    # Appended, not replacing: replacing the tag dropped types other mods add (Create Aeronautics' Airship
    # Ready and End Sea). The button order is set client-side (WorldCreationUiStateOrderMixin).
    write('minecraft/tags/worldgen/world_preset/normal.json', {"values": [
        "terra_continental:large_biomes", "terra_continental:temperate", "terra_continental:climates"]})
    print('world types written from', os.path.basename(jar))


if __name__ == '__main__':
    main(sys.argv[1])
