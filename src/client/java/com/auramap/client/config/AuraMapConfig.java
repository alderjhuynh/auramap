package com.auramap.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.auramap.AuraMap;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AuraMapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "auramap.json";

    public boolean caveModeAllowed = true;
    public int caveModeDepth = 30;
    public boolean lighting = true;
    public boolean terrainShading = true;
    public boolean terrainDepth = true;
    public boolean biomeBlending = true;
    public boolean showCoordinates = true;
    public boolean showBiome = true;
    @Deprecated public boolean showZoomButtons = false;
    public double waypointsScale = 1.0;
    public float caveToggleSeconds = 1.0f;
    public int mapWritingDistance = -1;
    public boolean openingAnimation = true;
    public boolean displayZoom = true;
    public boolean arrow = true;
    public int arrowColor = -2;
    public boolean minimapRadar = true;

    private transient Path file;

    public static AuraMapConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        AuraMapConfig cfg = new AuraMapConfig();
        cfg.file = path;
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                AuraMapConfig loaded = GSON.fromJson(json, AuraMapConfig.class);
                if (loaded != null) {
                    loaded.file = path;

                    loaded.caveModeDepth = clamp(loaded.caveModeDepth, 1, 64);
                    loaded.caveToggleSeconds = clamp(loaded.caveToggleSeconds, 0f, 10f);
                    loaded.waypointsScale = clamp(loaded.waypointsScale, 0.5, 5.0);
                    return loaded;
                }
            } catch (IOException e) {
                AuraMap.LOGGER.warn("[auramap] failed to read config, using defaults", e);
            }
        }
        cfg.save();
        return cfg;
    }

    public void save() {
        if (file == null) file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            AuraMap.LOGGER.warn("[auramap] failed to write config", e);
        }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
