package com.auramap.client.world;

import com.auramap.client.storage.RegionFileStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class DimensionContext {
    private DimensionContext() {}

    public static String worldId() {
        var mc = Minecraft.getInstance();
        var serverData = mc.getCurrentServer();
        if (serverData != null && serverData.ip != null) {
            return "server_" + serverData.ip.replace(':', '_');
        }
        var level = mc.level;
        if (level != null) {
            var ws = mc.getSingleplayerServer();
            if (ws != null) {
                return "sp_" + ws.getWorldData().getLevelName();
            }
        }
        return "unknown_world";
    }

    public static String dimId(ClientLevel level) {
        ResourceKey<Level> key = level.dimension();
        return key.identifier().toString().replace(':', '_').replace('/', '_');
    }

    public static RegionFileStorage storageFor(ClientLevel level) {
        return new RegionFileStorage(worldId(), dimId(level));
    }
}
