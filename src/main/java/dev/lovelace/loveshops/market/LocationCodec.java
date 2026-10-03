package dev.lovelace.loveshops.market;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/**
 * A location as one text cell: {@code world;x;y;z;yaw;pitch}. Used for the few extra spots of a
 * trade point (teleport spot, id sign) so a new spot does not need five new database columns.
 */
public final class LocationCodec {

    private LocationCodec() {}

    /** The parts of an encoded location; the world is only a name here (it may not be loaded). */
    public record Parts(String world, double x, double y, double z, float yaw, float pitch) {}

    public static String encode(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return encode(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public static String encode(String world, double x, double y, double z, float yaw, float pitch) {
        return String.format(Locale.ROOT, "%s;%.3f;%.3f;%.3f;%.1f;%.1f", world, x, y, z, yaw, pitch);
    }

    /** {@code null} for a blank or damaged value. */
    public static Parts parse(String text) {
        if (text == null || text.isBlank()) return null;
        String[] p = text.split(";", -1);
        if (p.length != 6 || p[0].isBlank()) return null;
        try {
            return new Parts(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The location, or {@code null} when the text is damaged or the world is not loaded. */
    public static Location decode(String text) {
        Parts parts = parse(text);
        if (parts == null) return null;
        World world = Bukkit.getWorld(parts.world());
        return world == null ? null : new Location(world, parts.x(), parts.y(), parts.z(), parts.yaw(), parts.pitch());
    }
}
