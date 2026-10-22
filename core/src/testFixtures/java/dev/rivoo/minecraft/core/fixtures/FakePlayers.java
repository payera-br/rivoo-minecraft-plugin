package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.Players;
import dev.rivoo.minecraft.core.platform.TakeResult;

import java.util.*;

public final class FakePlayers implements Players {
    private final Set<UUID> online = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();

    public FakePlayers online(UUID id, String name) { online.add(id); names.put(id, name); return this; }

    public FakePlayers offline(UUID id) { online.remove(id); return this; }

    public FakePlayers known(UUID id, String name) { names.put(id, name); return this; }

    @Override public boolean isOnline(UUID player) { return online.contains(player); }

    @Override public Optional<String> name(UUID player) { return Optional.ofNullable(names.get(player)); }

    private record Stack(String material, int amount, boolean plain) {}

    private final Map<UUID, List<Stack>> stacks = new HashMap<>();

    /** Uma pilha comum no inventário do jogador. */
    public FakePlayers give(UUID id, String material, int amount) { stacks.computeIfAbsent(id, k -> new ArrayList<>()).add(new Stack(material, amount, true)); return this; }

    /** Uma pilha com nome/encantamento: não é a mercadoria. */
    public FakePlayers giveCustom(UUID id, String material, int amount) { stacks.computeIfAbsent(id, k -> new ArrayList<>()).add(new Stack(material, amount, false)); return this; }

    /** Total de itens (comuns ou não) desse material com o jogador. */
    public int held(UUID id, String material) {
        return stacks.getOrDefault(id, List.of()).stream().filter(s -> s.material.equals(material)).mapToInt(Stack::amount).sum();
    }

    @Override public Optional<UUID> onlineByName(String name) {
        return online.stream().filter(id -> name.equalsIgnoreCase(names.get(id))).findFirst();
    }

    @Override public TakeResult takeExact(String player, String material, int quantity) {
        UUID id = onlineByName(player).orElse(null);
        if (id == null) return new TakeResult(false, 0);
        List<Stack> list = stacks.getOrDefault(id, new ArrayList<>());
        int have = list.stream().filter(s -> s.plain && s.material.equals(material)).mapToInt(Stack::amount).sum();
        if (have < quantity) return new TakeResult(false, have);
        int left = quantity;
        List<Stack> next = new ArrayList<>();
        for (Stack s : list) {
            if (!s.plain || !s.material.equals(material) || left == 0) { next.add(s); continue; }
            int took = Math.min(s.amount, left);
            left -= took;
            if (took < s.amount) next.add(new Stack(s.material, s.amount - took, true));
        }
        stacks.put(id, next);
        return new TakeResult(true, have);
    }
}
