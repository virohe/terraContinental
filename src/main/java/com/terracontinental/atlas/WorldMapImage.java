package com.terracontinental.atlas;

import com.terracontinental.plan.WorldPlan;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The atlas as a PNG, for an admin on the console or RCON (the PowerShell route): the World Atlas
 * screen's picture, cropped to the land, two pixels per 512-block point, continents labelled.
 */
public final class WorldMapImage {
    private static final int PX = 2;

    private WorldMapImage() {
    }

    public static void write(ContinentAtlas a, Path file) throws IOException {
        int n = a.n;
        // Crop to the land plus 16 km.
        int minI = n, minJ = n, maxI = -1, maxJ = -1;
        for (int i = 0; i < n * n; i++) {
            if (a.label[i] == 0) continue;
            minI = Math.min(minI, i % n);
            maxI = Math.max(maxI, i % n);
            minJ = Math.min(minJ, i / n);
            maxJ = Math.max(maxJ, i / n);
        }
        int pad = 16_000 / ContinentAtlas.STEP;
        if (maxI < 0) {
            minI = minJ = 0;
            maxI = maxJ = n - 1;
        }
        minI = Math.max(0, minI - pad);
        minJ = Math.max(0, minJ - pad);
        maxI = Math.min(n - 1, maxI + pad);
        maxJ = Math.min(n - 1, maxJ + pad);
        int w = (maxI - minI + 1) * PX, h = (maxJ - minJ + 1) * PX;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                int idx = j * n + i;
                int rgb = WorldPlan.color(a.label[idx] == 0, a.height[idx], a.sea) & 0xFFFFFF;
                for (int dy = 0; dy < PX; dy++)
                    for (int dx = 0; dx < PX; dx++) img.setRGB((i - minI) * PX + dx, (j - minJ) * PX + dy, rgb);
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        // Spawn cross.
        int sx = (a.half - minI) * PX, sz = (a.half - minJ) * PX;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawLine(sx - 6, sz, sx + 6, sz);
        g.drawLine(sx, sz - 6, sx, sz + 6);
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        java.util.List<ContinentAtlas.Continent> byArea = new java.util.ArrayList<>(a.continents);
        byArea.sort((x, y) -> Double.compare(y.areaKm2(), x.areaKm2()));
        for (ContinentAtlas.Continent c : byArea) {
            int cx = (Math.floorDiv(c.centerX(), ContinentAtlas.STEP) + a.half - minI) * PX;
            String label = c.name() + " (" + Math.round(c.areaKm2()) + " km²)";
            int tw = g.getFontMetrics().stringWidth(label);
            int cz = free(placed, cx - tw / 2 - 3, (Math.floorDiv(c.centerZ(), ContinentAtlas.STEP) + a.half - minJ) * PX,
                    tw + 6, 18) + 4;
            g.setColor(new Color(16, 20, 24, 170));
            g.fillRect(cx - tw / 2 - 3, cz - 12, tw + 6, 17);
            g.setColor(new Color(0xF2, 0xC1, 0x4E));
            g.drawString(label, cx - tw / 2, cz + 1);
        }
        g.dispose();
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    private static int free(java.util.List<int[]> placed, int x, int y, int w, int h) {
        for (int dy : new int[]{0, h, -h, 2 * h, -2 * h, 3 * h, -3 * h}) {
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
}
