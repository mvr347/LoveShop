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
import dev.lovelace.loveshops.integration.CitizensIntegration;
import dev.lovelace.loveshops.listeners.InventoryClickListener;
import dev.lovelace.loveshops.listeners.ScheduleListener;
import dev.lovelace.loveshops.managers.*;
import dev.lovelace.loveshops.market.ChatPromptService;
import dev.lovelace.loveshops.market.MarketConfig;
import dev.lovelace.loveshops.market.MarketMessages;
import dev.lovelace.loveshops.market.MarketModule;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.RatingService;
import dev.lovelace.loveshops.market.ReputationGate;
import dev.lovelace.loveshops.market.StallTradeService;
import dev.lovelace.loveshops.market.StallUpgradeService;
import dev.lovelace.loveshops.market.TradePointManager;
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
    private AuctionManager auctionManager;
    private NpcDialogueManager npcDialogueManager;
    private WarMerchantManager warMerchantManager;
    private WandererManager wandererManager;
    private BankerManager bankerManager;
    private CitizensIntegration citizensIntegration;
    private MarketConfig marketConfig;
    private MarketMessages marketMessages;
    private MarketStyle marketStyle;
    private MarketModule marketModule;

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
        this.wandererManager = new WandererManager(this);
        this.npcManager = new NpcManager(this);
        this.auctionManager = new AuctionManager(this);
        this.npcDialogueManager = new NpcDialogueManager(this);
        this.warMerchantManager = new WarMerchantManager(this);
        this.bankerManager = new BankerManager(this);
        this.citizensIntegration = new CitizensIntegration();

        // 3b. Рынок игроков: конфиг/тексты/оформление создаются всегда, сам модуль — только с Citizens и LoveClaims
        this.marketConfig = new MarketConfig(this);
        this.marketStyle = new MarketStyle(this);
        this.marketMessages = new MarketMessages(this);
        this.marketModule = new MarketModule(this);
        this.marketModule.start();

        // 4. Register Commands
        ShopsCommand shopsCmd = new ShopsCommand(this);
        for (String cmdName : java.util.List.of("loveshops", "shops", "шоп", "loveshop", "lshops", "lshop", "auction", "auctioneer", "wanderer")) {
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
        // Price-change journal keeps 90 days; trim once a day (the first run shortly after start).
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> adminCmd.pruneAudit(), 5, 24 * 60, java.util.concurrent.TimeUnit.MINUTES);

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
        if (marketModule != null) {
            marketModule.stop();
        }
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
    public AuctionManager getAuctionManager() { return auctionManager; }
    public NpcDialogueManager getNpcDialogueManager() { return npcDialogueManager; }
    public WarMerchantManager getWarMerchantManager() { return warMerchantManager; }
    public WandererManager getWandererManager() { return wandererManager; }
    public BankerManager getBankerManager() { return bankerManager; }
    public CitizensIntegration getCitizensIntegration() { return citizensIntegration; }
    public MarketConfig getMarketConfig() { return marketConfig; }
    public MarketMessages getMarketMessages() { return marketMessages; }
    public MarketStyle getMarketStyle() { return marketStyle; }
    /** {@code null} while the market is not running (no Citizens/LoveClaims or disabled). */
    public TradePointManager getTradePointManager() { return marketModule == null ? null : marketModule.manager(); }
    public ChatPromptService getChatPromptService() { return marketModule == null ? null : marketModule.prompts(); }
    public StallTradeService getTradeService() { return marketModule == null ? null : marketModule.trade(); }
    public RatingService getRatingService() { return marketModule == null ? null : marketModule.ratings(); }
    public StallUpgradeService getUpgradeService() { return marketModule == null ? null : marketModule.upgrades(); }
    /** {@code true} while the flea trader is open around the clock (market running, no Sunday schedule). */
    public boolean isFleaPermanent() { return false; }
    public GuardService getGuardService() { return marketModule == null ? null : marketModule.guards(); }
    public ReputationGate getReputationGate() { return marketModule == null ? null : marketModule.gate(); }

    /**
     * Служба валюты ядра. LoveCore проверен обязательным в {@link #onEnable}, поэтому пусто
     * здесь означает только то, что служба ещё не поднялась (ядро включается раньше LoveShops,
     * но регистрирует службы в своём onEnable) — не «ядра нет».
     */
    public Optional<LoveEconomy> getEconomy() {
        return LoveCore.service(LoveEconomy.class);
    }
}
