package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 27-slot Guard Management GUI for trade point owner.
 * Allows hiring guard for selected durations (1, 3, 7, 14 days) and early dismissal.
 */
public final class StallGuardGui extends MarketGui {

    private static final int SIZE = 27;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;
    private final Map<Integer, Integer> durationAtSlot = new HashMap<>();

    public StallGuardGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<blue>Торговая точка — стража</blue>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        durationAtSlot.clear();

        // Header
        inventory.setItem(0, statusHead());

        // Work zone: duration buttons
        List<Integer> durations = plugin.getMarketConfig().guardDurations();
        long costPerDay = plugin.getMarketConfig().guardCostPerDay();
        int[] slots = {10, 12, 14, 16};

        for (int i = 0; i < Math.min(durations.size(), slots.length); i++) {
            int days = durations.get(i);
            int slot = slots[i];
            long totalCost = costPerDay * days;
            inventory.setItem(slot, hireItem(days, totalCost));
            durationAtSlot.put(slot, days);
        }

        // Footer
        if (point.guardState() == GuardState.ACTIVE) {
            inventory.setItem(MarketLayout.extraSlot(SIZE), icon(Material.BARRIER, "<red>Уволить стражу</red>", List.of(
                    "",
                    "<gray>Досрочно завершить службу стражи.</gray>",
                    "<yellow>Деньги за оставшийся срок не возвращаются.</yellow>",
                    "",
                    "<red>ЛКМ </red><gray>— уволить</gray>"
            )));
        }

        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK, "<yellow>Назад</yellow>",
                List.of("", "<gray>В главное меню точки</gray>", "<yellow>ЛКМ </yellow><gray>— вернуться</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack statusHead() {
        GuardState state = point.guardState();
        String status = switch (state) {
            case ACTIVE -> "<green>Активна</green> <gray>до " + WHEN.format(Instant.ofEpochMilli(point.guardPaidUntil())) + "</gray>";
            case UNPAID -> "<red>Не оплачена</red> <gray>(срок истёк)</gray>";
            case NONE -> "<gray>Не нанята</gray>";
        };
        return head(HeadTextures.BANKER_INFO, "<blue>Служба охраны</blue>", List.of(
                "",
                "<gray>Текущий статус: " + status + "</gray>",
                "<gray>Стража защищает точку от ограблений,</gray>",
                "<gray>прогоняет нарушителей спокойствия.</gray>",
                "",
                "<gray>Выберите срок для найма или продления.</gray>"
        ));
    }

    private ItemStack hireItem(int days, long cost) {
        String dayWord = switch (days) {
            case 1 -> "день";
            case 2, 3, 4 -> "дня";
            default -> "дней";
        };
        boolean active = point.guardState() == GuardState.ACTIVE;
        String actionName = active ? "<green>Продлить на " + days + " " + dayWord + "</green>" : "<green>Нанять на " + days + " " + dayWord + "</green>";
        return icon(Material.SHIELD, actionName, List.of(
                "",
                "<gray>Срок службы: <white>" + days + " " + dayWord + "</white></gray>",
                "<gray>Стоимость: " + plugin.getMarketStyle().money(cost) + "</gray>",
                "<gray>Оплата сначала из кассы, затем из инвентаря.</gray>",
                "",
                "<green>ЛКМ </green><gray>— " + (active ? "продлить службу" : "нанять стражу") + "</gray>"
        ));
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtils.parse(viewer, name));
        List<Component> compLore = new ArrayList<>();
        for (String line : lore) compLore.add(MessageUtils.parse(viewer, line));
        meta.lore(compLore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();

        if (rawSlot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }

        if (rawSlot == MarketLayout.backSlot(SIZE)) {
            new StallOwnerGui(plugin, viewer, point).open();
            return;
        }

        if (rawSlot == MarketLayout.extraSlot(SIZE) && point.guardState() == GuardState.ACTIVE) {
            // Fire guard
            plugin.getGuardService().fire(viewer, point);
            viewer.sendMessage(MessageUtils.parse(viewer, "<yellow>Стража досрочно уволена.</yellow>"));
            render();
            return;
        }

        Integer days = durationAtSlot.get(rawSlot);
        if (days != null) {
            GuardService.Result res = plugin.getGuardService().hire(viewer, point, days);
            var msg = plugin.getMarketMessages();
            switch (res) {
                case OK -> msg.send(viewer, "guard-hired");
                case NO_MONEY -> msg.send(viewer, "trade-no-money");
                case DISABLED -> msg.send(viewer, "guard-disabled");
                case NOT_OWNER -> msg.send(viewer, "not-owner");
                default -> msg.send(viewer, "listing-error");
            }
            render();
        }
    }
}
