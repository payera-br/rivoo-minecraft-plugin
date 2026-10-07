package dev.rivoo.minecraft.paper.map;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;

final class CraftingInventoryHelper {
    private CraftingInventoryHelper() {}

    static void assertResultCleared(ServerMock server, PlayerMock player, MapGuard guard, ItemStack pixMap) {
        InventoryView view = player.openInventory(server.createInventory(player, InventoryType.WORKBENCH));
        CraftingInventory inv = (CraftingInventory) view.getTopInventory();
        inv.setMatrix(new ItemStack[]{pixMap, new ItemStack(Material.MAP), null, null, null, null, null, null, null});
        inv.setResult(pixMap.clone());
        guard.onPrepareCraft(new PrepareItemCraftEvent(inv, view, false));
        assertThat(inv.getResult()).isNull();
    }
}
