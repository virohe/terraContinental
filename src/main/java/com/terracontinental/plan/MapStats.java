package com.terracontinental.plan;

import com.terracontinental.density.SpawnSteer;

import java.util.ArrayList;
import java.util.List;

/**
 * The loading map's numbers, from the finished world plan (the same cells the map paints): the
 * spawn continent's land area and highest point, the gap to the nearest other continent, and how
 * much of the map is land (the user, 2026-11-10: a box beside the map with the map's statistics).
 * Land is a cell whose sea-level biome isn't ocean ({@link WorldPlan#isOcean}), as the map draws it.
 *
 * @param spawnArea   km2 of the landmass at (or nearest) the origin
 * @param landPercent all land, % of the map
 * @param gap         km from the spawn continent's coast to the nearest other landmass of 50 km2 or
 *                    more; NaN when there is none on the map
 * @param peakY       highest ground on the spawn continent, block y
 */
public record MapStats(double spawnArea, double landPercent, double gap, int peakY) {

    /** km2: smaller land doesn't count as a continent (SpawnSteer's measure). */
    private static final double BIG = 50.0;

    static MapStats of(byte[] kind, short[] height, int n, int step) {
        double px = step * (double) step / 1e6;
        boolean[] land = new boolean[n * n];
        int landCount = 0;
        for (int q = 0; q < n * n; q++) {
            land[q] = kind[q] == 1;
            if (land[q]) landCount++;
        }
        // 4-connected landmasses.
        int[] lab = new int[n * n];
        List<Integer> sizes = new ArrayList<>();
        sizes.add(0);
        int[] stack = new int[n * n];
        int nl = 0;
        for (int q = 0; q < n * n; q++) {
            if (!land[q] || lab[q] != 0) continue;
            nl++;
            int top = 0, size = 0;
            stack[top++] = q;
            lab[q] = nl;
            while (top > 0) {
                int v = stack[--top];
                size++;
                int i = v / n, j = v % n;
                if (i > 0 && land[v - n] && lab[v - n] == 0) { lab[v - n] = nl; stack[top++] = v - n; }
                if (i < n - 1 && land[v + n] && lab[v + n] == 0) { lab[v + n] = nl; stack[top++] = v + n; }
                if (j > 0 && land[v - 1] && lab[v - 1] == 0) { lab[v - 1] = nl; stack[top++] = v - 1; }
                if (j < n - 1 && land[v + 1] && lab[v + 1] == 0) { lab[v + 1] = nl; stack[top++] = v + 1; }
            }
            sizes.add(size);
        }
        double landPercent = landCount * 100.0 / (n * n);
        int c = n / 2;
        int sp = 0;
        if (land[c * n + c]) {
            sp = lab[c * n + c];
        } else {
            long best = Long.MAX_VALUE;
            for (int q = 0; q < n * n; q++) {
                if (!land[q]) continue;
                long dy = q / n - c, dx = q % n - c;
                if (dy * dy + dx * dx < best) {
                    best = dy * dy + dx * dx;
                    sp = lab[q];
                }
            }
        }
        if (sp == 0) return new MapStats(0.0, landPercent, Double.NaN, Integer.MIN_VALUE);

        int peak = Integer.MIN_VALUE;
        for (int q = 0; q < n * n; q++) if (lab[q] == sp) peak = Math.max(peak, height[q]);

        double gap = Double.NaN;
        boolean any = false;
        boolean[] big = new boolean[nl + 1];
        for (int i = 1; i <= nl; i++) {
            if (i != sp && sizes.get(i) * px >= BIG) {
                big[i] = true;
                any = true;
            }
        }
        if (any && n == SpawnSteer.N) {
            double[] f = new double[n * n];
            for (int q = 0; q < n * n; q++) f[q] = big[lab[q]] ? 0.0 : SpawnSteer.INF;
            SpawnSteer.edtSquared(f);
            double min = Double.POSITIVE_INFINITY;
            for (int q = 0; q < n * n; q++) if (lab[q] == sp) min = Math.min(min, f[q]);
            gap = Math.sqrt(min) * step / 1000.0;
        }
        return new MapStats(sizes.get(sp) * px, landPercent, gap, peak);
    }
}
