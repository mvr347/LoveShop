package dev.lovelace.loveshops.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@code items_json} of {@code robbery_log}: {@code [{"item":"<base64>","amount":n}, ...]}.
 * Kept free of Bukkit so it can be tested; a malformed entry is skipped rather than failing the
 * whole restore (the coins and the rest of the goods must still reach the victim).
 */
public final class RobberyLoot {

    public record Entry(String itemData, int amount) {}

    private RobberyLoot() {
    }

    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!root.isJsonArray()) return out;
        JsonArray array = root.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            if (!o.has("item") || !o.has("amount")) continue;
            try {
                String item = o.get("item").getAsString();
                int amount = o.get("amount").getAsInt();
                if (!item.isEmpty() && amount > 0) out.add(new Entry(item, amount));
            } catch (RuntimeException ignored) {
                // wrong type in this entry: skip it
            }
        }
        return out;
    }
}
