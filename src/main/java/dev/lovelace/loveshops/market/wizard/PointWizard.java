package dev.lovelace.loveshops.market.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The creation wizard of a trade point as a plain state machine (no server needed, tested):
 * zone corner, zone corner, trader, "closed" sign, id sign, teleport spot, then a summary to
 * confirm. The corners and the trader are required, the rest can be skipped.
 */
public final class PointWizard {

    /** A spot in a world; the world is only a name here. */
    public record Pos(String world, double x, double y, double z, float yaw, float pitch) {
        public int bx() { return (int) Math.floor(x); }
        public int by() { return (int) Math.floor(y); }
        public int bz() { return (int) Math.floor(z); }
    }

    public enum Step {
        CORNER_1, CORNER_2, NPC, CLOSED_SIGN, ID_SIGN, TELEPORT, CONFIRM;

        /** Required steps cannot be skipped. */
        public boolean required() {
            return this == CORNER_1 || this == CORNER_2 || this == NPC;
        }

        public Step next() {
            return this == CONFIRM ? CONFIRM : values()[ordinal() + 1];
        }

        public Step previous() {
            return this == CORNER_1 ? CORNER_1 : values()[ordinal() - 1];
        }
    }

    public enum SetResult { OK, WRONG_STEP, WRONG_WORLD, TOO_LARGE, OUTSIDE_ZONE }

    private static final Pattern ID = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");

    private final String id;
    private final long price;
    private final int maxFootprint;
    private Step step = Step.CORNER_1;
    private Pos corner1;
    private Pos corner2;
    private Pos npc;
    private Pos closedSign;
    private Pos idSign;
    private Pos teleport;

    public PointWizard(String id, long price, int maxFootprint) {
        this.id = id;
        this.price = price;
        this.maxFootprint = Math.max(1, maxFootprint);
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public String id() { return id; }
    public long price() { return price; }
    public Step step() { return step; }
    public Pos corner1() { return corner1; }
    public Pos corner2() { return corner2; }
    public Pos npc() { return npc; }
    public Pos closedSign() { return closedSign; }
    public Pos idSign() { return idSign; }
    public Pos teleport() { return teleport; }

    /**
     * Stores {@code pos} for the current step and moves on. Corner two must be in the world of corner
     * one and give a footprint within the limit; the trader must stand inside the zone.
     */
    public SetResult set(Pos pos) {
        switch (step) {
            case CORNER_1 -> corner1 = pos;
            case CORNER_2 -> {
                if (!corner1.world().equals(pos.world())) return SetResult.WRONG_WORLD;
                if (Math.abs(pos.bx() - corner1.bx()) + 1 > maxFootprint || Math.abs(pos.bz() - corner1.bz()) + 1 > maxFootprint) {
                    return SetResult.TOO_LARGE;
                }
                corner2 = pos;
            }
            case NPC -> {
                if (!insideZone(pos)) return SetResult.OUTSIDE_ZONE;
                npc = pos;
            }
            case CLOSED_SIGN -> closedSign = pos;
            case ID_SIGN -> idSign = pos;
            case TELEPORT -> {
                if (!corner1.world().equals(pos.world())) return SetResult.WRONG_WORLD;
                teleport = pos;
            }
            default -> {
                return SetResult.WRONG_STEP;
            }
        }
        step = step.next();
        return SetResult.OK;
    }

    /** Skips an optional step (its value is cleared); {@code false} for a required one or at the summary. */
    public boolean skip() {
        if (step.required() || step == Step.CONFIRM) return false;
        switch (step) {
            case CLOSED_SIGN -> closedSign = null;
            case ID_SIGN -> idSign = null;
            case TELEPORT -> teleport = null;
            default -> { }
        }
        step = step.next();
        return true;
    }

    /** One step back, keeping what was set; {@code false} on the first step. */
    public boolean back() {
        if (step == Step.CORNER_1) return false;
        step = step.previous();
        return true;
    }

    /** Names of the required parts that are still missing (empty = ready to create). */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (corner1 == null) out.add("corner1");
        if (corner2 == null) out.add("corner2");
        if (npc == null) out.add("npc");
        return out;
    }

    public boolean ready() {
        return step == Step.CONFIRM && missing().isEmpty();
    }

    /** {@code true} when {@code pos} lies inside the footprint of the two corners (heights are not compared). */
    public boolean insideZone(Pos pos) {
        if (corner1 == null || corner2 == null) return false;
        if (!corner1.world().equals(pos.world())) return false;
        int minX = Math.min(corner1.bx(), corner2.bx());
        int maxX = Math.max(corner1.bx(), corner2.bx());
        int minZ = Math.min(corner1.bz(), corner2.bz());
        int maxZ = Math.max(corner1.bz(), corner2.bz());
        return pos.bx() >= minX && pos.bx() <= maxX && pos.bz() >= minZ && pos.bz() <= maxZ;
    }
}
