package dev.rivoo.minecraft.core.platform;

import java.util.Optional;
import java.util.UUID;

public interface Players {
    boolean isOnline(UUID player);

    /** Nome atual, inclusive de jogador offline que já entrou no servidor. */
    Optional<String> name(UUID player);
}
