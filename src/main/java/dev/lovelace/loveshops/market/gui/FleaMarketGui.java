package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.SellerGui;
import dev.lovelace.loveshops.market.FleaService;
import dev.lovelace.loveshops.market.MarketRepository.FleaListing;
import dev.lovelace.loveshops.market.MarketRepository.ReturnEntry;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.ReturnsService;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The flea trader's menu (54 slots, standalone): what players sell ("Игроки", paged - the only
 * menu with arrows, slots 36/44), the server's own weekly goods, "my lots" and "my things" (what
 * the market owes the player).
 */
public final class FleaMarketGui extends MarketGui {

    public enum Tab { PLAYERS, SERVER, MINE, THINGS }

    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 21;
    private static final int PREV_SLOT = 36;
    private static final int NEXT_SLOT = 44;

    private Tab tab;
    private int page;
    private int totalLots;
    private final Map<Integer, FleaListing> lotAt = new HashMap<>();
    private final Map<Integer, ReturnEntry> returnAt = new HashMap<>();
    private int addSlot = -1;

    public FleaMarketGui(LoveShops plugin, Player viewer) {
        this(plugin, viewer, Tab.PLAYERS, 0);
    }

    public FleaMarketGui(LoveShops plugin, Player viewer, Tab tab, int page) {
        super(plugin, viewer);
        this.tab = tab;
        this.page = Math.max(0, page);
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, plugin.getMarketStyle().icon(MarketStyle.Icon.FLEA)
                + " <gradient:#FFE000:#799F0C>Барахольщик</gradient>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return null;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        lotAt.clear();
        returnAt.clear();
        addSlot = -1;

        inventory.setItem(0, head(HeadTextures.TAB_SELLER, "<gradient:#FFE000:#799F0C>Барахольщик</gradient>",
                List.of("", "<gray>Здесь игроки продают друг другу", "<gray>всё, что не нужно, — без аренды.")));
        Tab[] tabs = Tab.values();
        int[] slots = MarketLayout.controlSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) inventory.setItem(slots[i], tabItem(tabs[i]));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        switch (tab) {
            case PLAYERS -> renderPlayers();
            case SERVER -> renderServer();
            case MINE -> renderMine();
            case THINGS -> renderThings();
        }
        refreshClient();
    }

    private ItemStack tabItem(Tab t) {
        String base64;
        String name;
        String hint;
        switch (t) {
            case PLAYERS -> { base64 = HeadTextures.TAB_SELLER; name = "Игроки"; hint = "Лоты других игроков"; }
            case SERVER -> { base64 = HeadTextures.TAB_AUCTION; name = "Сервер"; hint = "Товары от сервера"; }
            case MINE -> { base64 = HeadTextures.TAB_BUYER; name = "Мои лоты"; hint = "Ваши товары на продаже"; }
            default -> { base64 = HeadTextures.BANKER_DEPOSIT_FILLED; name = "Мои вещи"; hint = "Деньги и вещи, что вам должны"; }
        }
        boolean selected = t == tab;
        ItemStack item = head(base64, (selected ? "<gold>" : "<white>") + name + (selected ? "</gold>" : "</white>"),
                List.of("", "<gray>" + hint + "</gray>", "", selected ? "<green>▶ Открыто</green>" : "<yellow>ЛКМ </yellow><gray>— открыть</gray>"));
        if (selected && item.getItemMeta() != null) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String sellerName(UUID seller) {
        String name = Bukkit.getOfflinePlayer(seller).getName();
        return name == null ? "?" : name;
    }

    private ItemStack lotItem(FleaListing f, boolean mine) {
        MarketStyle style = plugin.getMarketStyle();
        ItemStack item = f.template();
        item.setAmount(Math.max(1, Math.min(f.amountLeft(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        if (!mine) lore.add(MessageUtils.parse(viewer, "<gray>Продавец: <white>" + sellerName(f.seller()) + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Цена за шт.:</gray> " + style.money(f.unitPrice())));
        lore.add(MessageUtils.parse(viewer, "<gray>В наличии: <white>" + f.amountLeft() + "</white></gray>"));
        lore.add(Component.empty());
        if (mine) {
            lore.add(MessageUtils.parse(viewer, "<red>ПКМ </red><gray>— снять с продажи, забрать остаток</gray>"));
            lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— изменить цену</gray>"));
        } else if (f.seller().equals(viewer.getUniqueId())) {
            lore.add(MessageUtils.parse(viewer, "<gray>Это ваш лот</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— купить 1</gray>"));
            int many = Math.min(f.amountLeft(), item.getMaxStackSize());
            if (many > 1) lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— купить " + many + "</gray>"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void renderPlayers() {
        int[] content = MarketLayout.contentSlots(SIZE);
        List<FleaListing> lots = new ArrayList<>();
        totalLots = plugin.getFleaService().page(page, PAGE_SIZE, lots::addAll);
        int pages = Math.max(1, (totalLots + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) {
            page = pages - 1;
            lots.clear();
            totalLots = plugin.getFleaService().page(page, PAGE_SIZE, lots::addAll);
        }
        for (int i = 0; i < lots.size() && i < content.length; i++) {
            lotAt.put(content[i], lots.get(i));
            inventory.setItem(content[i], lotItem(lots.get(i), false));
        }
        if (page > 0) {
            inventory.setItem(PREV_SLOT, head(HeadTextures.ARROW_LEFT, "<gold>← Назад</gold>",
                    List.of("", "<gray>Страница " + page + " из " + pages + "</gray>", "<yellow>ЛКМ </yellow><gray>— назад</gray>")));
        }
        if (page + 1 < pages) {
            inventory.setItem(NEXT_SLOT, head(HeadTextures.ARROW_RIGHT, "<gold>Вперёд →</gold>",
                    List.of("", "<gray>Страница " + (page + 2) + " из " + pages + "</gray>", "<yellow>ЛКМ </yellow><gray>— вперёд</gray>")));
        }
        if (lots.isEmpty()) {
            inventory.setItem(content[10], head(HeadTextures.BANKER_DEPOSIT_EMPTY, "<gray>Пока пусто</gray>",
                    List.of("", "<gray>Выставьте свой товар во вкладке", "<gray>«Мои лоты» — станете первым.")));
        }
    }

    private void renderServer() {
        int[] content = MarketLayout.contentSlots(SIZE);
        boolean open = plugin.getSellerManager().isSellerArrived();
        if (open) {
            inventory.setItem(content[3], head(HeadTextures.TAB_SELLER, "<gold>Товары сервера</gold>",
                    List.of("", "<gray>Сейчас на прилавке то, что сдали", "<gray>скупщику за неделю.", "", "<green>ЛКМ </green><gray>— открыть</gray>")));
        } else {
            inventory.setItem(content[3], head(HeadTextures.WANDERER_WAITING, "<gray>Товары сервера</gray>",
                    List.of("", "<gray>Сервер привозит свои товары", "<gray>по воскресеньям с 10:00 до 18:00.")));
        }
    }

    private void renderMine() {
        int[] content = MarketLayout.contentSlots(SIZE);
        List<FleaListing> mine = plugin.getFleaService().mine(viewer.getUniqueId());
        for (int i = 0; i < mine.size() && i < content.length; i++) {
            lotAt.put(content[i], mine.get(i));
            inventory.setItem(content[i], lotItem(mine.get(i), true));
        }
        int max = plugin.getMarketConfig().fleaMaxListings();
        if (mine.size() < max && mine.size() < content.length) {
            addSlot = content[mine.size()];
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("<gray>Возьмите товар в руку и нажмите —");
            lore.add("<gray>вся стопка встанет на продажу.");
            lore.add("");
            lore.add("<gray>Лотов: <white>" + mine.size() + "</white> / <white>" + max + "</white></gray>");
            lore.add("<green>ЛКМ </green><gray>— выставить</gray>");
            inventory.setItem(addSlot, head(HeadTextures.BANKER_DEPOSIT_EMPTY, "<green>+ Выставить товар</green>", lore));
        }
    }

    private void renderThings() {
        MarketStyle style = plugin.getMarketStyle();
        int[] content = MarketLayout.contentSlots(SIZE);
        List<ReturnEntry> entries = plugin.getFleaService().returnsOf(viewer.getUniqueId());
        for (int i = 0; i < entries.size() && i < content.length; i++) {
            ReturnEntry e = entries.get(i);
            returnAt.put(content[i], e);
            if (e.isCoins()) {
                inventory.setItem(content[i], head(HeadTextures.BANKER_DEPOSIT_FILLED, "<gold>Деньги</gold>",
                        List.of("", style.money(e.amount()), "", "<green>ЛКМ </green><gray>— забрать всё</gray>")));
            } else {
                ItemStack item = ItemStackConverter.itemStackFromBase64(e.itemData());
                if (item == null) continue;
                item.setAmount(1);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(Component.empty());
                    lore.add(MessageUtils.parse(viewer, "<gray>Ждёт вас: <white>" + e.amount() + "</white> шт.</gray>"));
                    lore.add(Component.empty());
                    lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— забрать всё</gray>"));
                    meta.lore(lore);
                    item.setItemMeta(meta);
                }
                inventory.setItem(content[i], item);
            }
        }
        if (entries.isEmpty()) {
            inventory.setItem(content[10], head(HeadTextures.BANKER_DEPOSIT_EMPTY, "<gray>Вам ничего не должны</gray>", List.of()));
        } else {
            inventory.setItem(MarketLayout.extraSlot(SIZE), head(HeadTextures.MARKET_OPEN, "<green>Забрать всё</green>",
                    List.of("", "<gray>Что не влезет в инвентарь —", "<gray>останется здесь.", "", "<green>ЛКМ </green><gray>— забрать</gray>")));
        }
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        ClickType click = event.getClick();

        if (slot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }
        Tab[] tabs = Tab.values();
        int[] tabSlots = MarketLayout.controlSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            if (slot == tabSlots[i]) {
                if (tabs[i] != tab) {
                    tab = tabs[i];
                    page = 0;
                    render();
                }
                return;
            }
        }

        switch (tab) {
            case PLAYERS -> onPlayers(slot, click);
            case SERVER -> onServer(slot);
            case MINE -> onMine(slot, click);
            case THINGS -> onThings(slot);
        }
    }

    private void onPlayers(int slot, ClickType click) {
        if (slot == PREV_SLOT && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == NEXT_SLOT && inventory.getItem(NEXT_SLOT) != null && (page + 1) * PAGE_SIZE < totalLots) {
            page++;
            render();
            return;
        }
        FleaListing f = lotAt.get(slot);
        if (f == null) return;
        boolean many = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (click != ClickType.LEFT && !many) return;
        if (f.seller().equals(viewer.getUniqueId())) {
            plugin.getMarketMessages().send(viewer, "trade-self");
            return;
        }
        int amount = many ? Math.max(1, Math.min(f.amountLeft(), f.template().getMaxStackSize())) : 1;
        long total;
        try {
            total = Math.multiplyExact(f.unitPrice(), (long) amount);
        } catch (ArithmeticException e) {
            return;
        }
        Tab returnTo = tab;
        int returnPage = page;
        if (total < plugin.getMarketConfig().confirmThreshold()) {
            finishBuy(plugin.getFleaService().buy(viewer, f.id(), amount), f);
            return;
        }
        List<String> summary = List.of(
                "<gray>Товар: <white>" + f.template().getType().name() + "</white> x<white>" + amount + "</white></gray>",
                "<gray>К оплате:</gray> " + plugin.getMarketStyle().money(total));
        new StallConfirmGui(plugin, viewer, null, "<gold>Подтвердите покупку</gold>", summary,
                () -> {
                    finishBuy(plugin.getFleaService().buy(viewer, f.id(), amount), f);
                    reopen(returnTo, returnPage);
                },
                () -> reopen(returnTo, returnPage)).open();
    }

    private void finishBuy(FleaService.Outcome o, FleaListing f) {
        var msg = plugin.getMarketMessages();
        switch (o.result()) {
            case OK -> msg.send(viewer, "trade-bought", "amount", String.valueOf(o.amount()),
                    "item", f.template().getType().name(), "money", plugin.getMarketStyle().money(o.total()));
            case GONE -> msg.send(viewer, "trade-gone");
            case SELF -> msg.send(viewer, "trade-self");
            case NOT_ENOUGH -> msg.send(viewer, "trade-not-enough-stock");
            case NO_MONEY -> msg.send(viewer, "trade-no-money");
            case NO_SPACE -> msg.send(viewer, "trade-no-space");
            case BAD_REPUTATION -> msg.send(viewer, "flea-outcast-refused");
            case ECONOMY_DOWN -> msg.send(viewer, "economy-down");
            case DB_ERROR -> msg.send(viewer, "listing-error");
            default -> { }
        }
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    private void onServer(int slot) {
        if (slot == MarketLayout.contentSlots(SIZE)[3] && plugin.getSellerManager().isSellerArrived()) {
            viewer.closeInventory();
            new SellerGui(plugin, viewer).open();
        }
    }

    private void onMine(int slot, ClickType click) {
        FleaService flea = plugin.getFleaService();
        if (slot == addSlot && (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT)) {
            FleaService.Result pre = flea.previewItem(viewer);
            if (pre != FleaService.Result.OK) {
                reportListing(pre);
                return;
            }
            long min = flea.minUnitPrice(viewer.getInventory().getItemInMainHand());
            String itemName = viewer.getInventory().getItemInMainHand().getType().name();
            Tab back = tab;
            promptNumber("prompt-price", min, plugin.getMarketConfig().priceMax(), () -> reopen(back, 0), price -> {
                FleaService.Result r = flea.addListing(viewer, price);
                if (r == FleaService.Result.OK) plugin.getMarketMessages().send(viewer, "flea-added");
                else reportListing(r);
            }, "item", itemName);
            return;
        }
        FleaListing f = lotAt.get(slot);
        if (f == null) return;
        if (click == ClickType.RIGHT) {
            int back = flea.cancel(viewer, f.id());
            plugin.getMarketMessages().send(viewer, back < 0 ? "listing-error" : "flea-cancelled", "count", String.valueOf(back));
        } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            Tab returnTo = tab;
            promptNumber("prompt-price", flea.minUnitPrice(f.template()), plugin.getMarketConfig().priceMax(),
                    () -> reopen(returnTo, 0), price -> {
                        FleaService.Result r = flea.changePrice(viewer, f.id(), price);
                        if (r == FleaService.Result.OK) plugin.getMarketMessages().send(viewer, "listing-price-changed");
                        else reportListing(r);
                    });
        }
    }

    private void onThings(int slot) {
        boolean claim = slot == MarketLayout.extraSlot(SIZE) || returnAt.containsKey(slot);
        if (!claim) return;
        plugin.getTradePointManager().claimReturns(viewer);
        render();
    }

    private void reportListing(FleaService.Result r) {
        var msg = plugin.getMarketMessages();
        switch (r) {
            case NO_ITEM -> msg.send(viewer, "listing-need-item");
            case IS_COIN -> msg.send(viewer, "listing-is-coin");
            case FORBIDDEN -> msg.send(viewer, "listing-forbidden");
            case PRICE_LOW -> msg.send(viewer, "listing-price-low");
            case PRICE_HIGH -> msg.send(viewer, "listing-price-high", "max", String.valueOf(plugin.getMarketConfig().priceMax()));
            case LIMIT -> msg.send(viewer, "flea-limit", "max", String.valueOf(plugin.getMarketConfig().fleaMaxListings()));
            case GONE -> msg.send(viewer, "listing-gone");
            default -> msg.send(viewer, "listing-error");
        }
    }

    private void reopen(Tab returnTo, int returnPage) {
        if (!viewer.isOnline() || plugin.getChatPromptService().has(viewer)) return;
        new FleaMarketGui(plugin, viewer, returnTo, returnPage).open();
    }
}
