package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.PairingApi;
import dev.rivoo.minecraft.core.api.PairingStatus;
import dev.rivoo.minecraft.core.api.PairingTicket;
import dev.rivoo.minecraft.core.api.RivooClient;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Pareamento por código ("conectar TV"): sorteia o segredo S, pede um código à Rivoo mandando só os
 * hashes de S, mostra no console e consulta até o dono usar o código no painel; então monta
 * rck_&lt;S&gt; e conecta pelo caminho do rivoo secret. tick() roda numa thread assíncrona a cada segundo e faz as
 * chamadas de rede; start()/stop() só mexem em flags (seguros na thread do jogo).
 */
public final class PairingFlow {
    public record ServerFacts(String pluginVersion, String mcVersion, String motd, int port) {}

    public static final String CODE_BANNER = "==================================================";
    public static final String NETWORK_WARNING = "Não foi possível falar com a Rivoo para parear; tentando de novo.";
    public static final String REJECTED_WARNING = "Pareamento falhou; gerando novo código.";
    static final Duration NEW_CODE_EVERY = Duration.ofMinutes(10);
    static final long MAX_BACKOFF_SECONDS = 300;
    /** 429 sem Retry-After legível: o bloqueio da API dura um minuto. */
    static final long RATE_LIMIT_FALLBACK_SECONDS = 60;

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PairingApi api;
    private final SecretConnector connector;
    private final ServerFacts facts;
    private final Clock clock;
    private final Logger log;
    private final Consumer<String> announce;
    private final Supplier<String> secrets;
    private final AtomicBoolean ticking = new AtomicBoolean();

    private volatile boolean active;
    private volatile boolean restart;
    private volatile PairingTicket ticket;
    // Só a thread do tick mexe nestes.
    private String secret;
    private Instant nextAt = Instant.MIN;
    private Instant lastCodeAt;
    private int failures;
    private Instant rateLimitedUntil = Instant.MIN;

    public PairingFlow(PairingApi api, SecretConnector connector, ServerFacts facts, Clock clock, Logger log,
                       Consumer<String> announce, Supplier<String> secrets) {
        this.api = api;
        this.connector = connector;
        this.facts = facts;
        this.clock = clock;
        this.log = log;
        this.announce = announce;
        this.secrets = secrets;
    }

    public static String randomSecret() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Começa, ou recomeça com código novo. A chave atual, se houver, vale até o código ser usado. */
    public void start() {
        restart = true;
        active = true;
    }

    /** Revogação da chave: só começa se nada estiver rodando (não descarta um código recém-usado). */
    public void startIfIdle() {
        if (!active) start();
    }

    public void stop() {
        active = false;
        ticket = null;
    }

    public boolean active() {
        return active;
    }

    public Optional<String> statusLine() {
        PairingTicket t = ticket;
        if (!active || t == null) return Optional.empty();
        long minutes = Math.max(0, (Duration.between(clock.instant(), t.expiresAt()).toSeconds() + 59) / 60);
        return Optional.of("Aguardando pareamento: código " + t.code() + " (expira em " + minutes + " min)");
    }

    /** Thread assíncrona, a cada 1 s. Uma execução por vez. */
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            step();
        } finally {
            ticking.set(false);
        }
    }

    private void step() {
        if (!active) return;
        Instant now = clock.instant();
        if (restart) {
            restart = false;
            ticket = null;
            secret = null;
            lastCodeAt = null;
            failures = 0;
            // rivoo connect não fura a espera de um 429.
            nextAt = now.isBefore(rateLimitedUntil) ? rateLimitedUntil : now;
        }
        if (now.isBefore(nextAt)) return;
        PairingTicket t = ticket;
        // Mesmo depois do expiresAt local pergunta à API: um código usado no último segundo ainda vale,
        // e o relógio do servidor de jogo pode estar adiantado. Vencido de verdade = 404.
        if (t == null) requestCode(now);
        else poll(now, t);
    }

    private void requestCode(Instant now) {
        String s = secrets.get();
        PairingTicket t;
        try {
            t = api.startPairing(s, facts.pluginVersion(), facts.mcVersion(), facts.motd(), facts.port());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            failed(now, e);
            return;
        }
        secret = s;
        ticket = t;
        lastCodeAt = now;
        failures = 0;
        nextAt = now.plusSeconds(t.pollSeconds());
        log.warning(CODE_BANNER);
        log.warning("Conecte este servidor à Rivoo: no painel, Integrações → Servidores de jogo → Parear servidor, digite "
                + t.code() + " (vale 10 min)");
        log.warning(CODE_BANNER);
    }

    private void poll(Instant now, PairingTicket t) {
        PairingStatus st;
        try {
            st = api.pairingStatus(secret);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ApiException e) {
            if (e.httpStatus == 404) expired(now);
            else failed(now, e);
            return;
        } catch (IOException e) {
            failed(now, e);
            return;
        }
        failures = 0;
        if (!st.claimed()) {
            nextAt = now.plusSeconds(t.pollSeconds());
            return;
        }
        String key = RivooClient.KEY_PREFIX + secret;
        switch (connector.connect(key)) {
            case SecretConnector.Connected c -> {
                active = false;
                ticket = null;
                secret = null;
                announce.accept("Loja conectada: " + st.projectName() + " · " + st.connectionName());
            }
            case SecretConnector.Rejected r -> {
                log.warning(REJECTED_WARNING);
                ticket = null;
                nextAt = now;
            }
            case SecretConnector.Unreachable u -> failed(now, null);
        }
    }

    /** Código vencido: outro, no máximo um a cada 10 min (contado do código anterior). */
    private void expired(Instant now) {
        ticket = null;
        Instant earliest = lastCodeAt == null ? now : lastCodeAt.plus(NEW_CODE_EVERY);
        nextAt = earliest.isAfter(now) ? earliest : now;
    }

    private void failed(Instant now, IOException e) {
        if (e instanceof ApiException a && a.rateLimited()) {
            long wait = a.retryAfterSeconds > 0 ? a.retryAfterSeconds : RATE_LIMIT_FALLBACK_SECONDS;
            rateLimitedUntil = now.plusSeconds(Math.max(5, wait));
            nextAt = rateLimitedUntil;
            return;
        }
        if (failures++ == 0) {
            boolean refused = e instanceof ApiException a && a.httpStatus >= 400 && a.httpStatus < 500;
            log.warning(refused
                    ? "A Rivoo recusou o pedido de pareamento (HTTP " + ((ApiException) e).httpStatus + "); tentando de novo."
                    : NETWORK_WARNING);
        }
        long seconds = Math.min(MAX_BACKOFF_SECONDS, 5L << Math.min(failures - 1, 6));
        nextAt = now.plusSeconds(seconds);
    }
}
