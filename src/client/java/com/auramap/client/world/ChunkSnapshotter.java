package com.auramap.client.world;

import com.auramap.client.render.MapColorSampler;
import com.auramap.client.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

public final class ChunkSnapshotter {
    private ChunkSnapshotter() {}

    public static final int CHUNK_SIZE = 16;

    public record ChunkTile(int chunkX, int chunkZ, int[] pixels, int minHeight, int maxHeight) {}

    private static final ThreadLocal<int[]> RAW_RGB = ThreadLocal.withInitial(() -> new int[CHUNK_SIZE * CHUNK_SIZE]);
    private static final ThreadLocal<int[]> HEIGHTS = ThreadLocal.withInitial(() -> new int[CHUNK_SIZE * CHUNK_SIZE]);
    private static final ThreadLocal<int[]> OUT = ThreadLocal.withInitial(() -> new int[CHUNK_SIZE * CHUNK_SIZE]);
    private static final ThreadLocal<boolean[]> TRANSPARENT = ThreadLocal.withInitial(() -> new boolean[CHUNK_SIZE * CHUNK_SIZE]);
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final ThreadLocal<MapColorSampler.FastOut> FAST_OUT = ThreadLocal.withInitial(MapColorSampler.FastOut::new);

    public static ChunkTile snapshot(LevelChunk chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY() - 1;

        int[] rawRgb = RAW_RGB.get();
        int[] heights = HEIGHTS.get();
        boolean[] transparent = TRANSPARENT.get();
        var level = chunk.getLevel();
        var mutable = MUTABLE.get();
        var fast = FAST_OUT.get();

        int globalMin = Integer.MAX_VALUE;
        int globalMax = Integer.MIN_VALUE;

        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int idx = z * CHUNK_SIZE + x;
                int mapped = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                int startY = mapped < minY
                        ? MapColorSampler.sectionBasedHeight(chunk, 64)
                        : Math.min(mapped, maxY);
                boolean found = MapColorSampler.scanFast(chunk, x, z, startY, minY, mutable, fast);
                if (!found) {
                    transparent[idx] = true;
                    heights[idx] = minY;
                    rawRgb[idx] = 0;
                } else {
                    transparent[idx] = false;
                    rawRgb[idx] = fast.rgb;
                    heights[idx] = fast.height;
                    globalMin = Math.min(globalMin, fast.height);
                    globalMax = Math.max(globalMax, fast.height);
                }
            }
        }
        if (globalMin == Integer.MAX_VALUE) globalMin = minY;
        if (globalMax == Integer.MIN_VALUE) globalMax = minY;

        int[] out = OUT.get();
        var cfg = com.auramap.client.AuraMapClient.CONFIG;
        boolean doBiomeBlend = cfg == null || cfg.biomeBlending;
        boolean doDepth = cfg == null || cfg.terrainDepth;
        boolean doShading = cfg == null || cfg.terrainShading;
        int slopeMode = doShading ? 2 : 0;
        boolean doLighting = cfg == null || cfg.lighting;
        int baseBlockX = chunk.getPos().getMinBlockX();
        int baseBlockZ = chunk.getPos().getMinBlockZ();

        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int idx = z * CHUNK_SIZE + x;
                if (transparent[idx]) {
                    out[idx] = 0xFF000000;
                    continue;
                }
                int base = rawRgb[idx];
                int h = heights[idx];

                int muted = ColorUtil.desaturate(base, 0.18f);

                boolean needsPos = (doBiomeBlend && isVegetation(base)) || doLighting;
                BlockPos p = null;
                if (needsPos) {
                    mutable.set(baseBlockX + x, h, baseBlockZ + z);
                    p = mutable;
                }

                if (doBiomeBlend && p != null) {
                    boolean vegetative = isVegetation(base);
                    if (vegetative) {
                        var biome = level.getBiome(p).value();
                        int tint;
                        var state = chunk.getBlockState(p);
                        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN)) {
                            tint = biome.getGrassColor(p.getX(), p.getZ());
                        } else {
                            tint = biome.getFoliageColor();
                        }
                        muted = ColorUtil.biomeTint(muted, tint);
                        muted = ColorUtil.desaturate(muted, 0.08f);
                    }
                }

                if (doLighting && p != null) {
                    int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p);
                    int blk = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p);
                    float min = 9f;
                    float bright = (min + Math.max(sky, blk)) / (15f + min);
                    bright = Math.max(0.65f, Math.min(1.0f, bright));
                    muted = ColorUtil.brightnessRgb(muted, bright);
                }

                if (doDepth) {
                    float depth;

                    float norm = (h - 40f) / 88f;
                    norm = Math.max(0f, Math.min(1f, norm));
                    if (slopeMode >= 2) {
                        depth = 0.90f + 0.20f * norm;
                    } else if (slopeMode == 1) {
                        depth = 0.85f + 0.30f * norm;
                    } else {
                        depth = 0.92f + 0.16f * norm;
                    }
                    muted = ColorUtil.brightnessRgb(muted, depth);
                }

                if (slopeMode > 0) {
                    int northH = (z > 0) ? heights[idx - CHUNK_SIZE] : h;
                    int diagH = (x > 0 && z > 0) ? heights[idx - CHUNK_SIZE - 1] : northH;
                    int vSlope = h - northH;
                    int dSlope = h - diagH;

                    vSlope = Math.max(-12, Math.min(12, vSlope));
                    dSlope = Math.max(-12, Math.min(12, dSlope));
                    muted = ColorUtil.applyHillShade(muted, vSlope, dSlope, slopeMode);
                }

                out[idx] = 0xFF000000 | (muted & 0xFFFFFF);
            }
        }

        return new ChunkTile(cx, cz, out.clone(), globalMin, globalMax);
    }

    private static boolean isVegetation(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

        return g > r * 0.85f && g > b && g > 90;
    }
}
