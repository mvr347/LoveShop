---
name: minecraft-love-plugins
description: >
  Use this skill for ANY task involving Minecraft Java plugin development — creating, editing, refactoring,
  debugging, or reviewing plugins. Triggers for: "напиши плагин", "сделай команду", "добавь систему",
  "переделай класс", "отрефактори", "создай плагин", "исправь баг", "добавь фичу", "добавь GUI",
  "сделай базу данных", "напиши listener", or any request mentioning Paper, Purpur, Bukkit, plugin.yml,
  pom.xml in a Minecraft context, Java listener, command executor, SQLite, ItemStack, Player event,
  LoveAuth, LoveHunt, LoveCore, or any Love* plugin. Skill covers the full Love* ecosystem —
  a family of interconnected Paper/Purpur plugins communicating via a shared LoveCore API layer.
  Always use this skill when the user is building or modifying anything for a Minecraft server.
---

# Love\* Minecraft Plugin Ecosystem — Developer Skill

## Philosophy

The Love\* ecosystem is a family of **Paper/Purpur plugins** sharing a common API layer (`LoveCore`).
Each new plugin is a natural extension of the previous ones — they are designed to communicate with each
other through a clean, versioned service interface rather than direct plugin coupling.

Core values, in priority order:
1. **Functional and stable** — it must work correctly and not crash the server
2. **High performance** — async where possible, minimal main-thread blocking
3. **Clean and readable** — well-structured, commented in Russian where appropriate
4. **No hardcode** — all configurable values live in `config.yml` or `lang.yml`

---

## Ecosystem Architecture

### Plugin Naming Convention
All plugins in the ecosystem follow the `Love*` prefix:
- `LoveCore` — shared API, utilities, database base class, cross-plugin service registry
- `LoveAuth` — authentication system (register/login, sessions, queue/limbo)
- `LoveHunt` — bounty hunting system
- Future plugins extend the same pattern

### Cross-Plugin Communication Pattern
Plugins communicate via **Bukkit's `ServicesManager`**, not direct plugin casting:

```java
// In LoveCore — register the API
ServicesManager services = Bukkit.getServicesManager();
services.register(LoveCoreAPI.class, apiInstance, plugin, ServicePriority.Normal);

// In LoveHunt — consume the API
LoveCoreAPI api = Bukkit.getServicesManager()
    .load(LoveCoreAPI.class);
if (api == null) {
    getLogger().severe("LoveCore not found! Disabling LoveHunt.");
    Bukkit.getPluginManager().disablePlugin(this);
    return;
}
```

Plugin dependencies are declared in `plugin.yml`:
```yaml
depend: [LoveCore]
# or
softdepend: [LoveAuth]
```

### Cross-Plugin Custom Events
For loose coupling, plugins fire and listen to **custom Bukkit events** from LoveCore:
```java
// Custom event example
public class PlayerAuthenticatedEvent extends Event {
    // other plugins can listen without hard dependency
}
```

---

## Project Structure (Maven)

Every Love\* plugin follows this layout:

```
LovePluginName/
├── pom.xml
├── plugin.yml  (or paper-plugin.yml for Paper plugins)
└── src/main/java/dev/lovelace/lovepluginname/
    ├── LovePluginName.java          ← main class
    ├── api/                         ← public API interface (if exposing to others)
    │   └── LovePluginNameAPI.java
    ├── commands/                    ← command executors / brigadier
    │   └── SomeCommand.java
    ├── listeners/                   ← event listeners
    │   └── SomeListener.java
    ├── managers/                    ← business logic, stateful managers
    │   └── SomeManager.java
    ├── database/                    ← DB access layer
    │   └── DatabaseManager.java
    ├── gui/                         ← inventory GUI builders
    │   └── SomeGui.java
    ├── models/                      ← plain data/model classes
    │   └── SomeModel.java
    └── utils/                       ← static helpers
        └── ColorUtils.java
```

---

## Maven `pom.xml` Template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>dev.lovelace</groupId>
    <artifactId>LovePluginName</artifactId>
    <version>1.0.0-SNAPSHOT</version>
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
        <!-- Paper API — compile-time only, provided by server -->
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>1.21.1-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>

        <!-- SQLite driver — shaded into jar -->
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
                                    <shadedPattern>dev.lovelace.lovepluginname.libs.sqlite</shadedPattern>
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

## Main Plugin Class Pattern

```java
public final class LovePluginName extends JavaPlugin {

    private static LovePluginName instance;
    private DatabaseManager databaseManager;
    private SomeManager someManager;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config first
        saveDefaultConfig();

        // 2. Database
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // 3. Managers
        this.someManager = new SomeManager(this);

        // 4. Commands
        getCommand("somecommand").setExecutor(new SomeCommand(this));

        // 5. Listeners
        getServer().getPluginManager().registerEvents(new SomeListener(this), this);

        // 6. Register API in ServicesManager (if this plugin exposes an API)
        getServer().getServicesManager().register(
            LovePluginNameAPI.class,
            new LovePluginNameAPIImpl(this),
            this,
            ServicePriority.Normal
        );

        getLogger().info("LovePluginName включён успешно!");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) databaseManager.close();
        getLogger().info("LovePluginName отключён.");
    }

    public static LovePluginName getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public SomeManager getSomeManager() { return someManager; }
}
```

---

## Async / Threading Rules

**Paper 1.21+ uses `AsyncScheduler` — prefer it over BukkitScheduler for async work.**

```java
// ✅ Async DB / IO work via Paper AsyncScheduler
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    // safe: DB queries, file IO, web requests
});

Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
    // ...
}, 5, TimeUnit.SECONDS);

// ✅ Back to main thread for Bukkit API calls
Bukkit.getScheduler().runTask(plugin, () -> {
    player.sendMessage("...");  // Bukkit API — main thread only
});

// ❌ NEVER call Bukkit world/entity API from async context
// ❌ NEVER use BukkitRunnable for new async work — use AsyncScheduler
```

**Key rule:** anything touching `World`, `Player` state, `Inventory`, `Entity` — must run on main thread.
DB queries, config saves, HTTP calls, file IO — always async.

---

## SQLite Database Pattern

```java
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
            plugin.getLogger().info("База данных подключена.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка подключения к БД: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    created_at INTEGER DEFAULT (strftime('%s', 'now'))
                )
            """);
        }
    }

    // Always async-safe: connection is accessed only from async tasks
    public Optional<PlayerData> getPlayer(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new PlayerData(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error getPlayer: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
```

---

## GUI (Inventory) Pattern

```java
public class SomeGui {

    private final LovePluginName plugin;
    private final Player player;

    public SomeGui(LovePluginName plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        int size = plugin.getConfig().getInt("gui.some-gui.size", 27);
        String title = plugin.getConfig().getString("gui.some-gui.title", "Меню");

        Inventory inv = Bukkit.createInventory(null, size,
            Component.text(title));

        // Build items
        ItemStack infoItem = new ItemStack(Material.PAPER);
        ItemMeta meta = infoItem.getItemMeta();
        meta.displayName(Component.text("Информация").color(NamedTextColor.GOLD));
        infoItem.setItemMeta(meta);
        inv.setItem(13, infoItem);

        player.openInventory(inv);
    }
}
```

GUI click handling goes in a dedicated `InventoryClickEvent` listener that identifies the GUI by title.

---

## Commands Pattern

For Paper 1.21+, use `PluginCommand` with tab completion via `TabCompleter`.
For complex argument trees, consider Brigadier via `LifecycleEvents`.

```java
public class SomeCommand implements CommandExecutor, TabCompleter {

    private final LovePluginName plugin;

    public SomeCommand(LovePluginName plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "subcommand" -> handleSubcommand(player, args);
            default -> { sendHelp(player); yield true; }
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("subcommand").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
```

---

## Config / Lang Pattern

`config.yml` — all tunable values (cooldowns, sizes, toggles, etc.)
`lang.yml` — all player-facing strings in Russian

```java
// Never hardcode player messages:
String msg = plugin.getConfig().getString("lang.no-permission",
    "&cУ вас нет прав!");
player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
```

Or use a dedicated `LangManager` that loads `lang.yml` and provides `getMessage(String key)`.

---

## Code Style Rules

- Java 21 features encouraged: records, sealed classes, switch expressions, text blocks, pattern matching
- No raw `ChatColor.RED + "text"` for new code — use `Component` (Adventure API) where possible
- All managers receive `plugin` instance via constructor — no static singletons except `getInstance()`
- Log with `plugin.getLogger()`, never `System.out.println`
- Catch specific exceptions, not bare `Exception e`
- Use `Optional<T>` for nullable DB results
- Use `CompletableFuture<T>` for async methods that return values
- `// TODO:` / `// FIXME:` comments in English; inline Russian comments for complex business logic

---

## Clarification Protocol

**Always ask before writing code when:**
- The feature touches multiple files and the scope is unclear
- The user's request could mean two different architectural approaches
- Integration with another Love\* plugin is needed but the API contract is unknown
- The task is destructive (removing/renaming a core class, changing DB schema)

Ask **one focused question** at a time. Don't write placeholder code "to get started" when requirements
are genuinely unclear — a wrong design costs more to undo than a short delay.

---

## Quick-Reference: API JavaDocs
- Paper 1.21.1 API: https://jd.papermc.io/paper/1.21.1/
- Paper 1.21.11 API: https://jd.papermc.io/paper/1.21.11/
- Purpur API: https://purpurmc.org/javadoc/
- PaperMC Dev Docs: https://docs.papermc.io/paper/dev/

When unsure about a specific API method signature or whether a method is Paper-only vs Bukkit — check
the JavaDocs via web search rather than guessing.
