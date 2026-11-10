package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.Players;

import java.util.*;

public final class FakePlayers implements Players {
    private final Set<UUID> online = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Integer> slots = new HashMap<>();
    /** Chat messages sent through {@link #message}, in order. */
    public final List<String> messages = new ArrayList<>();

    public FakePlayers online(UUID id, String name) { online.add(id); names.put(id, name); return this; }

    public FakePlayers offline(UUID id) { online.remove(id); return this; }

    public FakePlayers known(UUID id, String name) { names.put(id, name); return this; }

    public FakePlayers freeSlots(UUID id, int free) { slots.put(id, free); return this; }

    @Override public boolean isOnline(UUID player) { return online.contains(player); }

    @Override public Optional<String> name(UUID player) { return Optional.ofNullable(names.get(player)); }

    @Override public int freeSlots(UUID player) { return slots.getOrDefault(player, Integer.MAX_VALUE); }

    @Override public void message(UUID player, String text) { if (online.contains(player)) messages.add(text); }
}
