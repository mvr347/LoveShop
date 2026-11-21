package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.lovecore.api.provider.Providers;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The only door from LoveShops to NPCs. The provider is always LoveEssential (2026-11-21: the provider switch
 * was removed) and is looked up on every use: it registers late (after the server has loaded), so a reference
 * kept in a field would miss it.
 *
 * <p>When LoveEssential is not there, one warning names it and every NPC feature quietly does nothing
 * until it appears; nothing throws.</p>
 */
public final class NpcBridge {

    /** The only supported provider. */
    public static final String PROVIDER_ID = Providers.LOVE_ESSENTIAL;

    private final Function<String, Optional<NpcProvider>> resolver;
    private final Consumer<String> warn;
    /** The provider id we already warned about; cleared once it shows up, so a later loss warns again. */
    private volatile String warnedFor;
    private final java.util.function.BooleanSupplier warningsReady;
    private volatile boolean seenReady;

    public NpcBridge(Function<String, Optional<NpcProvider>> resolver, Consumer<String> warn) {
        this(resolver, warn, () -> true);
    }

    public NpcBridge(Function<String, Optional<NpcProvider>> resolver,
                     Consumer<String> warn, java.util.function.BooleanSupplier warningsReady) {
        this.warningsReady = warningsReady;
        this.resolver = resolver;
        this.warn = warn;
    }

    /** Production wiring: providers come from LoveCore. */
    public static NpcBridge viaLoveCore(Consumer<String> warn) {
        int started = org.bukkit.Bukkit.getServer() == null ? 0 : org.bukkit.Bukkit.getCurrentTick();
        return new NpcBridge(id -> LoveCore.provider(NpcProvider.class, id), warn,
                () -> org.bukkit.Bukkit.getServer() == null || org.bukkit.Bukkit.getCurrentTick() - started >= 200);
    }

    public String providerId() {
        return PROVIDER_ID;
    }

    /** LoveEssential if it is registered right now. */
    public Optional<NpcProvider> provider() {
        String id = providerId();
        Optional<NpcProvider> found;
        try {
            found = resolver.apply(id);
        } catch (RuntimeException | LinkageError e) {
            // LoveCore missing or older than the contract: same as "provider absent".
            found = Optional.empty();
        }
        if (found.isPresent()) {
            warnedFor = null;
            seenReady = true;
        } else if ((seenReady || warningsReady.getAsBoolean()) && !id.equals(warnedFor)) {
            warnedFor = id;
            warn.accept("NPC-провайдер '" + id + "' недоступен: плагин не установлен или ещё не "
                    + "поднялся. NPC-функции LoveShops отключены, пока он не появится.");
        }
        return found;
    }

    public boolean available() {
        return provider().isPresent();
    }

    /** Whether the provider is registered right now, without the missing-provider warning (for status lines). */
    public boolean present() {
        try {
            return resolver.apply(providerId()).isPresent();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Whether an NPC belongs to LoveEssential; events fire for every provider and must be filtered. */
    public boolean owns(NpcHandle npc) {
        return npc != null && providerId().equalsIgnoreCase(npc.providerId());
    }

    public boolean ownsProvider(String providerId) {
        return providerId != null && providerId().equalsIgnoreCase(providerId);
    }

    public Optional<NpcHandle> byKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return provider().flatMap(p -> p.byKey(key.trim()));
    }

    public Optional<NpcHandle> byEntity(Entity entity) {
        if (entity == null) return Optional.empty();
        return provider().flatMap(p -> p.byEntity(entity));
    }

    /** The NPC the player has selected or is looking at. */
    public Optional<NpcHandle> targeted(Player player) {
        if (player == null) return Optional.empty();
        return provider().flatMap(p -> p.targeted(player));
    }

    public Optional<NpcHandle> create(NpcSpec spec) {
        return provider().map(p -> p.create(spec));
    }

    public boolean remove(String key) {
        if (key == null || key.isBlank()) return false;
        return provider().map(p -> p.remove(key.trim())).orElse(false);
    }

    public Collection<NpcHandle> all() {
        return provider().map(NpcProvider::all).orElse(List.of());
    }

    /** Every NPC carrying the given tag value. */
    public List<NpcHandle> withTag(String tag, String value) {
        List<NpcHandle> found = new ArrayList<>();
        if (value == null) return found;
        for (NpcHandle npc : all()) {
            if (npc.tag(tag).filter(value::equals).isPresent()) found.add(npc);
        }
        return found;
    }

    /** Every NPC carrying the tag, whatever its value. */
    public List<NpcHandle> withTag(String tag) {
        List<NpcHandle> found = new ArrayList<>();
        for (NpcHandle npc : all()) {
            if (npc.tag(tag).isPresent()) found.add(npc);
        }
        return found;
    }
}
