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
     * Legacy/no-Citizens creation path: LoveShops spawns and fully owns a plain Villager NPC at
     * the given location. This is what runs when Citizens isn't installed at all - there's no
     * Citizens NPC to bind to, so it's the only way to place an NPC merchant on the map.
     */
    public CompletableFuture<NpcData> createNpc(String type, String name, Location loc, String skinOwner) {
        return insertNpc(type, name, loc, skinOwner, null);
    }

    /**
     * Binds a LoveShops role onto a Citizens NPC the admin already created and positioned
     * themselves ({@code /npc create}, then {@code /loveshopsadmin npc create <type> [name]}
     * while looking at it or having it Citizens-selected). LoveShops never creates or destroys
     * that Citizens NPC - {@link #spawnCitizensNpc} only tags/looks it up by id from here on,
     * and {@link #deleteNpc} only untags it. See {@code CitizensIntegration}.
     */
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
        return insertNpc(type, name, loc, boundBy, citizensId);
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
                    Bukkit.getScheduler().runTask(plugin, () -> unbindNpcEntity(npcUuid, citizensId));
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

        if ((npc.type().equalsIgnoreCase("seller") || npc.type().equalsIgnoreCase("auctioneer"))
            && !plugin.getSellerManager().isSellerActive()) {
            despawnNpcEntity(npc.uuid());
            return;
        }

        if (npc.type().equalsIgnoreCase("wanderer") && !plugin.getWandererManager().isWandererActive()) {
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
                case "wanderer" -> v.setProfession(Villager.Profession.FLETCHER);
                case "warmerchant" -> v.setProfession(Villager.Profession.TOOLSMITH);
            }
        });

        spawnedEntities.put(npc.uuid(), villager);
    }

    /**
     * Resolves this row's Citizens NPC handle and tags it, without ever creating or destroying
     * the underlying entity for a bound row (citizens_id set) - only a legacy row (citizens_id
     * NULL, created before the 2026-09-23 bind workflow) still gets a NPC created for it here,
     * exactly as every row used to be handled.
     */
    private void spawnCitizensNpc(NpcData npc, Location loc) {
        try {
            net.citizensnpcs.api.npc.NPCRegistry registry = net.citizensnpcs.api.CitizensAPI.getNPCRegistry();
            net.citizensnpcs.api.npc.NPC cNpc;
            boolean bound = npc.citizensId() != null;

            if (bound) {
                cNpc = registry.getById(npc.citizensId());
                if (cNpc == null) {
                    plugin.getLogger().warning("LoveShops NPC #" + npc.id() + " (" + npc.type()
                        + ") привязан к Citizens NPC #" + npc.citizensId() + ", но такого NPC "
                        + "больше нет в Citizens. Пересоздайте привязку: /loveshopsadmin npc create "
                        + npc.type() + " (глядя на нужный Citizens NPC), затем /loveshopsadmin npc delete " + npc.id() + ".");
                    return;
                }
            } else {
                cNpc = null;
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
            cNpc.data().setPersistent("loveshops_type", npc.type());

            if (bound) {
                // The admin positions/moves a bound NPC with Citizens' own commands - LoveShops
                // only makes sure it's actually spawned somewhere, never force-teleports it.
                if (!cNpc.isSpawned()) {
                    Location spawnAt = cNpc.getStoredLocation() != null ? cNpc.getStoredLocation() : loc;
                    cNpc.spawn(spawnAt);
                }
            } else if (!cNpc.isSpawned()) {
                cNpc.spawn(loc);
            } else {
                cNpc.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }

            citizensNpcs.put(npc.uuid(), cNpc);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка спавна Citizens NPC: " + e.getMessage());
        }
    }

    /**
     * Routine hide, called on every gating transition (weekly Барахолка leaving, plugin
     * disable, a role being deleted, etc.) - never {@code destroy()}s a Citizens NPC, only
     * despawns it, since a bound NPC belongs to the admin's own Citizens setup and routine
     * plugin-side state changes must not delete it. Full unbinding is {@link #unbindNpcEntity}.
     */
    public void despawnNpcEntity(UUID npcUuid) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> despawnNpcEntity(npcUuid));
            return;
        }
        Entity entity = spawnedEntities.remove(npcUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }

        net.citizensnpcs.api.npc.NPC cNpc = citizensNpcs.remove(npcUuid);
        if (cNpc == null && Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            // 2026-09-26: citizensNpcs получает запись ТОЛЬКО через spawnCitizensNpc() - а
            // spawnNpcEntity() для seller/auctioneer/wanderer с неактивным расписанием вызывает
            // despawnNpcEntity() ДО того, как spawnCitizensNpc успевал отработать хоть раз (см.
            // ранний return в spawnNpcEntity). Из-за этого только что привязанный NPC (или любой
            // NPC seller/auctioneer, чьё расписание уже неактивно на старте сервера) оставался
            // видимым навсегда - Citizens сам держит его заспавненным с /npc create, а этот метод
            // был no-op'ом, не находя cNpc в ещё пустой карте. Резолвим напрямую по citizens_id
            // из БД (loadedNpcs уже содержит эту строку к моменту любого вызова despawn).
            NpcData data = loadedNpcs.get(npcUuid);
            if (data != null && data.citizensId() != null) {
                cNpc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(data.citizensId());
            }
        }
        if (cNpc != null && cNpc.isSpawned()) {
            cNpc.despawn();
        }
    }

    /**
     * Fully removes a LoveShops role from its NPC, called only from {@link #deleteNpc}. A
     * Citizens-bound NPC is left exactly as Citizens shows it - only the {@code loveshops_*}
     * tags come off, the entity itself is never despawned or destroyed, since the admin created
     * and owns it. A legacy/no-Citizens row has no other owner, so its Villager is removed
     * outright, same as this plugin has always done for it.
     */
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

    /** Looks up by {@code shops_npcs.id} (the short number shown in {@code npc list}), for {@code /loveshopsadmin npc delete <id>}. */
    public NpcData getNpcById(int id) {
        for (NpcData npc : loadedNpcs.values()) {
            if (npc.id() == id) return npc;
        }
        return null;
    }

    /** Looks up the LoveShops row bound to a given Citizens NPC id, for the look-at-and-delete path. */
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
        // wasNull() reflects only the most recently read column, so the NULL check has to happen
        // right next to getInt() - not after the several other rs.getXxx() calls the NpcData
        // constructor below makes, which would each overwrite it first.
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
