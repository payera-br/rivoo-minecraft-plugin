package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.Fulfillment;
import dev.rivoo.minecraft.core.api.FulfillmentPage;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.platform.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Consome a fila de entregas (GET /fulfillments + POST /fulfillments/ack). Pelo menos uma vez: o
 * plugin deduplica pelo estado local (DELIVERED). Consulta async; entrega na thread do jogo; ack async.
 */
public final class FulfillmentPoller implements Runnable {
    /** Máximo da API. Sem cursor: a página é sempre o início da fila (paymentDate asc). */
    private static final int PAGE = 100;
    /** Intervalo mínimo entre avisos de fila parada, para não poluir o log a cada ciclo. */
    static final Duration STUCK_WARNING_EVERY = Duration.ofMinutes(5);

    private final RivooApi api;
    private final String channel;
    private final Scheduler scheduler;
    private final Function<List<Fulfillment>, List<String>> deliverSync;
    private final Logger log;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Instant backoffUntil = Instant.EPOCH;
    private volatile long backoffSeconds = 5;
    private volatile Instant lastStuckWarning;

    public FulfillmentPoller(RivooApi api, String channel, Scheduler scheduler,
                             Function<List<Fulfillment>, List<String>> deliverSync, Logger log, Clock clock) {
        this.api = api;
        this.channel = channel;
        this.scheduler = scheduler;
        this.deliverSync = deliverSync;
        this.log = log;
        this.clock = clock;
    }

    @Override
    public void run() {
        if (clock.instant().isBefore(backoffUntil) || !busy.compareAndSet(false, true)) return;
        try {
            FulfillmentPage page = api.listFulfillments(channel, PAGE);
            backoffSeconds = 5;
            if (page.entries().isEmpty()) {
                busy.set(false);
                return;
            }
            scheduler.sync(() -> {
                List<String> ackable;
                try {
                    ackable = deliverSync.apply(page.entries());
                } catch (RuntimeException e) {
                    log.warning("Falha ao entregar itens da fila: " + e);
                    busy.set(false);
                    return;
                }
                if (ackable.isEmpty()) {
                    if (page.hasMore()) warnStuckHead(page.entries().size());
                    busy.set(false); // ninguém online para receber: fica na fila
                    return;
                }
                try {
                    scheduler.async(() -> {
                        try {
                            api.ack(ackable);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            // sem ack o item volta na próxima consulta; o estado local evita entregar de novo
                            log.warning("Falha no ack de " + ackable + ": " + e.getMessage());
                        } finally {
                            busy.set(false);
                        }
                    });
                } catch (RuntimeException e) {
                    log.warning("Falha ao agendar o ack de " + ackable + ": " + e);
                    busy.set(false);
                }
            });
        } catch (ApiException e) {
            busy.set(false);
            if (e.rateLimited()) {
                long wait = Math.max(e.retryAfterSeconds, backoffSeconds);
                backoffUntil = clock.instant().plusSeconds(wait);
                backoffSeconds = Math.min(backoffSeconds * 2, 120);
                log.warning("Rate limit na fila de entregas (429). Pausando por " + wait + "s");
            } else {
                log.warning("Erro consultando a fila de entregas: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            busy.set(false);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            busy.set(false);
            log.warning("Falha de rede consultando a fila de entregas: " + e);
        }
    }

    /**
     * A API não tem cursor: entradas que nunca recebem ack (jogador que não volta, externalId de outra
     * integração) ocupam o início da fila e as seguintes nunca aparecem. Mitigação: avisar o operador.
     */
    private void warnStuckHead(int stuck) {
        Instant now = clock.instant();
        Instant last = lastStuckWarning;
        if (last != null && now.isBefore(last.plus(STUCK_WARNING_EVERY))) return;
        lastStuckWarning = now;
        log.warning("Fila de entregas parada: " + stuck + " entrada(s) no início da fila sem entrega possível"
                + " (jogador offline ou externalId de outra integração) e há mais entradas depois delas,"
                + " que não serão lidas até essas saírem.");
    }
}
