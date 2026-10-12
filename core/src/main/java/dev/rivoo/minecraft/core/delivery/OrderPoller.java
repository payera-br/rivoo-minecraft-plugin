package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Acompanha os Pix abertos ({@code GET /v1/agent/orders/:id}) para tirar o mapa e comemorar quando o
 * pedido é pago. Não entrega nada: os comandos chegam pela fila. Consulta UM pedido por execução, em
 * rodízio, para o custo não crescer com o número de Pix abertos. Thread assíncrona.
 */
public final class OrderPoller implements Runnable {
    /** Margem após o vencimento do Pix antes de desistir: o pagamento pode ser compensado no limite. */
    static final Duration EXPIRY_GRACE = Duration.ofMinutes(2);
    private static final Set<String> PAID = Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED");
    private static final Set<String> DEAD = Set.of("EXPIRED", "CANCELED");

    private final RivooApi api;
    private final PurchaseStore store;
    private final Consumer<Purchase> onPaid;
    private final Consumer<Purchase> onExpired;
    private final Runnable onUnauthorized;
    private final Logger log;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile int cursor;
    private volatile Instant backoffUntil = Instant.EPOCH;
    private volatile long backoffSeconds = 5;

    public OrderPoller(RivooApi api, PurchaseStore store, Consumer<Purchase> onPaid, Consumer<Purchase> onExpired,
                       Runnable onUnauthorized, Logger log, Clock clock) {
        this.api = api;
        this.store = store;
        this.onPaid = onPaid;
        this.onExpired = onExpired;
        this.onUnauthorized = onUnauthorized;
        this.log = log;
        this.clock = clock;
    }

    @Override
    public void run() {
        // Uma consulta lenta (timeout) não pode se sobrepor à próxima execução agendada.
        if (clock.instant().isBefore(backoffUntil) || !busy.compareAndSet(false, true)) return;
        try {
            poll();
        } finally {
            busy.set(false);
        }
    }

    private void poll() {
        List<Purchase> pending = store.inState(State.PENDING);
        if (pending.isEmpty()) return;
        Purchase p = pending.get(Math.floorMod(cursor++, pending.size()));
        try {
            String status = api.orderStatus(p.chargeId);
            backoffSeconds = 5;
            if (PAID.contains(status)) {
                paid(p);
            } else if (DEAD.contains(status) || clock.instant().isAfter(p.expiresAt.plus(EXPIRY_GRACE))) {
                // O pedido vive mais que o Pix (fica OPEN depois do QR vencer): o plugin decide pelo expiresAt local.
                expire(p);
            }
        } catch (ApiException e) {
            if (e.unauthorized()) {
                onUnauthorized.run();
            } else if (e.httpStatus == 404) {
                // Pedido de outra conexão ou de antes da 1.0.0 (cobrança da API antiga): nada a acompanhar.
                expire(p);
            } else if (e.rateLimited()) {
                long wait = Math.max(e.retryAfterSeconds, backoffSeconds);
                backoffUntil = clock.instant().plusSeconds(wait);
                backoffSeconds = Math.min(backoffSeconds * 2, 120);
                log.warning("Rate limit (429). Pausando a consulta de pedidos por " + wait + "s");
            } else {
                log.warning("Erro consultando o pedido " + p.chargeId + ": " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Falha de rede consultando o pedido " + p.chargeId + ": " + e);
        }
    }

    private void paid(Purchase p) {
        if (store.markDelivered(p)) {
            log.info("Pago: " + p.chargeId + " (" + p.playerName + ", " + p.sku + ")");
            onPaid.accept(p);
        }
    }

    private void expire(Purchase p) {
        if (store.transition(p, State.PENDING, State.EXPIRED)) {
            log.info("Expirado: " + p.chargeId);
            onExpired.accept(p);
        }
    }
}
