package com.auramap.client.world;

import com.auramap.AuraMap;
import com.auramap.client.storage.RegionFileStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MapUpdateQueue {
    private static final int MAX_CHUNKS_PER_DRAIN = 24;
    private static final long MAX_NANOS_PER_DRAIN = 6_000_000L;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "auramap-writer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "auramap-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private volatile RegionFileStorage storage;
    private volatile boolean closed;

    private final Map<Long, long[]> pendingDirty = new ConcurrentHashMap<>();
    private final Map<Long, int[]> lastPixels = new ConcurrentHashMap<>();
    private static final int LAST_CAP = 1024;

    public void setStorage(RegionFileStorage s) {
        RegionFileStorage old = this.storage;
        if (old != null) {
            final RegionFileStorage toFlush = old;
            io.execute(() -> {
                try { toFlush.flushDirtyForced(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
            });
        }
        this.storage = s;
        pendingDirty.clear();
    }

    public void markDirty(int chunkX, int chunkZ) {
        if (closed || storage == null) return;
        pendingDirty.put(ChunkPos.pack(chunkX, chunkZ), new long[]{chunkX, chunkZ});
    }

    public void enqueue(LevelChunk chunk) {
        markDirty(chunk.getPos().x(), chunk.getPos().z());
    }

    public void drainWithBudget(net.minecraft.client.multiplayer.ClientLevel level, int playerChunkX, int playerChunkZ, int radius) {
        if (closed || storage == null || level == null) return;
        RegionFileStorage s = storage;

        List<long[]> candidates = new ArrayList<>(pendingDirty.values());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                long key = ChunkPos.pack(cx, cz);
                if (!lastPixels.containsKey(key) && !pendingDirty.containsKey(key)) {
                    candidates.add(new long[]{cx, cz});
                }
            }
        }
        if (candidates.isEmpty()) return;

        candidates.sort((a, b) -> {
            int da = Math.max(Math.abs((int) a[0] - playerChunkX), Math.abs((int) a[1] - playerChunkZ));
            int db = Math.max(Math.abs((int) b[0] - playerChunkX), Math.abs((int) b[1] - playerChunkZ));
            return Integer.compare(da, db);
        });

        long start = System.nanoTime();
        int done = 0;
        for (long[] c : candidates) {
            if (done >= MAX_CHUNKS_PER_DRAIN) break;
            if (System.nanoTime() - start >= MAX_NANOS_PER_DRAIN) break;
            int cx = (int) c[0];
            int cz = (int) c[1];
            long key = ChunkPos.pack(cx, cz);
            if (Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ)) > radius) {
                continue;
            }
            boolean dirty = ChunkDirtyTracker.consumeDirty(cx, cz) || pendingDirty.containsKey(key) || !lastPixels.containsKey(key);
            if (!dirty) {
                pendingDirty.remove(key);
                continue;
            }
            var chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (!(chunk instanceof LevelChunk lc)) {
                continue;
            }
            pendingDirty.remove(key);
            final RegionFileStorage fs = s;
            writer.execute(() -> {
                try {
                    var tile = ChunkSnapshotter.snapshot(lc);
                    if (isMostlyBlack(tile.pixels())) {
                        return;
                    }
                    int[] prev = lastPixels.get(key);
                    if (prev != null && java.util.Arrays.equals(prev, tile.pixels())) {
                        return;
                    }
                    if (lastPixels.size() > LAST_CAP) lastPixels.clear();
                    lastPixels.put(key, tile.pixels().clone());
                    fs.putChunkTile(tile.chunkX(), tile.chunkZ(), tile.pixels());
                } catch (Exception e) {
                    AuraMap.LOGGER.warn("[auramap] snapshot failed for chunk {},{}", cx, cz, e);
                }
            });
            done++;
        }
    }

    private static boolean isMostlyBlack(int[] pixels) {
        int black = 0;
        for (int p : pixels) if ((p & 0xFFFFFF) == 0) black++;
        return black > 220;
    }

    public void flushAsync() {
        RegionFileStorage s = storage;
        if (s != null) io.execute(() -> {
            try { s.flushDirty(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
        });
    }

    public void flushAsyncForced() {
        RegionFileStorage s = storage;
        if (s != null) io.execute(() -> {
            try { s.flushDirtyForced(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
        });
    }

    public void loadRegionAsync(RegionFileStorage s, com.auramap.client.storage.RegionPos pos, java.util.function.Consumer<com.auramap.client.storage.MapRegionData> cb) {
        io.execute(() -> {
            try {
                cb.accept(s.getOrLoad(pos));
            } catch (Exception e) {
                AuraMap.LOGGER.warn("[auramap] async region load failed {}", pos, e);
            }
        });
    }

    public void close() {
        closed = true;
        writer.shutdown();
        io.execute(() -> {
            if (storage != null) try { storage.flushDirtyForced(); } catch (Exception ignored) {}
        });
        io.shutdown();
    }
}
