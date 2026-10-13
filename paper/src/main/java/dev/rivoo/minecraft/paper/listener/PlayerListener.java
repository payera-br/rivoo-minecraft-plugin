package dev.rivoo.minecraft.paper.listener;

import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
import dev.rivoo.minecraft.paper.hud.PixHud;
import dev.rivoo.minecraft.paper.map.MapGuard;
import dev.rivoo.minecraft.paper.map.QrMaps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class PlayerListener implements Listener {
    private final Plugin plugin;
    private final EngineHolder holder;
    private final PurchaseStore store;
    private final QrMaps maps;
    private final PixHud hud;
    private final PaperPurchaseView view;
    private final MapGuard guard;

    public PlayerListener(Plugin plugin, EngineHolder holder, PurchaseStore store, QrMaps maps, PixHud hud,
                          PaperPurchaseView view, MapGuard guard) {
        this.plugin = plugin;
        this.holder = holder;
        this.store = store;
        this.maps = maps;
        this.hud = hud;
        this.view = view;
        this.guard = guard;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joined.isOnline()) return;
            guard.sweep(joined);
            view.showBars(joined);
        }, 40L);
    }

    /** O mapa carrega o QR do Pix de outra pessoa: some dos drops na morte e volta no respawn. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(maps::isPixMap);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> store.latestPendingOf(player.getUniqueId()).ifPresent(p -> {
            maps.removeFrom(player, p.chargeId);
            view.giveMap(player, p,
                    holder.engine().map(e -> e.productFor(p)).orElseGet(() -> Product.fallback(p.sku, p.amount)));
        }));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (maps.isPixMap(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            hud.dropBlocked(event.getPlayer());
            event.getPlayer().sendMessage(Component.text(
                    "O mapa do Pix não pode ser descartado. Ele some sozinho após o pagamento.", NamedTextColor.GRAY));
        }
    }

    /** O fogo de artifício da comemoração é só visual: não pode ferir ninguém. */
    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(hud.fireworkKey(), PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
