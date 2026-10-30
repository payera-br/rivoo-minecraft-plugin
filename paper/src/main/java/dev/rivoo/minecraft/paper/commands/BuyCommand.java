package dev.rivoo.minecraft.paper.commands;

import dev.rivoo.minecraft.core.Money;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.delivery.BuyResult;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
import dev.rivoo.minecraft.paper.menu.ShopMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BuyCommand implements TabExecutor {
    public static final String NOT_CONFIGURED = "A loja deste servidor ainda não foi configurada.";
    static final String GIFT = "presentear";
    static final String GIFT_USAGE = "/comprar presentear <nick> [pacote]";
    /** Nick do Minecraft (Java, Bedrock com prefixo "."): o que o plugin aceita antes de chamar a Rivoo. */
    private static final java.util.regex.Pattern NICK = java.util.regex.Pattern.compile("^[A-Za-z0-9_.]{1,16}$");

    private final EngineHolder holder;
    private final Catalog catalog;
    private final PaperPurchaseView view;
    private final ShopMenu menu;

    public BuyCommand(EngineHolder holder, Catalog catalog, PaperPurchaseView view, ShopMenu menu) {
        this.holder = holder;
        this.catalog = catalog;
        this.view = view;
        this.menu = menu;
    }

    /** O menu de baú; sem produto para mostrar, a lista no chat (que diz que está vazia). */
    private void showProducts(Player player) {
        if (!menu.open(player, catalog, null)) view.listProducts(player, catalog);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Somente jogadores.");
            return true;
        }
        DeliveryEngine engine = holder.engine().orElse(null);
        if (engine == null) {
            player.sendMessage(NOT_CONFIGURED);
            return true;
        }
        // /comprar presentear <nick> [pacote]: tudo o que sair daqui vai para o nick.
        String giftTo = null;
        String sku;
        if (args.length >= 1 && GIFT.equalsIgnoreCase(args[0])) {
            if (args.length < 2 || args.length > 3) {
                player.sendMessage(Component.text("Use " + GIFT_USAGE, NamedTextColor.YELLOW));
                return true;
            }
            giftTo = args[1];
            if (!NICK.matcher(giftTo).matches()) {
                player.sendMessage(Component.text("Nick inválido.", NamedTextColor.RED));
                return true;
            }
            if (giftTo.equalsIgnoreCase(player.getName())) {
                player.sendMessage(Component.text("Você não pode presentear a si mesmo.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 2) {
                if (!menu.open(player, catalog, giftTo)) {
                    player.sendMessage(Component.text("Nenhum pacote pode ser presenteado agora.", NamedTextColor.YELLOW));
                }
                return true;
            }
            sku = args[2];
        } else if (args.length == 1) {
            sku = args[0];
        } else {
            showProducts(player);
            return true;
        }
        if (catalog.find(sku).isEmpty()) {
            showProducts(player);
            return true;
        }
        UUID id = player.getUniqueId();
        Product product = catalog.find(sku).orElseThrow();
        // Mesma ordem de hoje: o aviso sai antes da chamada à API e só quando não há Pix aberto.
        boolean hasOpenPix = engine.snapshot().pending().stream()
                .anyMatch(p -> p.playerId.equals(id) && p.expiresAt.isAfter(Instant.now()));
        if (!hasOpenPix) {
            player.sendMessage(Component.text("Gerando Pix de " + Money.format(product.price()) + "...", NamedTextColor.GRAY));
        }
        String to = giftTo;
        engine.buy(id, player.getName(), sku, giftTo).thenAccept(result -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            if (to != null && (result instanceof BuyResult.Created || result instanceof BuyResult.Free)) {
                online.sendMessage(Component.text("Presente para " + to + ": " + product.name(), NamedTextColor.LIGHT_PURPLE));
            }
            switch (result) {
                case BuyResult.Created c -> view.created(online, c.purchase(), c.product(), c.superseded());
                case BuyResult.AlreadyPending a -> view.reshown(online, a.purchase(), a.product());
                case BuyResult.Free f -> view.free(online, f.product());
                case BuyResult.UnknownProduct u -> showProducts(online);
                case BuyResult.Failed f -> online.sendMessage(Component.text(f.message(), NamedTextColor.RED));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      String @NotNull [] args) {
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>(catalog.skus());
            options.add(GIFT);
            return options.stream().filter(o -> o.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (GIFT.equalsIgnoreCase(args[0]) && args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> !n.equals(sender.getName()) && n.toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (GIFT.equalsIgnoreCase(args[0]) && args.length == 3) {
            return catalog.all().stream().filter(Product::giftable).map(Product::sku).toList();
        }
        return List.of();
    }
}
