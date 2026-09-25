package com.auramap.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

public final class MapColorSampler {
    private MapColorSampler() {}

    public record Sample(int rgb, int height, boolean transparent, BlockState state, BlockPos pos) {}

    public static Sample sample(LevelChunk chunk, int localX, int localZ, int topY, int minY) {
        return scan(chunk, localX, localZ, topY, minY);
    }

    public static Sample sampleAround(LevelChunk chunk, int localX, int localZ, int estimatedY, int radius, int minY, int maxY) {
        int low = Math.max(minY, estimatedY - radius);
        int high = Math.min(maxY, estimatedY + radius);
        Sample s = scan(chunk, localX, localZ, high, low);
        if (!s.transparent()) return s;

        if (high < maxY) {
            s = scan(chunk, localX, localZ, maxY, high + 1);
            if (!s.transparent()) return s;
        }
        return s;
    }

    private static Sample scan(LevelChunk chunk, int localX, int localZ, int topY, int minY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = topY; y >= minY; y--) {
            pos.set(chunk.getPos().getMinBlockX() + localX, y, chunk.getPos().getMinBlockZ() + localZ);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) continue;
            int cached = com.auramap.client.cache.BlockColorCache.get(state, () -> state.getMapColor(chunk.getLevel(), pos));
            if (com.auramap.client.cache.BlockColorCache.isTransparent(cached)) continue;
            int base = cached & 0xFFFFFF;
            BlockPos copy = pos.immutable();
            return new Sample(base | 0xFF000000, y, false, state, copy);
        }
        return new Sample(0xFF000000, minY, true, null, null);
    }
}
