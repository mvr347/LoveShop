package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.loveshops.models.NpcData;

import java.util.Map;
import java.util.UUID;

/**
 * The decisions LoveShops takes about an NPC it is handed, free of any server so they can be tested: which row an
 * NPC belongs to, whether it may be taken over, whether it belongs to somebody else.
 */
public final class NpcLookup {

    private NpcLookup() {
    }

    /**
     * @param data     the row, or {@code null} when the NPC belongs to none
     * @param viaKey   {@code true} when the row was found by the NPC's key because the NPC carried no usable tag
     *                 (an NPC bound before tags moved into the provider's storage): the caller tags it
     */
    public record Resolution(NpcData data, boolean viaKey) {
    }

    /** The row of an NPC: by its uuid tag first, then by the key stored in the row. */
    public static Resolution resolve(Map<UUID, NpcData> rows, NpcHandle npc) {
        if (npc == null) return new Resolution(null, false);
        String raw = npc.tag(NpcTags.UUID).orElse(null);
        if (raw != null) {
            try {
                NpcData data = rows.get(UUID.fromString(raw));
                if (data != null) return new Resolution(data, false);
            } catch (IllegalArgumentException ignored) {
                // a malformed tag is treated like a missing one
            }
        }
        String key = npc.key();
        if (key != null && !key.isBlank()) {
            String trimmed = key.trim();
            for (NpcData row : rows.values()) {
                if (trimmed.equals(row.npcKey())) return new Resolution(row, true);
            }
        }
        return new Resolution(null, false);
    }

    /** Writes the row's identity onto the NPC where it is missing or wrong; {@code true} when anything changed. */
    public static boolean ensureTags(NpcHandle npc, UUID uuid, String type) {
        String id = uuid.toString();
        String lowerType = type.toLowerCase(java.util.Locale.ROOT);
        boolean changed = false;
        if (!npc.tag(NpcTags.UUID).filter(id::equals).isPresent()) {
            npc.tag(NpcTags.UUID, id);
            changed = true;
        }
        if (!npc.tag(NpcTags.TYPE).filter(lowerType::equals).isPresent()) {
            npc.tag(NpcTags.TYPE, lowerType);
            changed = true;
        }
        return changed;
    }

    /** Whether assigning this NPC to the given permanent role would steal another identity. */
    public static boolean claimedByOtherRole(NpcHandle npc, UUID row) {
        return npc.tag(NpcTags.UUID).filter(id -> !id.equals(row.toString())).isPresent()
                || npc.tag(NpcTags.FEUDAL).isPresent() || npc.tag(NpcTags.STALL).isPresent()
                || npc.tag(NpcTags.GUARD).isPresent();
    }

    /** A persisted visible number must not silently claim a new NPC after number reuse. */
    public static boolean confirmsStoredBinding(NpcHandle npc, NpcData row) {
        if (claimedByOtherRole(npc, row.uuid())) return false;
        String key = row.npcKey();
        boolean alias = "loveessential".equalsIgnoreCase(npc.providerId()) && key != null
                && key.matches("[0-9]+") && !key.equals(npc.key());
        return !alias || npc.tag(NpcTags.UUID).filter(row.uuid().toString()::equals).isPresent();
    }

    /** Whether the NPC already has a LoveShops identity: a role row, a stall or guard post, or the landlord. */
    public static boolean carriesIdentity(NpcHandle npc) {
        return npc.tag(NpcTags.UUID).isPresent() || npc.tag(NpcTags.FEUDAL).isPresent()
                || npc.tag(NpcTags.STALL).isPresent() || npc.tag(NpcTags.GUARD).isPresent();
    }

    /**
     * Whether the NPC is claimed by something other than the given point: a role row, the landlord, or the
     * post of another point. Used before removing an NPC by a stored key, because provider ids can be reused.
     */
    public static boolean claimedByOther(NpcHandle npc, UUID pointId) {
        if (npc.tag(NpcTags.UUID).isPresent() || npc.tag(NpcTags.FEUDAL).isPresent()) return true;
        String id = pointId == null ? null : pointId.toString();
        return npc.tag(NpcTags.STALL).filter(v -> !v.equals(id)).isPresent()
                || npc.tag(NpcTags.GUARD).filter(v -> !v.equals(id)).isPresent();
    }

    /** A pre-tag landlord: named exactly like the configured one and without any LoveShops identity. */
    public static boolean isLegacyLandlord(NpcHandle npc, String plainName) {
        return plainName != null && !plainName.isBlank() && plainName.equals(npc.name()) && !carriesIdentity(npc);
    }
}
