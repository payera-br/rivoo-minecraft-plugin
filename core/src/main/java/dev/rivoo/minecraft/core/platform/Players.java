package dev.rivoo.minecraft.core.platform;

import java.util.Optional;
import java.util.UUID;

public interface Players {
    boolean isOnline(UUID player);

    /** Nome atual, inclusive de jogador offline que já entrou no servidor. */
    Optional<String> name(UUID player);

    /** Espaços vazios no inventário principal; MAX_VALUE se a plataforma não sabe dizer. */
    default int freeSlots(UUID player) {
        return Integer.MAX_VALUE;
    }

    /** Mensagem no chat do jogador online; nada se offline. */
    default void message(UUID player, String text) {}
}
