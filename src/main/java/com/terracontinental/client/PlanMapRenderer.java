package com.terracontinental.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.terracontinental.TerraContinental;
import com.terracontinental.plan.LoadingStatus;
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
                return false;
            }
            // The overworld isn't built yet: the empty frame at its final size.
            int[] r = mapRect(x, y, screenW, screenH, WorldPlan.CELLS);
            g.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, 0xFF101418);
            drawStatus(g, x, r[1] + r[3] + 8, progressPercent);
            mapTop = r[1];
            return true;
        }
        if (p != shown || texture == null) {
            close();
            shown = p;
            texture = new DynamicTexture(p.cellsX(), p.cellsZ(), true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            order = revealOrder(p.cellsX(), p.cellsZ());
            painted = new boolean[order.length];
        }

        long now = Util.getMillis();
        update(p, Math.max(0, Math.min(100, progressPercent)) / 100f, now);
        boolean hamlets = villagesShown(now);
        updateCamera(p, now, hamlets);

        int[] r = mapRect(x, y, screenW, screenH, p.cellsX());
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
        drawStatus(g, x, top + h + 8, progressPercent, now, hamlets);
        mapTop = top;
        return true;
    }

    // -- the sequence: map, then villages, then the hamlets with a zoom on each -----------------
    // The user, 2026-10-31: "map first, then dot the villages", then the hamlet search; zoom in to
    // the hamlet being placed, back out to the whole map once it's built, in again for the next.
    // The animation trails the server rather than holding it up: if building runs ahead, the
    // zooms play the sites in order until the screen closes.

    private static final long VILLAGE_STAGGER = 90, VILLAGE_POP = 240;
    private static final long ZOOM_IN = 750, ZOOM_OUT = 600, HOLD_IN = 500, HOLD_OUT = 250;
    /** How far in the camera goes: 4x shows about 16 km of the 64 km map. */
    private static final double ZOOM = 4.0;

    /** When the whole map had been painted; -1 until then. */
    private static long mapDoneAt = -1, fullSince = -1;
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

    /** When village k pops in: after the map, one after another, never before it's planned. */
    private static long villageAppearsAt(int k) {
        return Math.max(villageSeen.get(k), mapDoneAt + k * VILLAGE_STAGGER);
    }

    /** True once the map is painted and every village so far has popped in: the hamlets' turn. */
    private static boolean villagesShown(long now) {
        java.util.List<int[]> v = LoadingStatus.villageSpots();
        while (villageSeen.size() < v.size()) villageSeen.add(now);
        if (mapDoneAt < 0) return false;
        return villageSeen.isEmpty() || now >= villageAppearsAt(villageSeen.size() - 1) + VILLAGE_POP;
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

    /**
     * Left, top, width, height of the map centred on (x, y): square, a whole multiple of the cell
     * grid where it fits, so cells stay crisp. Room is left below for the status line.
     */
    private static int[] mapRect(int x, int y, int screenW, int screenH, int cells) {
        int room = Math.min(screenW - 40, screenH - 80);
        int scale = Math.max(1, room / cells);
        int w = cells * scale;
        if (room < cells) { // tiny window: shrink instead
            w = Math.max(32, room);
        }
        return new int[] {x - w / 2, y - w / 2, w, w};
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

    private static void update(PlanPreview p, float share, long now) {
        NativeImage img = texture.getPixels();
        if (img == null) return;
        int nx = p.cellsX();
        int count = share >= 1f ? order.length : (int) (share * order.length);
        boolean changed = false;
        for (int k = 0; k < count; k++) {
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
        if (share >= 1f && fullSince < 0) fullSince = now;
        // All painted; or 3 s at 100% with cells still missing (a failed plan), so the villages
        // and hamlets still get their turn.
        if (mapDoneAt < 0 && (paintedCount == order.length || fullSince >= 0 && now - fullSince > 3000)) {
            mapDoneAt = now;
        }
    }

    private static final int DARK = 0xFF141414, GOLD = 0xFFF2C14E, WHITE = 0xFFFFFFFF, VILLAGE = 0xFFE06B4A;

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

        java.util.List<int[]> villages = LoadingStatus.villageSpots();
        for (int k = 0; mapDoneAt >= 0 && k < Math.min(villages.size(), villageSeen.size()); k++) {
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

    /** Terra Towns' line waits for its turn too: the villages are still popping in before it. */
    private static void drawStatus(GuiGraphics g, int centreX, int y, int progressPercent, long now, boolean hamlets) {
        String text = hamlets ? LoadingStatus.message() : null;
        if (text == null) {
            text = mapDoneAt < 0 && progressPercent < 100 ? "Generating the land around spawn"
                    : !hamlets && !villageSeen.isEmpty() ? "Placing villages" : "Getting the world ready";
        }
        Font font = Minecraft.getInstance().font;
        g.drawCenteredString(font, text, centreX, y, 0xFFD8DEE2);
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
        fullSince = -1;
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
