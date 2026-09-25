package com.auramap.client.world;

import com.auramap.AuraMap;
import com.auramap.client.storage.RegionFileStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MapUpdateQueue {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "auramap-writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private volatile RegionFileStorage storage;
    private volatile boolean closed;
    private final Map<Long, Long> lastEnqueueMs = new ConcurrentHashMap<>();
    private static final long DEDUP_MS = 350;

    public void setStorage(RegionFileStorage s) {
        RegionFileStorage old = this.storage;
        if (old != null) {
            worker.execute(() -> {
                try { old.flushDirtyForced(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
            });
        }
        this.storage = s;
    }

    public void enqueue(LevelChunk chunk) {
        if (closed || storage == null) return;
        long pos = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        long now = System.currentTimeMillis();
        Long last = lastEnqueueMs.get(pos);
        if (last != null && now - last < DEDUP_MS) return;
        lastEnqueueMs.put(pos, now);
        if (lastEnqueueMs.size() > 2048) {
            lastEnqueueMs.entrySet().removeIf(e -> now - e.getValue() > 5000);
        }
        worker.execute(() -> {
            try {
                var tile = ChunkSnapshotter.snapshot(chunk);

                if (isMostlyBlack(tile.pixels())) {

                    lastEnqueueMs.remove(pos);
                    return;
                }
                storage.putChunkTile(tile.chunkX(), tile.chunkZ(), tile.pixels());
            } catch (Exception e) {
                AuraMap.LOGGER.warn("[auramap] snapshot failed for chunk {},{}", chunk.getPos().x(), chunk.getPos().z(), e);
                lastEnqueueMs.remove(pos);
            }
        });
    }

    private static boolean isMostlyBlack(int[] pixels) {
        int black = 0;
        for (int p : pixels) if ((p & 0xFFFFFF) == 0) black++;
        return black > 220;
    }

    public void flushAsync() {
        RegionFileStorage s = storage;
        if (s != null) worker.execute(() -> {
            try { s.flushDirty(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
        });
    }

    public void flushAsyncForced() {
        RegionFileStorage s = storage;
        if (s != null) worker.execute(() -> {
            try { s.flushDirtyForced(); } catch (Exception e) { AuraMap.LOGGER.warn("[auramap] flush failed", e); }
        });
    }

    public void close() {
        closed = true;
        worker.execute(() -> {
            if (storage != null) try { storage.flushDirtyForced(); } catch (Exception ignored) {}
        });
        worker.shutdown();
    }
}
