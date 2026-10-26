package dev.lovelace.loveshops.npc;

import dev.lovelace.loveshops.models.NpcData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcLookupTest {

    private static NpcData row(UUID uuid, String type, String key) {
        return new NpcData(1, uuid, type, "world", 0, 0, 0, 0f, 0f, "n", "<gold>n</gold>", "Steve", key, 0L, 0L);
    }

    private final UUID bankerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final Map<UUID, NpcData> rows = new HashMap<>();

    {
        rows.put(bankerId, row(bankerId, "banker", "12"));
        rows.put(otherId, row(otherId, "warmerchant", "ext-0a1b2c3d"));
    }

    @Test
    void aReusedVisibleNumberDoesNotSilentlyTakeOverTheBanker() {
        var legacy = row(bankerId, "banker", "8");
        var newNpc = new FakeNpc("loveessential", "ext-new");
        assertFalse(NpcLookup.confirmsStoredBinding(newNpc, legacy));
        newNpc.with(NpcTags.UUID, bankerId.toString());
        assertTrue(NpcLookup.confirmsStoredBinding(newNpc, legacy));
        assertTrue(NpcLookup.confirmsStoredBinding(new FakeNpc("citizens", "8"), legacy));
    }

    @Test
    void permanentRoleRebindCannotTakeAnotherRoleOrMarketNpc() {
        assertFalse(NpcLookup.claimedByOtherRole(new FakeNpc("loveessential", "ext-new"), bankerId));
        assertFalse(NpcLookup.claimedByOtherRole(new FakeNpc("loveessential", "ext-own").with(NpcTags.UUID, bankerId.toString()), bankerId));
        assertTrue(NpcLookup.claimedByOtherRole(new FakeNpc("loveessential", "ext-other").with(NpcTags.UUID, otherId.toString()), bankerId));
        for (String tag : java.util.List.of(NpcTags.FEUDAL, NpcTags.STALL, NpcTags.GUARD))
            assertTrue(NpcLookup.claimedByOtherRole(new FakeNpc("loveessential", "ext-market").with(tag, "owned"), bankerId));
    }

    @Test
    void tagWinsAndNeedsNoRetag() {
        FakeNpc npc = new FakeNpc("citizens", "99").with(NpcTags.UUID, bankerId.toString());
        NpcLookup.Resolution r = NpcLookup.resolve(rows, npc);
        assertSame(rows.get(bankerId), r.data());
        assertFalse(r.viaKey());
    }

    @Test
    void untaggedNpcIsFoundByTheKeyTheRowStores() {
        // An NPC bound before tags moved to the provider: no tag, but the row knows its numeric id.
        FakeNpc npc = new FakeNpc("citizens", "12");
        NpcLookup.Resolution r = NpcLookup.resolve(rows, npc);
        assertSame(rows.get(bankerId), r.data());
        assertTrue(r.viaKey());
    }

    @Test
    void legacyNpcIsRetaggedOnceAndThenFoundByTag() {
        FakeNpc npc = new FakeNpc("citizens", "12");
        NpcData data = NpcLookup.resolve(rows, npc).data();
        assertTrue(NpcLookup.ensureTags(npc, data.uuid(), data.type()));
        assertEquals(bankerId.toString(), npc.tags.get(NpcTags.UUID));
        assertEquals("banker", npc.tags.get(NpcTags.TYPE));
        // second pass: nothing to do, and the lookup no longer goes through the key
        assertFalse(NpcLookup.ensureTags(npc, data.uuid(), data.type()));
        assertFalse(NpcLookup.resolve(rows, npc).viaKey());
    }

    @Test
    void wrongTagIsOverwrittenFromTheRow() {
        FakeNpc npc = new FakeNpc("citizens", "12").with(NpcTags.UUID, bankerId.toString()).with(NpcTags.TYPE, "wanderer");
        assertTrue(NpcLookup.ensureTags(npc, bankerId, "Banker"));
        assertEquals("banker", npc.tags.get(NpcTags.TYPE));
    }

    @Test
    void malformedOrUnknownTagFallsBackToTheKey() {
        FakeNpc bad = new FakeNpc("citizens", "12").with(NpcTags.UUID, "not-a-uuid");
        assertSame(rows.get(bankerId), NpcLookup.resolve(rows, bad).data());
        FakeNpc stale = new FakeNpc("citizens", "12").with(NpcTags.UUID, UUID.randomUUID().toString());
        assertSame(rows.get(bankerId), NpcLookup.resolve(rows, stale).data());
    }

    @Test
    void strangersResolveToNothing() {
        assertNull(NpcLookup.resolve(rows, new FakeNpc("citizens", "77")).data());
        assertNull(NpcLookup.resolve(rows, null).data());
        // a non-numeric key from another provider matches only the row that stores exactly it
        assertSame(rows.get(otherId), NpcLookup.resolve(rows, new FakeNpc("loveessential", "ext-0a1b2c3d")).data());
    }

    @Test
    void identityCoversEveryLoveShopsTag() {
        assertFalse(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1")));
        assertTrue(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1").with(NpcTags.UUID, "x")));
        assertTrue(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1").with(NpcTags.FEUDAL, "true")));
        assertTrue(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1").with(NpcTags.STALL, "x")));
        assertTrue(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1").with(NpcTags.GUARD, "x")));
        // the ephemeral and type tags alone are not an identity (they always come with a uuid)
        assertFalse(NpcLookup.carriesIdentity(new FakeNpc("citizens", "1").with(NpcTags.TYPE, "banker")));
    }

    @Test
    void aStoredKeyMustNotTakeDownAStrangersNpc() {
        UUID point = UUID.randomUUID();
        assertFalse(NpcLookup.claimedByOther(new FakeNpc("citizens", "5"), point));
        assertFalse(NpcLookup.claimedByOther(new FakeNpc("citizens", "5").with(NpcTags.STALL, point.toString()), point));
        assertFalse(NpcLookup.claimedByOther(new FakeNpc("citizens", "5").with(NpcTags.GUARD, point.toString()), point));
        assertTrue(NpcLookup.claimedByOther(new FakeNpc("citizens", "5").with(NpcTags.STALL, UUID.randomUUID().toString()), point));
        assertTrue(NpcLookup.claimedByOther(new FakeNpc("citizens", "5").with(NpcTags.UUID, bankerId.toString()), point));
        assertTrue(NpcLookup.claimedByOther(new FakeNpc("citizens", "5").with(NpcTags.FEUDAL, "true"), point));
    }

    @Test
    void legacyLandlordIsMatchedByExactNameAndNoIdentity() {
        assertTrue(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "Феодал"), "Феодал"));
        assertFalse(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "Феодал 2"), "Феодал"));
        assertFalse(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "феодал"), "Феодал"));
        assertFalse(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "Феодал").with(NpcTags.UUID, "x"), "Феодал"));
        assertFalse(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "Феодал"), ""));
        assertFalse(NpcLookup.isLegacyLandlord(new FakeNpc("citizens", "3", "Феодал"), null));
    }
}
