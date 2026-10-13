package dev.rivoo.minecraft.paper.commands;

import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.connect.PairingFlow;
import dev.rivoo.minecraft.core.connect.SecretConnector;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.core.delivery.Snapshot;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.paper.RivooPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public final class AdminCommand implements TabExecutor {
    public static final String CONSOLE_ONLY = "Use no console do servidor.";
    public static final String VALIDATING =
            "Validando a chave... o resultado aparece no log do servidor (confira com rivoo status).";
    public static final String PAIRING_STARTED = "Gerando código de pareamento; ele aparece no console em instantes."
            + " A chave atual continua valendo até o código ser usado.";
    static final String USAGE = "Uso: /rivoo <status|secret <chave>|connect>";

    private final EngineHolder holder;
    private final PurchaseStore store;
    private final SecretConnector connector;
    private final PairingFlow pairing;
    private final Executor async;
    private final Logger log;

    public AdminCommand(EngineHolder holder, PurchaseStore store, SecretConnector connector, PairingFlow pairing,
                        Executor async, Logger log) {
        this.holder = holder;
        this.store = store;
        this.connector = connector;
        this.pairing = pairing;
        this.async = async;
        this.log = log;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "secret" -> secret(sender, args);
            // "conectar" é o que o painel manda digitar.
            case "connect", "conectar" -> connect(sender);
            case "status", "pendentes" -> status(sender);
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void secret(CommandSender sender, String[] args) {
        // Comando de jogador vai para o latest.log com os argumentos: a chave vazaria.
        if (!fromConsole(sender)) {
            sender.sendMessage(CONSOLE_ONLY);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Uso: rivoo secret <chave> (no console)");
            return;
        }
        String key = args[1];
        // RCON só devolve o que foi enviado durante onCommand: avisa já e manda o resultado ao log.
        boolean rcon = sender instanceof RemoteConsoleCommandSender;
        if (rcon) sender.sendMessage(VALIDATING);
        async.execute(() -> {
            String reply = switch (connector.connect(key)) {
                case SecretConnector.Connected c -> SecretConnector.connectedMessage(c.info());
                case SecretConnector.Rejected x -> SecretConnector.REJECTED;
                case SecretConnector.Unreachable x -> SecretConnector.UNREACHABLE;
            };
            if (rcon) log.info(reply);
            else sender.sendMessage(reply);
        });
    }

    private static boolean fromConsole(CommandSender sender) {
        // Comando de jogador vai para o latest.log com os argumentos.
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private void connect(CommandSender sender) {
        if (!fromConsole(sender)) {
            sender.sendMessage(CONSOLE_ONLY);
            return;
        }
        pairing.start();
        sender.sendMessage(PAIRING_STARTED);
    }

    private void status(CommandSender sender) {
        Optional<DeliveryEngine> engine = holder.engine();
        if (engine.isEmpty()) {
            sender.sendMessage(pairing.statusLine().orElse(RivooPlugin.NOT_CONNECTED));
            return;
        }
        holder.info().ifPresent(i -> sender.sendMessage("Servidor: " + i.serverName() + " · projeto " + i.projectName()));
        Snapshot s = engine.get().snapshot();
        sender.sendMessage(s.catalogLoaded() ? "Catálogo: " + s.products() + " produto(s)" : "Catálogo: carregando");
        pairing.statusLine().ifPresent(sender::sendMessage);
        if (engine.get().revoked()) sender.sendMessage("Chave revogada: pareie de novo (rivoo connect) ou rode rivoo secret <chave> no console.");
        sender.sendMessage("Pix abertos: " + s.pending().size());
        s.pending().forEach(p -> sender.sendMessage(" " + p.chargeId + " " + p.playerName + " " + p.sku));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      String @NotNull [] args) {
        return args.length == 1 ? List.of("status", "secret", "connect") : List.of();
    }
}
