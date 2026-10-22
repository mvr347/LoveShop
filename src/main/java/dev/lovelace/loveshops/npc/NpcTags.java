package dev.lovelace.loveshops.npc;

/**
 * Tag keys LoveShops puts on the NPCs it owns. Plain keys: the NPC provider decides how to store them
 * (the Citizens adapter prefixes them), and a tag is how LoveShops recognises "its" NPC without
 * comparing names.
 */
public final class NpcTags {

    private NpcTags() {
    }

    /** UUID of the {@code shops_npcs} row an NPC belongs to. */
    public static final String UUID = "loveshops_uuid";
    /** Row type ({@code banker}, {@code wanderer}, ...). */
    public static final String TYPE = "loveshops_type";
    /** Set on NPCs created for the length of an event; unclaimed ones are leftovers of a crash. */
    public static final String EPHEMERAL = "loveshops_ephemeral";
    /** Claim id of the trade point whose trader this is. */
    public static final String STALL = "loveshops_stall_point";
    /** Claim id of the trade point whose guard this is. */
    public static final String GUARD = "loveshops_guard_point";
    /** Present on the landlord ("Feudal") NPC. */
    public static final String FEUDAL = "loveshops_feudal";
}
