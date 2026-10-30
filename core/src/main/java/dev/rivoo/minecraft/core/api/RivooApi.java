package dev.rivoo.minecraft.core.api;

import dev.rivoo.minecraft.core.catalog.Product;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Protocolo do agente ({@code /v1/agent/*}) com a chave da conexão (rck_). Chamadas bloqueantes:
 * nunca na thread do jogo. 401 (ApiException) = chave revogada ou conexão removida.
 */
public interface RivooApi {
    /** {@code GET /v1/agent/connection}: qual servidor/projeto a chave representa. */
    ServerInfo connection() throws IOException, InterruptedException;

    /** {@code POST /v1/agent/heartbeat}: versão do plugin e dados do servidor para o painel. */
    void heartbeat(String agentVersion, Map<String, Object> details) throws IOException, InterruptedException;

    /** {@code GET /v1/agent/catalog}: produtos com sku, preço já com descontos do catálogo. */
    List<Product> catalog() throws IOException, InterruptedException;

    /**
     * {@code POST /v1/agent/orders}: pedido de 1 unidade do sku para o jogador, pago por Pix. O plugin
     * não manda valor: a Rivoo aplica preço, regras e descontos e devolve o total. {@code giftTo}: nick de
     * outro jogador que recebe o pacote (null = o próprio comprador).
     */
    AgentOrder createOrder(String sku, UUID playerId, String playerName, String giftTo, String idempotencyKey)
            throws IOException, InterruptedException;

    /** {@code GET /v1/agent/orders/:id}: OPEN, PAID, PARTIALLY_REFUNDED, REFUNDED, EXPIRED ou CANCELED. */
    String orderStatus(String orderId) throws IOException, InterruptedException;

    /**
     * {@code POST /v1/agent/commands}: comandos vencidos da conexão que dá para executar agora, dado quem
     * está online (e com quantos espaços livres). Comandos esperando jogador offline ou inventário cheio
     * não voltam, então não seguram a fila. Long-poll: a API responde assim que houver comandos, ou vazio
     * depois de {@code waitSeconds}.
     */
    CommandPage pollCommands(int waitSeconds, int limit, List<OnlinePlayer> online) throws IOException, InterruptedException;

    /** {@code POST /v1/agent/commands/ack} com o resultado de cada um: devolve os ids que saíram da fila. */
    List<String> ackCommands(List<AckResult> results) throws IOException, InterruptedException;
}
