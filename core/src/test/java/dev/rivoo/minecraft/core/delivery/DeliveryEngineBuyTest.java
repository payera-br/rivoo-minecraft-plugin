package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.core.purchase.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.rivoo.minecraft.core.fixtures.EngineHarness.ALEX;
import static dev.rivoo.minecraft.core.fixtures.EngineHarness.STEVE;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryEngineBuyTest {
    @TempDir Path dir;

    @Test void autoPicksQueueWhenFulfillmentsAnswers() {
        EngineHarness h = new EngineHarness(dir);
        assertThat(h.start("auto").mode()).isEqualTo(Mode.FULFILLMENTS);
    }

    @Test void autoFallsBackToStatusOnlyOn404() {
        EngineHarness h = new EngineHarness(dir);
        h.api.listError = new ApiException(404, "{}", 0);
        assertThat(h.start("auto").mode()).isEqualTo(Mode.STATUS);
        assertThat(h.scheduler.activeRepeating()).isPositive(); // poller de status, sem retentativa
        h.clock.advance(java.time.Duration.ofMinutes(5));
        h.scheduler.runRepeating();
        assertThat(h.api.listCalls.get()).isEqualTo(1);
    }

    DeliveryEngine autoWithLog(EngineHarness h, dev.rivoo.minecraft.core.fixtures.CapturingLog log) {
        h.engine = new DeliveryEngine(h.api, h.store, h.catalog, h.scheduler, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "auto", 600, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3)),
                log.logger, h.clock);
        h.engine.start();
        return h.engine;
    }

    @Test void serverErrorAtDetectionKeepsModeUndecidedAndRetriesWithBackoff() {
        EngineHarness h = new EngineHarness(dir);
        var log = new dev.rivoo.minecraft.core.fixtures.CapturingLog();
        h.api.listError = new ApiException(500, "{}", 0);
        DeliveryEngine engine = autoWithLog(h, log);
        assertThat(engine.mode()).isNull();
        assertThat(engine.buy(STEVE, "Steve", "vip").join())
                .isEqualTo(new BuyResult.Failed("A loja está iniciando. Tente em alguns segundos."));
        assertThat(h.api.listCalls.get()).isEqualTo(1);
        assertThat(log.warnings).hasSize(1);

        h.clock.advance(java.time.Duration.ofSeconds(4));
        h.scheduler.runRepeating();
        assertThat(h.api.listCalls.get()).isEqualTo(1); // ainda dentro dos 5s
        h.clock.advance(java.time.Duration.ofSeconds(1));
        h.scheduler.runRepeating();
        assertThat(h.api.listCalls.get()).isEqualTo(2); // falha de novo: próxima em 10s
        assertThat(log.warnings).hasSize(2);
        assertThat(engine.mode()).isNull();

        h.api.listError = null;
        h.clock.advance(java.time.Duration.ofSeconds(9));
        h.scheduler.runRepeating();
        assertThat(h.api.listCalls.get()).isEqualTo(2);
        h.clock.advance(java.time.Duration.ofSeconds(1));
        h.scheduler.runRepeating();
        assertThat(engine.mode()).isEqualTo(Mode.FULFILLMENTS);

        int calls = h.api.listCalls.get();
        h.clock.advance(java.time.Duration.ofMinutes(10));
        h.scheduler.runRepeating();
        h.scheduler.runRepeating();
        // só o poller da fila consulta agora (2 execuções), nenhuma nova detecção
        assertThat(h.api.listCalls.get()).isEqualTo(calls + 2);
    }

    @Test void networkErrorAtDetectionAlsoRetries() {
        EngineHarness h = new EngineHarness(dir);
        h.api.listError = new IOException("connection refused");
        DeliveryEngine engine = autoWithLog(h, new dev.rivoo.minecraft.core.fixtures.CapturingLog());
        assertThat(engine.mode()).isNull();
        h.api.listError = new ApiException(404, "{}", 0);
        h.clock.advance(java.time.Duration.ofSeconds(5));
        h.scheduler.runRepeating();
        assertThat(engine.mode()).isEqualTo(Mode.STATUS);
    }

    @Test void retryBackoffIsCappedAt60Seconds() {
        EngineHarness h = new EngineHarness(dir);
        h.api.listError = new ApiException(503, "{}", 0);
        autoWithLog(h, new dev.rivoo.minecraft.core.fixtures.CapturingLog());
        for (long wait : new long[]{5, 10, 20, 40, 60, 60}) {
            int before = h.api.listCalls.get();
            h.clock.advance(java.time.Duration.ofSeconds(wait - 1));
            h.scheduler.runRepeating();
            assertThat(h.api.listCalls.get()).as("antes de %ss", wait).isEqualTo(before);
            h.clock.advance(java.time.Duration.ofSeconds(1));
            h.scheduler.runRepeating();
            assertThat(h.api.listCalls.get()).as("depois de %ss", wait).isEqualTo(before + 1);
        }
    }

    @Test void stopDuringDetectionRetriesSchedulesNothingMore() {
        EngineHarness h = new EngineHarness(dir);
        h.api.listError = new ApiException(500, "{}", 0);
        DeliveryEngine engine = autoWithLog(h, new dev.rivoo.minecraft.core.fixtures.CapturingLog());
        assertThat(h.scheduler.activeRepeating()).isPositive();
        engine.stop();
        assertThat(h.scheduler.activeRepeating()).isZero();
        h.api.listError = null;
        h.clock.advance(java.time.Duration.ofMinutes(5));
        h.scheduler.runRepeating();
        assertThat(h.api.listCalls.get()).isEqualTo(1);
        assertThat(engine.mode()).isNull();
    }

    @Test void explicitModesSkipDetection() {
        EngineHarness h = new EngineHarness(dir);
        h.api.listError = new ApiException(500, "{}", 0);
        assertThat(h.start("fulfillments").mode()).isEqualTo(Mode.FULFILLMENTS);
        assertThat(new EngineHarness(dir.resolve("s")).start("status").mode()).isEqualTo(Mode.STATUS);
    }

    @Test void unknownModeWarnsAndFallsBackToAuto() {
        EngineHarness h = new EngineHarness(dir);
        dev.rivoo.minecraft.core.fixtures.CapturingLog log = new dev.rivoo.minecraft.core.fixtures.CapturingLog();
        h.api.listError = new ApiException(404, "{}", 0);
        DeliveryEngine engine = new DeliveryEngine(h.api, h.store, h.catalog, h.scheduler, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "fila", 600, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3)),
                log.logger, h.clock);
        engine.start();
        assertThat(engine.mode()).isEqualTo(Mode.STATUS); // auto detectou: 404 -> status
        assertThat(log.warnings).anyMatch(m -> m.contains("'fila'") && m.contains("auto"));
    }

    @Test void buyCreatesChargeWithExternalIdChannelAndPersists() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        BuyResult r = h.engine.buy(STEVE, "Steve", "vip").join();

        assertThat(r).isInstanceOf(BuyResult.Created.class);
        BuyResult.Created c = (BuyResult.Created) r;
        assertThat(c.product().name()).isEqualTo("VIP Mensal");
        assertThat(c.superseded()).isEmpty();
        assertThat(c.purchase().state).isEqualTo(State.PENDING);
        assertThat(h.store.get(c.purchase().chargeId)).isPresent();
        var call = h.api.creates.get(0);
        assertThat(call.amount()).isEqualTo(1990);
        assertThat(call.externalId()).isEqualTo("mc:" + STEVE + ":vip");
        assertThat(call.expiresIn()).isEqualTo(600);
        assertThat(call.channel()).isEqualTo("minecraft");
        assertThat(call.idempotencyKey()).hasSize(36);
    }

    @Test void statusModeCreatesChargeWithoutChannel() {
        EngineHarness h = new EngineHarness(dir);
        h.start("status");
        h.engine.buy(STEVE, "Steve", "kit").join();
        assertThat(h.api.creates.get(0).channel()).isNull();
    }

    @Test void unknownProduct() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        assertThat(h.engine.buy(STEVE, "Steve", "nope").join()).isEqualTo(new BuyResult.UnknownProduct("nope"));
        assertThat(h.api.creates).isEmpty();
    }

    @Test void openPixIsReturnedInsteadOfANewCharge() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        BuyResult.Created first = (BuyResult.Created) h.engine.buy(STEVE, "Steve", "vip").join();
        BuyResult again = h.engine.buy(STEVE, "Steve", "kit").join();
        assertThat(again).isInstanceOf(BuyResult.AlreadyPending.class);
        assertThat(((BuyResult.AlreadyPending) again).purchase()).isSameAs(first.purchase());
        assertThat(((BuyResult.AlreadyPending) again).product().sku()).isEqualTo("vip");
        assertThat(h.api.creates).hasSize(1);
    }

    @Test void pixInsideGraceIsSupersededByNewPurchase() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        BuyResult.Created first = (BuyResult.Created) h.engine.buy(STEVE, "Steve", "vip").join();
        h.clock.advance(java.time.Duration.ofSeconds(601)); // vencido, ainda na tolerância de 2 min
        h.api.expiresAt = h.clock.instant().plusSeconds(600);
        BuyResult.Created second = (BuyResult.Created) h.engine.buy(STEVE, "Steve", "vip").join();
        assertThat(second.superseded()).containsExactly(first.purchase());
    }

    @Test void apiFailureLeavesNoState() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        h.api.createError = new ApiException(400, "{\"message\":\"limite\"}", 0);
        BuyResult r = h.engine.buy(STEVE, "Steve", "vip").join();
        assertThat(r).isInstanceOf(BuyResult.Failed.class);
        assertThat(((BuyResult.Failed) r).message()).isEqualTo("Não foi possível gerar o Pix. Tente novamente.");
        assertThat(h.store.inState(State.PENDING)).isEmpty();
    }

    @Test void failedWriteOfNewPurchaseIsAFailedBuy() throws Exception {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        java.nio.file.Files.createDirectory(dir.resolve("purchases.json.tmp"));
        java.nio.file.Files.writeString(dir.resolve("purchases.json.tmp").resolve("x"), "x");
        BuyResult r = h.engine.buy(STEVE, "Steve", "vip").join();
        assertThat(r).isEqualTo(new BuyResult.Failed("Não foi possível gerar o Pix. Tente novamente."));
        assertThat(h.store.inState(State.PENDING)).isEmpty();
        assertThat(h.api.creates).hasSize(1); // a cobrança fica órfã e vence sem pagamento
    }

    @Test void buyBeforeModeIsDecidedIsRefused() {
        EngineHarness h = new EngineHarness(dir);
        // auto com a detecção ainda em andamento: o scheduler não roda o async da detecção
        h.engine = new DeliveryEngine(h.api, h.store, h.catalog, new dev.rivoo.minecraft.core.platform.Scheduler() {
            public void sync(Runnable t) { t.run(); }
            public void async(Runnable t) { }
            public Task repeatSync(Runnable t, java.time.Duration d) { return () -> { }; }
            public Task repeatAsync(Runnable t, java.time.Duration d) { return () -> { }; }
        }, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "auto", 600, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3)),
                java.util.logging.Logger.getLogger("t"), h.clock);
        h.engine.start();
        assertThat(h.engine.mode()).isNull();
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join())
                .isEqualTo(new BuyResult.Failed("A loja está iniciando. Tente em alguns segundos."));
        assertThat(h.api.creates).isEmpty();
    }

    @Timeout(10)
    @Test void secondBuyWhileFirstIsInFlightIsRefused() throws Exception {
        EngineHarness h = new EngineHarness(dir);
        // async em outra thread para a primeira compra ficar presa na API
        h.engine = new DeliveryEngine(h.api, h.store, h.catalog, new dev.rivoo.minecraft.core.platform.Scheduler() {
            public void sync(Runnable t) { t.run(); }
            public void async(Runnable t) { new Thread(t).start(); }
            public Task repeatSync(Runnable t, java.time.Duration d) { return () -> { }; }
            public Task repeatAsync(Runnable t, java.time.Duration d) { return () -> { }; }
        }, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "fulfillments", 600, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3)),
                java.util.logging.Logger.getLogger("t"), h.clock);
        h.engine.start();
        h.api.holdCreates = true;
        CompletableFuture<BuyResult> first = h.engine.buy(STEVE, "Steve", "vip");
        while (h.api.creates.isEmpty()) Thread.onSpinWait();
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join())
                .isEqualTo(new BuyResult.Failed("Já estamos gerando seu Pix."));
        var alex = h.engine.buy(ALEX, "Alex", "vip"); // outro jogador não é bloqueado
        h.api.release();
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(BuyResult.Created.class);
        assertThat(alex.get(5, TimeUnit.SECONDS)).isInstanceOf(BuyResult.Created.class);
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join()).isInstanceOf(BuyResult.AlreadyPending.class);
    }

    @Test void simulateCallsApi() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        h.engine.simulate("charge_9").join();
        assertThat(h.api.simulated).containsExactly("charge_9");
    }

    @Test void schedulingFailureReleasesInFlightGuard() {
        EngineHarness h = new EngineHarness(dir);
        boolean[] fail = {false};
        h.engine = new DeliveryEngine(h.api, h.store, h.catalog, new dev.rivoo.minecraft.core.platform.Scheduler() {
            public void sync(Runnable t) { t.run(); }
            public void async(Runnable t) { if (fail[0]) throw new IllegalStateException("disabled"); t.run(); }
            public Task repeatSync(Runnable t, java.time.Duration d) { return () -> { }; }
            public Task repeatAsync(Runnable t, java.time.Duration d) { return () -> { }; }
        }, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "fulfillments", 600, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3)),
                java.util.logging.Logger.getLogger("t"), h.clock);
        h.engine.start();
        fail[0] = true;
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join())
                .isEqualTo(new BuyResult.Failed("Não foi possível gerar o Pix. Tente novamente."));
        fail[0] = false;
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join()).isInstanceOf(BuyResult.Created.class);
    }
}
