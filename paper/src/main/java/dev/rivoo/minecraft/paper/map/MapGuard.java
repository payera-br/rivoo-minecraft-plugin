package dev.rivoo.minecraft.paper.map;

import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;

/**
 * O mapa de Pix não sai do inventário do comprador (camada 1) e o que escapar é removido na varredura
 * (camada 2). A camada 3 — só o comprador vê o QR — está no QrRenderer.
 */
public final class MapGuard implements Listener {
    private final QrMaps maps;
    private final PurchaseStore store;

    public MapGuard(QrMaps maps, PurchaseStore store) {
        this.maps = maps;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        PlayerInventory inv = player.getInventory();
        boolean involved = maps.isPixMap(e.getCurrentItem()) || maps.isPixMap(e.getCursor())
                || (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0 && maps.isPixMap(inv.getItem(e.getHotbarButton())))
                || (e.getClick() == ClickType.SWAP_OFFHAND && maps.isPixMap(inv.getItemInOffHand()));
        if (!involved) return;
        if (isBundle(e.getCurrentItem()) || isBundle(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        InventoryType top = e.getView().getTopInventory().getType();
        // Com qualquer container aberto, o mapa congela; no inventário próprio só a grade 2x2 é proibida.
        if (top != InventoryType.CRAFTING || (e.getRawSlot() >= 0 && e.getRawSlot() <= 4)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!maps.isPixMap(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(slot -> slot < topSize)) e.setCancelled(true);
    }

    /** Moldura, suporte de armadura, allay: nenhuma entidade recebe o mapa. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (maps.isPixMap(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    /** Clique direito em armor stand dispara PlayerInteractAtEntityEvent (lista própria). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (maps.isPixMap(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (maps.isPixMap(e.getPlayerItem())) e.setCancelled(true);
    }

    /** Vaso decorado aceita o item no clique direito e um funil o puxaria sem evento de inventário. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType() == Material.DECORATED_POT && maps.isPixMap(e.getItem())) {
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setCancelled(true);
        }
    }

    /** Copiar o mapa (mapa vazio + mapa de Pix) na grade de crafting: sem resultado. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (Arrays.stream(e.getInventory().getMatrix()).anyMatch(maps::isPixMap)) e.getInventory().setResult(null);
    }

    /** Remove mapas de Pix que não são de uma compra PENDING deste jogador. */
    public void sweep(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (!ownedOpen(player, contents[i])) inv.setItem(i, null);
        }
        if (!ownedOpen(player, player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    public void sweepOnline(Server server) {
        server.getOnlinePlayers().forEach(this::sweep);
    }

    /** true para qualquer item que NÃO seja mapa de Pix, ou mapa de Pix aberto do próprio jogador. */
    private boolean ownedOpen(Player player, ItemStack item) {
        if (maps.isLegacyPixMap(item)) return false; // mapa do POC: sem desenho no plugin novo
        return maps.chargeIdOf(item)
                .map(id -> store.get(id)
                        .filter(p -> p.state == State.PENDING && p.playerId.equals(player.getUniqueId()))
                        .isPresent())
                .orElse(true);
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }
}
