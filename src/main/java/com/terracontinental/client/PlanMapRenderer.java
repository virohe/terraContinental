package com.terracontinental.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.terracontinental.TerraContinental;
import com.terracontinental.plan.LoadingStatus;
import com.terracontinental.plan.MapStats;
import com.terracontinental.plan.PlanPreview;
import com.terracontinental.plan.PlanPreviews;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Arrays;

/**
 * Draws the world plan's preview on the world-loading screen in place of vanilla's chunk-status
 * grid. The share of the map shown matches vanilla's spawn-area percentage (the user, 2026-10-30:
 * "at 50%, the map is 50% full"), nearest cells to the centre first; cells the plan hasn't worked
 * out yet stay transparent. Once the map is whole the planned villages pop in, then Terra Towns'
 * hamlet search shows: the spot it is checking, the sites it found, and a zoom onto each hamlet
 * as it is built ({@link LoadingStatus}), with a line of text under the map saying what's happening.
 */
public final class PlanMapRenderer {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(TerraContinental.MOD_ID, "plan_preview");

    private static DynamicTexture texture;
    private static PlanPreview shown;
    /** Cell indices nearest the centre first: the reveal order. */
    private static int[] order;
    private static boolean[] painted;
    /** Top and bottom edges of the map last frame, for placing text; -1 when not drawn. */
    private static int mapTop = -1;

    private PlanMapRenderer() {}

    /** Draws the map centred on (x, y); false when there is no plan and vanilla's grid should draw. */
    public static boolean draw(GuiGraphics g, int x, int y, int screenW, int screenH, int progressPercent) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        PlanPreview p = PlanPreviews.forServer(server);
        if (p == null) {
            if (!PlanPreviews.expected(server)) {
                mapTop = -1;
                placeControls(x, -1, screenW);
                return false;
            }
            // The overworld isn't built yet: the empty frame at its final size.
            int[] r = mapRect(x, screenW, screenH, WorldPlan.CELLS);
            g.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, 0xFF101418);
            updateOverall(null, progressPercent);
            drawStatus(g, x, r[1] + r[3] + 8, progressPercent);
            placeControls(x, -1, screenW);
            mapTop = r[1];
            return true;
        }
        if (p != shown || texture == null) {
            close();
            shown = p;
            texture = new DynamicTexture(p.cellsX(), p.cellsZ(), true);
            // The constructor only allocates GPU memory; until the first upload it holds whatever the
            // driver hands back, which after "Try a New Seed" was the discarded world's map, shown at
            // 0% under the new world (the user, 2026-11-10). Upload the cleared image at once.
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            order = revealOrder(p.cellsX(), p.cellsZ());
            painted = new boolean[order.length];
        }

        long now = Util.getMillis();
        update(p, now);
        updateOverall(p, progressPercent);
        boolean hamlets = villagesShown(now, progressPercent);
        updateCamera(p, now, hamlets);

        int[] r = mapRect(x, screenW, screenH, p.cellsX());
        int left = r[0], top = r[1], w = r[2], h = r[3];
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF101418);
        g.enableScissor(left, top, left + w, top + h);
        g.pose().pushPose();
        g.pose().translate(left + w / 2.0 - camX * w * camZoom, top + h / 2.0 - camZ * h * camZoom, 0);
        g.pose().scale((float) camZoom, (float) camZoom, 1f);
        g.blit(TEXTURE, 0, 0, w, h, 0f, 0f, p.cellsX(), p.cellsZ(), p.cellsX(), p.cellsZ());
        g.pose().popPose();
        drawMarkers(g, server, p, left, top, w, h, now, hamlets);
        g.disableScissor();
        int below = drawStatus(g, x, top + h + STATUS_GAP, progressPercent, now, hamlets);
        placeControls(x, below + BUTTON_GAP, screenW);
        drawStats(g, p, left, top);
        mapTop = top;
        return true;
    }

    // -- the sequence: map, then villages, then the hamlets with a zoom on each -----------------
    // The user, 2026-10-31: "map first, then dot the villages", then the hamlet search; zoom in to
    // the hamlet being placed, back out to the whole map once it's built, in again for the next.
    // Since 2026-11-10 ("I want to watch and see how the program runs") everything is live: the map
    // paints as the world plan learns each cell, and the village planner shows its candidate spots,
    // the spot it is checking, the spots that failed and each village as it is placed. Before, the
    // map waited on vanilla's spawn-area percentage, which only moves after all of that is done.
    // The zooms still trail the server: if building runs ahead, they play the sites in order.

    private static final long VILLAGE_POP = 240;
    private static final long ZOOM_IN = 750, ZOOM_OUT = 600, HOLD_IN = 500, HOLD_OUT = 250;
    /** How far in the camera goes: 4x shows about 16 km of the 64 km map. */
    private static final double ZOOM = 4.0;

    /** When the whole map had been painted; -1 until then. */
    private static long mapDoneAt = -1;
    private static int paintedCount;
    /** When each planned village was first seen, in LoadingStatus order. */
    private static final java.util.List<Long> villageSeen = new java.util.ArrayList<>();

    private enum Cam { FULL, GOING_IN, IN, GOING_OUT }
    private static Cam cam = Cam.FULL;
    private static double camX = 0.5, camZ = 0.5, camZoom = 1;
    private static double fromX, fromZ, fromZoom, toX, toZ, toZoom;
    private static long tweenStart, tweenLength, arrivedAt;
    private static int nextBuild;
    private static int[] focus;

    /** When village k pops in: the moment the planner placed it. */
    private static long villageAppearsAt(int k) {
        return villageSeen.get(k);
    }

    /**
     * True once the village plan is done and its last village has popped in: the hamlets' turn. The
     * spawn area starting to load also counts as done, for a world without a village plan.
     */
    private static boolean villagesShown(long now, int progressPercent) {
        java.util.List<int[]> v = LoadingStatus.villageSpots();
        while (villageSeen.size() < v.size()) villageSeen.add(now);
        if (!LoadingStatus.villagesDone() && progressPercent <= 0) return false;
        return villageSeen.isEmpty() || now >= villageAppearsAt(villageSeen.size() - 1) + VILLAGE_POP;
    }

    // -- one percentage for the whole load ------------------------------------------------------
    // The user, 2026-11-10: vanilla's percentage counts only the spawn area, a few seconds at the
    // end of the map and villages, then sits at 100% through the hamlets. Each stage gets a share
    // of the bar, roughly its share of the time on the real pack (rig, 2026-11-10: plan ~37 s,
    // villages ~6 s, spawn area ~5 s, hamlet search ~40 s, eight builds ~60 s each).

    private static int overall;

    private static final double[] WITH_HAMLETS = {0.15, 0.05, 0.05, 0.15, 0.60};
    private static final double[] WITHOUT_HAMLETS = {0.60, 0.20, 0.20, 0, 0};
    private static final int HAMLETS = 8;

    private static void updateOverall(PlanPreview p, int vanillaPercent) {
        boolean towns = SeedRetry.isNewWorld() && net.neoforged.fml.ModList.get().isLoaded("terra_towns");
        double[] w = towns ? WITH_HAMLETS : WITHOUT_HAMLETS;
        double plan = p == null ? 0 : p.progress();
        double villages;
        int target = LoadingStatus.villageTarget();
        if (LoadingStatus.villagesDone() || vanillaPercent > 0 || !com.terracontinental.spawn.SpawnVillagePlanner.enabled()) {
            villages = 1;
        } else if (target == 0) {
            villages = 0;
        } else {
            double measured = LoadingStatus.villageCandidates().isEmpty() ? 0 : 0.3;
            villages = measured + 0.7 * Math.min(1, LoadingStatus.villageSpots().size() / (double) target);
        }
        double spawn = Math.max(0, Math.min(100, vanillaPercent)) / 100.0;
        int built = LoadingStatus.builtSpots().size();
        int found = LoadingStatus.chosenSpots().size() + built;
        boolean building = !LoadingStatus.buildOrder().isEmpty();
        double search = building ? 1 : Math.min(1, found / (double) HAMLETS);
        double builds = building ? Math.min(1, built / (double) Math.max(1, found)) : 0;
        double share = w[0] * plan + w[1] * villages + w[2] * spawn + w[3] * search + w[4] * builds;
        overall = Math.max(overall, Math.min(100, (int) Math.floor(100 * share)));
    }

    /** The percentage above the map: the whole load, not just vanilla's spawn area. */
    public static net.minecraft.network.chat.Component progressText(net.minecraft.network.chat.Component vanilla) {
        if (mapTop < 0) return vanilla;
        return net.minecraft.network.chat.Component.translatable("loading.progress", overall);
    }

    private static void updateCamera(PlanPreview p, long now, boolean hamlets) {
        if (cam == Cam.GOING_IN || cam == Cam.GOING_OUT) {
            double t = Math.min(1, (now - tweenStart) / (double) tweenLength);
            double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; // ease in-out
            // Zoom in log space so the speed looks even; centre linearly.
            camZoom = Math.exp(Math.log(fromZoom) + (Math.log(toZoom) - Math.log(fromZoom)) * e);
            camX = fromX + (toX - fromX) * e;
            camZ = fromZ + (toZ - fromZ) * e;
            if (t >= 1) {
                cam = cam == Cam.GOING_IN ? Cam.IN : Cam.FULL;
                arrivedAt = now;
            }
            return;
        }
        if (cam == Cam.IN && LoadingStatus.isBuilt(focus[0], focus[1]) && now - arrivedAt >= HOLD_IN) {
            tween(0.5, 0.5, 1, now, ZOOM_OUT, Cam.GOING_OUT);
        } else if (cam == Cam.FULL && hamlets && nextBuild < LoadingStatus.buildOrder().size()
                && now - arrivedAt >= HOLD_OUT) {
            focus = LoadingStatus.buildOrder().get(nextBuild++);
            double half = 0.5 / ZOOM; // keep the zoomed view inside the map
            double fx = (focus[0] - p.originX()) / (double) (p.cellsX() * p.blocksPerCell());
            double fz = (focus[1] - p.originZ()) / (double) (p.cellsZ() * p.blocksPerCell());
            tween(Math.max(half, Math.min(1 - half, fx)), Math.max(half, Math.min(1 - half, fz)), ZOOM,
                    now, ZOOM_IN, Cam.GOING_IN);
        }
    }

    private static void tween(double x, double z, double zoom, long now, long length, Cam state) {
        fromX = camX; fromZ = camZ; fromZoom = camZoom;
        toX = x; toZ = z; toZoom = zoom;
        tweenStart = now;
        tweenLength = length;
        cam = state;
    }

    /** Text and button rows round the map: the percentage above it; status, warning, buttons below. */
    private static final int ABOVE = 13, STATUS_GAP = 8, LINE = 11, BUTTON_GAP = 6;

    /** Is the "Try a New Seed" row shown on this load? Only for a world being created now. */
    private static boolean controls() {
        return SeedRetry.available() || SeedRetry.requested();
    }

    /**
     * Left, top, width, height of the map, centred across at x: square, a whole multiple of the cell
     * grid where it fits, so cells stay crisp. The map, the percentage above it and the rows below it
     * (status, one warning line kept free so the button doesn't jump when a warning arrives, the
     * buttons) are centred down the screen together.
     */
    private static int[] mapRect(int x, int screenW, int screenH, int cells) {
        int below = STATUS_GAP + 2 * LINE + (controls() ? BUTTON_GAP + LoadingControls.BUTTON_H : 0);
        int room = Math.min(screenW - 40, screenH - ABOVE - below - 16);
        int scale = Math.max(1, room / cells);
        int w = cells * scale;
        if (room < cells) { // tiny window: shrink instead
            w = Math.max(32, room);
        }
        int top = (screenH - ABOVE - w - below) / 2 + ABOVE;
        return new int[] {x - w / 2, top, w, w};
    }

    /**
     * Puts the buttons on their row at {@code y}, or hides them ({@code y < 0}). "Try a New Seed"
     * sits under the middle of the map; once clicked it reads "Keep This World" in the same place and
     * the discard button appears to its right, or on the next row if the window is too narrow.
     */
    private static void placeControls(int x, int y, int screenW) {
        net.minecraft.client.gui.components.Button t = LoadingControls.tryButton, c = LoadingControls.confirmButton;
        if (t == null || c == null) return;
        boolean show = y >= 0 && SeedRetry.available();
        t.visible = show;
        c.visible = show && LoadingControls.confirming;
        if (!show) return;
        t.setPosition(x - t.getWidth() / 2, y);
        int cx = x + t.getWidth() / 2 + 24;
        if (cx + c.getWidth() <= screenW - 4) {
            c.setPosition(cx, y);
        } else {
            c.setPosition(x - c.getWidth() / 2, y + LoadingControls.BUTTON_H + 4);
        }
    }

    /** Where vanilla's percentage text should go: just above the map, or unchanged. */
    public static int textY(int vanillaY) {
        return mapTop < 0 ? vanillaY : mapTop - 13;
    }

    private static int[] revealOrder(int nx, int nz) {
        double cx = (nx - 1) / 2.0, cz = (nz - 1) / 2.0;
        Integer[] idx = new Integer[nx * nz];
        double[] d2 = new double[nx * nz];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
            double dx = i % nx - cx, dz = i / nx - cz;
            d2[i] = dx * dx + dz * dz;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(d2[a], d2[b]));
        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i];
        return out;
    }

    /** Paints every cell the world plan knows so far; nearest the centre first, as it plans them. */
    private static void update(PlanPreview p, long now) {
        NativeImage img = texture.getPixels();
        if (img == null) return;
        int nx = p.cellsX();
        boolean changed = false;
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            if (painted[i]) continue;
            int argb = p.colorAt(i % nx, i / nx);
            if (argb == 0) continue; // the plan hasn't reached it yet; try again next frame
            img.setPixelRGBA(i % nx, i / nx, toAbgr(argb));
            painted[i] = true;
            paintedCount++;
            changed = true;
        }
        if (changed) texture.upload();
        if (mapDoneAt < 0 && (paintedCount == order.length || p.progress() >= 1f)) {
            mapDoneAt = now;
        }
    }

    private static final int DARK = 0xFF141414, GOLD = 0xFFF2C14E, WHITE = 0xFFFFFFFF, VILLAGE = 0xFFE06B4A,
            CANDIDATE = 0x99F4F1E6, REJECTED = 0xFF7A2E22;

    /**
     * Markers in screen pixels, not GUI pixels: at GUI scale 4 a 7-unit square was 28 pixels and
     * the hamlet markers covered the spawn continent (the user, 2026-10-31: "small indicators, not
     * map-blockers"). Each meaning gets its own shape: village diamond, found hollow, built filled,
     * a thin pulsing ring for the spot being checked, a white dot for spawn. On very high GUI
     * scales (4K) they grow in whole steps so they don't vanish.
     */
    private static void drawMarkers(GuiGraphics g, MinecraftServer server, PlanPreview p, int left, int top, int w, int h,
                                    long now, boolean hamlets) {
        double gui = Minecraft.getInstance().getWindow().getGuiScale();
        int u = Math.max(1, (int) (gui / 4));
        g.pose().pushPose();
        g.pose().scale((float) (1 / gui), (float) (1 / gui), 1f);
        Px px = new Px(p, left * gui, top * gui, w * gui, h * gui, camX, camZ, camZoom);

        if (!LoadingStatus.villagesDone()) {
            // The planner at work: faint dots for the spots it measured, dark red for checked spots
            // that failed, a ring on the one being checked now.
            for (int[] c : LoadingStatus.villageCandidates()) {
                int[] s = px.at(c[0], c[1]);
                if (s != null) square(g, s[0], s[1], u, CANDIDATE);
            }
            for (int[] c : LoadingStatus.villageRejectedSpots()) {
                int[] s = px.at(c[0], c[1]);
                if (s != null) square(g, s[0], s[1], u + (u + 1) / 2, REJECTED);
            }
            int[] vp = LoadingStatus.villageProbe();
            int[] vs = vp == null ? null : px.at(vp[0], vp[1]);
            if (vs != null) {
                ring(g, vs[0], vs[1], 6 * u, u, DARK);
                ring(g, vs[0], vs[1], 5 * u, u, WHITE);
            }
        }
        java.util.List<int[]> villages = LoadingStatus.villageSpots();
        for (int k = 0; k < Math.min(villages.size(), villageSeen.size()); k++) {
            double t = (now - villageAppearsAt(k)) / (double) VILLAGE_POP;
            if (t < 0) continue;
            int[] s = px.at(villages.get(k)[0], villages.get(k)[1]);
            if (s == null) continue;
            double pop = t >= 1 ? 1 : 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2); // ease-out-back
            int r = (int) Math.round(4 * u * pop);
            if (r < 1) continue;
            diamond(g, s[0], s[1], r + u, DARK);
            diamond(g, s[0], s[1], r, VILLAGE);
        }
        for (int[] c : hamlets ? LoadingStatus.chosenSpots() : java.util.List.<int[]>of()) {
            int[] s = px.at(c[0], c[1]);
            if (s == null) continue;
            ring(g, s[0], s[1], 5 * u, u, DARK);
            ring(g, s[0], s[1], 4 * u, 2 * u, GOLD);
            ring(g, s[0], s[1], 2 * u, u, DARK);
        }
        for (int[] b : hamlets ? LoadingStatus.builtSpots() : java.util.List.<int[]>of()) {
            int[] s = px.at(b[0], b[1]);
            if (s == null) continue;
            square(g, s[0], s[1], 4 * u, DARK);
            square(g, s[0], s[1], 3 * u, GOLD);
        }
        int[] probe = hamlets ? LoadingStatus.currentProbe() : null;
        int[] ps = probe == null ? null : px.at(probe[0], probe[1]);
        if (ps != null) {
            int r = (6 + (int) ((now / 200) % 2)) * u;
            ring(g, ps[0], ps[1], r + u, u, DARK);
            ring(g, ps[0], ps[1], r, u, WHITE);
        }
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld != null) {
            BlockPos spawn = overworld.getSharedSpawnPos();
            int[] s = px.at(spawn.getX(), spawn.getZ());
            if (s != null) {
                square(g, s[0], s[1], 3 * u, DARK);
                square(g, s[0], s[1], 2 * u, WHITE);
            }
        }
        g.pose().popPose();
    }

    /** Block column to screen pixel through the camera; null when well outside the map's frame. */
    private record Px(PlanPreview p, double left, double top, double w, double h, double cx, double cz, double zoom) {
        int[] at(int bx, int bz) {
            double fx = (bx - p.originX()) / (double) (p.cellsX() * p.blocksPerCell());
            double fz = (bz - p.originZ()) / (double) (p.cellsZ() * p.blocksPerCell());
            double sx = left + w / 2 + (fx - cx) * w * zoom, sz = top + h / 2 + (fz - cz) * h * zoom;
            if (sx < left - 16 || sz < top - 16 || sx > left + w + 16 || sz > top + h + 16) return null;
            return new int[] {(int) sx, (int) sz};
        }
    }

    /** A filled square 2r across centred on (cx, cy). */
    private static void square(GuiGraphics g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r, cy + r, color);
    }

    /** A square outline 2r across, {@code t} thick: crisp at any scale, which a drawn circle isn't. */
    private static void ring(GuiGraphics g, int cx, int cy, int r, int t, int color) {
        g.fill(cx - r, cy - r, cx + r, cy - r + t, color);
        g.fill(cx - r, cy + r - t, cx + r, cy + r, color);
        g.fill(cx - r, cy - r + t, cx - r + t, cy + r - t, color);
        g.fill(cx + r - t, cy - r + t, cx + r, cy + r - t, color);
    }

    /** A filled diamond of radius r, one row at a time. */
    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void drawStatus(GuiGraphics g, int centreX, int y, int progressPercent) {
        drawStatus(g, centreX, y, progressPercent, Util.getMillis(), false);
    }

    private static final int TEXT = 0xFFD8DEE2, WARNING = 0xFFFFB347;

    /**
     * The status line, then the warnings (the user, 2026-11-10: the first line says what's happening,
     * the second what looks wrong). Terra Towns' line and the warnings wait for their turn: the
     * villages are still popping in before them. Returns the y below the last line.
     */
    private static int drawStatus(GuiGraphics g, int centreX, int y, int progressPercent, long now, boolean hamlets) {
        String text = LoadingStatus.message();
        if (text == null) {
            text = mapDoneAt < 0 ? "Mapping the world"
                    : progressPercent < 100 ? "Generating the land around spawn" : "Getting the world ready";
        }
        if (LoadingStatus.retryRequested()) {
            text = "Stopping this world to try a new seed";
        } else if (LoadingControls.confirming && SeedRetry.available()) {
            text = "Discard this world and start again on a new seed?";
        }
        Font font = Minecraft.getInstance().font;
        g.drawCenteredString(font, text, centreX, y, TEXT);
        y += LINE;
        java.util.List<String> warnings = hamlets && !LoadingStatus.retryRequested()
                ? LoadingStatus.warnings() : java.util.List.of();
        for (String w : warnings) {
            g.drawCenteredString(font, w, centreX, y, WARNING);
            y += LINE;
        }
        return y + (warnings.isEmpty() ? LINE : 0);
    }

    private static final int STATS_W = 150, STATS_PAD = 6, LABEL = 0xFF9AA4AA;

    /**
     * The map's statistics in a box to the left of the map (the user, 2026-11-10), from the
     * finished plan; "Measuring" until then. Left out when the window is too narrow for it.
     */
    private static void drawStats(GuiGraphics g, PlanPreview p, int mapLeft, int top) {
        int x1 = mapLeft - 12, x0 = x1 - STATS_W;
        if (x0 < 4) return;
        Font font = Minecraft.getInstance().font;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        MapStats s = p.stats();
        if (s == null) {
            rows.add(new String[] {"Measuring the map", ""});
        } else {
            rows.add(new String[] {"Spawn continent", String.format("%,d km²", Math.round(s.spawnArea()))});
            rows.add(new String[] {"Highest point", s.peakY() == Integer.MIN_VALUE ? "-" : "y " + s.peakY()});
            rows.add(new String[] {"Nearest continent", Double.isNaN(s.gap()) ? "none in view"
                    : String.format("%.1f km", s.gap())});
            rows.add(new String[] {"Land on map", String.format("%.0f%%", s.landPercent())});
        }
        if (LoadingStatus.villagesDone()) {
            rows.add(new String[] {"Villages", Integer.toString(LoadingStatus.villageSpots().size())});
        }
        if (SeedRetry.isNewWorld() && net.neoforged.fml.ModList.get().isLoaded("terra_towns")) {
            rows.add(new String[] {"Hamlets", LoadingStatus.builtSpots().size() + " of " + HAMLETS});
        }
        int h = STATS_PAD * 2 + rows.size() * LINE + 2;
        g.fill(x0, top, x1, top + h, 0xB0101418);
        g.fill(x0, top, x1, top + 1, 0xFF2A3238);
        int y = top + STATS_PAD + 1;
        for (String[] r : rows) {
            g.drawString(font, r[0], x0 + STATS_PAD, y, LABEL, false);
            g.drawString(font, r[1], x1 - STATS_PAD - font.width(r[1]), y, 0xFFFFFFFF, false);
            y += LINE;
        }
    }

    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
    }

    /** Frees the texture (the screen calls this when it closes). */
    public static void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
        shown = null;
        order = null;
        painted = null;
        mapTop = -1;
        mapDoneAt = -1;
        overall = 0;
        paintedCount = 0;
        villageSeen.clear();
        cam = Cam.FULL;
        camX = camZ = 0.5;
        camZoom = 1;
        arrivedAt = 0;
        nextBuild = 0;
        focus = null;
    }
}
