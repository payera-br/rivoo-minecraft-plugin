package dev.rivoo.minecraft.paper.menu;

import dev.rivoo.minecraft.core.Money;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu do /comprar: um baú com os produtos do catálogo (até 54). O ícone é o item do Minecraft
 * informado no produto (ex.: diamond_sword); sem um válido, papel. Clicar compra pelo mesmo caminho de
 * {@code /comprar <sku>}.
 */
public final class ShopMenu implements Listener {
    static final int MAX = 54;
    static final Material DEFAULT_ICON = Material.PAPER;

    /** Dono do inventário: marca o menu e guarda o sku de cada posição. */
    static final class Holder implements InventoryHolder {
        final Map<Integer, String> skus = new HashMap<>();
        private Inventory inventory;

        @Override public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** false quando não há produto para mostrar. */
    public boolean open(Player player, Catalog catalog) {
        List<Product> products = catalog.all().stream().limit(MAX).toList();
        if (products.isEmpty()) return false;
        Holder holder = new Holder();
        int size = Math.min(MAX, ((products.size() + 8) / 9) * 9);
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Loja"));
        holder.inventory = inv;
        for (int slot = 0; slot < products.size(); slot++) {
            Product p = products.get(slot);
            inv.setItem(slot, item(p));
            holder.skus.put(slot, p.sku());
        }
        player.openInventory(inv);
        return true;
    }

    static ItemStack item(Product p) {
        ItemStack stack = new ItemStack(material(p.icon()));
        stack.editMeta(meta -> {
            meta.displayName(Component.text(p.name(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(Money.format(p.price()), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                    Component.text("Clique para comprar com Pix", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        });
        return stack;
    }

    /** "diamond_sword" ou "minecraft:diamond_sword"; ar, item inválido ou outro texto → padrão. */
    static Material material(String icon) {
        if (icon == null || icon.isBlank()) return DEFAULT_ICON;
        Material m = Material.matchMaterial(icon.trim().toLowerCase(Locale.ROOT));
        return m == null || !m.isItem() || m.isAir() ? DEFAULT_ICON : m;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        String sku = holder.skus.get(event.getSlot());
        if (sku == null) return;
        player.closeInventory();
        player.performCommand("comprar " + sku);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
