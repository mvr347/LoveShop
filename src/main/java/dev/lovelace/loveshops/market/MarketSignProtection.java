package dev.lovelace.loveshops.market;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Protects only the two registered signs of each trading point and their supporting block. */
final class MarketSignProtection implements Listener {
    private final java.util.function.Supplier<
                    java.util.Collection<dev.lovelace.loveshops.market.model.TradePoint>>
            points;

    MarketSignProtection(
            java.util.function.Supplier<
                            java.util.Collection<dev.lovelace.loveshops.market.model.TradePoint>>
                    points) {
        this.points = points;
    }

    boolean protectedBlock(Block block) {
        for (var point : points.get()) {
            if (matches(block, point.idSignLocation())
                    || matches(block, point.closedSignLocation())) return true;
        }
        return false;
    }

    private boolean matches(Block block, Location location) {
        if (location == null
                || location.getWorld() == null
                || !block.getWorld().equals(location.getWorld())) return false;
        int dx = block.getX() - location.getBlockX();
        int dy = block.getY() - location.getBlockY();
        int dz = block.getZ() - location.getBlockZ();
        if (dx == 0 && dy == 0 && dz == 0) return true;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) return false;
        // Nearby only, never load a chunk to protect a sign that is not loaded.
        if (!location.getWorld()
                .isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        Block sign = location.getBlock();
        var data = sign.getBlockData();
        BlockFace support;
        if (data instanceof WallSign || data instanceof WallHangingSign) {
            support = ((Directional) data).getFacing().getOppositeFace();
        } else if (data instanceof HangingSign) {
            support = BlockFace.UP;
        } else if (data instanceof org.bukkit.block.data.type.Sign) {
            support = BlockFace.DOWN;
        } else return false;
        return block.equals(sign.getRelative(support));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void editSign(SignChangeEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent e) {
        // Most physics events concern terrain: avoid scanning trading points for every sand/fluid
        // update.
        if (e.getBlock().getType().name().endsWith("_SIGN") && protectedBlock(e.getBlock()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::protectedBlock)
                || e.getBlocks().stream()
                        .map(b -> b.getRelative(e.getDirection()))
                        .anyMatch(this::protectedBlock)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
    }
}
