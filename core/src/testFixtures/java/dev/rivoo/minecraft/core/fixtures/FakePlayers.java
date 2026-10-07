package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.Players;

import java.util.*;

public final class FakePlayers implements Players {
    private final Set<UUID> online = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();

    public FakePlayers online(UUID id, String name) { online.add(id); names.put(id, name); return this; }

    public FakePlayers offline(UUID id) { online.remove(id); return this; }

    public FakePlayers known(UUID id, String name) { names.put(id, name); return this; }

    @Override public boolean isOnline(UUID player) { return online.contains(player); }

    @Override public Optional<String> name(UUID player) { return Optional.ofNullable(names.get(player)); }
}
