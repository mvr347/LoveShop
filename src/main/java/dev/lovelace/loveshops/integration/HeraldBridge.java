package dev.lovelace.loveshops.integration;

import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Bridge to the LoveTweaks herald: server-wide announcements (caravans, wanderer) go through it so
 * they look like every other herald announcement. LoveTweaks exposes no service, so this calls
 * {@code LoveTweaks#getHeraldManager()} -> {@code HeraldManager#announce(Component)} by reflection.
 * Without LoveTweaks (or with an older build) the same prefix is put in front locally.
 */
public final class HeraldBridge {

    /** Same look as the herald prefix in LoveTweaks ({@code herald.gui.messages.prefix}). */
    private static final String FALLBACK_PREFIX = "<dark_gray>»</dark_gray> <gold>Глашатай</gold> <dark_gray>»</dark_gray> ";

    private HeraldBridge() {}

    /** Main thread only: the herald broadcasts through Bukkit. */
    public static void announce(Logger log, Component body) {
        if (body == null) return;
        Plugin loveTweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
        if (loveTweaks != null && loveTweaks.isEnabled()) {
            try {
                Object herald = loveTweaks.getClass().getMethod("getHeraldManager").invoke(loveTweaks);
                if (herald != null) {
                    Method announce = herald.getClass().getMethod("announce", Component.class);
                    announce.invoke(herald, body);
                    return;
                }
            } catch (NoSuchMethodException ignored) {
                // An older LoveTweaks without HeraldManager#announce: fall through to the local prefix.
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.fine("Herald announce failed, using the local prefix: " + e.getMessage());
            }
        }
        Bukkit.broadcast(MessageUtils.parse(FALLBACK_PREFIX).append(body));
    }
}
