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

    /** Inventário principal (36): sem armadura nem mão secundária. */
    @Override public int freeSlots(UUID player) {
        var online = server.getPlayer(player);
        if (online == null) return 0;
        int free = 0;
        for (var item : online.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) free++;
        }
        return free;
    }

    @Override public void message(UUID player, String text) {
        var online = server.getPlayer(player);
        if (online != null) online.sendMessage(text);
    }
}
