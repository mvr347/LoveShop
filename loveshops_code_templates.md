# LoveShops — Code Templates для быстрого старта

Используй эти шаблоны при работе с JetBrains AI Assistant.

---

## 1. pom.xml (Фаза 1)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>dev.lovelace</groupId>
    <artifactId>LoveShops</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>1.21.1-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.47.0.0</version>
            <scope>compile</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.3</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <relocations>
                                <relocation>
                                    <pattern>org.sqlite</pattern>
                                    <shadedPattern>dev.lovelace.loveshops.libs.sqlite</shadedPattern>
                                </relocation>
                            </relocations>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 2. plugin.yml (Фаза 1)

```yaml
name: LoveShops
version: 1.0.0
main: dev.lovelace.loveshops.LoveShops
description: Advanced trading system with NPC merchants

softdepend:
  - LoveBehavior
  - PlaceholderAPI

commands:
  loveshops:
    description: LoveShops admin command
    usage: /loveshops <subcommand>
    permission: loveshops.admin

permissions:
  loveshops.admin:
    description: Access to all admin commands
    default: op
  loveshops.admin.create:
    description: Create NPC
    default: op
  loveshops.admin.delete:
    description: Delete NPC
    default: op
  loveshops.admin.buyer:
    description: Set buyer status
    default: op
  loveshops.admin.reload:
    description: Reload plugin
    default: op
```

---

## 3. LoveShops.java (Фаза 1)

```java
package dev.lovelace.loveshops;

import org.bukkit.plugin.java.JavaPlugin;
import dev.lovelace.loveshops.database.DatabaseManager;
import dev.lovelace.loveshops.managers.*;
import dev.lovelace.loveshops.commands.ShopsCommand;
import dev.lovelace.loveshops.listeners.InventoryClickListener;
import dev.lovelace.loveshops.listeners.ScheduleListener;

public final class LoveShops extends JavaPlugin {

    private static LoveShops instance;
    private DatabaseManager databaseManager;
    private NpcManager npcManager;
    private BuyerManager buyerManager;
    private SellerManager sellerManager;
    private AuctionManager auctionManager;
    private PriceCalculator priceCalculator;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config
        saveDefaultConfig();

        // 2. Database
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // 3. Managers
        this.priceCalculator = new PriceCalculator(this);
        this.npcManager = new NpcManager(this);
        this.buyerManager = new BuyerManager(this, priceCalculator);
        this.sellerManager = new SellerManager(this);
        this.auctionManager = new AuctionManager(this);

        // 4. Commands
        getCommand("loveshops").setExecutor(new ShopsCommand(this));

        // 5. Listeners
        getServer().getPluginManager().registerEvents(
            new InventoryClickListener(this), this
        );
        getServer().getPluginManager().registerEvents(
            new ScheduleListener(this), this
        );

        // 6. Register API
        getServer().getServicesManager().register(
            LoveShopsAPI.class,
            new LoveShopsAPIImpl(this),
            this,
            org.bukkit.plugin.ServicePriority.Normal
        );

        getLogger().info("LoveShops включён успешно!");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) databaseManager.close();
        getLogger().info("LoveShops отключён.");
    }

    public static LoveShops getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public NpcManager getNpcManager() { return npcManager; }
    public BuyerManager getBuyerManager() { return buyerManager; }
    public SellerManager getSellerManager() { return sellerManager; }
    public AuctionManager getAuctionManager() { return auctionManager; }
    public PriceCalculator getPriceCalculator() { return priceCalculator; }
}
```

---

## 4. DatabaseManager.java (Фаза 1 - Часть 1)

```java
package dev.lovelace.loveshops.database;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.sql.*;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "database.db");
            plugin.getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            plugin.getLogger().info("✓ База данных подключена.");
        } catch (SQLException e) {
            plugin.getLogger().severe("✗ Ошибка подключения к БД: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // shops_npcs
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS shops_npcs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x DOUBLE NOT NULL,
                    y DOUBLE NOT NULL,
                    z DOUBLE NOT NULL,
                    yaw FLOAT,
                    pitch FLOAT,
                    name TEXT NOT NULL,
                    display_name TEXT,
                    skin_owner TEXT,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                )
            """);

            // buyer_inventory
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_inventory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    npc_id INTEGER NOT NULL REFERENCES shops_npcs(id) ON DELETE CASCADE,
                    player_uuid TEXT NOT NULL,
                    item_data TEXT NOT NULL,
                    base_price INTEGER NOT NULL,
                    quantity INTEGER DEFAULT 1,
                    received_at INTEGER DEFAULT (strftime('%s', 'now')),
                    sold_at INTEGER,
                    INDEX idx_npc_player (npc_id, player_uuid),
                    INDEX idx_sold_at (sold_at)
                )
            """);

            // buyer_prices_history
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_prices_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    submit_count INTEGER DEFAULT 1,
                    last_submitted_at INTEGER DEFAULT (strftime('%s', 'now')),
                    price_penalty_percent REAL DEFAULT 0,
                    UNIQUE(player_uuid, item_type)
                )
            """);

            // buyer_reputation_overrides
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS buyer_reputation_overrides (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL,
                    set_by TEXT NOT NULL,
                    set_at INTEGER DEFAULT (strftime('%s', 'now')),
                    custom_message TEXT,
                    reason TEXT
                )
            """);

            // auctions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    auctioneer_npc_id INTEGER NOT NULL REFERENCES shops_npcs(id) ON DELETE CASCADE,
                    item_data TEXT NOT NULL,
                    starting_price INTEGER NOT NULL,
                    current_highest_bid INTEGER DEFAULT 0,
                    highest_bidder_uuid TEXT,
                    starts_at INTEGER NOT NULL,
                    ends_at INTEGER NOT NULL,
                    status TEXT DEFAULT 'active',
                    winner_uuid TEXT,
                    completed_at INTEGER,
                    INDEX idx_auctioneer (auctioneer_npc_id),
                    INDEX idx_status (status),
                    INDEX idx_ends_at (ends_at)
                )
            """);

            // auction_bids
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auction_bids (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    auction_id INTEGER NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
                    bidder_uuid TEXT NOT NULL,
                    bid_amount INTEGER NOT NULL,
                    placed_at INTEGER DEFAULT (strftime('%s', 'now')),
                    INDEX idx_auction (auction_id),
                    INDEX idx_bidder (bidder_uuid)
                )
            """);

            // reserved_currency
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS reserved_currency (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    reserved_amount INTEGER DEFAULT 0,
                    last_bid_auction_id INTEGER,
                    INDEX idx_player (player_uuid)
                )
            """);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }
}
```

---

## 5. PriceCalculator.java (Фаза 3)

```java
package dev.lovelace.loveshops.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import java.util.Random;
import java.util.Optional;

public class PriceCalculator {

    private final JavaPlugin plugin;
    private final Random random = new Random();

    public PriceCalculator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public int calculateBuyPrice(Player player, ItemStack item) {
        int basePrice = getBasePrice(item);
        double variance = getVariance();
        double penaltyPercent = getPenaltyPercent(player, item);
        int reputationBonus = getReputationBonus(player);

        return (int) (basePrice * variance 
                    * (1 - penaltyPercent / 100) 
                    * (1 + reputationBonus / 100));
    }

    public int getBasePrice(ItemStack item) {
        String key = "prices-config." + item.getType().name();
        return plugin.getConfig().getInt(key, 100);
    }

    public double getVariance() {
        int minPercent = plugin.getConfig().getInt("buyer.price-variance.min-percent", -30);
        int maxPercent = plugin.getConfig().getInt("buyer.price-variance.max-percent", 30);
        double randomPercent = minPercent + random.nextDouble() * (maxPercent - minPercent);
        return 1.0 + (randomPercent / 100.0);
    }

    public double getPenaltyPercent(Player player, ItemStack item) {
        // TODO: Запрос из БД buyer_prices_history
        return 0; // Placeholder
    }

    public int getReputationBonus(Player player) {
        // TODO: Запрос LoveBehavior API
        return 0; // Placeholder
    }
}
```

---

## 6. config.yml Template (Фаза 9)

```yaml
# ===== ВАЛЮТА И ИНТЕГРАЦИЯ =====
currency:
  type: "itemsadder"
  currency-item: "coins"
  currency-display-name: "Монеты"

# ===== СКУПЩИК =====
buyer:
  enabled: true
  base-price-config:
    type: "config"
    default-price: 100
  
  price-variance:
    min-percent: -30
    max-percent: 30
  
  repetition-penalty:
    enabled: true
    penalty-per-submit: 5
    max-penalty: 50
  
  reputation-bonus:
    good-status: 20
    bad-status: -50
    aggressive-status: -30
  
  messages:
    reject-bad-reputation:
      - "Скупщик: Про тебя ходят плохие слухи... Катись своей дорогой!"
      - "Скупщик: Мне не нравится твоё имя в городе. Уходи."
    reject-aggressive:
      - "Скупщик: Дружок, я не хочу проблем, иди своей дорогой."
      - "Скупщик: Твой агрессивный тон мне не нравится. Ступай!"
    accept: "&aСкупщик: Отличный товар! Вот тебе &b{price}&a!"
    accept-with-bonus: "&aСкупщик: У тебя хорошая репутация! Вот ещё &b{bonus}&a."

# ===== ТОРГОВЕЦ-БАРАХОЛКА =====
seller:
  enabled: true
  arrival-day: "SUNDAY"
  arrival-time: "10:00"
  departure-time: "18:00"
  min-items-to-spawn: 10
  markup-percent: 15
  
  messages:
    arrival:
      - "&e[Барахолка] &fТорговец приехал! &6Сейчас с 10:00 до 18:00."
    departure:
      - "&e[Барахолка] &fТорговец уезжает! Встретимся в следующее воскресенье."

# ===== АУКЦИОН =====
auctioneer:
  enabled: true
  auction-duration-hours: 24
  
  bid-step:
    type: "percentage"
    value: 5
  
  messages:
    auction-started: "&6Аукцион! &eПредмет: {item_name}&e на сумму {starting_price}"
    auction-ended: "&6Аукцион завершён! &eПобедитель: {winner} за {final_price}"

# ===== ПОЗИЦИИ ПРЕДМЕТОВ =====
prices-config:
  DIAMOND: 500
  GOLD_INGOT: 150
  EMERALD: 300
  NETHERITE_INGOT: 2000
```

---

## 7. ShopsCommand.java Skeleton (Фаза 2)

```java
package dev.lovelace.loveshops.commands;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import dev.lovelace.loveshops.LoveShops;
import java.util.List;

public class ShopsCommand implements CommandExecutor, TabCompleter {

    private final LoveShops plugin;

    public ShopsCommand(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("loveshops.admin")) {
            player.sendMessage("§cНет прав!");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "npc" -> handleNpc(player, args);
            case "buyer" -> handleBuyer(player, args);
            case "reload" -> handleReload(player);
            default -> { sendHelp(player); yield true; }
        };
    }

    private boolean handleNpc(Player player, String[] args) {
        // TODO: Реализовать
        return true;
    }

    private boolean handleBuyer(Player player, String[] args) {
        // TODO: Реализовать /loveshops buyer <player> <status>
        return true;
    }

    private boolean handleReload(Player player) {
        player.sendMessage("§eПеregрузка конфига...");
        plugin.reloadConfig();
        player.sendMessage("§a✓ Перезагрузка завершена.");
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("""
            §6=== LoveShops ===
            §7/loveshops npc create <type> <name>
            §7/loveshops npc delete
            §7/loveshops buyer <player> <status>
            §7/loveshops reload
            """);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, 
                                      String label, String[] args) {
        if (args.length == 1) {
            return List.of("npc", "buyer", "reload")
                .stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
```

---

## 8. InventoryClickListener.java Skeleton (Фаза 4)

```java
package dev.lovelace.loveshops.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import net.kyori.adventure.text.Component;
import dev.lovelace.loveshops.LoveShops;

public class InventoryClickListener implements Listener {

    private final LoveShops plugin;

    public InventoryClickListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory topInventory = event.getView().getTopInventory();
        Component title = topInventory.viewers().isEmpty() ? null : 
                         event.getView().title();

        if (title == null) return;
        String titleStr = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                         .plainText().serialize(title);

        if (titleStr.contains("Скупщик")) {
            handleBuyerClick(event, player);
        } else if (titleStr.contains("Торговец")) {
            handleSellerClick(event, player);
        } else if (titleStr.contains("Аукцион")) {
            handleAuctionClick(event, player);
        }
    }

    private void handleBuyerClick(InventoryClickEvent event, Player player) {
        // TODO: Реализовать логику покупки у скупщика
    }

    private void handleSellerClick(InventoryClickEvent event, Player player) {
        // TODO: Реализовать логику покупки на барахолке
    }

    private void handleAuctionClick(InventoryClickEvent event, Player player) {
        // TODO: Реализовать логику аукциона
    }
}
```

---

## Инструкции по использованию

1. **Скопируй содержимое** любого шаблона
2. **Вставь в JetBrains AI Assistant**
3. **Попроси расширить/доработать**: "Расширь этот класс, добавив методы для работы с БД"
4. **Итеративно добавляй** детали и функциональность

---

**Каждый шаблон готов к использованию и соответствует Love* ecosystem стандартам!** ✅
