package dev.rivoo.minecraft.paper.map;

import dev.rivoo.minecraft.core.api.Charge;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class MapGuardTest {
    @TempDir Path dir;
    ServerMock server;
    PurchaseStore store;
    QrMaps maps;
    MapGuard guard;
    PlayerMock steve;
    PlayerMock alex;
    ItemStack pixMap;
    Purchase purchase;

    @BeforeEach void setUp() throws Exception {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("Rivoo");
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        store = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"),
                new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
        maps = new QrMaps(new NamespacedKey(plugin, "charge-id"), store, p -> "VIP");
        guard = new MapGuard(maps, store);
        purchase = store.newPurchase(new Charge("c1", "000201", "u", Instant.parse("2026-10-05T12:10:00Z")),
                steve.getUniqueId(), "Steve", "vip", 1990);
        store.add(purchase);
        pixMap = maps.itemFor(steve, purchase, "VIP");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    InventoryClickEvent click(InventoryView view, int rawSlot, ClickType click, InventoryAction action) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action);
    }

    /** MockBukkit não expõe a view padrão (top nulo): simula a view do inventário próprio, topo do tipo CRAFTING. */
    InventoryView ownCraftingView() {
        Inventory top = (Inventory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> InventoryType.CRAFTING;
                    case "getSize" -> 5;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Map<Integer, ItemStack> slots = new java.util.HashMap<>();
        return (InventoryView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InventoryView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> steve.getInventory();
                    case "getPlayer" -> steve;
                    case "getCursor" -> steve.getItemOnCursor();
                    case "getItem" -> slots.get((Integer) args[0]);
                    case "setItem" -> slots.put((Integer) args[0], (ItemStack) args[1]);
                    case "convertSlot" -> args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void placingIntoChestIsCancelled() {
        Inventory chest = server.createInventory(null, 27);
        InventoryView view = steve.openInventory(chest);
        steve.setItemOnCursor(pixMap);
        InventoryClickEvent e = click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void shiftClickWithChestOpenIsCancelled() {
        InventoryView view = steve.openInventory(server.createInventory(null, 27));
        steve.getInventory().setItem(0, pixMap);
        InventoryClickEvent e = click(view, 27 + 27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        e.setCurrentItem(pixMap);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void numberKeySwapIntoChestIsCancelled() {
        InventoryView view = steve.openInventory(server.createInventory(null, 27));
        steve.getInventory().setItem(3, pixMap);
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 5,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 3);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void movingInsideOwnInventoryIsAllowedButCraftGridIsNot() {
        InventoryView own = ownCraftingView();
        steve.setItemOnCursor(pixMap);
        InventoryClickEvent inside = click(own, 20, ClickType.LEFT, InventoryAction.PLACE_ALL);
        guard.onClick(inside);
        assertThat(inside.isCancelled()).isFalse();

        InventoryClickEvent grid = click(own, 1, ClickType.LEFT, InventoryAction.PLACE_ALL);
        guard.onClick(grid);
        assertThat(grid.isCancelled()).isTrue();
    }

    @Test void bundleInteractionIsCancelled() {
        InventoryView own = ownCraftingView();
        steve.setItemOnCursor(new ItemStack(Material.BUNDLE));
        InventoryClickEvent e = click(own, 20, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        e.setCurrentItem(pixMap);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void dragOverChestSlotsIsCancelled() {
        InventoryView view = steve.openInventory(server.createInventory(null, 27));
        InventoryDragEvent e = new InventoryDragEvent(view, null, pixMap, false, Map.of(2, pixMap));
        guard.onDrag(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void itemFrameIsCancelled() {
        steve.getInventory().setItemInMainHand(pixMap);
        ItemFrame frame = (ItemFrame) steve.getWorld().spawnEntity(steve.getLocation(), EntityType.ITEM_FRAME);
        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(steve, frame, EquipmentSlot.HAND);
        guard.onInteractEntity(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void craftingResultIsCleared() {
        CraftingInventoryHelper.assertResultCleared(server, steve, guard, pixMap);
    }

    @Test void sweepRemovesForeignAndClosedMapsButKeepsOwnOpenOne() {
        steve.getInventory().setItem(0, pixMap);
        alex.getInventory().setItem(0, pixMap.clone());
        guard.sweep(steve);
        guard.sweep(alex);
        assertThat(steve.getInventory().getItem(0)).isNotNull();
        assertThat(alex.getInventory().getItem(0)).isNull();

        store.transition(purchase, State.PENDING, State.DELIVERED);
        guard.sweep(steve);
        assertThat(steve.getInventory().getItem(0)).isNull();
    }

    @Test void armorStandManipulateIsCancelled() {
        org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) steve.getWorld()
                .spawnEntity(steve.getLocation(), EntityType.ARMOR_STAND);
        org.bukkit.event.player.PlayerArmorStandManipulateEvent e = new org.bukkit.event.player.PlayerArmorStandManipulateEvent(
                steve, stand, pixMap, new ItemStack(Material.AIR), EquipmentSlot.HEAD, EquipmentSlot.HAND);
        guard.onArmorStand(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void interactAtEntityIsCancelled() {
        steve.getInventory().setItemInMainHand(pixMap);
        org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) steve.getWorld()
                .spawnEntity(steve.getLocation(), EntityType.ARMOR_STAND);
        org.bukkit.event.player.PlayerInteractAtEntityEvent e = new org.bukkit.event.player.PlayerInteractAtEntityEvent(
                steve, stand, new org.bukkit.util.Vector(0, 0, 0), EquipmentSlot.HAND);
        guard.onInteractAt(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void decoratedPotIsDenied() {
        org.bukkit.block.Block pot = steve.getWorld().getBlockAt(0, 64, 0);
        pot.setType(Material.DECORATED_POT);
        steve.getInventory().setItemInMainHand(pixMap);
        org.bukkit.event.player.PlayerInteractEvent e = new org.bukkit.event.player.PlayerInteractEvent(
                steve, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, pixMap, pot, org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND);
        guard.onInteractBlock(e);
        assertThat(e.isCancelled()).isTrue();
        assertThat(e.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test void swapOffhandWithChestOpenIsCancelled() {
        InventoryView view = steve.openInventory(server.createInventory(null, 27));
        steve.getInventory().setItemInOffHand(pixMap);
        InventoryClickEvent e = click(view, 5, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void sweepRemovesForeignMapInOffhandAndOnCursor() {
        alex.getInventory().setItemInOffHand(pixMap.clone());
        alex.setItemOnCursor(pixMap.clone());
        guard.sweep(alex);
        assertThat(alex.getInventory().getItemInOffHand().getType()).isEqualTo(Material.AIR);
        assertThat(alex.getItemOnCursor().getType()).isEqualTo(Material.AIR);
    }

    /** Mapa do POC (RivooPoc): sem desenho no plugin novo, mesmo apontando para uma compra aberta. */
    private ItemStack legacyMap(String chargeId) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(NamespacedKey.fromString("rivoopoc:charge-id"),
                org.bukkit.persistence.PersistentDataType.STRING, chargeId);
        item.setItemMeta(meta);
        return item;
    }

    @Test void legacyPocMapIsRecognizedAndBlocked() {
        ItemStack legacy = legacyMap("c1");
        assertThat(maps.isPixMap(legacy)).isTrue();
        InventoryView view = steve.openInventory(server.createInventory(null, 27));
        steve.setItemOnCursor(legacy);
        InventoryClickEvent e = click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        guard.onClick(e);
        assertThat(e.isCancelled()).isTrue();
    }

    @Test void sweepAlwaysRemovesLegacyPocMaps() {
        steve.getInventory().setItem(0, legacyMap("c1")); // compra aberta do próprio jogador
        steve.getInventory().setItem(1, legacyMap("charge_old"));
        steve.getInventory().setItem(2, pixMap);
        guard.sweep(steve);
        assertThat(steve.getInventory().getItem(0)).isNull();
        assertThat(steve.getInventory().getItem(1)).isNull();
        assertThat(steve.getInventory().getItem(2)).isNotNull();
    }

    @Test void plainFilledMapIsNotAPixMap() {
        assertThat(maps.isPixMap(new ItemStack(Material.FILLED_MAP))).isFalse();
    }
}
