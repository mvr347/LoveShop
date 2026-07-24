package dev.lovelace.loveshops.utils;

import org.bukkit.inventory.ItemStack;
import java.util.Base64;

public final class ItemStackConverter {

    private ItemStackConverter() {}

    public static String itemStackToBase64(ItemStack item) {
        if (item == null) return "";
        try {
            byte[] bytes = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static ItemStack itemStackFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
