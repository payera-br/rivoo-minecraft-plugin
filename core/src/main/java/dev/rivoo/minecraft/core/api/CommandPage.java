package dev.rivoo.minecraft.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Uma consulta da fila: os comandos que dá para executar agora e, à parte, quem está online mas tem
 * comando esperando espaço livre no inventário (o plugin pede para liberar).
 */
public record CommandPage(List<CommandRun> runs, boolean hasMore, List<SlotWait> needSlots) {
    public CommandPage(List<CommandRun> runs, boolean hasMore) {
        this(runs, hasMore, List.of());
    }

    /** Jogador (UUID ou nick) com comando esperando {@code requiredSlots} espaços livres. */
    public record SlotWait(UUID uuid, String name, int requiredSlots) {}
}
