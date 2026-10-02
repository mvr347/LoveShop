package dev.lovelace.loveshops.market;

/**
 * Short human duration for menus and notices: the two biggest non-zero units, e.g.
 * {@code 3 д 4 ч}, {@code 5 ч 20 мин}, {@code 12 мин}, {@code меньше минуты}. The unit words are
 * passed in so they can come from lang.yml.
 */
public final class DurationText {

    private DurationText() {}

    /** @param units day, hour, minute and "less than a minute" texts, in this order */
    public static String format(long millis, String[] units) {
        long minutes = Math.max(0L, millis) / 60_000L;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) return hours > 0 ? days + " " + units[0] + " " + hours + " " + units[1] : days + " " + units[0];
        if (hours > 0) return mins > 0 ? hours + " " + units[1] + " " + mins + " " + units[2] : hours + " " + units[1];
        if (mins > 0) return mins + " " + units[2];
        return units[3];
    }

    /** Russian defaults: д, ч, мин. */
    public static String format(long millis) {
        return format(millis, new String[]{"д", "ч", "мин", "меньше минуты"});
    }
}
