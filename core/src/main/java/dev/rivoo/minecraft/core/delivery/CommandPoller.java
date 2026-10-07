package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.CommandPage;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.platform.Scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Modo game (POC): consome a fila de comandos (GET /game/commands + POST /game/commands/ack).
 * Consulta async; execução na thread do jogo; ack async. O flag busy é sempre liberado.
 */
public final class CommandPoller implements Runnable {
    static final int PAGE = 100;

    private final RivooApi api;
    private final String channel;
    private final Scheduler scheduler;
    private final Function<List<CommandRun>, List<String>> runSync;
    private final Consumer<List<String>> onAcked;
    private final Logger log;
    private final Clock clock;
    private final Runnable onUnauthorized;
    private volatile boolean stopped;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Instant backoffUntil = Instant.EPOCH;
    private volatile long backoffSeconds = 5;

    public CommandPoller(RivooApi api, String channel, Scheduler scheduler,
                         Function<List<CommandRun>, List<String>> runSync, Consumer<List<String>> onAcked,
                         Logger log, Clock clock, Runnable onUnauthorized) {
        this.onAcked = onAcked;
        this.onUnauthorized = onUnauthorized;
        this.api = api;
        this.channel = channel;
        this.scheduler = scheduler;
        this.runSync = runSync;
        this.log = log;
        this.clock = clock;
    }

    boolean busy() {
        return busy.get();
    }

    @Override
    public void run() {
        if (stopped) return;
        if (clock.instant().isBefore(backoffUntil) || !busy.compareAndSet(false, true)) return;
        boolean handedOff = false;
        try {
            CommandPage page = api.listCommands(channel, PAGE);
            backoffSeconds = 5;
            if (page.runs().isEmpty()) return;
            scheduler.sync(() -> runAndAck(page.runs()));
            handedOff = true;
        } catch (ApiException e) {
            if (e.httpStatus == 401 || e.httpStatus == 403) {
                stopped = true; // chave revogada não volta sozinha: só um novo rivoo secret (motor novo)
                onUnauthorized.run();
                return;
            }
            if (e.rateLimited()) {
                long wait = Math.max(e.retryAfterSeconds, backoffSeconds);
                backoffUntil = clock.instant().plusSeconds(wait);
                backoffSeconds = Math.min(backoffSeconds * 2, 120);
                log.warning("Rate limit na fila de comandos (429). Pausando por " + wait + "s");
            } else {
                log.warning("Erro consultando a fila de comandos: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Falha consultando a fila de comandos: " + e);
        } finally {
            if (!handedOff) busy.set(false);
        }
    }

    /** Thread do jogo. */
    private void runAndAck(List<CommandRun> runs) {
        List<String> ackable;
        try {
            ackable = runSync.apply(runs);
        } catch (RuntimeException e) {
            log.warning("Falha ao executar comandos da fila: " + e);
            busy.set(false);
            return;
        }
        if (ackable.isEmpty()) {
            busy.set(false); // ninguém online para receber: fica na fila
            return;
        }
        try {
            scheduler.async(() -> {
                try {
                    onAcked.accept(api.ackCommands(ackable));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // sem ack o run volta na próxima consulta e é executado de novo (pelo menos uma vez)
                    log.warning("Falha no ack de comandos " + ackable + ": " + e.getMessage());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            log.warning("Falha ao agendar o ack de comandos " + ackable + ": " + e);
            busy.set(false);
        }
    }
}
