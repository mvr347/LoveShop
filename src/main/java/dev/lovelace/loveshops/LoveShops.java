package dev.lovelace.loveshops;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import dev.lovelace.loveshops.api.LoveShopsAPI;
import dev.lovelace.loveshops.api.LoveShopsAPIImpl;
import dev.lovelace.loveshops.commands.LoveShopsAdminCommand;
import dev.lovelace.loveshops.commands.ShopsCommand;
import dev.lovelace.loveshops.database.DatabaseManager;
import dev.lovelace.loveshops.listeners.InventoryClickListener;
import dev.lovelace.loveshops.listeners.ScheduleListener;
import dev.lovelace.loveshops.managers.*;
import dev.lovelace.loveshops.placeholders.LoveShopsPlaceholder;

import java.util.Optional;

public final class LoveShops extends JavaPlugin {

    private static LoveShops instance;
    private DatabaseManager databaseManager;
    private LangManager langManager;
    private PriceCalculator priceCalculator;
    private PricesManager pricesManager;
    private ForbiddenManager forbiddenManager;
    private NpcManager npcManager;
    private BuyerManager buyerManager;
    private SellerManager sellerManager;
    private AuctionManager auctionManager;

    @Override
    public void onEnable() {
        instance = this;

        if (!LoveCore.isAvailable()) {
            getLogger().severe("LoveCore не найден. Вся валюта LoveShops — монеты LoveCore, "
                    + "без ядра магазину нечем торговать. Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 1. Config & Lang
        saveDefaultConfig();
        this.langManager = new LangManager(this);
        this.langManager.loadLang();

        // 2. Database
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // 3. Core Services & Managers
        this.pricesManager = new PricesManager(this);
        this.pricesManager.load();
        this.forbiddenManager = new ForbiddenManager(this);
        this.forbiddenManager.load();
        this.priceCalculator = new PriceCalculator(this);
        this.npcManager = new NpcManager(this);
        this.buyerManager = new BuyerManager(this, priceCalculator);
        this.sellerManager = new SellerManager(this);
        this.auctionManager = new AuctionManager(this);

        // 4. Register Commands
        ShopsCommand shopsCmd = new ShopsCommand(this);
        for (String cmdName : java.util.List.of("loveshops", "shops", "шоп", "loveshop", "lshops", "lshop", "buyer", "seller", "auction", "auctioneer")) {
            var cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(shopsCmd);
                cmd.setTabCompleter(shopsCmd);
            }
        }

        LoveShopsAdminCommand adminCmd = new LoveShopsAdminCommand(this);
        var adminCommand = getCommand("loveshopsadmin");
        if (adminCommand != null) {
            adminCommand.setExecutor(adminCmd);
            adminCommand.setTabCompleter(adminCmd);
        }

        // 5. Register Listeners
        getServer().getPluginManager().registerEvents(new InventoryClickListener(this), this);
        getServer().getPluginManager().registerEvents(new ScheduleListener(this), this);
        if (getServer().getPluginManager().isPluginEnabled("Citizens")) {
            getServer().getPluginManager().registerEvents(new dev.lovelace.loveshops.listeners.CitizensListener(this), this);
            getLogger().info("✓ Citizens интеграция активирована.");
        }

        // 6. PlaceholderAPI Integration
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") &&
            getConfig().getBoolean("placeholders.enabled", true)) {
            new LoveShopsPlaceholder(this).register();
            getLogger().info("✓ PlaceholderAPI интеграция активирована.");
        }

        // 7. Register API into ServicesManager
        getServer().getServicesManager().register(
            LoveShopsAPI.class,
            new LoveShopsAPIImpl(this),
            this,
            ServicePriority.Normal
        );

        getLogger().info("LoveShops v" + getDescription().getVersion() + " успешно включён!");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (npcManager != null) {
            npcManager.despawnAllNpcs();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("LoveShops отключён.");
    }

    public static LoveShops getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public LangManager getLangManager() { return langManager; }
    public PriceCalculator getPriceCalculator() { return priceCalculator; }
    public PricesManager getPricesManager() { return pricesManager; }
    public ForbiddenManager getForbiddenManager() { return forbiddenManager; }
    public NpcManager getNpcManager() { return npcManager; }
    public BuyerManager getBuyerManager() { return buyerManager; }
    public SellerManager getSellerManager() { return sellerManager; }
    public AuctionManager getAuctionManager() { return auctionManager; }

    /**
     * Служба валюты ядра. LoveCore проверен обязательным в {@link #onEnable}, поэтому пусто
     * здесь означает только то, что служба ещё не поднялась (ядро включается раньше LoveShops,
     * но регистрирует службы в своём onEnable) — не «ядра нет».
     */
    public Optional<LoveEconomy> getEconomy() {
        return LoveCore.service(LoveEconomy.class);
    }
}
