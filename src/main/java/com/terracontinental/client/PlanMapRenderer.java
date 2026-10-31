package com.terracontinental.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.terracontinental.TerraContinental;
import com.terracontinental.plan.LoadingStatus;
import com.terracontinental.plan.PlanPreview;
import com.terracontinental.plan.PlanPreviews;
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
import java.util.List;

/**
 * Draws the world plan's preview on the world-loading screen in place of vanilla's chunk-status
 * grid. The share of the map shown matches vanilla's spawn-area percentage (the user, 2026-10-30:
 * "at 50%, the map is 50% full"), nearest cells to the centre first; cells the plan hasn't worked
 * out yet stay transparent. After 100%, while Terra Towns looks for the starting hamlets, the spot
 * it is checking and the spots it chose are drawn on top ({@link LoadingStatus}), with a line of
 * text under the map saying what's happening.
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
            mapTop = -1;
            return false;
        }
        if (p != shown || texture == null) {
            close();
            shown = p;
            texture = new DynamicTexture(p.cellsX(), p.cellsZ(), true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            order = revealOrder(p.cellsX(), p.cellsZ());
            painted = new boolean[order.length];
        }

        update(p, Math.max(0, Math.min(100, progressPercent)) / 100f);

        // Square, a whole multiple of the cell grid where it fits, so cells stay crisp. Room is
        // left below for the status line.
        int room = Math.min(screenW - 40, screenH - 80);
        int scale = Math.max(1, room / p.cellsX());
        int w = p.cellsX() * scale, h = p.cellsZ() * scale;
        if (room < p.cellsX()) { // tiny window: shrink instead
            w = h = Math.max(32, room);
        }
        int left = x - w / 2, top = y - h / 2;
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF101418);
        g.blit(TEXTURE, left, top, w, h, 0f, 0f, p.cellsX(), p.cellsZ(), p.cellsX(), p.cellsZ());
        drawSpawn(g, server, p, left, top, w, h);
        drawSearch(g, p, left, top, w, h);
        drawStatus(g, x, top + h + 8, progressPercent);
        mapTop = top;
        return true;
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

    private static void update(PlanPreview p, float share) {
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
            changed = true;
        }
        if (changed) texture.upload();
    }

    private static int[] toScreen(PlanPreview p, int bx, int bz, int left, int top, int w, int h) {
        double fx = (bx - p.originX()) / (double) (p.cellsX() * p.blocksPerCell());
        double fz = (bz - p.originZ()) / (double) (p.cellsZ() * p.blocksPerCell());
        if (fx < 0 || fz < 0 || fx > 1 || fz > 1) return null;
        return new int[] {left + (int) (fx * w), top + (int) (fz * h)};
    }

    private static void drawSpawn(GuiGraphics g, MinecraftServer server, PlanPreview p, int left, int top, int w, int h) {
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;
        BlockPos spawn = overworld.getSharedSpawnPos();
        int[] s = toScreen(p, spawn.getX(), spawn.getZ(), left, top, w, h);
        if (s == null) return;
        g.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, 0xFF000000);
        g.fill(s[0] - 1, s[1] - 1, s[0] + 2, s[1] + 2, 0xFFFFFFFF);
    }

    /** Chosen spots as gold dots; the spot being checked as a pulsing ring. */
    private static void drawSearch(GuiGraphics g, PlanPreview p, int left, int top, int w, int h) {
        List<int[]> chosen = LoadingStatus.chosenSpots();
        for (int[] c : chosen) {
            int[] s = toScreen(p, c[0], c[1], left, top, w, h);
            if (s == null) continue;
            g.fill(s[0] - 3, s[1] - 3, s[0] + 4, s[1] + 4, 0xFF1B1B1B);
            g.fill(s[0] - 2, s[1] - 2, s[0] + 3, s[1] + 3, 0xFFF2C14E);
        }
        int[] probe = LoadingStatus.currentProbe();
        if (probe == null) return;
        int[] s = toScreen(p, probe[0], probe[1], left, top, w, h);
        if (s == null) return;
        int r = 4 + (int) ((Util.getMillis() / 120) % 4);
        ring(g, s[0], s[1], r + 1, 0xFF000000);
        ring(g, s[0], s[1], r, 0xFFFFFFFF);
    }

    /** A square outline: crisp at any GUI scale, which a drawn circle isn't. */
    private static void ring(GuiGraphics g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);
        g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);
        g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);
        g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);
    }

    private static void drawStatus(GuiGraphics g, int centreX, int y, int progressPercent) {
        String text = LoadingStatus.message();
        if (text == null) {
            text = progressPercent < 100 ? "Generating the land around spawn" : "Getting the world ready";
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
    }
}
