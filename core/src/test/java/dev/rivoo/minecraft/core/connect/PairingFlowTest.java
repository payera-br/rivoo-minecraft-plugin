package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.PairingStatus;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.FakeApi;
import dev.rivoo.minecraft.core.fixtures.FakePairingApi;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PairingFlowTest {
    static final PairingFlow.ServerFacts FACTS = new PairingFlow.ServerFacts("0.3.0", "1.21.4", "Rivoo Craft", 25565);

    @TempDir Path dir;
    FakePairingApi pairingApi = new FakePairingApi();
    FakeApi api = new FakeApi();
    MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
    CapturingLog log = new CapturingLog();
    List<String> announced = new ArrayList<>();
    List<String> keysAsked = new ArrayList<>();
    List<ServerInfo> connected = new ArrayList<>();
    AtomicInteger secretSeq = new AtomicInteger();
    KeyFile keyFile;
    PairingFlow flow;

    /** Segredos previsíveis: "s1" + 30 zeros, "s2" + ... (32 caracteres [a-z0-9]). */
    String secret(int n) { return ("s" + n + "0".repeat(32)).substring(0, 32); }

    @BeforeEach void setUp() {
        keyFile = new KeyFile(dir.resolve("server.key"));
        SecretConnector connector = new SecretConnector(k -> { keysAsked.add(k); return api; }, keyFile,
                (RivooApi a, ServerInfo info) -> connected.add(info));
        flow = new PairingFlow(pairingApi, connector, FACTS, clock, log.logger, announced::add,
                () -> secret(secretSeq.incrementAndGet()));
    }

    @Test void randomSecretsAre32LowercaseAlphanumerics() {
        String a = PairingFlow.randomSecret();
        assertThat(a).matches("[a-z0-9]{32}");
        assertThat(PairingFlow.randomSecret()).isNotEqualTo(a);
    }

    @Test void idleUntilStarted() {
        flow.tick();
        assertThat(pairingApi.startedSecrets).isEmpty();
        assertThat(flow.statusLine()).isEmpty();
    }

    @Test void showsTheCodeThenPollsEveryPollSeconds() {
        flow.start();
        flow.tick();
        assertThat(pairingApi.startedSecrets).containsExactly(secret(1));
        assertThat(log.warnings).contains(PairingFlow.CODE_BANNER,
                "Conecte este servidor à Rivoo: no painel, Minecraft → Conectar servidor, digite K7QM-2XPA (vale 10 min)");
        assertThat(String.join("\n", log.messages)).doesNotContain(secret(1));
        assertThat(flow.statusLine()).contains("Aguardando pareamento: código K7QM-2XPA (expira em 10 min)");

        flow.tick();
        assertThat(pairingApi.polledSecrets).isEmpty();
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(pairingApi.polledSecrets).containsExactly(secret(1));
    }

    @Test void connectedBuildsTheKeySavesItAndAnnounces() throws Exception {
        flow.start();
        flow.tick();
        pairingApi.status = new PairingStatus(true, true, "Rivoo", "Rivoo Craft", "Survival");
        clock.advance(Duration.ofSeconds(5));
        flow.tick();

        assertThat(keysAsked).containsExactly("cpk_sandbox_" + secret(1));
        assertThat(keyFile.read()).contains("cpk_sandbox_" + secret(1));
        assertThat(connected).containsExactly(api.serverInfo);
        assertThat(announced).containsExactly("Loja conectada: Rivoo · Rivoo Craft · modo TESTE");
        assertThat(flow.active()).isFalse();
        assertThat(flow.statusLine()).isEmpty();

        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(pairingApi.polledSecrets).hasSize(1);
    }

    @Test void liveModeBuildsALiveKey() {
        flow.start();
        flow.tick();
        pairingApi.status = new PairingStatus(true, false, "Rivoo", "Rivoo Craft", "Survival");
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(keysAsked).containsExactly("cpk_live_" + secret(1));
        assertThat(announced).containsExactly("Loja conectada: Rivoo · Rivoo Craft · modo PRODUÇÃO");
    }

    @Test void anExpiredCodeIsReplacedWithANewSecretNoSoonerThanTenMinutes() {
        flow.start();
        flow.tick();
        pairingApi.statusError = new ApiException(404, "{}", 0);
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(flow.statusLine()).isEmpty();

        clock.advance(Duration.ofMinutes(5));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);

        clock.set(Instant.parse("2026-10-05T12:10:00Z"));
        flow.tick();
        assertThat(pairingApi.startedSecrets).containsExactly(secret(1), secret(2));
        assertThat(log.warnings).contains(
                "Conecte este servidor à Rivoo: no painel, Minecraft → Conectar servidor, digite M8RT-3ZQB (vale 10 min)");
    }

    @Test void rateLimitedWaitsForRetryAfter() {
        pairingApi.startError = new ApiException(429, "{}", 30);
        flow.start();
        flow.tick();
        clock.advance(Duration.ofSeconds(29));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);
        pairingApi.startError = null;
        clock.advance(Duration.ofSeconds(1));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(2);
        assertThat(log.warnings).doesNotContain(PairingFlow.NETWORK_WARNING);
    }

    @Test void networkErrorsBackOffAndWarnOncePerStreak() {
        pairingApi.startError = new IOException("connection refused");
        flow.start();
        flow.tick();                                   // 1ª falha → espera 5 s
        clock.advance(Duration.ofSeconds(5));
        flow.tick();                                   // 2ª falha → espera 10 s
        clock.advance(Duration.ofSeconds(9));
        flow.tick();                                   // ainda esperando
        assertThat(pairingApi.startedSecrets).hasSize(2);
        clock.advance(Duration.ofSeconds(1));
        flow.tick();                                   // 3ª falha
        assertThat(pairingApi.startedSecrets).hasSize(3);
        assertThat(log.warnings.stream().filter(PairingFlow.NETWORK_WARNING::equals)).hasSize(1);
    }

    @Test void aRejectedBuiltKeyStartsOverWithANewCode() {
        api.gameServerError = new ApiException(401, "{}", 0);
        flow.start();
        flow.tick();
        pairingApi.status = new PairingStatus(true, true, "Rivoo", "Rivoo Craft", "Survival");
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(log.warnings).contains(PairingFlow.REJECTED_WARNING);
        assertThat(connected).isEmpty();

        flow.tick();
        assertThat(pairingApi.startedSecrets).containsExactly(secret(1), secret(2));
    }

    @Test void startAgainAsksANewCodeRightAway() {
        flow.start();
        flow.tick();
        flow.start();
        flow.tick();
        assertThat(pairingApi.startedSecrets).containsExactly(secret(1), secret(2));
    }

    @Test void stopEndsPolling() {
        flow.start();
        flow.tick();
        flow.stop();
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(pairingApi.polledSecrets).isEmpty();
        assertThat(flow.statusLine()).isEmpty();
    }

    @Test void aRevocationDoesNotThrowAwayAnActivePairing() {
        flow.start();
        flow.tick();
        flow.startIfIdle();          // a chave antiga morreu porque o código acabou de ser usado
        clock.advance(Duration.ofSeconds(5));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);
        assertThat(pairingApi.polledSecrets).containsExactly(secret(1));
    }

    @Test void startIfIdleStartsWhenNothingIsRunning() {
        flow.startIfIdle();
        flow.tick();
        assertThat(pairingApi.startedSecrets).containsExactly(secret(1));
    }

    @Test void aClaimAtTheLastSecondStillConnects() {
        flow.start();
        flow.tick();
        pairingApi.status = new PairingStatus(true, true, "Rivoo", "Rivoo Craft", "Survival");
        clock.set(Instant.parse("2026-10-05T12:10:00Z"));
        flow.tick();
        assertThat(keysAsked).containsExactly("cpk_sandbox_" + secret(1));
    }

    @Test void aRateLimitWithoutRetryAfterWaitsAMinute() {
        pairingApi.startError = new ApiException(429, "{}", 0);
        flow.start();
        flow.tick();
        pairingApi.startError = null;
        clock.advance(Duration.ofSeconds(59));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);
        clock.advance(Duration.ofSeconds(1));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(2);
    }

    @Test void rivooConnectDoesNotSkipARateLimitWait() {
        pairingApi.startError = new ApiException(429, "{}", 30);
        flow.start();
        flow.tick();
        pairingApi.startError = null;
        flow.start();
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);
        clock.advance(Duration.ofSeconds(30));
        flow.tick();
        assertThat(pairingApi.startedSecrets).hasSize(2);
    }

    @Test void aRefusedRequestIsNotBlamedOnTheNetwork() {
        pairingApi.startError = new ApiException(400, "{}", 0);
        flow.start();
        flow.tick();
        assertThat(log.warnings).contains("A Rivoo recusou o pedido de pareamento (HTTP 400); tentando de novo.")
                .doesNotContain(PairingFlow.NETWORK_WARNING);
    }
}
