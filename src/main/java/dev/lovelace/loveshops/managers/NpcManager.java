package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.NpcData;
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
import java.sql.Types;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class NpcManager {

    private final LoveShops plugin;
    private final NamespacedKey npcKey;
    private final Map<UUID, NpcData> loadedNpcs = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> spawnedEntities = new ConcurrentHashMap<>();
    private final Map<UUID, net.citizensnpcs.api.npc.NPC> citizensNpcs = new ConcurrentHashMap<>();

    /**
     * Event NPCs: the plugin creates the Citizens NPC itself when the event starts and destroys it when it
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
     * Создаёт новый Citizens NPC (PLAYER) на указанной локации и регистрирует его в LoveShops.
     * Видимость дальше управляется расписанием (spawnNpcEntity / despawnNpcEntity).
     * Citizens обязателен.
     */
    public CompletableFuture<NpcData> createNpc(String type, String name, Location loc, String skinOwner) {
        CompletableFuture<NpcData> future = new CompletableFuture<>();
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            future.completeExceptionally(new IllegalStateException("Citizens обязателен для создания NPC"));
            return future;
        }
        if (isEphemeralType(type)) {
            // Only the spawn point is stored; the NPC itself appears with the event.
            return insertNpc(type, name, loc, skinOwner, null);
        }
        Runnable work = () -> {
            try {
                net.citizensnpcs.api.npc.NPCRegistry registry = net.citizensnpcs.api.CitizensAPI.getNPCRegistry();
                net.citizensnpcs.api.npc.NPC cNpc = registry.createNPC(org.bukkit.entity.EntityType.PLAYER, name);
                if (skinOwner != null && !skinOwner.isEmpty()) {
                    net.citizensnpcs.trait.SkinTrait skinTrait = cNpc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class);
                    skinTrait.setSkinName(skinOwner);
                }
                cNpc.data().setPersistent("loveshops_type", type.toLowerCase(Locale.ROOT));
                int citizensId = cNpc.getId();
                insertNpc(type, name, loc, skinOwner, citizensId).whenComplete((npc, err) -> {
                    if (err != null) {
                        try { registry.deregister(cNpc); } catch (Exception e) { plugin.getLogger().warning("Откат создания NPC: не удалось снять Citizens NPC: " + e.getMessage()); }
                        future.completeExceptionally(err);
                    } else {
                        cNpc.data().setPersistent("loveshops_uuid", npc.uuid().toString());
                        citizensNpcs.put(npc.uuid(), cNpc);
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

    public CompletableFuture<NpcData> bindNpc(String type, int citizensId, String displayNameOverride, String boundBy) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<NpcData> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> bindNpc(type, citizensId, displayNameOverride, boundBy).whenComplete((npc, err) -> {
                if (err != null) future.completeExceptionally(err); else future.complete(npc);
            }));
            return future;
        }
        net.citizensnpcs.api.npc.NPC cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(citizensId);
        if (cNpc == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Citizens NPC #" + citizensId + " не найден"));
        }
        String name = (displayNameOverride != null && !displayNameOverride.isBlank()) ? displayNameOverride : cNpc.getName();
        Location loc = cNpc.isSpawned() && cNpc.getEntity() != null ? cNpc.getEntity().getLocation() : cNpc.getStoredLocation();
        if (loc == null || loc.getWorld() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("У Citizens NPC #" + citizensId + " нет известного местоположения"));
        }
        if (isEphemeralType(type)) {
            // Take place, name and skin of the NPC as a spawn point, then remove it: the plugin creates its own.
            String skin = skinNameOf(cNpc);
            Location point = loc.clone();
            try {
                if (cNpc.isSpawned()) cNpc.despawn();
                net.citizensnpcs.api.CitizensAPI.getNPCRegistry().deregister(cNpc);
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось убрать привязанный Citizens NPC #" + citizensId + ": " + e.getMessage());
            }
            return insertNpc(type, name, point, skin != null ? skin : boundBy, null);
        }
        return insertNpc(type, name, loc, boundBy, citizensId).whenComplete((npc, err) -> {
            if (err == null && npc != null) {
                cNpc.data().setPersistent("loveshops_uuid", npc.uuid().toString());
                cNpc.data().setPersistent("loveshops_type", npc.type().toLowerCase(Locale.ROOT));
                citizensNpcs.put(npc.uuid(), cNpc);
            }
        });
    }

    private CompletableFuture<NpcData> insertNpc(String type, String name, Location loc, String skinOwner, Integer citizensId) {
        CompletableFuture<NpcData> future = new CompletableFuture<>();
        UUID npcUuid = UUID.randomUUID();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO shops_npcs (uuid, type, world, x, y, z, yaw, pitch, name, display_name, skin_owner, citizens_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                if (citizensId != null) {
                    ps.setInt(12, citizensId);
                } else {
                    ps.setNull(12, Types.INTEGER);
                }

                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                int generatedId = rs.next() ? rs.getInt(1) : 0;

                NpcData npcData = new NpcData(
                    generatedId, npcUuid, type.toLowerCase(), loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                    name, "<gold>" + name + "</gold>", skinOwner, citizensId,
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
        Integer citizensId = existing != null ? existing.citizensId() : null;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM shops_npcs WHERE uuid = ?")) {
                ps.setString(1, npcUuid.toString());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    loadedNpcs.remove(npcUuid);
                    Bukkit.getScheduler().runTask(plugin, () -> destroyNpcEntity(npcUuid, citizensId));
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

        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            plugin.getLogger().warning("Citizens не установлен — NPC " + npc.type() + " не может быть показан.");
            return;
        }

        Location loc = new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        spawnCitizensNpc(npc, loc);
    }

    private void spawnCitizensNpc(NpcData npc, Location loc) {
        try {
            net.citizensnpcs.api.npc.NPCRegistry registry = net.citizensnpcs.api.CitizensAPI.getNPCRegistry();
            net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.get(npc.uuid());
            boolean bound = npc.citizensId() != null;

            if (cNpc == null && bound) {
                cNpc = registry.getById(npc.citizensId());
                if (cNpc == null) {
                    plugin.getLogger().warning("LoveShops NPC #" + npc.id() + " (" + npc.type()
                        + ") привязан к Citizens NPC #" + npc.citizensId() + ", но такого NPC нет. Удалите запись #" + npc.id());
                    return;
                }
            } else if (cNpc == null) {
                for (net.citizensnpcs.api.npc.NPC existing : registry) {
                    if (npc.uuid().toString().equals(existing.data().get("loveshops_uuid", null))) {
                        cNpc = existing;
                        break;
                    }
                }
                if (cNpc == null) {
                    cNpc = registry.createNPC(org.bukkit.entity.EntityType.PLAYER, npc.name());
                    if (npc.skinOwner() != null && !npc.skinOwner().isEmpty()) {
                        net.citizensnpcs.trait.SkinTrait skinTrait = cNpc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class);
                        skinTrait.setSkinName(npc.skinOwner());
                    }
                }
            }

            cNpc.data().setPersistent("loveshops_uuid", npc.uuid().toString());
            cNpc.data().setPersistent("loveshops_type", npc.type().toLowerCase(Locale.ROOT));

            citizensNpcs.put(npc.uuid(), cNpc);

            if (!cNpc.isSpawned()) {
                Location spawnAt = (bound && cNpc.getStoredLocation() != null) ? cNpc.getStoredLocation() : loc;
                cNpc.spawn(spawnAt);
            } else {
                cNpc.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка спавна Citizens NPC: " + e.getMessage());
        }
    }

    public void ensureNpcDespawned(UUID npcUuid) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> ensureNpcDespawned(npcUuid));
            return;
        }
        NpcData known = loadedNpcs.get(npcUuid);
        if (known != null && isEphemeralType(known.type())) {
            // Schedulers call this every 30 s for an inactive event: nothing tracked means nothing to scan for.
            if (citizensNpcs.containsKey(npcUuid)) destroyEphemeral(known);
            return;
        }
        Entity entity = spawnedEntities.get(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
            spawnedEntities.remove(npcUuid);
        }

        net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.get(npcUuid);
        if (cNpc == null && Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            NpcData data = loadedNpcs.get(npcUuid);
            if (data != null && data.citizensId() != null) {
                cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(data.citizensId());
                if (cNpc != null) {
                    citizensNpcs.put(npcUuid, cNpc);
                }
            }
            if (cNpc == null) {
                for (net.citizensnpcs.api.npc.NPC existing : net.citizensnpcs.api.CitizensAPI.getNPCRegistry()) {
                    if (npcUuid.toString().equals(existing.data().get("loveshops_uuid", null))) {
                        cNpc = existing;
                        citizensNpcs.put(npcUuid, cNpc);
                        break;
                    }
                }
            }
        }
        if (cNpc != null && cNpc.isSpawned()) {
            cNpc.despawn();
        }
    }

    public void despawnNpcEntity(UUID npcUuid) {
        ensureNpcDespawned(npcUuid);
    }

    private void unbindNpcEntity(UUID npcUuid, Integer citizensId) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> unbindNpcEntity(npcUuid, citizensId));
            return;
        }
        Entity entity = spawnedEntities.remove(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }

        net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.remove(npcUuid);
        if (cNpc == null && citizensId != null && Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(citizensId);
        }
        if (cNpc != null) {
            cNpc.data().remove("loveshops_uuid");
            cNpc.data().remove("loveshops_type");
        }
    }

    /** Полностью уничтожает Citizens NPC (для delete). */
    private void destroyNpcEntity(UUID npcUuid, Integer citizensId) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> destroyNpcEntity(npcUuid, citizensId));
            return;
        }
        Entity entity = spawnedEntities.remove(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.remove(npcUuid);
        if (cNpc == null && citizensId != null && Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(citizensId);
        }
        if (cNpc != null) {
            try {
                if (cNpc.isSpawned()) cNpc.despawn();
                net.citizensnpcs.api.CitizensAPI.getNPCRegistry().deregister(cNpc);
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось уничтожить Citizens NPC: " + e.getMessage());
            }
        }
    }

    public void spawnAllNpcs() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::spawnAllNpcs);
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            migrateBoundEventNpcs();
            purgeOrphanEventNpcs();
        }
        for (NpcData npc : loadedNpcs.values()) {
            spawnNpcEntity(npc);
        }
    }

    // ------------------------------------------------------------------ event NPCs

    private net.citizensnpcs.api.npc.NPC liveEventNpc(NpcData npc) {
        net.citizensnpcs.api.npc.NPC c = citizensNpcs.get(npc.uuid());
        return c != null && c.isSpawned() ? c : null;
    }

    /** Creates or destroys the event NPC of a spawn point according to whether its event runs. */
    private void syncEphemeral(NpcData npc) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return;
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
            net.citizensnpcs.api.npc.NPC cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
                    .createNPC(org.bukkit.entity.EntityType.PLAYER, npc.name());
            if (npc.skinOwner() != null && !npc.skinOwner().isEmpty()) {
                cNpc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).setSkinName(npc.skinOwner());
            }
            cNpc.data().setPersistent("loveshops_uuid", npc.uuid().toString());
            cNpc.data().setPersistent("loveshops_type", npc.type().toLowerCase(Locale.ROOT));
            cNpc.data().setPersistent(TAG_EPHEMERAL, true);
            citizensNpcs.put(npc.uuid(), cNpc);
            cNpc.spawn(loc);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось создать NPC события " + npc.type() + ": " + e.getMessage());
        }
    }

    /** Removes the event NPC for good (not hidden): open menus are closed first. */
    private void destroyEphemeral(NpcData npc) {
        closeMenus(npc.type());
        net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.remove(npc.uuid());
        if (cNpc == null && Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            for (net.citizensnpcs.api.npc.NPC existing : net.citizensnpcs.api.CitizensAPI.getNPCRegistry()) {
                if (npc.uuid().toString().equals(existing.data().get("loveshops_uuid", null))) {
                    cNpc = existing;
                    break;
                }
            }
        }
        if (cNpc == null) return;
        try {
            if (cNpc.isSpawned()) cNpc.despawn();
            net.citizensnpcs.api.CitizensAPI.getNPCRegistry().deregister(cNpc);
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

    /** Skin name of a Citizens NPC, {@code null} when it has no named skin. */
    private static String skinNameOf(net.citizensnpcs.api.npc.NPC cNpc) {
        if (cNpc == null || !cNpc.hasTrait(net.citizensnpcs.trait.SkinTrait.class)) return null;
        String n = cNpc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).getSkinName();
        return n == null || n.isBlank() ? null : n;
    }

    /** Old rows were bound to a Citizens NPC; the spawn point keeps its place, name and skin and the NPC goes. */
    private void migrateBoundEventNpcs() {
        List<String> migrated = new ArrayList<>();
        for (NpcData npc : new ArrayList<>(loadedNpcs.values())) {
            if (!isEphemeralType(npc.type()) || npc.citizensId() == null) continue;
            net.citizensnpcs.api.npc.NPC cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(npc.citizensId());
            String skin = skinNameOf(cNpc);
            Location where = cNpc != null && cNpc.getStoredLocation() != null ? cNpc.getStoredLocation() : null;
            NpcData fresh = new NpcData(npc.id(), npc.uuid(), npc.type(), npc.world(),
                    where != null ? where.getX() : npc.x(), where != null ? where.getY() : npc.y(),
                    where != null ? where.getZ() : npc.z(), where != null ? where.getYaw() : npc.yaw(),
                    where != null ? where.getPitch() : npc.pitch(), npc.name(), npc.displayName(),
                    skin != null ? skin : npc.skinOwner(), null, npc.createdAt(), System.currentTimeMillis() / 1000);
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE shops_npcs SET citizens_id = NULL, skin_owner = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, updated_at = ? WHERE uuid = ?")) {
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
            if (cNpc != null) {
                try {
                    if (cNpc.isSpawned()) cNpc.despawn();
                    net.citizensnpcs.api.CitizensAPI.getNPCRegistry().deregister(cNpc);
                } catch (Exception e) {
                    plugin.getLogger().warning("Не удалось убрать старый Citizens NPC #" + npc.citizensId() + ": " + e.getMessage());
                }
            }
            migrated.add("#" + npc.id() + " " + npc.type());
        }
        if (!migrated.isEmpty()) {
            plugin.getLogger().warning("NPC событий больше не привязаны к Citizens, плагин создаёт их сам. Перенесены точки: " + String.join(", ", migrated));
        }
    }

    /** Event NPCs of a previous run (crash, restart during an event) are removed; a running event gets a fresh one. */
    private void purgeOrphanEventNpcs() {
        List<net.citizensnpcs.api.npc.NPC> tagged = new ArrayList<>();
        for (net.citizensnpcs.api.npc.NPC existing : net.citizensnpcs.api.CitizensAPI.getNPCRegistry()) {
            if (existing.data().has(TAG_EPHEMERAL)) tagged.add(existing);
        }
        Set<net.citizensnpcs.api.npc.NPC> live = new HashSet<>(citizensNpcs.values());
        for (net.citizensnpcs.api.npc.NPC orphan : orphans(tagged, live::contains)) {
            try {
                if (orphan.isSpawned()) orphan.despawn();
                net.citizensnpcs.api.CitizensAPI.getNPCRegistry().deregister(orphan);
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
        for (UUID uuid : new HashSet<>(citizensNpcs.keySet())) {
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

    public Optional<NpcData> getNpcByCitizensId(int citizensId) {
        return loadedNpcs.values().stream()
            .filter(n -> n.citizensId() != null && n.citizensId() == citizensId)
            .findFirst();
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
        int rawCitizensId = rs.getInt("citizens_id");
        Integer citizensId = rs.wasNull() ? null : rawCitizensId;
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
            citizensId,
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}
