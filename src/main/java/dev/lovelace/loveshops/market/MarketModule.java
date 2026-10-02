package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

/**
 * Starts and stops the player market. It needs Citizens (NPCs) and LoveClaims (rentable points);
 * without either the rest of LoveShops runs as before. All LoveClaims-dependent classes are created
 * here, only after the plugin is known to be enabled.
 */
public final class MarketModule {

    private final LoveShops plugin;
    private MarketRepository repo;
    private TradePointManager manager;
    private StallTradeService trade;
    private RatingService ratings;
    private StallUpgradeService upgrades;
    private RobberyService robbery;
    private GuardService guards;
    private StallNpcService npcs;
    private ReputationGate gate;
    private ChatPromptService prompts;
    private dev.lovelace.loveshops.market.feudal.FeudalService feudal;
    private dev.lovelace.loveshops.market.wizard.WizardService wizard;
    private org.bukkit.scheduler.BukkitTask reconcileTask;
    private MarketGuiListener guiListener;

    public MarketModule(LoveShops plugin) {
        this.plugin = plugin;
    }

    public MarketRepository repo() { return repo; }
    public TradePointManager manager() { return manager; }
    public StallNpcService npcs() { return npcs; }
    public ChatPromptService prompts() { return prompts; }
    public dev.lovelace.loveshops.market.feudal.FeudalService feudal() { return feudal; }
    public dev.lovelace.loveshops.market.wizard.WizardService wizard() { return wizard; }
    public StallTradeService trade() { return trade; }
    public RatingService ratings() { return ratings; }
    public StallUpgradeService upgrades() { return upgrades; }
    public ReputationGate gate() { return gate; }
    public GuardService guards() { return guards; }
    public RobberyService robbery() { return robbery; }

    public boolean start() {
        if (!plugin.getMarketConfig().enabled()) {
            plugin.getLogger().info("Рынок игроков выключен в config.yml (market.enabled).");
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LoveClaims")) {
            plugin.getLogger().info("Рынок игроков не запущен: LoveClaims не найден.");
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            plugin.getLogger().info("Рынок игроков не запущен: Citizens не найден.");
            return false;
        }
        try {
            ClaimsLink link = new ClaimsBridge(plugin);
            this.repo = new MarketRepository(plugin);
            MarketRepository repo = this.repo;
            this.npcs = new StallNpcService(plugin);
            StallNpcService npcs = this.npcs;
            this.gate = new ReputationGate(plugin);
            TaxService tax = new TaxService(plugin, gate);
            ReturnsService returns = new ReturnsService(plugin, repo);
            this.manager = new TradePointManager(plugin, repo, npcs, link, gate, tax, returns);
            this.trade = new StallTradeService(plugin, repo, manager, gate, tax);
            this.robbery = new RobberyService(plugin, repo, manager, gate);
            this.guards = new GuardService(plugin, repo, manager);
            manager.attach(robbery, guards);
            this.ratings = new RatingService(plugin, repo);
            this.upgrades = new StallUpgradeService(plugin, repo);
            this.prompts = new ChatPromptService(plugin);
            this.feudal = new dev.lovelace.loveshops.market.feudal.FeudalService(plugin);
            this.wizard = new dev.lovelace.loveshops.market.wizard.WizardService(plugin);
            this.guiListener = new MarketGuiListener(plugin);
            Bukkit.getPluginManager().registerEvents(prompts, plugin);
            Bukkit.getPluginManager().registerEvents(guiListener, plugin);
            Bukkit.getPluginManager().registerEvents(wizard, plugin);
            manager.enable();
            // Finish what a crash left half-way, then keep an eye on trades that got stuck.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (trade != null) trade.reconcile(0L);
            }, 100L);
            reconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (trade != null) trade.reconcile(plugin.getMarketConfig().pendingTimeoutSeconds() * 1000L);
            }, 1200L, 1200L);
            plugin.getLogger().info("✓ Рынок игроков (торговые точки) запущен.");
            return true;
        } catch (LinkageError e) {
            // LoveClaims is present but too old to have the trade-point API.
            plugin.getLogger().warning("Рынок игроков не запущен: версия LoveClaims не поддерживает торговые точки ("
                    + e.getMessage() + ").");
            stop();
            return false;
        }
    }

    public void stop() {
        if (reconcileTask != null) {
            reconcileTask.cancel();
            reconcileTask = null;
        }
        feudal = null;
        if (wizard != null) {
            wizard.shutdown();
            HandlerList.unregisterAll(wizard);
            wizard = null;
        }
        trade = null;
        ratings = null;
        upgrades = null;
        robbery = null;
        guards = null;
        npcs = null;
        gate = null;
        if (manager != null) {
            manager.disable();
            manager = null;
        }
        if (prompts != null) {
            prompts.clear();
            HandlerList.unregisterAll(prompts);
            prompts = null;
        }
        if (guiListener != null) {
            HandlerList.unregisterAll(guiListener);
            guiListener = null;
        }
    }
}
