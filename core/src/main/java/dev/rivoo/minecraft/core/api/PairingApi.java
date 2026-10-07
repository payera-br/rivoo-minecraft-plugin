package dev.rivoo.minecraft.core.api;

import java.io.IOException;

/** Pareamento por código: rotas públicas, sem chave. Bloqueante: nunca na thread do jogo. */
public interface PairingApi {
    /** @param secret 32 caracteres [a-z0-9] sorteados pelo plugin; nunca logar. */
    PairingTicket startPairing(String secret, String pluginVersion, String mcVersion, String motd, int port)
            throws IOException, InterruptedException;

    /** 404 (ApiException) = código vencido ou desconhecido. */
    PairingStatus pairingStatus(String secret) throws IOException, InterruptedException;
}
