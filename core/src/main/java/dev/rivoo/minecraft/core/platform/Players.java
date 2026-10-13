package dev.rivoo.minecraft.core.platform;

import java.util.Optional;
import java.util.UUID;

public interface Players {
    boolean isOnline(UUID player);

    /** Nome atual, inclusive de jogador offline que já entrou no servidor. */
    Optional<String> name(UUID player);

    /** Jogador online com esse nick (sem diferenciar maiúsculas); vazio se offline. */
    default Optional<UUID> onlineByName(String name) {
        return Optional.empty();
    }

    /** Espaços vazios no inventário principal; MAX_VALUE se a plataforma não sabe dizer. */
    default int freeSlots(UUID player) {
        return Integer.MAX_VALUE;
    }

    /** Thread do jogo. Quem está online agora, com os espaços livres de cada um. */
    default java.util.List<dev.rivoo.minecraft.core.api.OnlinePlayer> online() {
        return java.util.List.of();
    }

    /** Mensagem no chat do jogador online; nada se offline. */
    default void message(UUID player, String text) {}
}
