package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.AckResult;
import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.CommandPage;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.OnlinePlayer;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.platform.Scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Consome a fila de comandos da conexão (POST /v1/agent/commands em long-poll, dizendo quem está online,
 * + POST /v1/agent/commands/ack com o resultado de cada um). Consulta async; execução na thread do jogo;
 * ack async. O flag busy é sempre liberado. Atraso e repetição são da Rivoo: um comando só aparece na
 * fila quando vence e dá para executar, e a próxima repetição é agendada a partir do ack.
 */
public final class CommandPoller implements Runnable {
    static final int PAGE = 100;

    private final RivooApi api;
    private final int waitSeconds;
    private final Scheduler scheduler;
    private final Function<List<CommandRun>, List<AckResult>> runSync;
    private final Supplier<List<OnlinePlayer>> online;
    private final Consumer<List<CommandPage.SlotWait>> onNeedSlots;
    private final Consumer<List<String>> onAcked;
    private final Logger log;
    private final Clock clock;
    private final Runnable onUnauthorized;
    private volatile boolean stopped;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Instant backoffUntil = Instant.EPOCH;
    private volatile long backoffSeconds = 5;
    public CommandPoller(RivooApi api, int waitSeconds, Scheduler scheduler, Supplier<List<OnlinePlayer>> online,
                         Function<List<CommandRun>, List<AckResult>> runSync, Consumer<List<String>> onAcked,
                         Consumer<List<CommandPage.SlotWait>> onNeedSlots, Logger log, Clock clock,
                         Runnable onUnauthorized) {
        this.online = online;
        this.onNeedSlots = onNeedSlots;
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
            CommandPage page = api.pollCommands(waitSeconds, PAGE, online.get());
            backoffSeconds = 5;
            if (!stopped && !page.needSlots().isEmpty()) scheduler.sync(() -> onNeedSlots.accept(page.needSlots()));
            if (stopped || page.runs().isEmpty()) return;
            scheduler.sync(() -> runAndAck(page));
            handedOff = true;
        } catch (ApiException e) {
            if (e.unauthorized()) {
                stopped = true; // chave revogada não volta sozinha: só um pareamento ou rivoo secret (motor novo)
                onUnauthorized.run();
                return;
            }
            long wait = backOff(e.rateLimited() ? e.retryAfterSeconds : 0);
            log.warning((e.rateLimited() ? "Rate limit na fila de comandos (429)" : "Erro consultando a fila de comandos: " + e.getMessage())
                    + ". Pausando por " + wait + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            long wait = backOff(0);
            log.warning("Falha consultando a fila de comandos: " + e + ". Pausando por " + wait + "s");
        } finally {
            if (!handedOff) busy.set(false);
        }
    }

    /** Pausa de 5 s, dobrando até 120 s (ou o Retry-After, se maior) enquanto os erros seguirem. */
    private long backOff(long retryAfterSeconds) {
        long wait = Math.max(retryAfterSeconds, backoffSeconds);
        backoffUntil = clock.instant().plusSeconds(wait);
        backoffSeconds = Math.min(backoffSeconds * 2, 120);
        return wait;
    }

    /** Thread do jogo. */
    private void runAndAck(CommandPage page) {
        List<CommandRun> runs = page.runs();
        if (stopped) {
            busy.set(false);
            return;
        }
        List<AckResult> ackable;
        try {
            ackable = runSync.apply(runs);
        } catch (RuntimeException e) {
            log.warning("Falha ao executar comandos da fila: " + e);
            busy.set(false);
            return;
        }
        if (ackable.isEmpty()) {
            busy.set(false); // o jogador saiu entre a consulta e a execução: fica na fila
            return;
        }
        try {
            scheduler.async(() -> {
                try {
                    onAcked.accept(api.ackCommands(ackable));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // sem ack o run volta na próxima consulta e recebe o ack guardado (acks.json), sem rodar de novo
                    log.warning("Falha no ack de " + ackable.size() + " comando(s): " + e.getMessage());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            log.warning("Falha ao agendar o ack de " + ackable.size() + " comando(s): " + e);
            busy.set(false);
        }
    }
}
