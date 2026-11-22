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

    private static final String ID = "loveessential";
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, FakeProvider> registered = new HashMap<>();

    private NpcBridge bridge() {
        return new NpcBridge(id -> Optional.ofNullable(registered.get(id)), warnings::add);
    }

    @Test
    void startupGraceStillResolvesLateProvidersAndReportsARealLossImmediately() {
        boolean[] graceDone = {false}, available = {false};
        var provider = new FakeProvider(ID);
        var bridge = new NpcBridge(id -> available[0] ? Optional.of(provider) : Optional.empty(),
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
    void theProviderIsAlwaysLoveEssential() {
        assertEquals("loveessential", bridge().providerId());
        assertEquals("loveessential", NpcBridge.PROVIDER_ID);
    }

    @Test
    void onlyLoveEssentialIsAsked() {
        FakeProvider other = new FakeProvider("other");
        FakeProvider essential = new FakeProvider(ID);
        other.add("5", null, null);
        essential.add("ext-1", null, null);
        registered.put("other", other);
        registered.put(ID, essential);

        assertTrue(bridge().byKey("ext-1").isPresent());
        assertTrue(bridge().byKey("5").isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingProviderWarnsOnceAndFeaturesDoNothing() {
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
        registered.put(ID, new FakeProvider(ID));
        assertTrue(bridge.available());
        registered.clear();
        assertFalse(bridge.available());
        assertEquals(2, warnings.size());
    }

    @Test
    void providerRegisteringLateIsPickedUp() {
        NpcBridge bridge = bridge();
        assertFalse(bridge.available());
        FakeProvider late = new FakeProvider(ID);
        late.add("3", null, null);
        registered.put(ID, late);
        assertTrue(bridge.byKey("3").isPresent());
    }

    @Test
    void aBrokenLookupCountsAsMissing() {
        NpcBridge bridge = new NpcBridge(id -> { throw new NoClassDefFoundError("dev/lovelace/lovecore/api/LoveCore"); }, warnings::add);
        assertFalse(bridge.available());
        assertEquals(1, warnings.size());
    }

    @Test
    void ownsComparesProviderIds() {
        NpcBridge bridge = bridge();
        assertTrue(bridge.owns(new FakeNpc(ID, "ext-1")));
        assertTrue(bridge.owns(new FakeNpc("LoveEssential", "ext-1")));
        assertFalse(bridge.owns(new FakeNpc("other", "1")));
        assertFalse(bridge.owns(null));
        assertTrue(bridge.ownsProvider(ID));
        assertFalse(bridge.ownsProvider("other"));
    }

    @Test
    void tagLookupFindsOnlyMatchingNpcs() {
        FakeProvider provider = new FakeProvider(ID);
        provider.add("1", "loveshops_uuid", "aaa");
        provider.add("2", "loveshops_uuid", "bbb");
        provider.add("3", null, null);
        registered.put(ID, provider);
        assertEquals(List.of("2"), bridge().withTag("loveshops_uuid", "bbb").stream().map(NpcHandle::key).toList());
        assertEquals(2, bridge().withTag("loveshops_uuid").size());
        assertTrue(bridge().withTag("loveshops_uuid", null).isEmpty());
    }

    @Test
    void removeGoesToTheProvider() {
        FakeProvider provider = new FakeProvider(ID);
        provider.add("4", null, null);
        registered.put(ID, provider);
        assertTrue(bridge().remove(" 4 "));
        assertEquals(List.of("4"), provider.removed);
        assertFalse(bridge().remove("4"));
        assertFalse(bridge().remove(null));
    }
}
