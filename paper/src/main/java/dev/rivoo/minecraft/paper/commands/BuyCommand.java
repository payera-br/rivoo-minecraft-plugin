package dev.rivoo.minecraft.paper.commands;

import dev.rivoo.minecraft.core.Money;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.delivery.BuyResult;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
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

    private final EngineHolder holder;
    private final Catalog catalog;
    private final PaperPurchaseView view;

    public BuyCommand(EngineHolder holder, Catalog catalog, PaperPurchaseView view) {
        this.holder = holder;
        this.catalog = catalog;
        this.view = view;
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
        if (args.length != 1 || catalog.find(args[0]).isEmpty()) {
            view.listProducts(player, catalog);
            return true;
        }
        UUID id = player.getUniqueId();
        Product product = catalog.find(args[0]).orElseThrow();
        // Mesma ordem de hoje: o aviso sai antes da chamada à API e só quando não há Pix aberto.
        boolean hasOpenPix = engine.snapshot().pending().stream()
                .anyMatch(p -> p.playerId.equals(id) && p.expiresAt.isAfter(Instant.now()));
        if (!hasOpenPix) {
            player.sendMessage(Component.text("Gerando Pix de " + Money.format(product.price()) + "...", NamedTextColor.GRAY));
        }
        engine.buy(id, player.getName(), args[0]).thenAccept(result -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            switch (result) {
                case BuyResult.Created c -> view.created(online, c.purchase(), c.product(), c.superseded());
                case BuyResult.AlreadyPending a -> view.reshown(online, a.purchase(), a.product());
                case BuyResult.Free f -> view.free(online, f.product());
                case BuyResult.UnknownProduct u -> view.listProducts(online, catalog);
                case BuyResult.Failed f -> online.sendMessage(Component.text(f.message(), NamedTextColor.RED));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      String @NotNull [] args) {
        return args.length == 1 ? catalog.skus() : List.of();
    }
}
