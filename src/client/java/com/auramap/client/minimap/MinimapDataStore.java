package com.auramap.client.minimap;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public final class MinimapDataStore {
    private final ConcurrentHashMap<MinimapChunkKey, MinimapChunkData> chunks = new ConcurrentHashMap<>();
    private static final int MAX_CHUNKS = 256;

    public MinimapChunkData getIfPresent(MinimapChunkKey key) {
        return chunks.get(key);
    }

    public MinimapChunkData getOrCreate(MinimapChunkKey key) {
        MinimapChunkData existing = chunks.get(key);
        if (existing != null) return existing;
        MinimapChunkData fresh = new MinimapChunkData(key);
        MinimapChunkData prev = chunks.putIfAbsent(key, fresh);
        if (chunks.size() > MAX_CHUNKS) evictFar();
        return prev != null ? prev : fresh;
    }

    public void putChunkTile(int chunkX, int chunkZ, int[] tile16) {
        MinimapChunkKey key = MinimapChunkKey.fromChunk(chunkX, chunkZ);
        getOrCreate(key).putChunkTile(chunkX, chunkZ, tile16);
    }

    public Collection<MinimapChunkData> all() {
        return chunks.values();
    }

    public void clear() {
        chunks.clear();
    }

    private void evictFar() {
        int removed = 0;
        for (var e : chunks.entrySet()) {
            if (removed >= 32) break;
            if (!e.getValue().isDirty()) {
                chunks.remove(e.getKey(), e.getValue());
                removed++;
            }
        }
    }

    public void pruneFar(int centerMx, int centerMz, int radiusChunks) {
        for (var e : chunks.entrySet()) {
            MinimapChunkKey k = e.getKey();
            int dx = Math.abs(k.mx() - centerMx);
            int dz = Math.abs(k.mz() - centerMz);
            if (Math.max(dx, dz) > radiusChunks && !e.getValue().isDirty()) {
                chunks.remove(k, e.getValue());
            }
        }
    }
}
