package com.auramap.client.gui;

import com.auramap.client.config.AuraMapConfig;
import com.auramap.client.render.MapTextureCache;
import com.auramap.client.storage.RegionFileStorage;
import com.auramap.client.storage.RegionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

public class WorldMapScreen extends Screen {
    private static final Component TITLE = Component.literal("AuraMap");

    private final RegionFileStorage storage;
    private final MapTextureCache cache;
    private final AuraMapConfig config;

    private double centerX;
    private double centerZ;
    private double zoom = 1.0;

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 8.0;

    private double dragStartX, dragStartZ;
    private double dragMouseX, dragMouseY;
    private boolean dragging;
    private double animProgress = 1.0;

    public WorldMapScreen(RegionFileStorage storage, AuraMapConfig config, double initialX, double initialZ) {
        super(TITLE);
        this.storage = storage;
        this.config = config;
        this.cache = new MapTextureCache(storage);
        this.centerX = initialX;
        this.centerZ = initialZ;
    }

    @Override
    protected void init() {
        animProgress = config.openingAnimation ? 0.0 : 1.0;
    }

    @Override
    public void tick() {
        if (animProgress < 1.0) animProgress = Math.min(1.0, animProgress + 0.12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {

        g.fill(0, 0, width, height, 0xDD0A0A0A);
        renderMap(g);
        renderHud(g);

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {

    }

    private void renderMap(GuiGraphicsExtractor g) {
        float scale = (float) easeOutCubic(animProgress);
        int screenCx = width / 2;
        int screenCz = height / 2;

        double halfWBlocks = (width / zoom) * 0.5;
        double halfHBlocks = (height / zoom) * 0.5;
        int minBlockX = (int) Math.floor(centerX - halfWBlocks);
        int maxBlockX = (int) Math.ceil(centerX + halfWBlocks);
        int minBlockZ = (int) Math.floor(centerZ - halfHBlocks);
        int maxBlockZ = (int) Math.ceil(centerZ + halfHBlocks);

        RegionPos minR = RegionPos.fromBlock(minBlockX, minBlockZ);
        RegionPos maxR = RegionPos.fromBlock(maxBlockX, maxBlockZ);

        Matrix3x2fStack pose = g.pose();
        if (scale != 1f) {
            pose.pushMatrix();
            pose.translate(screenCx, screenCz);
            pose.scale(scale, scale);
            pose.translate(-screenCx, -screenCz);
        }

        double baseX = screenCx - centerX * zoom;
        double baseZ = screenCz - centerZ * zoom;
        cache.beginFrame();
        for (int rz = minR.rz(); rz <= maxR.rz(); rz++) {
            for (int rx = minR.rx(); rx <= maxR.rx(); rx++) {
                RegionPos rp = new RegionPos(rx, rz);
                var texId = cache.getOrUpload(rp);
                int regionOriginX = rx * RegionPos.REGION_BLOCK_SIZE;
                int regionOriginZ = rz * RegionPos.REGION_BLOCK_SIZE;
                int ix0 = (int) Math.floor(baseX + regionOriginX * zoom);
                int iz0 = (int) Math.floor(baseZ + regionOriginZ * zoom);
                int ix1 = (int) Math.floor(baseX + (regionOriginX + RegionPos.REGION_BLOCK_SIZE) * zoom);
                int iz1 = (int) Math.floor(baseZ + (regionOriginZ + RegionPos.REGION_BLOCK_SIZE) * zoom);
                int iw = ix1 - ix0;
                int ih = iz1 - iz0;
                if (iw <= 0 || ih <= 0) continue;
                if (ix0 + iw < 0 || iz0 + ih < 0 || ix0 > width || iz0 > height) continue;
                try {
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texId,
                            ix0, iz0, 0f, 0f, iw, ih, 512, 512, 512, 512);
                } catch (Throwable t) {
                    g.fill(ix0, iz0, ix0 + iw, iz0 + ih, 0xFF334455);
                }
            }
        }

        if (scale != 1f) pose.popMatrix();
    }

    private void renderHud(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        var font = mc.font;
        var p = mc.player;
        int y = 6;
        if (config.showCoordinates && p != null) {
            String coords = String.format("x: %d  z: %d  y: %d", (int) p.getX(), (int) p.getY(), (int) p.getZ());
            g.text(font, coords, width / 2 - font.width(coords) / 2, y, 0xFFFFFF, true);
            y += 10;
        }
        if (config.showBiome && mc.level != null && p != null) {
            var biome = mc.level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
            String bText = "biome: " + biome;
            g.text(font, bText, 6, height - 12, 0xAAAAAA, true);
        }
        if (config.displayZoom) {
            String zoomText = String.format("zoom %.2fx", zoom);
            g.text(font, zoomText, width - font.width(zoomText) - 6, height - 12, 0xFFFFFF, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (event.button() == 0) {
            dragging = true;
            dragStartX = centerX;
            dragStartZ = centerZ;
            dragMouseX = event.x();
            dragMouseY = event.y();
            return true;
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (dragging) {
            centerX = dragStartX - (event.x() - dragMouseX) / zoom;
            centerZ = dragStartZ - (event.y() - dragMouseY) / zoom;
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double oldZoom = zoom;
        double factor = Math.pow(1.15, scrollY);
        zoom = Mth.clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (zoom != oldZoom) {
            double mouseBlockX = centerX + (mouseX - width / 2.0) / oldZoom;
            double mouseBlockZ = centerZ + (mouseY - height / 2.0) / oldZoom;
            centerX = mouseBlockX - (mouseX - width / 2.0) / zoom;
            centerZ = mouseBlockZ - (mouseY - height / 2.0) / zoom;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int code = event.key();
        if (code == 256 || code == 259) {
            onClose();
            return true;
        }
        if (code == 93 || code == 61 || code == 334) { zoomIn(); return true; }
        if (code == 47 || code == 45 || code == 333) { zoomOut(); return true; }
        return super.keyPressed(event);
    }

    private void zoomIn() { zoom = Mth.clamp(zoom * 1.25, MIN_ZOOM, MAX_ZOOM); }
    private void zoomOut() { zoom = Mth.clamp(zoom / 1.25, MIN_ZOOM, MAX_ZOOM); }

    @Override
    public void onClose() {
        cache.close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static double easeOutCubic(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        double u = 1 - t;
        return 1 - u * u * u;
    }
}
