package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The only door from LoveShops to NPCs. The provider is chosen by {@code integrations.npc-provider}
 * ({@code citizens} or {@code loveessential}) and looked up on every use: providers register late
 * (LoveEssential after the server has loaded), so a reference kept in a field would miss them.
 *
 * <p>When the chosen provider is not there, one warning names it and every NPC feature quietly does nothing
 * until it appears; nothing throws.</p>
 */
public final class NpcBridge {

    public static final String DEFAULT_PROVIDER = "citizens";
    public static final String CONFIG_PATH = "integrations.npc-provider";

    private final Supplier<String> configured;
    private final Function<String, Optional<NpcProvider>> resolver;
    private final Consumer<String> warn;
    /** The provider id we already warned about; cleared once it shows up, so a later loss warns again. */
    private volatile String warnedFor;

    public NpcBridge(Supplier<String> configured, Function<String, Optional<NpcProvider>> resolver, Consumer<String> warn) {
        this.configured = configured;
        this.resolver = resolver;
        this.warn = warn;
    }

    /** Production wiring: providers come from LoveCore. */
    public static NpcBridge viaLoveCore(Supplier<String> configured, Consumer<String> warn) {
        return new NpcBridge(configured, id -> LoveCore.provider(NpcProvider.class, id), warn);
    }

    /** Lower-case, trimmed provider id; an empty or missing value means Citizens, as before the toggle existed. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_PROVIDER;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public String providerId() {
        return normalize(configured.get());
    }

    /** The configured provider if it is registered right now. */
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
        } else if (!id.equals(warnedFor)) {
            warnedFor = id;
            warn.accept("NPC-провайдер '" + id + "' недоступен (integrations.npc-provider): плагин не установлен или ещё не "
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

    /** Whether an NPC belongs to the configured provider; events fire for every provider and must be filtered. */
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
