package dev.rivoo.minecraft.paper.hud;

import dev.rivoo.minecraft.core.purchase.Purchase;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tudo que o jogador vê e ouve do Pix, só com recursos nativos do jogo (tema "Cartógrafo":
 * mapas, esmeraldas e aldeões). Main thread.
 */
public final class PixHud {

    private static final Title.Times TIMES =
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600));
    private static final int BELL_AT_SECONDS = 60;
    private static final int TICK_FROM_SECONDS = 10;

    private final Plugin plugin;
    private final NamespacedKey fireworkKey;
    private final Map<String, BossBar> bars = new ConcurrentHashMap<>();
    private final Set<String> bellRung = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastTick = new ConcurrentHashMap<>();

    public PixHud(Plugin plugin) {
        this.plugin = plugin;
        this.fireworkKey = new NamespacedKey(plugin, "pix-firework");
    }

    /**
     * Coloca o mapa na mão principal sem mexer na câmera: o jogo mostra o mapa na parte de baixo da
     * tela sem atrapalhar, e o jogador olha para baixo quando quiser ampliar para escanear.
     */
    public void holdMap(Player player, ItemStack map) {
        PlayerInventory inv = player.getInventory();
        ItemStack current = inv.getItemInMainHand();
        inv.setItemInMainHand(map);
        if (current.getType() != Material.AIR) {
            inv.addItem(current).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        }
        if (inv.getItemInOffHand().getType() != Material.AIR) {
            player.sendMessage(Component.text(
                    "Dica: esvazie a mão secundária para ver o QR em tamanho grande.", NamedTextColor.GRAY));
        }
    }

    public void pixCreated(Player player, Purchase p) {
        player.showTitle(Title.title(
                Component.text("Pergaminho de Pix", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Escaneie o QR no mapa", NamedTextColor.YELLOW),
                TIMES));
        // a mesa de cartografia "imprimindo" o mapa
        player.playSound(sound("ui.cartography_table.take_result", 1.0f));
        player.playSound(sound("item.book.page_turn", 1.0f));
        showBar(player, p);
    }

    public void paid(Player player, Purchase p, String productName) {
        forget(player, p.chargeId);
        player.showTitle(Title.title(
                Component.text("Pagamento confirmado!", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text(productName + " entregue", NamedTextColor.WHITE),
                TIMES));
        player.playSound(sound("ui.toast.challenge_complete", 1.0f));
        player.playSound(sound("entity.villager.yes", 1.0f));
        Location at = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 40, 0.7, 0.9, 0.7);
        celebrate(player);
    }

    public void expired(Player player, Purchase p) {
        forget(player, p.chargeId);
        player.showTitle(Title.title(
                Component.text("Pix expirado", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Use /comprar " + p.sku + " para gerar outro", NamedTextColor.GRAY),
                TIMES));
        player.playSound(sound("block.beacon.deactivate", 1.0f));
        player.playSound(sound("entity.villager.no", 1.0f));
    }

    public void dropBlocked(Player player) {
        player.playSound(sound("entity.villager.no", 1.0f));
    }

    /** (Re)mostra a barra — usado ao gerar o Pix e quando o jogador volta ao servidor. */
    public void showBar(Player player, Purchase p) {
        BossBar bar = bars.computeIfAbsent(p.chargeId,
                id -> BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10));
        update(bar, p);
        player.showBossBar(bar);
    }

    public void hideBar(Player player, String chargeId) {
        BossBar bar = bars.remove(chargeId);
        if (bar != null && player != null) player.hideBossBar(bar);
    }

    private void forget(Player player, String chargeId) {
        hideBar(player, chargeId);
        bellRung.remove(chargeId);
        lastTick.remove(chargeId);
    }

    /** A cada segundo: contagem regressiva, sino no último minuto e tique nos últimos 10 segundos. */
    public void tick(Iterable<Purchase> pending, Function<UUID, Player> online) {
        for (Purchase p : pending) {
            BossBar bar = bars.get(p.chargeId);
            if (bar == null) continue;
            update(bar, p);
            Player player = online.apply(p.playerId);
            if (player == null) continue;
            long left = secondsLeft(p);
            if (left > TICK_FROM_SECONDS && left <= BELL_AT_SECONDS && bellRung.add(p.chargeId)) {
                player.playSound(sound("block.bell.use", 1.0f));
            }
            if (left > 0 && left <= TICK_FROM_SECONDS && !Long.valueOf(left).equals(lastTick.put(p.chargeId, left))) {
                // tom sobe conforme acaba o tempo
                player.playSound(sound("block.note_block.hat", 0.8f + (TICK_FROM_SECONDS - left) * 0.12f));
            }
        }
    }

    public NamespacedKey fireworkKey() {
        return fireworkKey;
    }

    /** Fogo de artifício verde e dourado acima do jogador; o dano dele é cancelado no listener. */
    private void celebrate(Player player) {
        Location at = player.getLocation().add(0, 2.5, 0);
        Firework firework = player.getWorld().spawn(at, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(org.bukkit.Color.LIME, org.bukkit.Color.GREEN)
                    .withFade(org.bukkit.Color.YELLOW)
                    .withFlicker()
                    .build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            fw.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, firework::detonate, 2L);
    }

    private static long secondsLeft(Purchase p) {
        return Math.max(0, Duration.between(Instant.now(), p.expiresAt).getSeconds());
    }

    private static void update(BossBar bar, Purchase p) {
        long total = Math.max(1, Duration.between(p.createdAt, p.expiresAt).getSeconds());
        long left = secondsLeft(p);
        float progress = Math.min(1f, (float) left / total);
        bar.progress(progress);
        if (left == 0) {
            // Vencido, mas o poller ainda dá uma tolerância: um Pix pago no último segundo pode chegar.
            bar.color(BossBar.Color.RED);
            bar.name(Component.text("Pix vencido  ·  confirmando pagamento...", NamedTextColor.RED));
            return;
        }
        bar.color(progress > 0.5f ? BossBar.Color.GREEN : progress > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(Component.text()
                .append(Component.text("◆ ", NamedTextColor.GREEN))
                .append(Component.text("Pix " + dev.rivoo.minecraft.core.Money.format(p.amount), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("  ·  expira em ", NamedTextColor.WHITE))
                .append(Component.text(String.format("%02d:%02d", left / 60, left % 60), NamedTextColor.YELLOW))
                .build());
    }

    private static Sound sound(String key, float pitch) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, pitch);
    }
}
