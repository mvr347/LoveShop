package dev.lovelace.loveshops.utils;

/**
 * One look for click hints in every menu: {@code Shift — сменить · ЛКМ + · ПКМ −}.
 * The key is coloured by the click (Shift yellow, ЛКМ green, ПКМ red), the label is gray,
 * hints are joined by a dark-gray dot, so a menu shows them in a single line.
 */
public final class Hints {

    private static final String SEP = " <dark_gray>·</dark_gray> ";

    private Hints() {}

    /** A hint with a described action: {@code ЛКМ — купить}. */
    public static String act(String key, String action) {
        return keyTag(key) + " <gray>— " + action + "</gray>";
    }

    /** A hint with a short sign or step: {@code ЛКМ +}, {@code Shift ×8}. */
    public static String op(String key, String sign) {
        return keyTag(key) + " <gray>" + sign + "</gray>";
    }

    /** Hints in one line. */
    public static String join(String... hints) {
        return String.join(SEP, hints);
    }

    /** Price / coin picker: {@code Shift — сменить · ЛКМ + · ПКМ −}. */
    public static String coinPicker() {
        return join(act("Shift", "сменить"), op("ЛКМ", "+"), op("ПКМ", "\u2212"));
    }

    /** Amount stepper: {@code ЛКМ +1 · ПКМ −1 · Shift ×8}. */
    public static String amountStepper(int bigStep) {
        return join(op("ЛКМ", "+1"), op("ПКМ", "\u22121"), op("Shift", "\u00d7" + bigStep));
    }

    private static String keyTag(String key) {
        String color = switch (key) {
            case "Shift" -> "yellow";
            case "ЛКМ" -> "green";
            case "ПКМ" -> "red";
            default -> "aqua";
        };
        return "<" + color + ">" + key + "</" + color + ">";
    }
}
