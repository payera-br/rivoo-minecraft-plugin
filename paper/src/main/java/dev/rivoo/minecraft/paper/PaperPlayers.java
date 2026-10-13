package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.api.OnlinePlayer;
import dev.rivoo.minecraft.core.platform.Players;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.List;
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

    @Override public Optional<UUID> onlineByName(String name) {
        var online = server.getPlayerExact(name);
        return online == null ? Optional.empty() : Optional.of(online.getUniqueId());
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

    @Override public List<OnlinePlayer> online() {
        List<OnlinePlayer> all = new ArrayList<>();
        for (var p : server.getOnlinePlayers()) all.add(new OnlinePlayer(p.getUniqueId(), p.getName(), freeSlots(p.getUniqueId())));
        return all;
    }

    @Override public void message(UUID player, String text) {
        var online = server.getPlayer(player);
        if (online != null) online.sendMessage(text);
    }
}
