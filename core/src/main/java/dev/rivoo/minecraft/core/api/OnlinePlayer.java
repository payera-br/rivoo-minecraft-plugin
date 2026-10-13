package dev.rivoo.minecraft.core.api;

import java.util.UUID;

/**
 * Jogador no servidor agora, com os espaços livres do inventário principal. Vai na consulta da fila:
 * a Rivoo só devolve o que dá para executar já.
 */
public record OnlinePlayer(UUID uuid, String name, int freeSlots) {}
