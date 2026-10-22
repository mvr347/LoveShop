package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.npc.NpcBridge;
import dev.lovelace.loveshops.npc.NpcKeys;
import dev.lovelace.loveshops.npc.NpcLookup;
import dev.lovelace.loveshops.npc.NpcTags;
import dev.lovelace.loveshops.npc.NpcText;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class NpcManager {

    private final LoveShops plugin;
    private final NamespacedKey npcKey;
    private final Map<UUID, NpcData> loadedNpcs = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> spawnedEntities = new ConcurrentHashMap<>();
    /** Provider key of the NPC LoveShops currently tracks for a row (found, bound or created by us). */
    private final Map<UUID, String> trackedKeys = new ConcurrentHashMap<>();
    /** Rows already reported as "should be hidden but the contract cannot hide it"; one line per row, not per tick. */
    private final Set<UUID> hideWarned = ConcurrentHashMap.newKeySet();
    /** NPCs re-tagged from their DB row during the current spawnAllNpcs pass (legacy-tag fallback). */
    private int retagged;

    /**
     * Event NPCs: the plugin creates the NPC itself when the event starts and destroys it when it
     * ends. The {@code shops_npcs} row of such a type is only a spawn point (place, name, skin).
     */
    public static final Set<String> EPHEMERAL_TYPES = Set.of("caravaner", "lostcaravan", "wanderer");
    private static final String TAG_EPHEMERAL = "loveshops_ephemeral";

    public static boolean isEphemeralType(String type) {
        return type != null && EPHEMERAL_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    public enum EphemeralAction { CREATE, DESTROY, NONE }

    /** What to do with the event NPC of a spawn point: create it, destroy it, or leave it alone. */
    public static EphemeralAction decide(boolean eventActive, boolean npcExists) {
        if (eventActive && !npcExists) return EphemeralAction.CREATE;
        if (!eventActive && npcExists) return EphemeralAction.DESTROY;
        return EphemeralAction.NONE;
    }

    /** Tagged NPCs nobody tracks any more (left behind by a crash or a restart in the middle of an event). */
    public static <T> List<T> orphans(Collection<T> tagged, Predicate<T> tracked) {
        List<T> out = new ArrayList<>();
        for (T t : tagged) if (!tracked.test(t)) out.add(t);
        return out;
    }

    private NpcBridge bridge() {
        return plugin.getNpcBridge();
    }

    public NpcManager(LoveShops plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "npc_uuid");
        loadAllNpcs();
    }

    public void loadAllNpcs() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM shops_npcs")) {
                ResultSet rs = ps.executeQuery();
                loadedNpcs.clear();
                while (rs.next()) {
                    NpcData npc = mapResultSet(rs);
                    loadedNpcs.put(npc.uuid(), npc);
                }
                plugin.getLogger().info("Загружено NPC из базы данных: " + loadedNpcs.size());
                Bukkit.getScheduler().runTask(plugin, this::spawnAllNpcs);
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка загрузки NPC: " + e.getMessage());
            }
        });
    }

    /**
     * Creates a new NPC (PLAYER) at the location through the configured NPC provider and registers it in
     * LoveShops. Visibility afterwards follows the schedule (the provider's spawn event is vetoed in
     * {@code NpcListener}; spawnNpcEntity / ensureNpcDespawned).
     */
    public CompletableFuture<NpcData> createNpc(String type, String name, Location loc, String skinOwner) {
        CompletableFuture<NpcData> future = new CompletableFuture<>();
        if (!bridge().available()) {
            future.completeExceptionally(new IllegalStateException("NPC-провайдер '" + bridge().providerId() + "' недоступен"));
            return future;
        }
        UUID npcUuid = UUID.randomUUID();
        if (isEphemeralType(type)) {
            // Only the spawn point is stored; the NPC itself appears with the event.
            return insertNpc(npcUuid, type, name, loc, skinOwner, null);
        }
        Runnable work = () -> {
            try {
                NpcSpec spec = NpcSpec.at(NpcText.section(name), loc).withTags(tagsOf(npcUuid, type));
                if (skinOwner != null && !skinOwner.isEmpty()) spec = spec.withSkinName(skinOwner);
                Optional<NpcHandle> created = bridge().create(spec);
                if (created.isEmpty()) {
                    future.completeExceptionally(new IllegalStateException("NPC-провайдер '" + bridge().providerId() + "' недоступен"));
                    return;
                }
                String key = created.get().key();
                insertNpc(npcUuid, type, name, loc, skinOwner, key).whenComplete((npc, err) -> {
                    if (err != null) {
                        // The provider is main-thread only: the rollback goes back to the main thread too.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                bridge().remove(key);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Откат создания NPC: не удалось снять NPC " + key + ": " + e.getMessage());
                            }
                        });
                        future.completeExceptionally(err);
                    } else {
                        trackedKeys.put(npc.uuid(), key);
                        future.complete(npc);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
        if (Bukkit.isPrimaryThread()) work.run();
        else Bukkit.getScheduler().runTask(plugin, work);
        return future;
    }

    private static Map<String, String> tagsOf(UUID uuid, String type) {
        return Map.of(NpcTags.UUID, uuid.toString(), NpcTags.TYPE, type.toLowerCase(Locale.ROOT));
    }

    /**
     * Binds an NPC that already exists in the provider (an admin placed it) to a LoveShops role.
     *
     * @param skinFallback skin for an event spawn point: the contract cannot read the skin of the NPC being
     *                     replaced, so the configured default of the type is used instead
     */
    public CompletableFuture<NpcData> bindNpc(String type, String npcKey, String displayNameOverride, String boundBy, String skinFallback) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<NpcData> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> bindNpc(type, npcKey, displayNameOverride, boundBy, skinFallback).whenComplete((npc, err) -> {
                if (err != null) future.completeExceptionally(err); else future.complete(npc);
            }));
            return future;
        }
        if (!bridge().available()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NPC-провайдер '" + bridge().providerId() + "' недоступен"));
        }
        Optional<NpcHandle> found = bridge().byKey(npcKey);
        if (found.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NPC #" + npcKey + " не найден у провайдера '" + bridge().providerId() + "'"));
        }
        NpcHandle handle = found.get();
        String name = (displayNameOverride != null && !displayNameOverride.isBlank()) ? displayNameOverride : handle.name();
        Location loc = handle.location();
        if (loc == null || loc.getWorld() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("У NPC #" + npcKey + " нет известного местоположения"));
        }
        UUID npcUuid = UUID.randomUUID();
        if (isEphemeralType(type)) {
            // Take place and name of the NPC as a spawn point, then remove it: the plugin creates its own.
            Location point = loc.clone();
            try {
                bridge().remove(handle.key());
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось убрать привязанный NPC #" + npcKey + ": " + e.getMessage());
            }
            return insertNpc(npcUuid, type, name, point, skinFallback != null && !skinFallback.isBlank() ? skinFallback : boundBy, null);
        }
        applyTags(handle, npcUuid, type);
        return insertNpc(npcUuid, type, name, loc, boundBy, handle.key()).whenComplete((npc, err) -> {
            if (err == null && npc != null) {
                trackedKeys.put(npc.uuid(), handle.key());
            } else {
                // The row was not written: the NPC must not stay claimed by a row that does not exist.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    handle.tag(NpcTags.UUID, null);
                    handle.tag(NpcTags.TYPE, null);
                });
            }
        });
    }

    private void applyTags(NpcHandle handle, UUID uuid, String type) {
        handle.tag(NpcTags.UUID, uuid.toString());
        handle.tag(NpcTags.TYPE, type.toLowerCase(Locale.ROOT));
    }

    private CompletableFuture<NpcData> insertNpc(UUID npcUuid, String type, String name, Location loc, String skinOwner, String npcKey) {
        CompletableFuture<NpcData> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO shops_npcs (uuid, type, world, x, y, z, yaw, pitch, name, display_name, skin_owner, citizens_id, npc_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, npcUuid.toString());
                ps.setString(2, type.toLowerCase());
                ps.setString(3, loc.getWorld().getName());
                ps.setDouble(4, loc.getX());
                ps.setDouble(5, loc.getY());
                ps.setDouble(6, loc.getZ());
                ps.setFloat(7, loc.getYaw());
                ps.setFloat(8, loc.getPitch());
                ps.setString(9, name);
                ps.setString(10, "<gold>" + name + "</gold>");
                ps.setString(11, skinOwner != null ? skinOwner : "Steve");
                // The text key, and for a numeric (Citizens) key the old INTEGER column too.
                NpcKeys.write(ps, 13, 12, npcKey);

                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                int generatedId = rs.next() ? rs.getInt(1) : 0;

                NpcData npcData = new NpcData(
                    generatedId, npcUuid, type.toLowerCase(), loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                    name, "<gold>" + name + "</gold>", skinOwner, npcKey == null || npcKey.isBlank() ? null : npcKey.trim(),
                    System.currentTimeMillis() / 1000, System.currentTimeMillis() / 1000
                );

                loadedNpcs.put(npcUuid, npcData);
                Bukkit.getScheduler().runTask(plugin, () -> spawnNpcEntity(npcData));
                future.complete(npcData);
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка создания NPC: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> deleteNpc(UUID npcUuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        NpcData existing = loadedNpcs.get(npcUuid);
        String npcKey = existing != null ? existing.npcKey() : null;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM shops_npcs WHERE uuid = ?")) {
                ps.setString(1, npcUuid.toString());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    loadedNpcs.remove(npcUuid);
                    Bukkit.getScheduler().runTask(plugin, () -> destroyNpcEntity(npcUuid, npcKey));
                    future.complete(true);
                } else {
                    future.complete(false);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка удаления NPC: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public boolean isNpcAllowedToSpawn(NpcData npc) {
        if (npc == null) return false;
        return isTypeAllowedToSpawn(npc.type());
    }

    public boolean isTypeAllowedToSpawn(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        if (t.equals("seller") || t.equals("buyer") || t.equals("auctioneer") || t.equals("flea")) {
            return false;
        }
        if (t.equals("wanderer")) {
            return plugin.getWandererManager().isWandererActive();
        }
        if (t.equals("caravaner")) {
            return plugin.getDailyCaravanManager() != null && plugin.getDailyCaravanManager().isCaravanerActive();
        }
        if (t.equals("commissioner")) {
            return plugin.getCommissionManager() != null && plugin.getCommissionManager().isEnabled();
        }
        if (t.equals("lostcaravan")) {
            return plugin.getLostCaravanManager() != null && plugin.getLostCaravanManager().isEventActive();
        }
        return true;
    }

    public void spawnNpcEntity(NpcData npc) {
        if (npc == null) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> spawnNpcEntity(npc));
            return;
        }

        if (isEphemeralType(npc.type())) {
            syncEphemeral(npc);
            return;
        }

        if (!isNpcAllowedToSpawn(npc)) {
            ensureNpcDespawned(npc.uuid());
            return;
        }

        World world = Bukkit.getWorld(npc.world());
        if (world == null) return;

        // The provider may register late; until it does there is nothing to show (one warning is logged).
        if (!bridge().available()) return;

        Location loc = new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        spawnProviderNpc(npc, loc);
    }

    /**
     * Finds the provider NPC of a row: the one we already track, the one its key names, or (after an id change
     * or a provider switch) the one tagged with the row's uuid.
     */
    private NpcHandle findHandle(NpcData npc) {
        String tracked = trackedKeys.get(npc.uuid());
        if (tracked != null) {
            Optional<NpcHandle> h = bridge().byKey(tracked);
            if (h.isPresent()) return h.get();
        }
        if (npc.npcKey() != null) {
            Optional<NpcHandle> h = bridge().byKey(npc.npcKey());
            if (h.isPresent()) return h.get();
        }
        List<NpcHandle> tagged = bridge().withTag(NpcTags.UUID, npc.uuid().toString());
        return tagged.isEmpty() ? null : tagged.get(0);
    }

    /** Makes sure the NPC carries this row's tags; {@code true} when it had to be (re)tagged. */
    private boolean ensureTags(NpcHandle handle, NpcData npc) {
        return NpcLookup.ensureTags(handle, npc.uuid(), npc.type());
    }

    private void spawnProviderNpc(NpcData npc, Location loc) {
        try {
            NpcHandle handle = findHandle(npc);
            boolean bound = npc.npcKey() != null;

            if (handle == null && bound) {
                plugin.getLogger().warning("LoveShops NPC #" + npc.id() + " (" + npc.type()
                    + ") привязан к NPC #" + npc.npcKey() + " провайдера '" + bridge().providerId()
                    + "', но такого NPC нет. Удалите запись #" + npc.id());
                return;
            }
            if (handle == null) {
                NpcSpec spec = NpcSpec.at(NpcText.section(npc.name()), loc).withTags(tagsOf(npc.uuid(), npc.type()));
                if (npc.skinOwner() != null && !npc.skinOwner().isEmpty()) spec = spec.withSkinName(npc.skinOwner());
                // create() spawns the NPC where we ask for it.
                bridge().create(spec).ifPresent(created -> trackedKeys.put(npc.uuid(), created.key()));
                return;
            }

            // An NPC bound before the tags moved to the provider's own storage carries none: tag it from the row.
            if (ensureTags(handle, npc)) retagged++;
            trackedKeys.put(npc.uuid(), handle.key());

            if (handle.spawned() && !sameSpot(handle.location(), loc)) {
                handle.teleport(loc);
            }
            // A bound NPC that is not spawned stays that way: the contract has no spawn(), and the provider
            // brings back its own saved NPCs (the schedule veto still applies to that spawn).
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка спавна NPC: " + e.getMessage());
        }
    }

    private static boolean sameSpot(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) return false;
        return a.distanceSquared(b) < 0.0004;
    }

    public void ensureNpcDespawned(UUID npcUuid) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> ensureNpcDespawned(npcUuid));
            return;
        }
        NpcData known = loadedNpcs.get(npcUuid);
        if (known != null && isEphemeralType(known.type())) {
            // Schedulers call this every 30 s for an inactive event: nothing tracked means nothing to scan for.
            if (trackedKeys.containsKey(npcUuid)) destroyEphemeral(known);
            return;
        }
        Entity entity = spawnedEntities.get(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
            spawnedEntities.remove(npcUuid);
        }

        if (known == null || !bridge().available()) return;
        NpcHandle handle = findHandle(known);
        if (handle == null) return;
        trackedKeys.put(npcUuid, handle.key());
        if (handle.spawned() && hideWarned.add(npcUuid)) {
            // NpcProvider has no despawn: the spawn veto keeps the NPC from coming back, but one that is
            // already standing stays until the provider unloads it.
            plugin.getLogger().warning("NPC " + known.type() + " #" + known.id() + " сейчас должен быть скрыт, но контракт NpcProvider "
                + "не умеет прятать NPC: он останется на месте до перезагрузки чанка/сервера (новые спавны запрещены).");
        }
    }

    public void despawnNpcEntity(UUID npcUuid) {
        ensureNpcDespawned(npcUuid);
    }

    /** Destroys the provider NPC of a deleted row. */
    private void destroyNpcEntity(UUID npcUuid, String npcKey) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> destroyNpcEntity(npcUuid, npcKey));
            return;
        }
        Entity entity = spawnedEntities.remove(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        hideWarned.remove(npcUuid);
        String key = trackedKeys.remove(npcUuid);
        if (key == null) key = npcKey;
        if (key == null) return;
        try {
            bridge().remove(key);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось уничтожить NPC: " + e.getMessage());
        }
    }

    public void spawnAllNpcs() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::spawnAllNpcs);
            return;
        }
        if (bridge().available()) {
            migrateBoundEventNpcs();
            purgeOrphanEventNpcs();
        }
        retagged = 0;
        for (NpcData npc : loadedNpcs.values()) {
            spawnNpcEntity(npc);
        }
        if (retagged > 0) {
            plugin.getLogger().info("NPC: повторно помечено NPC провайдера '" + bridge().providerId()
                + "' по записям БД (метки старого формата не читаются): " + retagged);
        }
    }

    // ------------------------------------------------------------------ event NPCs

    private NpcHandle liveEventNpc(NpcData npc) {
        String key = trackedKeys.get(npc.uuid());
        if (key == null) return null;
        return bridge().byKey(key).filter(NpcHandle::spawned).orElse(null);
    }

    /** Creates or destroys the event NPC of a spawn point according to whether its event runs. */
    private void syncEphemeral(NpcData npc) {
        if (!bridge().available()) return;
        boolean exists = liveEventNpc(npc) != null;
        switch (decide(isNpcAllowedToSpawn(npc), exists)) {
            case CREATE -> createEventNpc(npc);
            case DESTROY -> destroyEphemeral(npc);
            case NONE -> { }
        }
    }

    private void createEventNpc(NpcData npc) {
        World world = Bukkit.getWorld(npc.world());
        if (world == null) return;
        try {
            Location loc = new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
            if (!loc.getChunk().isLoaded()) loc.getChunk().load();
            Map<String, String> tags = new HashMap<>(tagsOf(npc.uuid(), npc.type()));
            tags.put(NpcTags.EPHEMERAL, "true");
            NpcSpec spec = NpcSpec.at(NpcText.section(npc.name()), loc).withTags(tags);
            if (npc.skinOwner() != null && !npc.skinOwner().isEmpty()) spec = spec.withSkinName(npc.skinOwner());
            bridge().create(spec).ifPresent(created -> trackedKeys.put(npc.uuid(), created.key()));
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось создать NPC события " + npc.type() + ": " + e.getMessage());
        }
    }

    /** Removes the event NPC for good (not hidden): open menus are closed first. */
    private void destroyEphemeral(NpcData npc) {
        closeMenus(npc.type());
        String key = trackedKeys.remove(npc.uuid());
        if (key == null && bridge().available()) {
            List<NpcHandle> tagged = bridge().withTag(NpcTags.UUID, npc.uuid().toString());
            if (!tagged.isEmpty()) key = tagged.get(0).key();
        }
        if (key == null) return;
        try {
            bridge().remove(key);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось убрать NPC события " + npc.type() + ": " + e.getMessage());
        }
    }

    /** Closes the menus of an event NPC for everybody who still has one open. */
    public void closeMenus(String type) {
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            var top = p.getOpenInventory().getTopInventory();
            var holder = top.getHolder();
            boolean match = switch (type.toLowerCase(Locale.ROOT)) {
                case "caravaner" -> holder instanceof dev.lovelace.loveshops.gui.DailyCaravanGui;
                case "lostcaravan" -> holder instanceof dev.lovelace.loveshops.gui.LostCaravanEntryGui
                        || holder instanceof dev.lovelace.loveshops.gui.LostCaravanAuctionGui
                        || holder instanceof dev.lovelace.loveshops.gui.LostCaravanInstantGui;
                case "wanderer" -> {
                    String title = plain.serialize(p.getOpenInventory().title());
                    yield title.contains(dev.lovelace.loveshops.gui.WandererShopGui.TITLE)
                            || title.contains(dev.lovelace.loveshops.gui.WandererDealGui.TITLE)
                            || title.contains(dev.lovelace.loveshops.gui.WandererWaitingGui.TITLE);
                }
                default -> false;
            };
            if (match) p.closeInventory();
        }
    }

    /** Old rows were bound to an NPC; the spawn point keeps its place, name and skin and the NPC goes. */
    private void migrateBoundEventNpcs() {
        List<String> migrated = new ArrayList<>();
        for (NpcData npc : new ArrayList<>(loadedNpcs.values())) {
            if (!isEphemeralType(npc.type()) || npc.npcKey() == null) continue;
            NpcHandle handle = bridge().byKey(npc.npcKey()).orElse(null);
            // The contract cannot read an NPC's skin, so the row keeps the skin it already has.
            Location where = handle != null ? handle.location() : null;
            NpcData fresh = new NpcData(npc.id(), npc.uuid(), npc.type(), npc.world(),
                    where != null ? where.getX() : npc.x(), where != null ? where.getY() : npc.y(),
                    where != null ? where.getZ() : npc.z(), where != null ? where.getYaw() : npc.yaw(),
                    where != null ? where.getPitch() : npc.pitch(), npc.name(), npc.displayName(),
                    npc.skinOwner(), null, npc.createdAt(), System.currentTimeMillis() / 1000);
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE shops_npcs SET citizens_id = NULL, npc_key = NULL, skin_owner = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, updated_at = ? WHERE uuid = ?")) {
                ps.setString(1, fresh.skinOwner());
                ps.setDouble(2, fresh.x());
                ps.setDouble(3, fresh.y());
                ps.setDouble(4, fresh.z());
                ps.setFloat(5, fresh.yaw());
                ps.setFloat(6, fresh.pitch());
                ps.setLong(7, fresh.updatedAt());
                ps.setString(8, npc.uuid().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Миграция NPC #" + npc.id() + " не удалась: " + e.getMessage());
                continue;
            }
            loadedNpcs.put(fresh.uuid(), fresh);
            if (handle != null) {
                try {
                    bridge().remove(handle.key());
                } catch (Exception e) {
                    plugin.getLogger().warning("Не удалось убрать старый NPC #" + npc.npcKey() + ": " + e.getMessage());
                }
            }
            migrated.add("#" + npc.id() + " " + npc.type());
        }
        if (!migrated.isEmpty()) {
            plugin.getLogger().warning("NPC событий больше не привязаны к чужим NPC, плагин создаёт их сам. Перенесены точки: " + String.join(", ", migrated));
        }
    }

    /** Event NPCs of a previous run (crash, restart during an event) are removed; a running event gets a fresh one. */
    private void purgeOrphanEventNpcs() {
        List<NpcHandle> tagged = bridge().withTag(NpcTags.EPHEMERAL);
        Set<String> live = new HashSet<>(trackedKeys.values());
        for (NpcHandle orphan : orphans(tagged, h -> live.contains(h.key()))) {
            try {
                bridge().remove(orphan.key());
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось убрать забытый NPC события: " + e.getMessage());
            }
        }
    }

    public void despawnAllNpcs() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::despawnAllNpcs);
            return;
        }
        for (UUID uuid : new HashSet<>(spawnedEntities.keySet())) {
            despawnNpcEntity(uuid);
        }
        for (UUID uuid : new HashSet<>(trackedKeys.keySet())) {
            despawnNpcEntity(uuid);
        }
    }

    public NpcData getNpcByUuid(UUID uuid) {
        if (uuid == null) return null;
        return loadedNpcs.get(uuid);
    }

    public NpcData getNpcById(int id) {
        for (NpcData npc : loadedNpcs.values()) {
            if (npc.id() == id) return npc;
        }
        return null;
    }

    /** The row bound to the NPC with this provider key (for Citizens: its numeric id as text). */
    public Optional<NpcData> getNpcByKey(String npcKey) {
        if (npcKey == null || npcKey.isBlank()) return Optional.empty();
        String key = npcKey.trim();
        return loadedNpcs.values().stream()
            .filter(n -> key.equals(n.npcKey()))
            .findFirst();
    }

    /**
     * The row an NPC of the configured provider belongs to: by its uuid tag first, then by the key stored in
     * the row. The key path is the legacy fallback - an NPC bound before tags moved into the provider's own
     * storage has no tag, and is tagged here from its row so the next lookup finds it directly.
     */
    public NpcData resolve(NpcHandle npc) {
        if (npc == null || !bridge().owns(npc)) return null;
        NpcLookup.Resolution found = NpcLookup.resolve(loadedNpcs, npc);
        if (found.viaKey()) {
            ensureTags(npc, found.data());
        }
        return found.data();
    }

    public Optional<NpcData> getNpcFromEntity(Entity entity) {
        if (entity == null) return Optional.empty();
        String uuidStr = entity.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (uuidStr != null) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                return Optional.ofNullable(loadedNpcs.get(uuid));
            } catch (IllegalArgumentException ignored) {}
        }
        return Optional.empty();
    }

    public Collection<NpcData> getAllNpcs() {
        return Collections.unmodifiableCollection(loadedNpcs.values());
    }

    public List<NpcData> getNpcsByType(String type) {
        return loadedNpcs.values().stream()
            .filter(n -> n.type().equalsIgnoreCase(type))
            .toList();
    }

    public Optional<NpcData> getNpcNear(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        return loadedNpcs.values().stream()
            .filter(n -> n.world().equals(loc.getWorld().getName()))
            .filter(n -> {
                double dx = n.x() - loc.getX();
                double dy = n.y() - loc.getY();
                double dz = n.z() - loc.getZ();
                return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
            })
            .findFirst();
    }

    private NpcData mapResultSet(ResultSet rs) throws SQLException {
        // npc_key first; the old citizens_id column is the fallback for a row the key migration has not filled.
        String npcKey = NpcKeys.read(rs, "npc_key", "citizens_id");
        return new NpcData(
            rs.getInt("id"),
            UUID.fromString(rs.getString("uuid")),
            rs.getString("type"),
            rs.getString("world"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z"),
            rs.getFloat("yaw"),
            rs.getFloat("pitch"),
            rs.getString("name"),
            rs.getString("display_name"),
            rs.getString("skin_owner"),
            npcKey,
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}
