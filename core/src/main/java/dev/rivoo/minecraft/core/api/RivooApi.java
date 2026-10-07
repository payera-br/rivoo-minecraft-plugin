package dev.rivoo.minecraft.core.api;

import dev.rivoo.minecraft.core.catalog.Product;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** API da Rivoo usada pelo plugin. Chamadas bloqueantes: nunca na thread do jogo. */
public interface RivooApi {
    /** @param fulfillmentChannel canal da fila de entregas, ou null no modo status (legado). */
    Charge createPixCharge(int amountCents, String externalId, int expiresIn, String idempotencyKey,
                           String fulfillmentChannel) throws IOException, InterruptedException;

    ChargeStatus getStatus(String chargeId) throws IOException, InterruptedException;

    FulfillmentPage listFulfillments(String channel, int limit) throws IOException, InterruptedException;

    /** Devolve os ids confirmados agora ou antes (acked + alreadyAcked). */
    List<String> ack(List<String> chargeIds) throws IOException, InterruptedException;

    /** Só para cobranças devMode (chave cpk_sandbox_). */
    void simulatePayment(String chargeId) throws IOException, InterruptedException;

    /**
     * Modo game: pacotes do servidor. Produtos sem comandos locais; a API manda os comandos prontos.
     * {@code channel} pode ser null: com chave de servidor a API resolve o servidor pela chave.
     */
    List<Product> gameCatalog(String channel) throws IOException, InterruptedException;

    /**
     * Modo game: cria a cobrança do pacote ({@code POST /game/purchases}). O plugin não manda valor:
     * a Rivoo cobra o preço do pacote e o devolve em {@link GamePurchase#price()}.
     */
    GamePurchase createGamePurchase(String channel, String sku, UUID playerId, String playerName,
                                    String idempotencyKey) throws IOException, InterruptedException;

    /** Modo game: comandos pendentes do servidor, na ordem de execução. {@code channel} null = chave de servidor. */
    CommandPage listCommands(String channel, int limit) throws IOException, InterruptedException;

    /** Modo game: devolve os runIds confirmados agora ou antes (done + alreadyDone). */
    List<String> ackCommands(List<String> runIds) throws IOException, InterruptedException;

    /** Chave de servidor: qual servidor/projeto ela representa. 401/403/404 = chave inválida, revogada ou de empresa. */
    ServerInfo gameServer() throws IOException, InterruptedException;

    /** Chave de servidor sandbox: simula o pagamento de uma compra deste servidor. */
    void simulateGamePurchase(String chargeId) throws IOException, InterruptedException;
}
