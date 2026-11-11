package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.platform.CommandRunner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Roda os comandos de entrega. Comando do próprio Minecraft (give, effect, tp…) roda por um
 * remetente que devolve a resposta: o jogo manda os erros em vermelho ("No player was found",
 * "Unknown item"), e um erro desses é falha de entrega em vez de "entregue". Comando de plugin
 * (LuckPerms, Essentials, inclusive um que substitui o give) roda pelo console, como sempre: muitos
 * respondem depois, e alguns só aceitam o console.
 */
final class PaperCommands implements CommandRunner {
    private final Server server;

    PaperCommands(Server server) {
        this.server = server;
    }

    @Override
    public String run(String command) {
        List<Component> feedback = new ArrayList<>();
        CommandSender capturing = isVanilla(command) ? capturing(feedback) : null;
        if (capturing == null) {
            return server.dispatchCommand(server.getConsoleSender(), command) ? null : unknown(command);
        }
        if (!server.dispatchCommand(capturing, command)) return unknown(command);
        // Só o que chegou durante o comando: a resposta do Minecraft é síncrona.
        for (Component c : List.copyOf(feedback)) {
            if (NamedTextColor.RED.equals(c.color())) return firstLine(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return null;
    }

    private CommandSender capturing(List<Component> feedback) {
        try {
            return server.createCommandSender(feedback::add);
        } catch (NoSuchMethodError | UnsupportedOperationException e) {
            return null; // servidor sem esse recurso: console, sem ler a resposta
        }
    }

    private boolean isVanilla(String command) {
        String label = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("minecraft:")) return true;
        Command c = server.getCommandMap().getCommand(label);
        return c != null && c.getClass().getSimpleName().equals("VanillaCommandWrapper");
    }

    private static String unknown(String command) {
        return "O servidor não reconheceu o comando: " + command;
    }

    private static String firstLine(String s) {
        String line = s.strip().split("\\R", 2)[0];
        String msg = "O jogo respondeu: " + line;
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
