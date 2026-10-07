package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.ChargeStatus;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Consulta UMA cobrança pendente por execução, em round-robin, para ficar abaixo de 30 req/min por IP
 * no ChargeController qualquer que seja o número de compras abertas. Thread assíncrona.
 */
public final class StatusPoller implements Runnable {
    /** Margem após expiresAt antes de desistir: o Pix pode ser compensado no limite. */
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(2);

    private final RivooApi api;
    private final PurchaseStore store;
    private final Consumer<Purchase> onPaid;
    private final Consumer<Purchase> onExpired;
    private final Logger log;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile int cursor;
    private volatile Instant backoffUntil = Instant.EPOCH;
    private volatile long backoffSeconds = 5;

    public StatusPoller(RivooApi api, PurchaseStore store, Consumer<Purchase> onPaid, Consumer<Purchase> onExpired,
                        Logger log, Clock clock) {
        this.api = api;
        this.store = store;
        this.onPaid = onPaid;
        this.onExpired = onExpired;
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
        Purchase p = pending.get(cursor++ % pending.size());
        try {
            ChargeStatus status = api.getStatus(p.chargeId);
            backoffSeconds = 5;
            if (status.paid()) {
                if (store.transition(p, State.PENDING, State.PAID)) {
                    log.info("Pago: " + p.chargeId + " (" + p.playerName + ", " + p.sku + ")");
                    onPaid.accept(p);
                }
            } else if (status.dead() || clock.instant().isAfter(p.expiresAt.plus(EXPIRY_GRACE))) {
                // devMode nunca expira no backend: o plugin decide pelo expiresAt local.
                if (store.transition(p, State.PENDING, State.EXPIRED)) {
                    log.info("Expirado: " + p.chargeId);
                    onExpired.accept(p);
                }
            }
        } catch (ApiException e) {
            if (e.rateLimited()) {
                long wait = Math.max(e.retryAfterSeconds, backoffSeconds);
                backoffUntil = clock.instant().plusSeconds(wait);
                backoffSeconds = Math.min(backoffSeconds * 2, 120);
                log.warning("Rate limit (429). Pausando polling por " + wait + "s");
            } else {
                log.warning("Erro consultando " + p.chargeId + ": " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Falha de rede consultando " + p.chargeId + ": " + e);
        }
    }
}
