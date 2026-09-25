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

    public static ChunkTile snapshot(LevelChunk chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY() - 1;

        int[] rawRgb = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockPos[] poses = new BlockPos[CHUNK_SIZE * CHUNK_SIZE];
        boolean[] transparent = new boolean[CHUNK_SIZE * CHUNK_SIZE];
        var level = chunk.getLevel();

        int globalMin = Integer.MAX_VALUE;
        int globalMax = Integer.MIN_VALUE;

        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int idx = z * CHUNK_SIZE + x;
                int estimated = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                estimated = Math.max(minY, Math.min(maxY, estimated));
                var s = MapColorSampler.sampleAround(chunk, x, z, estimated, 6, minY, maxY);
                if (s.transparent()) {
                    s = MapColorSampler.sample(chunk, x, z, maxY, minY);
                }
                rawRgb[idx] = s.rgb() & 0xFFFFFF;
                heights[idx] = s.height();
                poses[idx] = s.pos();
                transparent[idx] = s.transparent();
                if (!s.transparent()) {
                    globalMin = Math.min(globalMin, s.height());
                    globalMax = Math.max(globalMax, s.height());
                }
            }
        }
        if (globalMin == Integer.MAX_VALUE) globalMin = minY;
        if (globalMax == Integer.MIN_VALUE) globalMax = minY;

        int[] out = new int[CHUNK_SIZE * CHUNK_SIZE];
        var cfg = com.auramap.client.AuraMapClient.CONFIG;
        boolean doBiomeBlend = cfg == null || cfg.biomeBlending;
        boolean doDepth = cfg == null || cfg.terrainDepth;
        boolean doShading = cfg == null || cfg.terrainShading;
        int slopeMode = doShading ? 2 : 0;
        boolean doLighting = cfg == null || cfg.lighting;

        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int idx = z * CHUNK_SIZE + x;
                if (transparent[idx]) {
                    out[idx] = 0xFF000000;
                    continue;
                }
                int base = rawRgb[idx];
                int h = heights[idx];
                BlockPos p = poses[idx];

                int muted = ColorUtil.desaturate(base, 0.18f);

                if (doBiomeBlend && p != null) {
                    boolean vegetative = isVegetation(base);
                    if (vegetative) {
                        var biome = level.getBiome(p).value();
                        int tint = -1;
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

        return new ChunkTile(cx, cz, out, globalMin, globalMax);
    }

    private static boolean isVegetation(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

        return g > r * 0.85f && g > b && g > 90;
    }
}
