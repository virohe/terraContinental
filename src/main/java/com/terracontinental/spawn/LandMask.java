package com.terracontinental.spawn;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * The landmass under a point: 128-block lattice, land = the biome at sea level is not an ocean,
 * flood-filled from the point (rivers and lakes stay land). Read from the generator; no chunks.
 * What /continental villages searches (audit 2026-10-18: the old radius circles counted villages
 * on other islands and missed ones on long landmasses).
 */
public final class LandMask {
    public static final int STEP = 128;
    public final int originX, originZ, n;
    private final boolean[] land;
    public final int cells;
    public final int minX, maxX, minZ, maxZ;
    public final long key;
    public final double centroidX, centroidZ;

    private LandMask(int originX, int originZ, int n, boolean[] land, int cells, int[] box, long key, double cx, double cz) {
        this.originX = originX;
        this.originZ = originZ;
        this.n = n;
        this.land = land;
        this.cells = cells;
        this.minX = box[0];
        this.maxX = box[1];
        this.minZ = box[2];
        this.maxZ = box[3];
        this.key = key;
        this.centroidX = cx;
        this.centroidZ = cz;
    }

    public boolean contains(int bx, int bz) {
        int ix = Math.round((bx - originX) / (float) STEP);
        int iz = Math.round((bz - originZ) / (float) STEP);
        return ix >= 0 && iz >= 0 && ix < n && iz < n && land[iz * n + ix];
    }

    public double areaKm2() {
        return cells * (double) STEP * STEP / 1e6;
    }

    /** The landmass at (x, z), searched out to {@code reach} blocks; null if (x, z) is open ocean. */
    @Nullable
    public static LandMask at(ServerLevel level, double x, double z, int reach) {
        var gen = level.getChunkSource().getGenerator();
        BiomeSource bs = gen.getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int sea = gen.getSeaLevel();
        int half = reach / STEP;
        int n = 2 * half + 1;
        int ox = (int) Math.round(x / STEP) * STEP - half * STEP;
        int oz = (int) Math.round(z / STEP) * STEP - half * STEP;
        byte[] raw = new byte[n * n]; // 0 unknown, 1 land, 2 ocean (sampled lazily: only what the fill reaches)
        boolean[] land = new boolean[n * n];
        // start: nearest land within 4 cells of the point
        int start = -1;
        for (int r = 0; r <= 4 && start < 0; r++) {
            for (int dz = -r; dz <= r && start < 0; dz++) {
                for (int dx = -r; dx <= r && start < 0; dx++) {
                    int i = (half + dz) * n + half + dx;
                    if (isLand(raw, i, n, ox, oz, sea, bs, sampler)) start = i;
                }
            }
        }
        if (start < 0) return null;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        land[start] = true;
        int cells = 0;
        long sx = 0, sz = 0;
        int[] box = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int i = q.poll();
            int ix = i % n, iz = i / n;
            cells++;
            sx += ix;
            sz += iz;
            int bx = ox + ix * STEP, bz = oz + iz * STEP;
            box[0] = Math.min(box[0], bx);
            box[1] = Math.max(box[1], bx);
            box[2] = Math.min(box[2], bz);
            box[3] = Math.max(box[3], bz);
            for (int[] d : nb) {
                int jx = ix + d[0], jz = iz + d[1];
                if (jx < 0 || jz < 0 || jx >= n || jz >= n) continue;
                int j = jz * n + jx;
                if (!land[j] && isLand(raw, j, n, ox, oz, sea, bs, sampler)) {
                    land[j] = true;
                    q.add(j);
                }
            }
        }
        // key: the landmass's first cell in world terms, so every player on it shares one cached answer
        long key = Long.MAX_VALUE;
        for (int i = 0; i < n * n; i++) {
            if (land[i]) {
                key = ((long) (ox / STEP + i % n) << 32) ^ (oz / STEP + i / n);
                break;
            }
        }
        return new LandMask(ox, oz, n, land, cells, box, key,
                ox + (double) sx / cells * STEP, oz + (double) sz / cells * STEP);
    }

    private static boolean isLand(byte[] raw, int i, int n, int ox, int oz, int sea, BiomeSource bs, Climate.Sampler sampler) {
        if (raw[i] == 0) {
            int x = ox + (i % n) * STEP, z = oz + (i / n) * STEP;
            var b = bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea), QuartPos.fromBlock(z), sampler);
            raw[i] = SpawnVillagePlanner.isOcean(b) ? (byte) 2 : (byte) 1;
        }
        return raw[i] == 1;
    }
}
