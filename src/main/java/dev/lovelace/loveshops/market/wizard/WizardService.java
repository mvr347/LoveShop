package dev.lovelace.loveshops.market.wizard;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.wizard.PointWizard.Pos;
import dev.lovelace.loveshops.market.wizard.PointWizard.SetResult;
import dev.lovelace.loveshops.market.wizard.PointWizard.Step;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the creation wizard of a trade point for admins: one session per admin, each step is "stand
 * (or look) where it goes and press a button in the chat" (or the same /tradepointadmin wizard
 * command), with a particle preview of what is already set. Sessions end on cancel, on quit and
 * after ten idle minutes.
 */
public final class WizardService implements Listener {

    private static final long IDLE_MILLIS = 10L * 60_000L;
    private static final int MAX_FOOTPRINT = 64;

    private static final class Session {
        final PointWizard wizard;
        long lastActivity = System.currentTimeMillis();
        BukkitTask preview;

        Session(PointWizard wizard) {
            this.wizard = wizard;
        }
    }

    private final LoveShops plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public WizardService(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean hasSession(UUID admin) {
        return sessions.containsKey(admin);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start(Player admin, String id, long price) {
        var messages = plugin.getMarketMessages();
        if (sessions.containsKey(admin.getUniqueId())) {
            messages.sendAdmin(admin, "wiz-already");
            return;
        }
        if (!PointWizard.validId(id)) {
            messages.sendAdmin(admin, "wiz-id-bad");
            return;
        }
        if (plugin.getTradePointManager().claimsLink().byName(id).isPresent()) {
            messages.sendAdmin(admin, "wiz-id-taken", "id", id);
            return;
        }
        Session session = new Session(new PointWizard(id, price, MAX_FOOTPRINT));
        sessions.put(admin.getUniqueId(), session);
        session.preview = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(admin, session), 10L, 10L);
        messages.sendAdmin(admin, "wiz-started", "id", id);
        prompt(admin, session);
    }

    public void cancel(UUID admin, boolean announce) {
        Session session = sessions.remove(admin);
        if (session == null) return;
        if (session.preview != null) session.preview.cancel();
        Player player = Bukkit.getPlayer(admin);
        if (announce && player != null) plugin.getMarketMessages().send(player, "wiz-cancelled");
    }

    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(sessions.keySet())) cancel(id, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    private void tick(Player admin, Session session) {
        if (!admin.isOnline()) {
            cancel(admin.getUniqueId(), false);
            return;
        }
        if (System.currentTimeMillis() - session.lastActivity > IDLE_MILLIS) {
            plugin.getMarketMessages().send(admin, "wiz-timeout");
            cancel(admin.getUniqueId(), false);
            return;
        }
        preview(admin, session.wizard);
    }

    // ------------------------------------------------------------------ actions (/tradepointadmin wizard ...)

    public void action(Player admin, String action) {
        Session session = sessions.get(admin.getUniqueId());
        var messages = plugin.getMarketMessages();
        if (session == null) {
            messages.sendAdmin(admin, "wiz-no-session");
            return;
        }
        session.lastActivity = System.currentTimeMillis();
        PointWizard wizard = session.wizard;
        switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "set" -> set(admin, session);
            case "back" -> {
                if (!wizard.back()) messages.sendAdmin(admin, "wiz-back-denied");
                prompt(admin, session);
            }
            case "skip" -> {
                if (!wizard.skip()) messages.sendAdmin(admin, "wiz-skip-denied");
                prompt(admin, session);
            }
            case "finish" -> finish(admin, session);
            case "cancel" -> cancel(admin.getUniqueId(), true);
            case "status" -> prompt(admin, session);
            default -> messages.sendAdmin(admin, "wiz-usage");
        }
    }

    private void set(Player admin, Session session) {
        PointWizard wizard = session.wizard;
        var messages = plugin.getMarketMessages();
        Step step = wizard.step();
        Pos pos;
        switch (step) {
            case CORNER_1, CORNER_2 -> pos = blockPos(admin.getLocation());
            case NPC, TELEPORT -> pos = exactPos(admin.getLocation());
            case CLOSED_SIGN, ID_SIGN -> {
                pos = signSpot(admin);
                if (pos == null) {
                    messages.sendAdmin(admin, "wiz-err-sign-target");
                    return;
                }
            }
            default -> {
                messages.sendAdmin(admin, "wiz-err-summary");
                return;
            }
        }
        SetResult result = wizard.set(pos);
        switch (result) {
            case OK -> messages.sendAdmin(admin, "wiz-set-ok");
            case WRONG_WORLD -> messages.sendAdmin(admin, "wiz-err-wrong-world");
            case TOO_LARGE -> messages.sendAdmin(admin, "wiz-err-too-large", "max", String.valueOf(MAX_FOOTPRINT));
            case OUTSIDE_ZONE -> messages.sendAdmin(admin, "wiz-err-outside-zone");
            case WRONG_STEP -> messages.sendAdmin(admin, "wiz-err-summary");
        }
        prompt(admin, session);
    }

    private static Pos blockPos(Location loc) {
        return new Pos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), 0f, 0f);
    }

    private static Pos exactPos(Location loc) {
        return new Pos(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /**
     * Where a sign goes, from what the admin looks at: an existing sign is used as it is, a solid
     * block gets the sign on top of it (the sign itself is placed when the point is created).
     */
    private Pos signSpot(Player admin) {
        RayTraceResult hit = admin.rayTraceBlocks(6.0);
        if (hit == null || hit.getHitBlock() == null) return null;
        Block block = hit.getHitBlock();
        if (block.getState() instanceof Sign) return blockPos(block.getLocation());
        Block above = block.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) return null;
        return blockPos(above.getLocation());
    }

    // ------------------------------------------------------------------ prompts

    private void prompt(Player admin, Session session) {
        PointWizard wizard = session.wizard;
        var messages = plugin.getMarketMessages();
        Step step = wizard.step();
        if (step == Step.CONFIRM) {
            summary(admin, wizard);
            return;
        }
        admin.sendMessage(messages.plain(admin, "wiz-step-" + step.name().toLowerCase(java.util.Locale.ROOT),
                "id", wizard.id(), "n", String.valueOf(step.ordinal() + 1)));
        StringBuilder buttons = new StringBuilder(messages.raw("wiz-btn-set"));
        if (!step.required()) buttons.append(' ').append(messages.raw("wiz-btn-skip"));
        if (step != Step.CORNER_1) buttons.append(' ').append(messages.raw("wiz-btn-back"));
        buttons.append(' ').append(messages.raw("wiz-btn-cancel"));
        admin.sendMessage(MessageUtils.parse(admin, buttons.toString()));
    }

    private void summary(Player admin, PointWizard wizard) {
        var messages = plugin.getMarketMessages();
        String none = messages.raw("wiz-none");
        for (String line : messages.lines("wiz-summary",
                "id", wizard.id(),
                "price", plugin.getMarketStyle().money(wizard.price()),
                "size", sizeText(wizard),
                "npc", describe(wizard.npc(), none),
                "closed", describe(wizard.closedSign(), none),
                "idsign", describe(wizard.idSign(), none),
                "tp", describe(wizard.teleport(), none))) {
            admin.sendMessage(MessageUtils.parse(admin, line));
        }
        StringBuilder buttons = new StringBuilder();
        if (wizard.ready()) buttons.append(messages.raw("wiz-btn-finish")).append(' ');
        buttons.append(messages.raw("wiz-btn-back")).append(' ').append(messages.raw("wiz-btn-cancel"));
        admin.sendMessage(MessageUtils.parse(admin, buttons.toString()));
    }

    private static String sizeText(PointWizard w) {
        if (w.corner1() == null || w.corner2() == null) return "?";
        return (Math.abs(w.corner1().bx() - w.corner2().bx()) + 1) + "x" + (Math.abs(w.corner1().bz() - w.corner2().bz()) + 1);
    }

    private static String describe(Pos pos, String none) {
        return pos == null ? none : pos.bx() + ", " + pos.by() + ", " + pos.bz();
    }

    // ------------------------------------------------------------------ finish

    private void finish(Player admin, Session session) {
        PointWizard wizard = session.wizard;
        var messages = plugin.getMarketMessages();
        if (!wizard.ready()) {
            messages.sendAdmin(admin, "wiz-not-ready");
            prompt(admin, session);
            return;
        }
        World world = Bukkit.getWorld(wizard.corner1().world());
        if (world == null) {
            messages.sendAdmin(admin, "wiz-fail");
            return;
        }
        var manager = plugin.getTradePointManager();
        Location c1 = new Location(world, wizard.corner1().bx(), wizard.corner1().by(), wizard.corner1().bz());
        Location c2 = new Location(world, wizard.corner2().bx(), wizard.corner2().by(), wizard.corner2().bz());
        Pos n = wizard.npc();
        Location home = new Location(world, n.x(), n.y(), n.z(), n.yaw(), n.pitch());

        ClaimsLink.CreateResult result = manager.claimsLink().create(wizard.id(), world, c1, c2, home, wizard.price());
        switch (result.status()) {
            case OK -> { }
            case BAD_ID -> { messages.sendAdmin(admin, "wiz-id-bad"); return; }
            case ID_TAKEN -> { messages.sendAdmin(admin, "wiz-id-taken", "id", wizard.id()); return; }
            case OVERLAP -> { messages.sendAdmin(admin, "wiz-overlap"); return; }
            case BAD_PRICE -> { messages.sendAdmin(admin, "wiz-fail"); return; }
            default -> { messages.sendAdmin(admin, "wiz-fail"); return; }
        }

        TradePoint point = manager.registerNewPoint(result.claimId());
        if (wizard.closedSign() != null) point.closedSignLocation(blockLoc(world, wizard.closedSign()));
        if (wizard.idSign() != null) {
            Location sign = blockLoc(world, wizard.idSign());
            point.idSignLocation(sign);
            if (!(sign.getBlock().getState() instanceof Sign)) sign.getBlock().setType(Material.OAK_SIGN);
        }
        if (wizard.teleport() != null) {
            Pos t = wizard.teleport();
            point.teleportLocation(new Location(world, t.x(), t.y(), t.z(), t.yaw(), t.pitch()));
        }
        manager.saveSpots(point);
        manager.reconcileNpcs();
        cancel(admin.getUniqueId(), false);
        messages.sendAdmin(admin, "wiz-created", "id", wizard.id());
    }

    private static Location blockLoc(World world, Pos pos) {
        return new Location(world, pos.bx(), pos.by(), pos.bz());
    }

    // ------------------------------------------------------------------ particles

    private void preview(Player admin, PointWizard wizard) {
        World world = admin.getWorld();
        Pos c1 = wizard.corner1();
        if (c1 != null && c1.world().equals(world.getName())) {
            Pos c2 = wizard.corner2() != null ? wizard.corner2() : blockPos(admin.getLocation());
            double[] b = zoneBounds(c1, c2);
            outline(admin, b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        pillar(admin, wizard.npc(), Particle.HAPPY_VILLAGER);
        pillar(admin, wizard.teleport(), Particle.FLAME);
        mark(admin, wizard.closedSign(), Particle.SOUL_FIRE_FLAME);
        mark(admin, wizard.idSign(), Particle.END_ROD);
    }

    /** The zone the point will get: footprint of the two corners, one block below the lower one up to twelve above it. */
    static double[] zoneBounds(Pos a, Pos b) {
        int baseY = Math.min(a.by(), b.by());
        return new double[]{Math.min(a.bx(), b.bx()), baseY - 1, Math.min(a.bz(), b.bz()),
                Math.max(a.bx(), b.bx()) + 1, baseY + 13, Math.max(a.bz(), b.bz()) + 1};
    }

    private static void outline(Player viewer, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double step = Math.max(1.0, Math.max(maxX - minX, maxZ - minZ) / 24.0);
        World w = viewer.getWorld();
        for (double x = minX; x <= maxX; x += step) {
            for (double y : new double[]{minY, maxY}) {
                viewer.spawnParticle(Particle.END_ROD, new Location(w, x, y, minZ), 1, 0, 0, 0, 0);
                viewer.spawnParticle(Particle.END_ROD, new Location(w, x, y, maxZ), 1, 0, 0, 0, 0);
            }
        }
        for (double z = minZ; z <= maxZ; z += step) {
            for (double y : new double[]{minY, maxY}) {
                viewer.spawnParticle(Particle.END_ROD, new Location(w, minX, y, z), 1, 0, 0, 0, 0);
                viewer.spawnParticle(Particle.END_ROD, new Location(w, maxX, y, z), 1, 0, 0, 0, 0);
            }
        }
        for (double y = minY; y <= maxY; y += step) {
            for (double x : new double[]{minX, maxX}) {
                for (double z : new double[]{minZ, maxZ}) {
                    viewer.spawnParticle(Particle.END_ROD, new Location(w, x, y, z), 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static void pillar(Player viewer, Pos pos, Particle particle) {
        if (pos == null || !pos.world().equals(viewer.getWorld().getName())) return;
        for (int i = 0; i <= 5; i++) {
            viewer.spawnParticle(particle, new Location(viewer.getWorld(), pos.x(), pos.y() + i * 0.5, pos.z()), 2, 0.05, 0.05, 0.05, 0);
        }
    }

    private static void mark(Player viewer, Pos pos, Particle particle) {
        if (pos == null || !pos.world().equals(viewer.getWorld().getName())) return;
        viewer.spawnParticle(particle, new Location(viewer.getWorld(), pos.bx() + 0.5, pos.by() + 0.5, pos.bz() + 0.5), 6, 0.25, 0.25, 0.25, 0);
    }

    /** For tests and the command: which steps exist, in order. */
    static List<Step> steps() {
        return List.of(Step.values());
    }
}
