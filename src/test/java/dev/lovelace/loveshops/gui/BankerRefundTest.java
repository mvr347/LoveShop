package dev.lovelace.loveshops.gui;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.economy.*;
import dev.lovelace.loveshops.LoveShops;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BankerRefundTest {
  ServerMock server; LoveShops plugin; PlayerMock player; Coins coins;
  static class Coins implements LoveEconomy {
    long refunded; int calls;
    public String currencyName(){return "монеты";}
    public List<Denomination> denominations(){return List.of(new Denomination("copper_coin",1));}
    public long balance(Player p){return refunded;}
    public boolean has(Player p,long n){return refunded>=n;}
    public boolean charge(Player p,long n){return false;}
    public void give(Player p,long n){refunded+=n;calls++; for(var extra:p.getInventory().addItem(new ItemStack(Material.IRON_NUGGET,(int)n)).values())p.getWorld().dropItemNaturally(p.getLocation(),extra);}
    public boolean canFit(Player p,long n){return true;}
    public long valueOf(ItemStack stack){return stack.getType()==Material.IRON_NUGGET?1:0;}
  }
  @BeforeEach void setup(){
    server=MockBukkit.mock();server.addSimpleWorld("world");
    var core=MockBukkit.createMockPlugin("LoveCore");MockBukkit.createMockPlugin("LoveEssential");
    coins=new Coins();server.getServicesManager().register(LoveEconomy.class,coins,core,ServicePriority.Normal);
    plugin=MockBukkit.load(LoveShops.class);player=server.addPlayer();
  }
  @AfterEach void close(){MockBukkit.unmock();}
  void deposit(int amount){
    new BankerGui(plugin,player).open();
    player.setItemOnCursor(new ItemStack(Material.IRON_NUGGET,amount));
    var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,
        BankerGui.SLOT_DEPOSIT,ClickType.LEFT,InventoryAction.SWAP_WITH_CURSOR);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());assertTrue(player.getItemOnCursor().getType().isAir());
  }
  @Test void firstCloseRefundsExactlyOnceAndQueuedRefreshCannotReopen(){
    deposit(17);player.closeInventory();
    assertEquals(17,coins.refunded);assertEquals(1,coins.calls);
    server.getScheduler().performTicks(2);
    assertFalse(BankerGui.owns(player.getOpenInventory().getTopInventory(),player));
    BankerGui.onClose(plugin,player);assertEquals(1,coins.calls);
  }
  @Test void replacementCloseDoesNotSettleNewViewButFinalCloseDoes(){
    deposit(23);var old=player.getOpenInventory().getTopInventory();
    new BankerGui(plugin,player).open();assertEquals(0,coins.refunded);
    BankerGui.onClose(plugin,player,old);assertEquals(0,coins.refunded);
    player.closeInventory();assertEquals(23,coins.refunded);assertEquals(1,coins.calls);
  }
  @Test void cancelledReplacementCannotLeaveMoneyInAnUnreachableMenu(){
    deposit(19);
    server.getPluginManager().registerEvents(new org.bukkit.event.Listener(){
      @org.bukkit.event.EventHandler public void opening(InventoryOpenEvent event){
        if(event.getInventory().getHolder() instanceof BankerGui.Holder)event.setCancelled(true);
      }
    },plugin);
    new BankerGui(plugin,player).open();
    assertEquals(19,coins.refunded);assertNull(BankerGui.session(player.getUniqueId()));
    player.closeInventory();assertEquals(1,coins.calls);
  }
  @Test void temporaryServiceRemovalAndShutdownStillReturnTheAcceptedDeposit(){
    deposit(11);server.getServicesManager().unregister(LoveEconomy.class,coins);
    BankerGui.shutdown(plugin);BankerGui.onClose(plugin,player);
    assertEquals(11,coins.refunded);assertEquals(1,coins.calls);
  }
  @Test void fullInventoryReturnsOverflowAsItemsAndDoesNotLoseTheEscrow(){
    deposit(13);
    for(int i=0;i<player.getInventory().getSize();i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
    player.closeInventory();assertEquals(13,coins.refunded);
    assertTrue(player.getWorld().getEntities().stream().anyMatch(e -> e instanceof org.bukkit.entity.Item item && item.getItemStack().getType()==Material.IRON_NUGGET));
    assertNull(BankerGui.session(player.getUniqueId()));
  }
}
