package com.auramap.client;

import com.auramap.AuraMap;
import com.auramap.client.config.AuraMapConfig;
import com.auramap.client.gui.WorldMapScreen;
import com.auramap.client.input.MapKeybindings;
import com.auramap.client.minimap.MinimapDataStore;
import com.auramap.client.render.MinimapRenderer;
import com.auramap.client.storage.RegionFileStorage;
import com.auramap.client.world.DimensionContext;
import com.auramap.client.world.MapUpdateQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;

public class AuraMapClient implements ClientModInitializer {
    public static AuraMapConfig CONFIG;
    public static final MapUpdateQueue UPDATE_QUEUE = new MapUpdateQueue();
    private static RegionFileStorage currentStorage;
    private static final MinimapDataStore MINIMAP_STORE = new MinimapDataStore();
    private static final MinimapRenderer MINIMAP_RENDERER = new MinimapRenderer();
    private int sampleCursor = 0;

    @Override
    public void onInitializeClient() {
        CONFIG = AuraMapConfig.load();
        for (var kb : MapKeybindings.all()) {
            KeyMappingHelper.registerKeyMapping(kb);
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, AuraMap.id("minimap"),
                MINIMAP_RENDERER);
        UPDATE_QUEUE.setMinimapStore(MINIMAP_STORE);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        AuraMap.LOGGER.info("[auramap] client init");
    }

    private void onEndTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (currentStorage != null) {
                UPDATE_QUEUE.flushAsyncForced();
                currentStorage = null;
                MINIMAP_STORE.clear();
                MINIMAP_RENDERER.close();
            }
            return;
        }
        if (currentStorage == null || !isStorageCurrent(mc)) {
            currentStorage = DimensionContext.storageFor(mc.level);
            UPDATE_QUEUE.setStorage(currentStorage);
            UPDATE_QUEUE.setMinimapStore(MINIMAP_STORE);
            MINIMAP_STORE.clear();
            MINIMAP_RENDERER.close();
        }
        while (MapKeybindings.OPEN_MAP.consumeClick()) {
            openMap(mc);
        }
        if (mc.level != null && mc.level.getGameTime() % 4 == 0) {
            sampleNearbyChunksThrottled(mc, 8);
        }
        if (mc.level != null && mc.player != null && currentStorage != null) {
            int pcx = mc.player.chunkPosition().x();
            int pcz = mc.player.chunkPosition().z();
            int radius = mc.options.getEffectiveRenderDistance();
            radius = Math.max(4, Math.min(radius, 16));
            if (CONFIG.mapWritingDistance >= 0) radius = Math.min(radius, CONFIG.mapWritingDistance);
            UPDATE_QUEUE.drainWithBudget(mc.level, pcx, pcz, radius);
        }
    }

    private boolean isStorageCurrent(Minecraft mc) {
        if (mc.level == null || currentStorage == null) return false;
        var expected = DimensionContext.storageFor(mc.level);
        return expected.dimRoot().equals(currentStorage.dimRoot());
    }

    private void sampleNearbyChunksThrottled(Minecraft mc, int budget) {
        if (mc.level == null || mc.player == null) return;
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        int radius = mc.options.getEffectiveRenderDistance();
        radius = Math.max(4, Math.min(radius, 16));
        if (CONFIG.mapWritingDistance >= 0) radius = Math.min(radius, CONFIG.mapWritingDistance);
        int diameter = radius * 2 + 1;
        int total = diameter * diameter;
        for (int i = 0; i < budget; i++) {
            int idx = (sampleCursor + i) % total;
            int dx = (idx % diameter) - radius;
            int dz = (idx / diameter) - radius;
            int cx = pcx + dx;
            int cz = pcz + dz;
            var chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
            if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc) {
                UPDATE_QUEUE.enqueue(lc);
            }
        }
        sampleCursor = (sampleCursor + budget) % total;
    }

    private void openMap(Minecraft mc) {
        if (currentStorage == null && mc.level != null) {
            currentStorage = DimensionContext.storageFor(mc.level);
            UPDATE_QUEUE.setStorage(currentStorage);
        }
        if (currentStorage == null) {
            AuraMap.LOGGER.warn("[auramap] no storage available to open map");
            return;
        }
        UPDATE_QUEUE.flushAsync();
        double x = mc.player != null ? mc.player.getX() : 0;
        double z = mc.player != null ? mc.player.getZ() : 0;
        mc.setScreenAndShow(new WorldMapScreen(currentStorage, CONFIG, x, z));
    }

    public static RegionFileStorage currentStorage() { return currentStorage; }
    public static MinimapDataStore minimapStore() { return MINIMAP_STORE; }
}
