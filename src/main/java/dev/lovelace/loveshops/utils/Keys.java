package dev.lovelace.loveshops.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached {@link NamespacedKey}s: GUIs read them on every render and click, no need to allocate each time. */
public final class Keys {

    private static final Map<String, NamespacedKey> CACHE = new ConcurrentHashMap<>();

    private Keys() {}

    public static NamespacedKey of(Plugin plugin, String key) {
        return CACHE.computeIfAbsent(key, k -> new NamespacedKey(plugin, k));
    }
}
