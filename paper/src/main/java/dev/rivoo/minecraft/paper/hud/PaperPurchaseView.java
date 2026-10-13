package dev.rivoo.minecraft.paper.hud;

import dev.rivoo.minecraft.core.Money;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.platform.PurchaseView;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import dev.rivoo.minecraft.paper.map.QrMaps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;

/** Tudo que o jogador vê de uma compra: mapa, barra, títulos e mensagens. Thread do jogo. */
public final class PaperPurchaseView implements PurchaseView {
    private final Server server;
    private final PixHud hud;
    private final QrMaps maps;
    private final PurchaseStore store;

    public PaperPurchaseView(Server server, PixHud hud, QrMaps maps, PurchaseStore store) {
        this.server = server;
        this.hud = hud;
        this.maps = maps;
        this.store = store;
    }

    public void created(Player player, Purchase p, Product product, List<Purchase> superseded) {
        // Um Pix vencido (na tolerância) foi substituído: some da tela para não haver duas barras/mapas.
        superseded.forEach(old -> {
            hud.hideBar(player, old.chargeId);
            maps.removeFrom(player, old.chargeId);
        });
        giveMap(player, p, product);
        sendPaymentInfo(player, p);
        hud.pixCreated(player, p);
    }

    public void reshown(Player player, Purchase p, Product product) {
        player.sendMessage(Component.text("Você já tem um Pix aguardando pagamento:", NamedTextColor.YELLOW));
        maps.removeFrom(player, p.chargeId);
        giveMap(player, p, product);
        sendPaymentInfo(player, p);
        hud.showBar(player, p);
    }

    /** Pedido sem pagamento (total zero): nada para mostrar além do aviso. */
    public void free(Player player, Product product) {
        player.sendMessage(Component.text("Pedido confirmado! Entregando " + product.name() + ".", NamedTextColor.GREEN));
    }

    @Override public void paid(Purchase p, Product product) {
        Player player = server.getPlayer(p.playerId);
        hud.hideBar(player, p.chargeId);
        if (player == null) return;
        maps.removeFrom(player, p.chargeId);
        // Os comandos chegam pela fila da Rivoo, logo em seguida (ou quando houver espaço no inventário).
        player.sendMessage(Component.text("Pagamento confirmado! Entregando " + product.name() + ".", NamedTextColor.GREEN));
        hud.paid(player, p, product.name());
    }

    @Override public void expired(Purchase p, boolean replacedByNewer) {
        Player player = server.getPlayer(p.playerId);
        if (player == null) {
            hud.hideBar(null, p.chargeId);
            return;
        }
        maps.removeFrom(player, p.chargeId);
        if (replacedByNewer) {
            hud.hideBar(player, p.chargeId);
            return; // já existe um Pix novo ativo: avisar da expiração do antigo só confundiria
        }
        hud.expired(player, p);
        player.sendMessage(Component.text("Seu Pix expirou. Use /comprar " + p.sku + " para gerar outro.",
                NamedTextColor.RED));
    }

    @Override public void paidWhileOffline(Purchase p) {
        hud.hideBar(null, p.chargeId);
    }

    public void listProducts(Player player, Catalog catalog) {
        if (catalog.all().isEmpty()) {
            player.sendMessage(Component.text("Nenhum produto à venda ainda.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("Produtos disponíveis:", NamedTextColor.GOLD));
        for (Product product : catalog.all()) {
            player.sendMessage(Component.text(" " + product.name() + " - " + Money.format(product.price()) + " ",
                            NamedTextColor.WHITE)
                    .append(Component.text("[/comprar " + product.sku() + "]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/comprar " + product.sku()))));
        }
    }

    /** Ao entrar: barras dos Pix ainda abertos. */
    public void showBars(Player player) {
        Instant now = Instant.now();
        store.inState(State.PENDING).stream()
                .filter(p -> p.playerId.equals(player.getUniqueId()) && p.expiresAt.isAfter(now))
                .forEach(p -> hud.showBar(player, p));
    }

    /** A cada segundo: contagem regressiva das barras. */
    public void tickSecond() {
        hud.tick(store.inState(State.PENDING), server::getPlayer);
    }

    public void giveMap(Player player, Purchase p, Product product) {
        try {
            hud.holdMap(player, maps.itemFor(player, p, product.name()));
        } catch (Exception e) {
            server.getLogger().log(Level.WARNING, "Falha ao gerar mapa QR: " + e.getMessage());
        }
    }

    private void sendPaymentInfo(Player player, Purchase p) {
        long minutes = Math.max(0, Duration.between(Instant.now(), p.expiresAt).toMinutes());
        player.sendMessage(Component.text()
                .append(Component.text("Pix de " + Money.format(p.amount) + " - expira em " + minutes + " min. ",
                        NamedTextColor.AQUA))
                .append(Component.text("[Copiar Pix]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(p.brCode))
                        .hoverEvent(HoverEvent.showText(Component.text("Copia o código Pix copia e cola"))))
                .append(Component.text(" "))
                .append(Component.text("[Abrir checkout]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl(p.url))
                        .hoverEvent(HoverEvent.showText(Component.text(p.url))))
                .build());
        player.sendMessage(Component.text("Segure o mapa para ver o QR Code.", NamedTextColor.GRAY));
    }
}
