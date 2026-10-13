package dev.rivoo.minecraft.core.api;

import java.time.Instant;

/**
 * Pedido criado no jogo ({@code POST /v1/agent/orders}) com o Pix já iniciado.
 *
 * @param amount  total cobrado, em centavos (o preço vem sempre da Rivoo).
 * @param brCode  Pix copia e cola; null quando não há pagamento (pedido gratuito, já pago).
 * @param checkoutUrl página de pagamento da Rivoo (alternativa ao QR).
 * @param expiresAt vencimento do Pix; null sem pagamento.
 */
public record AgentOrder(String id, String status, int amount, String brCode, String checkoutUrl, Instant expiresAt) {
    public boolean payable() {
        return brCode != null && expiresAt != null;
    }
}
