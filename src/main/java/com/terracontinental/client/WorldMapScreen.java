package com.terracontinental.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.terracontinental.atlas.WorldMapPayload;
import com.terracontinental.plan.WorldPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The World Atlas screen: the whole world's simulated land (the same colours as the loading map),
 * each continent named. Drag to pan, scroll to zoom, hover for what's under the cursor, click a
 * continent to select it and pregenerate it from the panel (it runs /continental pregenerate, so
 * the server checks the permission again).
 */
public final class WorldMapScreen extends Screen {
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath("terra_continental", "world_atlas");
    private static final int PANEL_W = 150, PAD = 6;
    private static final int BG = 0xF0101418, PANEL = 0xE0182026, EDGE = 0xFF2A3238, TEXT = 0xFFD8DEE2,
            LABEL = 0xFF9AA4AA, GOLD = 0xFFF2C14E, GREEN = 0xFF8FD18A;

    private final WorldMapPayload data;
    private final int n;
    private final byte[] heights, owners;
    private DynamicTexture texture;
    private int selected = -1, painted = -2;

    // view: world block at the centre of the map area, and blocks per screen pixel
    private double viewX, viewZ, scale, minScale, maxScale;
    private boolean dragging;
    private Button pregenButton;

    public WorldMapScreen(WorldMapPayload data) {
        super(Component.literal("World Atlas"));
        this.data = data;
        this.n = data.n();
        byte[][] u = data.unpack();
        this.heights = u[0];
        this.owners = u[1];
    }

    @Override
    protected void init() {
        if (texture == null) {
            texture = new DynamicTexture(n, n, false);
            painted = -2;
            Minecraft.getInstance().getTextureManager().register(TEX, texture);
            fitWorld();
        }
        int px = width - PANEL_W + PAD;
        pregenButton = addRenderableWidget(Button.builder(Component.literal("Pregenerate"), b -> pregen())
                .bounds(px, height - 52, PANEL_W - 2 * PAD, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(px, height - 28, PANEL_W - 2 * PAD, 20).build());
        updateButton();
    }

    /** Fits the land (not the whole empty sea out to the border) into the map area. */
    private void fitWorld() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (WorldMapPayload.Entry e : data.continents()) {
            minX = Math.min(minX, e.minX());
            minZ = Math.min(minZ, e.minZ());
            maxX = Math.max(maxX, e.maxX());
            maxZ = Math.max(maxZ, e.maxZ());
        }
        if (minX > maxX) {
            minX = minZ = -data.half() * data.step();
            maxX = maxZ = data.half() * data.step();
        }
        int pad = 8000;
        int mapW = Math.max(1, width - PANEL_W), mapH = Math.max(1, height);
        viewX = (minX + maxX) / 2.0;
        viewZ = (minZ + maxZ) / 2.0;
        scale = Math.max((maxX - minX + 2.0 * pad) / mapW, (maxZ - minZ + 2.0 * pad) / mapH);
        maxScale = Math.max(scale, 2.0 * data.half() * data.step() / Math.min(mapW, mapH));
        minScale = data.step() / 16.0;
    }

    // -- painting ---------------------------------------------------------------------------------

    private void paint() {
        if (painted == selected) return;
        painted = selected;
        NativeImage img = texture.getPixels();
        if (img == null) return;
        int sea = data.sea();
        for (int i = 0; i < n * n; i++) {
            int x = i % n, z = i / n;
            int owner = owners[i] & 0xFF;
            int h = sea + heights[i] * 8;
            int argb = WorldPlan.color(owner == 0, h, sea);
            if (owner != 0) {
                // Hill shading from the north-west: relief at a glance.
                int hn = z > 0 && x > 0 ? heights[i - n - 1] : heights[i];
                double shade = Mth.clamp(1.0 + (heights[i] - hn) * 0.06, 0.75, 1.25);
                argb = scaleRgb(argb, shade);
                // Coast line.
                if (x > 0 && x < n - 1 && z > 0 && z < n - 1
                        && (owners[i - 1] == 0 || owners[i + 1] == 0 || owners[i - n] == 0 || owners[i + n] == 0)) {
                    argb = scaleRgb(argb, 0.7);
                }
                if (selected >= 0 && owner != selected + 1) argb = scaleRgb(argb, 0.65);
                if (selected >= 0 && owner == selected + 1) argb = scaleRgb(argb, 1.12);
            } else if (selected >= 0) {
                argb = scaleRgb(argb, 0.75);
            }
            img.setPixelRGBA(x, z, (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF);
        }
        texture.upload();
    }

    private static int scaleRgb(int argb, double f) {
        int r = Mth.clamp((int) ((argb >> 16 & 0xFF) * f), 0, 255);
        int g = Mth.clamp((int) ((argb >> 8 & 0xFF) * f), 0, 255);
        int b = Mth.clamp((int) ((argb & 0xFF) * f), 0, 255);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // -- coordinates ------------------------------------------------------------------------------

    private int mapW() {
        return width - PANEL_W;
    }

    private double toScreenX(double bx) {
        return mapW() / 2.0 + (bx - viewX) / scale;
    }

    private double toScreenZ(double bz) {
        return height / 2.0 + (bz - viewZ) / scale;
    }

    private double toWorldX(double sx) {
        return viewX + (sx - mapW() / 2.0) * scale;
    }

    private double toWorldZ(double sz) {
        return viewZ + (sz - height / 2.0) * scale;
    }

    private int ownerAt(double bx, double bz) {
        int ix = (int) Math.floor((bx + data.step() / 2.0) / data.step()) + data.half();
        int iz = (int) Math.floor((bz + data.step() / 2.0) / data.step()) + data.half();
        if (ix < 0 || iz < 0 || ix >= n || iz >= n) return -1;
        return owners[iz * n + ix] & 0xFF;
    }

    // -- render -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        paint();
        g.fill(0, 0, width, height, BG);
        int mw = mapW();
        g.enableScissor(0, 0, mw, height);
        // The texture covers the lattice: point i sits at block (i - half) * step, drawn as its cell.
        double half = data.half(), step = data.step();
        double x0 = toScreenX((-half - 0.5) * step), z0 = toScreenZ((-half - 0.5) * step);
        double size = n * step / scale;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x0, z0, 0);
        pose.scale((float) (size / n), (float) (size / n), 1f);
        g.blit(TEX, 0, 0, 0, 0, n, n, n, n);
        pose.popPose();

        // Continent names, biggest first; a label that would cover another moves down or up a line.
        List<int[]> placed = new ArrayList<>();
        List<WorldMapPayload.Entry> byArea = new ArrayList<>(data.continents());
        byArea.sort((a, b) -> Float.compare(b.areaKm2(), a.areaKm2()));
        for (WorldMapPayload.Entry e : byArea) {
            int sx = (int) toScreenX(e.centerX());
            String label = e.pregenerated() ? e.name() + " ✔" : e.name();
            int w = font.width(label);
            int sz = freeRow(placed, sx - w / 2 - 2, (int) toScreenZ(e.centerZ()), w + 4, 11);
            g.fill(sx - w / 2 - 2, sz - 6, sx + w / 2 + 2, sz + 5, 0x90101418);
            g.drawString(font, label, sx - w / 2, sz - 4, e.pregenerated() ? GREEN : GOLD, false);
        }
        // The player and the world spawn.
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int px = (int) toScreenX(mc.player.getX()), pz = (int) toScreenZ(mc.player.getZ());
            g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFF000000);
            g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFFFFFFFF);
        }
        g.disableScissor();

        // Scale bar: 10 km, or whatever fits.
        int km = scale * 100 > 20000 ? 50 : scale * 100 > 5000 ? 10 : scale * 100 > 1000 ? 2 : 1;
        int barW = (int) (km * 1000 / scale);
        g.fill(10, height - 14, 10 + barW, height - 12, TEXT);
        g.drawString(font, km + " km", 10, height - 25, TEXT, false);

        renderPanel(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);

        if (mouseX < mw) {
            double bx = toWorldX(mouseX), bz = toWorldZ(mouseY);
            int owner = ownerAt(bx, bz);
            String what = owner < 0 ? "Beyond the world's edge" : owner == 0 ? "Sea" : owner == 255 ? "Island"
                    : data.continents().get(owner - 1).name();
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(what));
            tip.add(Component.literal(String.format(Locale.ROOT, "%d, %d", Math.round(bx), Math.round(bz)))
                    .withColor(LABEL));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    /** A y near {@code y} where a w x h label centred there overlaps none placed; records it. */
    static int freeRow(List<int[]> placed, int x, int y, int w, int h) {
        int[] tries = {0, h, -h, 2 * h, -2 * h, 3 * h, -3 * h};
        for (int dy : tries) {
            int top = y + dy - h / 2;
            boolean clear = true;
            for (int[] r : placed) {
                if (x < r[0] + r[2] && r[0] < x + w && top < r[1] + r[3] && r[1] < top + h) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                placed.add(new int[]{x, top, w, h});
                return y + dy;
            }
        }
        placed.add(new int[]{x, y - h / 2, w, h});
        return y;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // drawn in render
    }

    private void renderPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = width - PANEL_W;
        g.fill(x0, 0, width, height, PANEL);
        g.fill(x0, 0, x0 + 1, height, EDGE);
        int x = x0 + PAD, y = PAD;
        g.drawString(font, "World Atlas", x, y, GOLD, false);
        y += 14;
        g.drawString(font, data.continents().size() + " continents", x, y, LABEL, false);
        y += 16;
        if (selected >= 0) {
            WorldMapPayload.Entry e = data.continents().get(selected);
            g.drawString(font, e.name(), x, y, TEXT, false);
            y += 12;
            y = row(g, x, y, "Land", String.format(Locale.ROOT, "%.0f km²", e.areaKm2()));
            y = row(g, x, y, "Size", String.format(Locale.ROOT, "%.0f x %.0f km",
                    (e.maxX() - e.minX()) / 1000.0, (e.maxZ() - e.minZ()) / 1000.0));
            y = row(g, x, y, "Centre", e.centerX() + ", " + e.centerZ());
            y = row(g, x, y, "Pregenerated", e.pregenerated() ? "yes" : "no");
        } else {
            g.drawString(font, "Click a continent", x, y, LABEL, false);
            y += 12;
        }
        if (!data.pregen().isEmpty()) {
            y += 8;
            g.drawString(font, "Pregen running:", x, y, LABEL, false);
            y += 11;
            for (var line : font.split(Component.literal(data.pregen()), PANEL_W - 2 * PAD)) {
                g.drawString(font, line, x, y, TEXT, false);
                y += 10;
            }
        }
        g.drawString(font, "Drag to pan, scroll to zoom", x, height - 66, LABEL, false);
    }

    private int row(GuiGraphics g, int x, int y, String k, String v) {
        g.drawString(font, k, x, y, LABEL, false);
        g.drawString(font, v, width - PAD - font.width(v), y, TEXT, false);
        return y + 11;
    }

    // -- input ------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= mapW()) return false;
        if (button == 0) {
            dragging = true;
            int owner = ownerAt(toWorldX(mx), toWorldZ(my));
            selected = owner > 0 && owner != 255 ? owner - 1 : -1;
            updateButton();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            viewX -= dx * scale;
            viewZ -= dy * scale;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= mapW()) return false;
        double bx = toWorldX(mx), bz = toWorldZ(my);
        scale = Mth.clamp(scale * Math.pow(0.85, sy), minScale, maxScale);
        // Keep the point under the cursor still.
        viewX = bx - (mx - mapW() / 2.0) * scale;
        viewZ = bz - (my - height / 2.0) * scale;
        return true;
    }

    private void updateButton() {
        if (pregenButton == null) return;
        boolean ok = selected >= 0 && data.pregen().isEmpty();
        pregenButton.active = ok;
        pregenButton.setMessage(Component.literal(selected >= 0
                ? "Pregenerate " + data.continents().get(selected).name() : "Pregenerate"));
    }

    private void pregen() {
        if (selected < 0) return;
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand("continental pregenerate " + data.continents().get(selected).key());
        }
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // A pause screen would stop the single-player server, and with it a pregen it starts.
        return false;
    }

    @Override
    public void removed() {
        Minecraft.getInstance().getTextureManager().release(TEX);
        texture = null;
    }
}
