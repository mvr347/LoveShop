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

public class NpcManager {

    private final LoveShops plugin;
    private final NamespacedKey npcKey;
    private final Map<UUID, NpcData> loadedNpcs = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> spawnedEntities = new ConcurrentHashMap<>();
    private final Map<UUID, net.citizensnpcs.api.npc.NPC> citizensNpcs = new ConcurrentHashMap<>();

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
                        try { registry.deregister(cNpc); } catch (Exception ignored) {}
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
        if (t.equals("seller") || t.equals("buyer") || t.equals("auctioneer")) {
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
        for (NpcData npc : loadedNpcs.values()) {
            spawnNpcEntity(npc);
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
