package dev.rivoo.minecraft.core.api;

import java.io.IOException;

/** Pareamento por código: rotas públicas, sem chave. Bloqueante: nunca na thread do jogo. */
public interface PairingApi {
    /**
     * {@code POST /v1/agent/pairings}. Só os hashes do segredo viajam aqui.
     *
     * @param secret 32 caracteres [a-z0-9] sorteados pelo plugin; nunca logar.
     */
    PairingTicket startPairing(String secret, String agentVersion, String gameVersion, String motd, int port)
            throws IOException, InterruptedException;

    /** {@code GET /v1/agent/pairings/current}. 404 (ApiException) = código vencido ou desconhecido. */
    PairingStatus pairingStatus(String secret) throws IOException, InterruptedException;
}
