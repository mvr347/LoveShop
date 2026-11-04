package dev.lovelace.loveshops.market;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveshops.market.model.TradePoint;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;

class MarketSignProtectionTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> calls) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (obj, method, args) -> {
                            if (method.getName().equals("equals")) return obj == args[0];
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(obj);
                            return calls.apply(method.getName(), args);
                        });
    }

    @Test
    void protectsRegisteredSignAndItsSupportButLeavesNearbyBlocksAndRemovedPointsAlone() {
        Map<String, Block> blocks = new HashMap<>();
        World[] world = new World[1];
        world[0] =
                proxy(
                        World.class,
                        (name, args) ->
                                switch (name) {
                                    case "isChunkLoaded" -> true;
                                    case "getBlockAt" ->
                                            args.length == 1
                                                    ? blocks.get(
                                                            ((Location) args[0]).getBlockX()
                                                                    + ","
                                                                    + ((Location) args[0])
                                                                            .getBlockY()
                                                                    + ","
                                                                    + ((Location) args[0])
                                                                            .getBlockZ())
                                                    : blocks.get(
                                                            args[0] + "," + args[1] + ","
                                                                    + args[2]);
                                    default -> null;
                                });
        WallSign data =
                proxy(
                        WallSign.class,
                        (name, args) -> name.equals("getFacing") ? BlockFace.NORTH : null);
        for (int z = 0; z <= 2; z++) {
            int bz = z;
            blocks.put(
                    "0,70," + z,
                    proxy(
                            Block.class,
                            (name, args) ->
                                    switch (name) {
                                        case "getX" -> 0;
                                        case "getY" -> 70;
                                        case "getZ" -> bz;
                                        case "getWorld" -> world[0];
                                        case "getBlockData" -> data;
                                        case "getRelative" -> blocks.get("0,70,1");
                                        default -> null;
                                    }));
        }
        TradePoint point = new TradePoint(UUID.randomUUID());
        point.idSignLocation(new Location(world[0], 0, 70, 0));
        List<TradePoint> points = new ArrayList<>(List.of(point));
        MarketSignProtection listener = new MarketSignProtection(() -> points);
        assertTrue(listener.protectedBlock(blocks.get("0,70,0")));
        assertTrue(listener.protectedBlock(blocks.get("0,70,1")));
        assertFalse(listener.protectedBlock(blocks.get("0,70,2")));
        BlockBreakEvent event = new BlockBreakEvent(blocks.get("0,70,0"), null);
        listener.breakBlock(event);
        assertTrue(event.isCancelled());
        points.clear();
        assertFalse(listener.protectedBlock(blocks.get("0,70,0")));
    }
}
