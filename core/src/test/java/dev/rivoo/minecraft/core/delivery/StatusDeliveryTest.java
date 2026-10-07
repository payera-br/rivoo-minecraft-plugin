package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.ChargeStatus;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static dev.rivoo.minecraft.core.fixtures.EngineHarness.STEVE;
import static org.assertj.core.api.Assertions.assertThat;

class StatusDeliveryTest {
    @TempDir Path dir;

    EngineHarness started() {
        EngineHarness h = new EngineHarness(dir);
        h.start("status");
        return h;
    }

    Purchase buy(EngineHarness h, String sku) {
        return ((BuyResult.Created) h.engine.buy(STEVE, "Steve", sku).join()).purchase();
    }

    @Test void paidStatusDeliversOnce() {
        EngineHarness h = started();
        Purchase p = buy(h, "vip");
        h.api.statuses.put(p.chargeId, new ChargeStatus("COMPLETED", "PAID"));
        h.scheduler.runRepeating();
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).hasSize(2);
        assertThat(p.state).isEqualTo(State.DELIVERED);
        assertThat(h.api.acks).isEmpty();
    }

    @Test void pollsOneChargePerRunRoundRobin() {
        EngineHarness h = started();
        Purchase a = buy(h, "vip");
        h.clock.advance(Duration.ofSeconds(1));
        h.engine.buy(EngineHarness.ALEX, "Alex", "kit").join();
        h.scheduler.runRepeating();
        h.scheduler.runRepeating();
        assertThat(h.api.statusCalls).hasSize(2).doesNotHaveDuplicates();
        assertThat(h.api.statusCalls.get(0)).isEqualTo(a.chargeId);
    }

    @Test void paidWhileOfflineWaitsForJoin() {
        EngineHarness h = started();
        Purchase p = buy(h, "vip");
        h.players.offline(STEVE);
        h.api.statuses.put(p.chargeId, new ChargeStatus("OPEN", "RECIEVED"));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.PAID);
        assertThat(h.view.events).containsExactly("offline:" + p.chargeId);
        assertThat(h.commands.ran).isEmpty();

        h.players.online(STEVE, "Steve");
        h.engine.onJoin(STEVE);
        assertThat(h.commands.ran).hasSize(2);
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void paidBeforeRestartIsDeliveredOnStart() {
        EngineHarness h = new EngineHarness(dir);
        h.start("status");
        Purchase p = buy(h, "vip");
        h.store.transition(p, State.PENDING, State.PAID);
        h.engine.stop();

        EngineHarness restarted = new EngineHarness(dir);
        restarted.start("status");
        assertThat(restarted.commands.ran).hasSize(2);
    }

    @Test void deadOrPastGraceExpires() {
        EngineHarness h = started();
        Purchase p = buy(h, "vip");
        h.api.statuses.put(p.chargeId, new ChargeStatus("EXPIRED", "EXPIRED"));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.EXPIRED);
        assertThat(h.view.events).containsExactly("expired:" + p.chargeId + ":false");

        Purchase q = buy(h, "kit");
        h.clock.advance(Duration.ofSeconds(600 + 121)); // devMode nunca expira no backend
        h.scheduler.runRepeating();
        assertThat(q.state).isEqualTo(State.EXPIRED);
    }

    @Test void rateLimitBacksOffAndNetworkErrorIsSwallowed() {
        EngineHarness h = started();
        Purchase p = buy(h, "vip");
        h.api.statusError = new ApiException(429, "{}", 20);
        h.scheduler.runRepeating();
        h.api.statusError = null;
        h.api.statuses.put(p.chargeId, new ChargeStatus("COMPLETED", "PAID"));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.PENDING);
        h.clock.advance(Duration.ofSeconds(21));
        h.api.statusError = new IOException("reset");
        h.scheduler.runRepeating();
        h.api.statusError = null;
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void overlappingRunIsSkippedAndGuardIsReleased() throws Exception {
        EngineHarness h = new EngineHarness(dir);
        Purchase p = h.store.newPurchase(new dev.rivoo.minecraft.core.api.Charge("c1", "br", "u",
                h.clock.instant().plusSeconds(600)), STEVE, "Steve", "vip", 1990);
        h.store.add(p);
        StatusPoller[] self = new StatusPoller[1];
        int[] calls = {0};
        dev.rivoo.minecraft.core.api.RivooApi reentrant = (dev.rivoo.minecraft.core.api.RivooApi)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{dev.rivoo.minecraft.core.api.RivooApi.class}, (proxy, m, args) -> {
                            if (!m.getName().equals("getStatus")) throw new UnsupportedOperationException();
                            calls[0]++;
                            self[0].run(); // execução anterior ainda em andamento
                            if (calls[0] == 1) throw new IOException("reset");
                            return new ChargeStatus("OPEN", "PENDING");
                        });
        self[0] = new StatusPoller(reentrant, h.store, x -> { }, x -> { }, java.util.logging.Logger.getLogger("t"), h.clock);

        self[0].run();
        assertThat(calls[0]).isEqualTo(1); // a chamada reentrante não consultou
        self[0].run();
        assertThat(calls[0]).isEqualTo(2); // guarda liberada mesmo depois da exceção
    }
}
