package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.DiscountEntry;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Personal discounts of the point (54 slots, gui_gen v2.1, paged): who gets how much off. */
public final class StallDiscountGui extends MarketGui {

    private static final int SIZE = 54;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;

    public StallDiscountGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-discount-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        frame();
        List<DiscountEntry> list = new ArrayList<>();
        try {
            list = plugin.getMarketRepository().loadDiscounts(point.claimId()).stream().filter(d -> !d.isExpired()).toList();
        } catch (Exception ignored) {
            // an unreadable list is shown as empty; the actions below report database errors
        }

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-discount-head", "gui-discount-head-lore",
                "count", String.valueOf(list.size()), "max", String.valueOf(plugin.getMarketConfig().discountMaxPercent())));
        controls(List.of(new Control(tile(HeadTextures.BUTTON_PLUS, "gui-discount-add", "gui-discount-add-lore"), e -> promptAdd())));

        int[] content = MarketLayout.contentSlots(SIZE);
        int start = pager((int) Math.ceil((double) list.size() / content.length)) * content.length;
        for (int i = 0; i < content.length && start + i < list.size(); i++) {
            DiscountEntry entry = list.get(start + i);
            button(content[i], entryItem(entry), e -> remove(entry));
        }
        footer(() -> new StallManageGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack entryItem(DiscountEntry de) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(de.beneficiaryUuid());
        meta.setOwningPlayer(op);
        String name = op.getName() != null ? op.getName() : de.beneficiaryUuid().toString().substring(0, 8);
        meta.displayName(MessageUtils.parse(viewer, t("gui-discount-entry", "player", name)));
        String until = de.expiresAt() <= 0 ? t("gui-discount-forever") : WHEN.format(Instant.ofEpochMilli(de.expiresAt()));
        List<Component> lore = new ArrayList<>();
        for (String line : lines("gui-discount-entry-lore", "percent", String.valueOf(de.percent()), "until", until)) {
            lore.add(MessageUtils.parse(viewer, line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void remove(DiscountEntry de) {
        try {
            plugin.getMarketRepository().removeDiscount(point.claimId(), de.beneficiaryUuid());
            OfflinePlayer op = Bukkit.getOfflinePlayer(de.beneficiaryUuid());
            plugin.getMarketMessages().send(viewer, "discount-removed", "player", op.getName() != null ? op.getName() : "?");
        } catch (Exception e) {
            plugin.getMarketMessages().send(viewer, "db-error");
        }
        render();
    }

    private void promptAdd() {
        int maxPercent = plugin.getMarketConfig().discountMaxPercent();
        promptPlayer("prompt-discount-target", this::open, target ->
                promptNumber("prompt-discount-percent", 1, maxPercent, this::open, percent ->
                        promptNumber("prompt-discount-days", 0, 365, this::open, days -> {
                            Long expiresAt = days <= 0 ? null : System.currentTimeMillis() + (days * 86_400_000L);
                            try {
                                plugin.getMarketRepository().setDiscount(point.claimId(), target.getUniqueId(), (int) percent, expiresAt);
                                plugin.getMarketMessages().send(viewer, "discount-given",
                                        "percent", String.valueOf(percent), "player", target.getName());
                            } catch (Exception e) {
                                plugin.getMarketMessages().send(viewer, "db-error");
                            }
                            new StallDiscountGui(plugin, viewer, point).open();
                        })));
    }
}
