package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NpcBridgeTest {

    /** A provider with fixed NPCs; remembers what was asked of it. */
    private static final class FakeProvider implements NpcProvider {
        final String id;
        final Map<String, FakeNpc> npcs = new LinkedHashMap<>();
        final List<String> removed = new ArrayList<>();

        FakeProvider(String id) {
            this.id = id;
        }

        FakeNpc add(String key, String tagKey, String tagValue) {
            FakeNpc npc = new FakeNpc(id, key);
            if (tagKey != null) npc.tags.put(tagKey, tagValue);
            npcs.put(key, npc);
            return npc;
        }

        @Override public String providerId() { return id; }
        @Override public Optional<NpcHandle> byKey(String key) { return Optional.ofNullable(npcs.get(key)); }
        @Override public Optional<NpcHandle> byEntity(Entity entity) { return Optional.empty(); }
        @Override public Optional<NpcHandle> targeted(Player player) { return npcs.values().stream().findFirst().map(n -> n); }
        @Override public NpcHandle create(NpcSpec spec) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(String key) { removed.add(key); return npcs.remove(key) != null; }
        @Override public Collection<NpcHandle> all() { return new ArrayList<>(npcs.values()); }
    }

    private final List<String> warnings = new ArrayList<>();
    private String configured = "citizens";
    private final Map<String, FakeProvider> registered = new HashMap<>();

    private NpcBridge bridge() {
        return new NpcBridge(() -> configured, id -> Optional.ofNullable(registered.get(id)), warnings::add);
    }

    @Test
    void startupGraceStillResolvesLateProvidersAndReportsARealLossImmediately() {
        boolean[] graceDone = {false}, available = {false};
        var provider = new FakeProvider("loveessential");
        var bridge = new NpcBridge(() -> "loveessential", id -> available[0] ? Optional.of(provider) : Optional.empty(),
                warnings::add, () -> graceDone[0]);
        assertFalse(bridge.available());
        assertTrue(warnings.isEmpty());
        available[0] = true;
        assertTrue(bridge.available());
        available[0] = false;
        assertFalse(bridge.available());
        assertEquals(1, warnings.size());
        graceDone[0] = true;
        bridge.available();
        assertEquals(1, warnings.size());
    }

    @Test
    void emptyOrMissingConfigMeansCitizens() {
        assertEquals("citizens", NpcBridge.normalize(null));
        assertEquals("citizens", NpcBridge.normalize(""));
        assertEquals("citizens", NpcBridge.normalize("   "));
        assertEquals("citizens", NpcBridge.normalize("global"), "without a core 'global' means citizens");
        assertEquals("citizens", NpcBridge.normalize(" GLOBAL "));
        assertEquals("loveessential", NpcBridge.normalize("  LoveEssential "));
        configured = null;
        assertEquals("citizens", bridge().providerId());
    }

    @Test
    void picksTheConfiguredProviderOnly() {
        FakeProvider citizens = new FakeProvider("citizens");
        FakeProvider essential = new FakeProvider("loveessential");
        citizens.add("5", null, null);
        essential.add("ext-1", null, null);
        registered.put("citizens", citizens);
        registered.put("loveessential", essential);

        assertTrue(bridge().byKey("5").isPresent());
        assertTrue(bridge().byKey("ext-1").isEmpty());
        configured = "loveessential";
        assertTrue(bridge().byKey("ext-1").isPresent());
        assertTrue(bridge().byKey("5").isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingProviderWarnsOnceAndFeaturesDoNothing() {
        configured = "loveessential";
        NpcBridge bridge = bridge();
        assertFalse(bridge.available());
        assertTrue(bridge.byKey("ext-1").isEmpty());
        assertTrue(bridge.all().isEmpty());
        assertFalse(bridge.remove("ext-1"));
        assertTrue(bridge.withTag("loveshops_uuid", "x").isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("loveessential"));
    }

    @Test
    void warnsAgainWhenAProviderIsLostAfterWorking() {
        NpcBridge bridge = bridge();
        assertFalse(bridge.available());
        assertEquals(1, warnings.size());
        registered.put("citizens", new FakeProvider("citizens"));
        assertTrue(bridge.available());
        registered.clear();
        assertFalse(bridge.available());
        assertEquals(2, warnings.size());
    }

    @Test
    void providerRegisteringLateIsPickedUp() {
        NpcBridge bridge = bridge();
        assertFalse(bridge.available());
        FakeProvider late = new FakeProvider("citizens");
        late.add("3", null, null);
        registered.put("citizens", late);
        assertTrue(bridge.byKey("3").isPresent());
    }

    @Test
    void aBrokenLookupCountsAsMissing() {
        NpcBridge bridge = new NpcBridge(() -> "citizens", id -> { throw new NoClassDefFoundError("dev/lovelace/lovecore/api/LoveCore"); }, warnings::add);
        assertFalse(bridge.available());
        assertEquals(1, warnings.size());
    }

    @Test
    void ownsComparesProviderIds() {
        NpcBridge bridge = bridge();
        assertTrue(bridge.owns(new FakeNpc("citizens", "1")));
        assertTrue(bridge.owns(new FakeNpc("Citizens", "1")));
        assertFalse(bridge.owns(new FakeNpc("loveessential", "ext-1")));
        assertFalse(bridge.owns(null));
        assertTrue(bridge.ownsProvider("citizens"));
        assertFalse(bridge.ownsProvider("loveessential"));
    }

    @Test
    void tagLookupFindsOnlyMatchingNpcs() {
        FakeProvider citizens = new FakeProvider("citizens");
        citizens.add("1", "loveshops_uuid", "aaa");
        citizens.add("2", "loveshops_uuid", "bbb");
        citizens.add("3", null, null);
        registered.put("citizens", citizens);
        assertEquals(List.of("2"), bridge().withTag("loveshops_uuid", "bbb").stream().map(NpcHandle::key).toList());
        assertEquals(2, bridge().withTag("loveshops_uuid").size());
        assertTrue(bridge().withTag("loveshops_uuid", null).isEmpty());
    }

    @Test
    void removeGoesToTheProvider() {
        FakeProvider citizens = new FakeProvider("citizens");
        citizens.add("4", null, null);
        registered.put("citizens", citizens);
        assertTrue(bridge().remove(" 4 "));
        assertEquals(List.of("4"), citizens.removed);
        assertFalse(bridge().remove("4"));
        assertFalse(bridge().remove(null));
    }
}
