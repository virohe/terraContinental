package com.terracontinental.atlas;

import com.terracontinental.plan.WorldPlan;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The chunks a continent's pregen covers: its land re-scanned at {@link #STEP} blocks, the small
 * islands whose coast is within {@link #ISLAND_REACH} of it, and {@link #SEA_MARGIN} of sea all
 * round (user, 2026-11-16: "a couple hundred blocks into the sea, so that an ocean horizon is
 * comprehended"; 512 picked).
 *
 * <p>The atlas's 512-block lattice finds and names the continent; this finer pass draws its
 * border. Land reached from the atlas continent's points is the continent; any other fine land
 * within the island reach, if the atlas doesn't count it as part of another continent, comes
 * along. Every chunk whose centre is within the margin of either is listed, region by region
 * (32 x 32 chunks: one region file at a time on disk) outward from the continent's centre.</p>
 */
public final class PregenMask {
    public static final int STEP = 128;
    public static final int SEA_MARGIN = 512;
    public static final int ISLAND_REACH = 2000;

    public final ContinentAtlas.Continent continent;
    /** Chunk positions, packed as {@link net.minecraft.world.level.ChunkPos#asLong}, in generation order. */
    public final long[] chunks;
    public final double landKm2;
    public final int islands;
    public final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;

    private PregenMask(ContinentAtlas.Continent continent, long[] chunks, double landKm2, int islands,
                       int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.continent = continent;
        this.chunks = chunks;
        this.landKm2 = landKm2;
        this.islands = islands;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    /** Builds the mask. Seconds of sampling: call off the server thread. */
    public static PregenMask build(ServerLevel level, ContinentAtlas atlas, ContinentAtlas.Continent c) {
        int pad = ISLAND_REACH + SEA_MARGIN + 2 * ContinentAtlas.STEP;
        int ox = Math.floorDiv(c.minX() - pad, STEP) * STEP, oz = Math.floorDiv(c.minZ() - pad, STEP) * STEP;
        int nx = (c.maxX() + pad - ox) / STEP + 1, nz = (c.maxZ() + pad - oz) / STEP + 1;

        var gen = level.getChunkSource().getGenerator();
        BiomeSource bs = gen.getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int sea = gen.getSeaLevel();
        boolean[] land = new boolean[nx * nz];
        IntStream.range(0, nx * nz).parallel().forEach(i -> {
            int x = ox + (i % nx) * STEP, z = oz + (i / nx) * STEP;
            land[i] = !WorldPlan.isOcean(bs.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(sea),
                    QuartPos.fromBlock(z), sampler));
        });
        return fromLand(atlas, c, land, ox, oz, nx, nz);
    }

    /** The geometry, given the fine land grid. Pure: the tests call it. */
    static PregenMask fromLand(ContinentAtlas atlas, ContinentAtlas.Continent c, boolean[] land,
                               int ox, int oz, int nx, int nz) {
        // 1. Fine components.
        int[] comp = new int[nx * nz];
        List<Integer> sizes = new ArrayList<>();
        sizes.add(0);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < nx * nz; s++) {
            if (!land[s] || comp[s] != 0) continue;
            int id = sizes.size(), cells = 0;
            comp[s] = id;
            q.add(s);
            while (!q.isEmpty()) {
                int i = q.poll();
                cells++;
                int ix = i % nx, iz = i / nx;
                if (ix > 0) push(i - 1, land, comp, id, q);
                if (ix < nx - 1) push(i + 1, land, comp, id, q);
                if (iz > 0) push(i - nx, land, comp, id, q);
                if (iz < nz - 1) push(i + nx, land, comp, id, q);
            }
            sizes.add(cells);
        }
        // 2. Which fine components are the continent, and which belong to another continent.
        boolean[] mine = new boolean[sizes.size()];
        boolean[] theirs = new boolean[sizes.size()];
        for (int i = 0; i < nx * nz; i++) {
            if (comp[i] == 0) continue;
            int ai = atlas.index(ox + (i % nx) * STEP, oz + (i / nx) * STEP);
            if (ai < 0 || atlas.label[ai] == 0) continue;
            int cont = atlas.componentContinent[atlas.label[ai] - 1];
            if (cont == c.id()) mine[comp[i]] = true;
            else if (cont >= 0) theirs[comp[i]] = true;
        }
        // 3. Islands near the coast come along (unless they're part of another continent).
        int reach = (int) Math.ceil(ISLAND_REACH / (double) STEP);
        boolean[] near = disk(comp, mine, nx, nz, reach, ISLAND_REACH);
        boolean[] take = mine.clone();
        int islands = 0;
        for (int i = 0; i < nx * nz; i++) {
            int id = comp[i];
            if (id != 0 && !take[id] && !theirs[id] && near[i]) {
                take[id] = true;
                islands++;
            }
        }
        double landKm2 = 0;
        for (int id = 1; id < take.length; id++) if (take[id]) landKm2 += sizes.get(id) * (double) STEP * STEP / 1e6;
        // 4. The sea margin: a cell is in if its centre is within the margin of taken land (plus
        // half a cell's diagonal, so no chunk centre near the margin falls through the lattice).
        double r = SEA_MARGIN + STEP * Math.sqrt(0.5);
        boolean[] in = disk(comp, take, nx, nz, (int) Math.ceil(r / STEP), r);
        // 5. Chunks whose centres fall in a taken cell, region by region outward from the centre.
        int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
        for (int i = 0; i < nx * nz; i++) {
            if (!in[i]) continue;
            int x = ox + (i % nx) * STEP, z = oz + (i / nx) * STEP;
            minCX = Math.min(minCX, (x - STEP / 2) >> 4);
            maxCX = Math.max(maxCX, (x + STEP / 2) >> 4);
            minCZ = Math.min(minCZ, (z - STEP / 2) >> 4);
            maxCZ = Math.max(maxCZ, (z + STEP / 2) >> 4);
        }
        long[] out = new long[(maxCX - minCX + 1) * (maxCZ - minCZ + 1)];
        int count = 0;
        int rx0 = minCX >> 5, rx1 = maxCX >> 5, rz0 = minCZ >> 5, rz1 = maxCZ >> 5;
        int ccx = c.centerX() >> 9, ccz = c.centerZ() >> 9;
        List<int[]> regions = new ArrayList<>();
        for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) regions.add(new int[]{rx, rz});
        regions.sort((a, b) -> Long.compare(
                (long) (a[0] - ccx) * (a[0] - ccx) + (long) (a[1] - ccz) * (a[1] - ccz),
                (long) (b[0] - ccx) * (b[0] - ccx) + (long) (b[1] - ccz) * (b[1] - ccz)));
        for (int[] reg : regions) {
            for (int dz = 0; dz < 32; dz++) {
                for (int dx = 0; dx < 32; dx++) {
                    int cx = (reg[0] << 5) + dx, cz = (reg[1] << 5) + dz;
                    int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
                    int ix = Math.floorDiv(bx - ox + STEP / 2, STEP), iz = Math.floorDiv(bz - oz + STEP / 2, STEP);
                    if (ix < 0 || iz < 0 || ix >= nx || iz >= nz || !in[iz * nx + ix]) continue;
                    out[count++] = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
                }
            }
        }
        return new PregenMask(c, Arrays.copyOf(out, count), landKm2, islands, minCX, minCZ, maxCX, maxCZ);
    }

    /** Cells whose centre is within {@code radius} blocks of a cell of a chosen component. */
    private static boolean[] disk(int[] comp, boolean[] chosen, int nx, int nz, int cells, double radius) {
        boolean[] out = new boolean[nx * nz];
        double r2 = (radius / STEP) * (radius / STEP);
        List<int[]> offsets = new ArrayList<>();
        for (int dz = -cells; dz <= cells; dz++)
            for (int dx = -cells; dx <= cells; dx++)
                if (dx * dx + dz * dz <= r2) offsets.add(new int[]{dx, dz});
        for (int i = 0; i < nx * nz; i++) {
            if (comp[i] == 0 || !chosen[comp[i]]) continue;
            int ix = i % nx, iz = i / nx;
            // Inland cells add nothing their neighbours don't: only spread from the coast.
            if (ix > 0 && ix < nx - 1 && iz > 0 && iz < nz - 1
                    && comp[i - 1] == comp[i] && comp[i + 1] == comp[i]
                    && comp[i - nx] == comp[i] && comp[i + nx] == comp[i]) {
                out[i] = true;
                continue;
            }
            for (int[] o : offsets) {
                int jx = ix + o[0], jz = iz + o[1];
                if (jx >= 0 && jz >= 0 && jx < nx && jz < nz) out[jz * nx + jx] = true;
            }
        }
        return out;
    }

    private static void push(int j, boolean[] land, int[] comp, int id, ArrayDeque<Integer> q) {
        if (land[j] && comp[j] == 0) {
            comp[j] = id;
            q.add(j);
        }
    }
}
