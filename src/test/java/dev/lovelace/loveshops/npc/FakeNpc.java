package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory NPC for tests: only identity and tags are real. */
final class FakeNpc implements NpcHandle {

    final String provider;
    final String key;
    final String name;
    final Map<String, String> tags = new HashMap<>();
    /** Whether the fake is currently shown; spawn() and despawn() flip it like the real providers. */
    boolean shown;
    String skin;
    int spawnCalls;
    int despawnCalls;

    FakeNpc(String provider, String key) {
        this(provider, key, "npc" + key);
    }

    FakeNpc(String provider, String key, String name) {
        this.provider = provider;
        this.key = key;
        this.name = name;
    }

    FakeNpc with(String tag, String value) {
        tags.put(tag, value);
        return this;
    }

    @Override public String providerId() { return provider; }
    @Override public String key() { return key; }
    @Override public String name() { return name; }
    @Override public Optional<Entity> entity() { return Optional.empty(); }
    @Override public boolean spawned() { return shown; }
    @Override public boolean spawn() { spawnCalls++; shown = true; return true; }
    @Override public boolean despawn() { despawnCalls++; boolean was = shown; shown = false; return was; }
    @Override public Optional<String> skinName() { return Optional.ofNullable(skin); }
    @Override public Location location() { return null; }
    @Override public void setName(Component name) { }
    @Override public void setSkin(String playerName) { }
    @Override public void setSkin(SkinTexture texture) { }
    @Override public void teleport(Location to) { }
    @Override public void lookClose(boolean enabled) { }
    @Override public void hologram(List<Component> lines) { }
    @Override public Optional<String> tag(String k) { return Optional.ofNullable(tags.get(k)); }
    @Override public void tag(String k, String v) { if (v == null) tags.remove(k); else tags.put(k, v); }
}
