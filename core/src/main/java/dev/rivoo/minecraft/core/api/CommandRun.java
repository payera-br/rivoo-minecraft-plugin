package dev.rivoo.minecraft.core.api;

import java.util.UUID;

/**
 * Comando pronto da fila da conexão ({@code GET /v1/agent/commands}), já com {player}/{uuid} trocados.
 *
 * @param recipientUuid UUID do jogador; null quando o pedido só tem o nick (loja web) ou não tem jogador.
 * @param recipientName nick do jogador; null quando o comando não é de ninguém (ex.: enviado pelo painel).
 * @param requiredSlots espaços livres no inventário antes de executar (0 = não exige).
 */
public record CommandRun(String id, String command, UUID recipientUuid, String recipientName,
                         boolean requireOnline, int requiredSlots) {
    public boolean hasRecipient() {
        return recipientUuid != null || recipientName != null;
    }
}
