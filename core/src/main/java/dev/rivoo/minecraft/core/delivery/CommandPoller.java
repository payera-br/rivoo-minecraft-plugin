package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.CommandPage;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.platform.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Consome a fila de comandos da conexão (GET /v1/agent/commands em long-poll + POST /v1/agent/commands/ack).
 * Consulta async; execução na thread do jogo; ack async. O flag busy é sempre liberado. Atraso e
 * repetição são da Rivoo: um comando só aparece na fila quando vence, e a próxima repetição é agendada
 * a partir do ack.
 */
public final class CommandPoller implements Runnable {
    static final int PAGE = 100;

    private final RivooApi api;
    private final int waitSeconds;
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
    /** Intervalo mínimo entre avisos de fila parada, para não poluir o log a cada ciclo. */
    static final Duration STUCK_WARNING_EVERY = Duration.ofMinutes(5);
    private volatile Instant lastStuckWarning;

    public CommandPoller(RivooApi api, int waitSeconds, Scheduler scheduler,
                         Function<List<CommandRun>, List<String>> runSync, Consumer<List<String>> onAcked,
                         Logger log, Clock clock, Runnable onUnauthorized) {
        this.onAcked = onAcked;
        this.onUnauthorized = onUnauthorized;
        this.api = api;
        this.waitSeconds = waitSeconds;
        this.scheduler = scheduler;
        this.runSync = runSync;
        this.log = log;
        this.clock = clock;
    }

    boolean busy() {
        return busy.get();
    }

    /** Motor parado ou trocado: uma consulta ainda aberta (long-poll) não executa nada ao voltar. */
    public void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        if (stopped) return;
        if (clock.instant().isBefore(backoffUntil) || !busy.compareAndSet(false, true)) return;
        boolean handedOff = false;
        try {
            CommandPage page = api.listCommands(waitSeconds, PAGE);
            backoffSeconds = 5;
            if (stopped || page.runs().isEmpty()) return;
            scheduler.sync(() -> runAndAck(page));
            handedOff = true;
        } catch (ApiException e) {
            if (e.unauthorized()) {
                stopped = true; // chave revogada não volta sozinha: só um pareamento ou rivoo secret (motor novo)
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
    private void runAndAck(CommandPage page) {
        List<CommandRun> runs = page.runs();
        if (stopped) {
            busy.set(false);
            return;
        }
        List<String> ackable;
        try {
            ackable = runSync.apply(runs);
        } catch (RuntimeException e) {
            log.warning("Falha ao executar comandos da fila: " + e);
            busy.set(false);
            return;
        }
        if (ackable.isEmpty()) {
            if (page.hasMore()) warnStuckHead(runs.size());
            busy.set(false); // ninguém online (ou sem espaço) para receber: fica na fila
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

    /**
     * A fila não tem cursor: comandos que esperam um jogador offline (ou sem espaço) ocupam o início e,
     * passando de uma página, os seguintes nunca aparecem. Mitigação: avisar o operador.
     */
    private void warnStuckHead(int stuck) {
        Instant now = clock.instant();
        Instant last = lastStuckWarning;
        if (last != null && now.isBefore(last.plus(STUCK_WARNING_EVERY))) return;
        lastStuckWarning = now;
        log.warning("Fila de comandos parada: " + stuck + " comando(s) no início da fila esperando jogador"
                + " online ou espaço no inventário, e há mais depois deles, que não serão lidos até esses saírem.");
    }
}
