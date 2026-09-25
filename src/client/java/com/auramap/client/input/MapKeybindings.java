package com.auramap.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.resources.Identifier;

public final class MapKeybindings {
    private MapKeybindings() {}

    public static final Category CATEGORY = Category.register(Identifier.fromNamespaceAndPath("auramap", "controls"));

    public static final KeyMapping OPEN_MAP = new KeyMapping("key.auramap.open_map", 77, CATEGORY);

    public static KeyMapping[] all() {
        return new KeyMapping[]{ OPEN_MAP };
    }
}
