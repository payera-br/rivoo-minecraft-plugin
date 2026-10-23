package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.platform.TakeResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Retirada exata do inventário (thread do jogo): principal (36) e mão secundária, sem armadura. */
final class PaperTake {
    private PaperTake() {}

    static TakeResult takeExact(Player player, String materialKey, int quantity) {
        Material material = Material.matchMaterial(materialKey);
        if (material == null || material.isAir()) return new TakeResult(false, 0);
        PlayerInventory inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < 36; i++) have += plainAmount(inv.getItem(i), material);
        have += plainAmount(inv.getItemInOffHand(), material);
        if (have < quantity) return new TakeResult(false, have);

        int left = quantity;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            int n = plainAmount(stack, material);
            if (n == 0) continue;
            int took = Math.min(n, left);
            inv.setItem(i, took == n ? null : stack.asQuantity(n - took));
            left -= took;
        }
        if (left > 0) {
            ItemStack off = inv.getItemInOffHand();
            inv.setItemInOffHand(left == off.getAmount() ? null : off.asQuantity(off.getAmount() - left));
        }
        return new TakeResult(true, have);
    }

    /** Itens com nome, encantamento ou outros dados não são a mercadoria: valem zero. */
    private static int plainAmount(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && !stack.hasItemMeta() ? stack.getAmount() : 0;
    }
}
