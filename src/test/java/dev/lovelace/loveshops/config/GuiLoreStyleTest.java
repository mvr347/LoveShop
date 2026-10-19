package dev.lovelace.loveshops.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the GUI lore standard (gui-gen-6, RULE 10) over every item text of lang.yml: {@code style.*}, {@code gui.*}
 * and the {@code market.gui-*} keys. Chat messages (the other keys of {@code market.*}, {@code commands.*}) are not item
 * text and are not looked at.
 */
class GuiLoreStyleTest {

    /** The only line that may carry the "max reached" check mark. */
    private static final String MAX_STATUS_PATH = "style.status.max";
    private static final String MAX_STATUS_TEXT = "<green>✔ Достигнут предел";
    private static final String FORBIDDEN_IN_NAMES = "✦✔✘▸▶⛃·⚑⌛⚠✕✓";
    private static final String SEPARATOR_PATH = "style.separator";

    private static final Pattern CLICK_LINE = Pattern.compile(
            "^(?:<green>ЛКМ|<gold>ПКМ|<yellow>Shift\\+(?:ЛКМ|ПКМ)) <dark_gray>— \\S.*");
    private static final Pattern BLOCKED_CLICK = Pattern.compile("^<dark_gray>(?:Shift\\+)?(?:ЛКМ|ПКМ) — .+ \\(заблокировано\\)$");
    private static final Pattern STATUS_LINE = Pattern.compile("^<(?:red|yellow|green)>(?:✕|🔒|⌛|✔) .+");
    private static final Pattern REASON_LINE = Pattern.compile("^<red>Причина: <gray>\\S.*");
    private static final Pattern MONEY_AFTER_COLOR = Pattern.compile(
            "<(yellow|gold|gray|green|red|aqua|dark_gray)>\\{(money|price|cost|cost_day|cost_week|refund|total|now|unit|receive|bid|start)}");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z_][a-zA-Z0-9_]*)");

    /** Names that have no {@code -lore} sibling to recognise them by. */
    private static final Set<String> NAME_EXTRAS = Set.of(
            "market.gui-price-btn", "market.gui-manage-guard-off", "market.gui-empty-title", "market.gui-page-prev",
            "market.gui-page-next", "market.gui-customer-head", "market.gui-confirm-ok", "market.gui-confirm-cancel",
            "gui.page-prev", "gui.page-next", "gui.empty-title", "gui.lost.entry-register-off",
            "gui.lost.auction-min-bid", "gui.lost.auction-min-bid-off", "gui.lost.auction-quick-bid",
            "gui.lost.auction-quick-bid-off", "gui.lost.auction-queue", "gui.lost.auction-queue-secret",
            "gui.lost.auction-bids", "gui.lost.auction-bids-leading", "gui.lost.auction-timer",
            "gui.lost.auction-info", "gui.banker.info-rates", "gui.banker.info-rules");

    /** One text: its dotted path (list items carry {@code [i]}) and value. */
    private record Text(String path, String value) {
    }

    /** One lang entry: a string or a list of lines. */
    private record Entry(String path, Object value) {
        List<String> lines() {
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) out.add(String.valueOf(o));
                return out;
            }
            return List.of(String.valueOf(value));
        }

        boolean isList() {
            return value instanceof List<?>;
        }

        String leaf() {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadLang() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream in = GuiLoreStyleTest.class.getResourceAsStream("/lang.yml")) {
            return new Yaml(options).load(in);
        }
    }

    private static void collect(String path, Object node, Map<String, Entry> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node != null) {
            out.put(path, new Entry(path, node));
        }
    }

    private static boolean inScope(String path) {
        if (path.equals("gui.item-bought")) return false; // a chat message that lives in the gui section
        return path.startsWith("style.") || path.startsWith("gui.") || path.startsWith("market.gui-");
    }

    /** Every item entry of lang.yml. */
    private static Map<String, Entry> entries() throws Exception {
        Map<String, Entry> all = new LinkedHashMap<>();
        collect("", loadLang(), all);
        Map<String, Entry> scoped = new LinkedHashMap<>();
        all.forEach((path, entry) -> {
            if (inScope(path)) scoped.put(path, entry);
        });
        return scoped;
    }

    private static List<Text> texts() throws Exception {
        List<Text> out = new ArrayList<>();
        for (Entry entry : entries().values()) {
            List<String> lines = entry.lines();
            for (int i = 0; i < lines.size(); i++) {
                out.add(new Text(entry.isList() ? entry.path() + "[" + i + "]" : entry.path(), lines.get(i)));
            }
        }
        return out;
    }

    private static boolean isName(Entry entry, Map<String, Entry> all) {
        if (entry.isList()) return false;
        String path = entry.path();
        if (NAME_EXTRAS.contains(path) || entry.leaf().equals("name") || entry.leaf().endsWith("-title")) return true;
        for (String base : new String[]{path, path.endsWith("-on") ? path.substring(0, path.length() - 3) : path}) {
            if (all.containsKey(base + "-lore")) return true;
        }
        return false;
    }

    private static boolean isClickLine(String line) {
        return CLICK_LINE.matcher(line).matches() || BLOCKED_CLICK.matcher(line).matches();
    }

    @Test
    void everyTagIsAKnownMiniMessageTagAndTheTextParses() throws Exception {
        TagResolver standard = TagResolver.standard();
        List<String> broken = new ArrayList<>();
        for (Text text : texts()) {
            Matcher m = TAG.matcher(text.value());
            while (m.find()) {
                String name = m.group(2).toLowerCase();
                // colour names are tags of their own; "#rrggbb" tags do not match the name pattern
                if (!standard.has(name)) broken.add(text.path() + " has unknown tag <" + m.group(2) + ">");
            }
            try {
                MiniMessage.miniMessage().deserialize(text.value());
            } catch (RuntimeException e) {
                broken.add(text.path() + " -> " + e.getMessage());
            }
        }
        assertTrue(broken.isEmpty(), "unparsable values: " + broken);
    }

    @Test
    void forbiddenStringsAreGone() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Text text : texts()) {
            String v = text.value();
            if (v.contains("Нажмите") || v.contains("•") || v.contains("›") || v.contains("▸")
                    || v.contains("ЛКМ -") || v.contains("ПКМ -") || v.contains("✘") || v.contains("✗")) {
                offenders.add(text.path() + " = " + v);
            }
            if (v.contains("✔") && !(text.path().equals(MAX_STATUS_PATH) || v.equals(MAX_STATUS_TEXT))) {
                offenders.add(text.path() + " uses ✔ outside the max status: " + v);
            }
            // click hints are never joined on one line
            if (v.contains("·")) offenders.add(text.path() + " joins with a dot: " + v);
            // the click verb is never an instruction: a click is a click line (ЛКМ / ПКМ / Shift+…), not a verb in a sentence
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.contains("клик") || lower.contains("нажм")) offenders.add(text.path() + " uses a click/press verb: " + v);
        }
        assertTrue(offenders.isEmpty(), "forbidden strings: " + offenders);
    }

    @Test
    void namesHaveNoIconsOrEmoji() throws Exception {
        Map<String, Entry> all = entries();
        List<String> offenders = new ArrayList<>();
        for (Entry entry : all.values()) {
            if (!isName(entry, all)) continue;
            String value = entry.value().toString();
            boolean bad = value.codePoints().anyMatch(cp -> FORBIDDEN_IN_NAMES.indexOf(cp) >= 0 || cp >= 0x1F000);
            if (bad) offenders.add(entry.path() + " = " + value);
        }
        assertTrue(offenders.isEmpty(), "names with icons: " + offenders);
    }

    @Test
    void namesHaveNoPriceInThem() throws Exception {
        Map<String, Entry> all = entries();
        List<String> offenders = new ArrayList<>();
        Pattern money = Pattern.compile("\\{(money|price|cost|cost_day|cost_week|refund|unit|receive|bid)}|%img_");
        for (Entry entry : all.values()) {
            if (isName(entry, all) && money.matcher(entry.value().toString()).find()) offenders.add(entry.path());
        }
        assertTrue(offenders.isEmpty(), "a price belongs to the lore, not the name: " + offenders);
    }

    @Test
    void everyClickLineUsesTheStandardFormatAndColour() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Text text : texts()) {
            String v = text.value();
            if (!v.contains("ЛКМ") && !v.contains("ПКМ")) continue;
            if (!isClickLine(v)) offenders.add(text.path() + " = " + v);
        }
        assertTrue(offenders.isEmpty(), "non-standard click lines (right click is gold, one action per line): " + offenders);
    }

    @Test
    void rightClickIsNeverRed() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Text text : texts()) {
            if (text.value().contains("<red>ПКМ") || text.value().contains("<red>Shift")) offenders.add(text.path());
        }
        assertTrue(offenders.isEmpty(), "red ПКМ: " + offenders);
    }

    @Test
    void clicksComeAfterTheSeparatorAndNothingFollowsThem() throws Exception {
        List<String> offenders = new ArrayList<>();
        String separator = String.valueOf(entries().get(SEPARATOR_PATH).value());
        for (Entry entry : entries().values()) {
            if (!entry.isList()) continue;
            List<String> lines = entry.lines();
            int sep = lines.indexOf(separator);
            boolean hasClick = lines.stream().anyMatch(GuiLoreStyleTest::isClickLine);
            if (sep < 0) {
                // a list without a separator is only allowed to be a pure click fragment (it follows a list that ends with one)
                if (hasClick && !lines.stream().allMatch(GuiLoreStyleTest::isClickLine)) {
                    offenders.add(entry.path() + ": clicks without a separator");
                }
                continue;
            }
            for (int i = sep + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!(isClickLine(l) || STATUS_LINE.matcher(l).matches() || REASON_LINE.matcher(l).matches())) {
                    offenders.add(entry.path() + ": line after the separator is not a click/status/reason: " + l);
                }
            }
            if (hasClick && lines.stream().noneMatch(l -> lines.indexOf(l) > sep && isClickLine(l))) {
                offenders.add(entry.path() + ": click before the separator");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void disabledButtonsHaveStatusReasonAndBlockedClickInOrder() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Entry entry : entries().values()) {
            if (!entry.isList()) continue;
            List<String> lines = entry.lines();
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("(заблокировано)")) continue;
                boolean ok = i >= 3
                        && lines.get(i - 3).equals(String.valueOf(entries().get(SEPARATOR_PATH).value()))
                        && lines.get(i - 2).startsWith("<red>✕ ")
                        && REASON_LINE.matcher(lines.get(i - 1)).matches();
                if (!ok) offenders.add(entry.path() + " line " + i);
            }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("Причина:") && !REASON_LINE.matcher(lines.get(i)).matches()) {
                    offenders.add(entry.path() + ": reason line must be '<red>Причина: <gray>…': " + lines.get(i));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "disabled-button blocks: " + offenders);
    }

    @Test
    void loreStartsWithASubtitleAndSectionsAreIndented() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Entry entry : entries().values()) {
            if (!entry.isList()) continue;
            List<String> lines = entry.lines();
            // a list that starts with a blank line is an extension appended to the lore of a real item
            boolean fragment = lines.get(0).isEmpty() || lines.stream().allMatch(l -> isClickLine(l) || l.startsWith("<dark_gray>▪ ") || l.startsWith("  "));
            if (entry.leaf().endsWith("-lore") && !fragment && !lines.get(0).startsWith("<dark_gray>")) {
                offenders.add(entry.path() + ": no dark_gray subtitle");
            }
            for (String l : lines) {
                if (l.startsWith(" ") && !l.matches("^ {2}\\S.*")) offenders.add(entry.path() + ": row must be indented by two spaces: " + l);
                if (l.contains("▪") && !l.startsWith("<dark_gray>▪ <gray>") && !l.startsWith("<dark_gray>▪ {")) {
                    offenders.add(entry.path() + ": section header format: " + l);
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void moneyValuesFollowAWhiteTag() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Text text : texts()) {
            if (MONEY_AFTER_COLOR.matcher(text.value()).find()) offenders.add(text.path() + " = " + text.value());
        }
        assertTrue(offenders.isEmpty(), "a coin value must follow <white>: " + offenders);
    }

    @Test
    void separatorIsTheSharedOne() throws Exception {
        Map<String, Entry> all = entries();
        String separator = String.valueOf(all.get(SEPARATOR_PATH).value());
        List<String> offenders = new ArrayList<>();
        for (Text text : texts()) {
            if (text.value().contains("─") && !text.value().equals(separator)) offenders.add(text.path() + " = " + text.value());
        }
        assertTrue(offenders.isEmpty(), "separator lines must equal style.separator: " + offenders);
    }
}
