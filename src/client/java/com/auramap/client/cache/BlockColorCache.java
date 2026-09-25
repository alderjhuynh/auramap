package com.auramap.client.cache;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockColorCache {
    private static final int CAP = 2048;
    private static final Map<BlockState, Integer> CACHE = new LinkedHashMap<>(CAP, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<BlockState, Integer> e) { return size() > CAP; }
    };

    private static final int TRANSPARENT_SENTINEL = Integer.MIN_VALUE;

    public static synchronized int get(BlockState state, java.util.function.Supplier<MapColor> supplier) {
        Integer cached = CACHE.get(state);
        if (cached != null) return cached;
        MapColor mc = supplier.get();
        int rgb;
        if (mc == MapColor.NONE) rgb = TRANSPARENT_SENTINEL;
        else {
            MapColor resolved = MapColor.byId(mc.id);
            if (resolved == null) resolved = MapColor.STONE;
            rgb = resolved.col & 0xFFFFFF;
        }
        CACHE.put(state, rgb);
        return rgb;
    }

    public static boolean isTransparent(int cachedRgb) { return cachedRgb == TRANSPARENT_SENTINEL; }
}
