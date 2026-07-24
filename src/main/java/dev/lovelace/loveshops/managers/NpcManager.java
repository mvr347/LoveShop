package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public CompletableFuture<NpcData> createNpc(String type, String name, Location loc, String skinOwner) {
        CompletableFuture<NpcData> future = new CompletableFuture<>();
        UUID npcUuid = UUID.randomUUID();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO shops_npcs (uuid, type, world, x, y, z, yaw, pitch, name, display_name, skin_owner)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

                ps.executeUpdate();
                ResultSet rs = ps.getGeneratedKeys();
                int generatedId = rs.next() ? rs.getInt(1) : 0;

                NpcData npcData = new NpcData(
                    generatedId, npcUuid, type.toLowerCase(), loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                    name, "<gold>" + name + "</gold>", skinOwner, System.currentTimeMillis() / 1000, System.currentTimeMillis() / 1000
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
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM shops_npcs WHERE uuid = ?")) {
                ps.setString(1, npcUuid.toString());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    loadedNpcs.remove(npcUuid);
                    Bukkit.getScheduler().runTask(plugin, () -> despawnNpcEntity(npcUuid));
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

    public void spawnNpcEntity(NpcData npc) {
        if (npc == null) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> spawnNpcEntity(npc));
            return;
        }

        if (npc.type().equalsIgnoreCase("seller") && !plugin.getSellerManager().isSellerActive()) {
            despawnNpcEntity(npc.uuid());
            return;
        }

        if (npc.type().equalsIgnoreCase("buyer") && plugin.getSellerManager().isSellerActive()) {
            despawnNpcEntity(npc.uuid());
            return;
        }

        World world = Bukkit.getWorld(npc.world());
        if (world == null) return;

        despawnNpcEntity(npc.uuid());

        Location loc = new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            spawnCitizensNpc(npc, loc);
            return;
        }

        Villager villager = world.spawn(loc, Villager.class, v -> {
            v.customName(MessageUtils.parse(npc.displayName()));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setRemoveWhenFarAway(false);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, npc.uuid().toString());

            switch (npc.type().toLowerCase()) {
                case "buyer" -> v.setProfession(Villager.Profession.WEAPONSMITH);
                case "seller" -> v.setProfession(Villager.Profession.ARMORER);
                case "auctioneer" -> v.setProfession(Villager.Profession.LIBRARIAN);
            }
        });

        spawnedEntities.put(npc.uuid(), villager);
    }

    private void spawnCitizensNpc(NpcData npc, Location loc) {
        try {
            net.citizensnpcs.api.npc.NPCRegistry registry = net.citizensnpcs.api.CitizensAPI.getNPCRegistry();
            net.citizensnpcs.api.npc.NPC cNpc = null;
            for (net.citizensnpcs.api.npc.NPC existing : registry) {
                if (npc.uuid().toString().equals(existing.data().get("loveshops_uuid", null))) {
                    cNpc = existing;
                    break;
                }
            }

            if (cNpc == null) {
                cNpc = registry.createNPC(org.bukkit.entity.EntityType.PLAYER, npc.name());
                cNpc.data().setPersistent("loveshops_uuid", npc.uuid().toString());
                cNpc.data().setPersistent("loveshops_type", npc.type());
            } else {
                cNpc.setName(npc.name());
            }

            if (npc.skinOwner() != null && !npc.skinOwner().isEmpty()) {
                net.citizensnpcs.trait.SkinTrait skinTrait = cNpc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class);
                skinTrait.setSkinName(npc.skinOwner());
            }

            if (!cNpc.isSpawned()) {
                cNpc.spawn(loc);
            } else {
                cNpc.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }

            citizensNpcs.put(npc.uuid(), cNpc);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка спавна Citizens NPC: " + e.getMessage());
        }
    }

    public void despawnNpcEntity(UUID npcUuid) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> despawnNpcEntity(npcUuid));
            return;
        }
        Entity entity = spawnedEntities.remove(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.remove(npcUuid);
            if (cNpc != null) {
                if (cNpc.isSpawned()) {
                    cNpc.despawn();
                }
                cNpc.destroy();
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
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}
