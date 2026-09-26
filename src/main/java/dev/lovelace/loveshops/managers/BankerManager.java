package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Комиссия банкира: базовая из config ({@code banker.fee-percent}), плюс персональные
 * оверрайды в таблице {@code banker_fee_overrides}.
 */
public class BankerManager {

    private final LoveShops plugin;
    /** Кэш: uuid → fee %. Отсутствие ключа = использовать базовую из конфига. */
    private final Map<UUID, Integer> feeOverrides = new ConcurrentHashMap<>();

    public BankerManager(LoveShops plugin) {
        this.plugin = plugin;
        loadAll();
    }

    public int getBaseFeePercent() {
        return Math.max(0, Math.min(100, plugin.getConfig().getInt("banker.fee-percent", 5)));
    }

    /**
     * Эффективная комиссия игрока: персональная, если задана, иначе базовая.
     */
    public int getFeePercent(UUID playerUuid) {
        if (playerUuid == null) return getBaseFeePercent();
        Integer override = feeOverrides.get(playerUuid);
        return override != null ? override : getBaseFeePercent();
    }

    public boolean hasOverride(UUID playerUuid) {
        return playerUuid != null && feeOverrides.containsKey(playerUuid);
    }

    /**
     * Установить персональную комиссию (0–100). Пишет в БД и кэш.
     */
    public CompletableFuture<Void> setFeePercent(UUID playerUuid, int feePercent, String setBy) {
        int clamped = Math.max(0, Math.min(100, feePercent));
        feeOverrides.put(playerUuid, clamped);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO banker_fee_overrides (player_uuid, fee_percent, set_by)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    fee_percent = EXCLUDED.fee_percent,
                    set_by = EXCLUDED.set_by,
                    set_at = strftime('%s', 'now')
                """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setInt(2, clamped);
                ps.setString(3, setBy != null ? setBy : "console");
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка записи комиссии банкира: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Сбросить персональную комиссию → снова базовая.
     */
    public CompletableFuture<Void> clearFeePercent(UUID playerUuid) {
        feeOverrides.remove(playerUuid);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM banker_fee_overrides WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка сброса комиссии банкира: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void loadAll() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, fee_percent FROM banker_fee_overrides");
                 ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        int fee = Math.max(0, Math.min(100, rs.getInt("fee_percent")));
                        feeOverrides.put(uuid, fee);
                        n++;
                    } catch (IllegalArgumentException ignored) {}
                }
                if (n > 0) {
                    plugin.getLogger().info("Загружено персональных комиссий банкира: " + n);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Не удалось загрузить комиссии банкира: " + e.getMessage());
            }
        });
    }
}
