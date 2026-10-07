package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.platform.Players;
import org.bukkit.Server;

import java.util.Optional;
import java.util.UUID;

public final class PaperPlayers implements Players {
    private final Server server;

    public PaperPlayers(Server server) { this.server = server; }

    @Override public boolean isOnline(UUID player) { return server.getPlayer(player) != null; }

    @Override public Optional<String> name(UUID player) {
        var online = server.getPlayer(player);
        if (online != null) return Optional.of(online.getName());
        return Optional.ofNullable(server.getOfflinePlayer(player).getName());
    }
}
