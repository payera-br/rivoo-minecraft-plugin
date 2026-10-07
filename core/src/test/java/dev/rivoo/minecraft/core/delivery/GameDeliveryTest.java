package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.core.fixtures.FakeApi;
import dev.rivoo.minecraft.core.fixtures.ManualScheduler;
import dev.rivoo.minecraft.core.platform.Scheduler;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.rivoo.minecraft.core.fixtures.EngineHarness.ALEX;
import static dev.rivoo.minecraft.core.fixtures.EngineHarness.STEVE;
import static org.assertj.core.api.Assertions.assertThat;

class GameDeliveryTest {
    @TempDir Path dir;

    EngineHarness started() {
        EngineHarness h = new EngineHarness(dir);
        h.api.gameProducts.add(new Product("vip", "VIP", 1990, List.of()));
        h.api.gameProducts.add(new Product("kit", "Kit", 150, List.of()));
        h.start("game");
        return h;
    }

    Purchase buy(EngineHarness h, UUID player, String name, String sku) {
        return ((BuyResult.Created) h.engine.buy(player, name, sku).join()).purchase();
    }

    static CommandRun run(String id, String chargeId, String trigger, String command, UUID player, boolean requireOnline) {
        return new CommandRun(id, chargeId, trigger, command, "mc:" + player, "Steve", requireOnline);
    }

    /** Só a fila de comandos (a recarga do catálogo é outra tarefa repetida). */
    void pollCommands(EngineHarness h) {
        h.scheduler.repeating.stream()
                .filter(r -> r.every().equals(Duration.ofSeconds(5)) && r.async() && !r.cancelled()[0])
                .forEach(r -> r.task().run());
    }

    @Test void catalogComesFromApiAndReplacesConfigProducts() {
        EngineHarness h = started();
        assertThat(h.engine.mode()).isEqualTo(Mode.GAME);
        assertThat(h.catalog.skus()).containsExactly("vip", "kit");
        assertThat(h.catalog.find("vip")).map(Product::name).contains("VIP");
        assertThat(h.catalog.find("vip").orElseThrow().commands()).isEmpty();
    }

    @Test void catalogIsReloadedEvery60sAndKeptOnFailure() {
        EngineHarness h = started();
        ManualScheduler.Repeating reload = h.scheduler.repeating.stream()
                .filter(r -> r.every().equals(Duration.ofSeconds(60))).findFirst().orElseThrow();
        assertThat(reload.async()).isTrue();

        h.api.gameProducts.add(new Product("rank", "Rank", 5000, List.of()));
        reload.task().run();
        assertThat(h.catalog.skus()).containsExactly("vip", "kit", "rank");

        h.api.catalogError = new IOException("down");
        reload.task().run();
        assertThat(h.catalog.skus()).containsExactly("vip", "kit", "rank");
        assertThat(h.api.catalogCalls.get()).isEqualTo(3);
    }

    @Test void buyBeforeCatalogLoadsSaysStarting() {
        EngineHarness h = new EngineHarness(dir);
        h.api.catalogError = new IOException("down");
        h.start("game");
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join()).isEqualTo(new BuyResult.Failed(DeliveryEngine.STARTING));
        assertThat(h.api.creates).isEmpty();
        assertThat(h.api.gamePurchases).isEmpty();
    }

    @Test void buyCallsGamePurchasesWithSkuAndPlayerAndNoPrice() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        assertThat(h.api.creates).as("nunca usa /charge/pix no modo game").isEmpty();
        assertThat(h.api.gamePurchases).hasSize(1);
        var call = h.api.gamePurchases.get(0);
        assertThat(call.channel()).isEqualTo("minecraft");
        assertThat(call.sku()).isEqualTo("vip");
        assertThat(call.playerId()).isEqualTo(STEVE);
        assertThat(call.playerName()).isEqualTo("Steve");
        assertThat(call.idempotencyKey()).isNotBlank();
        assertThat(p.amount).isEqualTo(1990);
        assertThat(p.sku).isEqualTo("vip");
        assertThat(h.engine.buy(ALEX, "Alex", "nope").join()).isEqualTo(new BuyResult.UnknownProduct("nope"));
        assertThat(h.api.gamePurchases).hasSize(1);
    }

    @Test void localPurchaseAndUiUseThePriceTheApiCharged() {
        EngineHarness h = started();
        // Catálogo local ainda diz 1990; a Rivoo já cobra 2490.
        h.api.gamePrices.put("vip", 2490);
        BuyResult.Created created = (BuyResult.Created) h.engine.buy(STEVE, "Steve", "vip").join();
        assertThat(created.purchase().amount).isEqualTo(2490);
        assertThat(created.product().price()).isEqualTo(2490);
        assertThat(created.product().name()).isEqualTo("VIP");
    }

    @Test void gamePurchaseFailureIsCreateFailed() {
        EngineHarness h = started();
        h.api.gamePurchaseError = new ApiException(404, "Pacote não encontrado", 0);
        assertThat(h.engine.buy(STEVE, "Steve", "vip").join()).isEqualTo(new BuyResult.Failed(DeliveryEngine.CREATE_FAILED));
        assertThat(h.engine.snapshot().pending()).isEmpty();
    }

    @Test void requireOnlineWaitsForThePlayerWithoutAck() {
        EngineHarness h = started();
        h.players.offline(STEVE);
        h.api.commandQueue.add(run("grun_1", "charge_x", "PURCHASED", "give Steve bread 16", STEVE, true));
        h.api.commandQueue.add(run("grun_2", "charge_x", "PURCHASED", "say Steve comprou o kit", STEVE, false));

        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("say Steve comprou o kit");
        assertThat(h.api.commandAcks).containsExactly(List.of("grun_2"));

        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("say Steve comprou o kit");
        assertThat(h.api.commandAcks).hasSize(1);

        h.players.online(STEVE, "Steve");
        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("say Steve comprou o kit", "give Steve bread 16");
        assertThat(h.api.commandAcks).containsExactly(List.of("grun_2"), List.of("grun_1"));
        assertThat(h.api.commandQueue).isEmpty();
    }

    @Test void invalidPlayerRefCountsAsOffline() {
        EngineHarness h = started();
        h.api.commandQueue.add(new CommandRun("grun_1", null, "REMOVED", "say x", "foo", null, true));
        h.api.commandQueue.add(new CommandRun("grun_2", null, "REMOVED", "say y", "mc:not-uuid", null, true));
        h.api.commandQueue.add(new CommandRun("grun_3", null, "REMOVED", "say z", null, null, false));
        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("say z");
        assertThat(h.api.commandAcks).containsExactly(List.of("grun_3"));
    }

    @Test void purchasedMarksDeliveredAndShowsPaidOncePerCharge() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.commandQueue.add(run("grun_1", p.chargeId, "PURCHASED", "give Steve diamond 5", STEVE, true));
        h.api.commandQueue.add(run("grun_2", p.chargeId, "PURCHASED", "say Steve agora é VIP!", STEVE, true));

        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("give Steve diamond 5", "say Steve agora é VIP!");
        assertThat(p.state).isEqualTo(State.DELIVERED);
        assertThat(h.view.events).containsExactly("paid:" + p.chargeId + ":VIP");
        assertThat(h.api.commandAcks).containsExactly(List.of("grun_1", "grun_2"));
    }

    @Test void purchasedWithPlayerOfflineHidesTheBarInsteadOfPaid() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "kit");
        h.players.offline(STEVE);
        h.api.commandQueue.add(run("grun_1", p.chargeId, "PURCHASED", "say Steve comprou o kit", STEVE, false));
        pollCommands(h);
        assertThat(p.state).isEqualTo(State.DELIVERED);
        assertThat(h.view.events).containsExactly("offline:" + p.chargeId);
    }

    @Test void removedAndRefundedOnlyRunTheCommand() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.commandQueue.add(run("grun_1", p.chargeId, "REMOVED", "say Steve perdeu o VIP (expirou)", STEVE, false));
        h.api.commandQueue.add(run("grun_2", "charge_other", "REFUNDED", "say Steve teve a compra estornada", STEVE, false));
        h.api.commandQueue.add(run("grun_3", "charge_unknown", "PURCHASED", "say foreign", STEVE, false));
        pollCommands(h);
        assertThat(h.commands.ran).hasSize(3);
        assertThat(h.view.events).isEmpty();
        assertThat(p.state).isEqualTo(State.PENDING);
    }

    @Test void deliveredPurchaseIsNotShownAgain() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.store.markDelivered(p);
        h.api.commandQueue.add(run("grun_1", p.chargeId, "PURCHASED", "give Steve diamond 5", STEVE, true));
        pollCommands(h);
        assertThat(h.view.events).isEmpty();
    }

    @Test void failingCommandDoesNotBlockOthersNorTheAck() {
        EngineHarness h = started();
        List<String> ran = new ArrayList<>();
        CapturingLog log = new CapturingLog();
        DeliveryEngine engine = new DeliveryEngine(h.api, h.store, h.catalog, h.scheduler, h.players, cmd -> {
            if (cmd.contains("boom")) throw new IllegalStateException("boom");
            ran.add(cmd);
        }, h.view, new DeliveryConfig("minecraft", "game", 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                log.logger, h.clock);
        List<String> ack = engine.runCommands(List.of(
                run("grun_1", null, "PURCHASED", "boom", STEVE, true),
                run("grun_2", null, "PURCHASED", "say ok", STEVE, true)));
        assertThat(ran).containsExactly("say ok");
        assertThat(ack).containsExactly("grun_1", "grun_2");
        assertThat(log.warnings).anyMatch(w -> w.contains("grun_1"));
    }

    @Test void runExecutedButNotAckedIsOnlyAckedAgain() {
        EngineHarness h = started();
        h.api.commandQueue.add(run("grun_1", null, "PURCHASED", "give Steve diamond 5", STEVE, true));
        h.api.commandAckError = new IOException("timeout");
        pollCommands(h);
        h.api.commandAckError = null;
        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("give Steve diamond 5");
        assertThat(h.api.commandAcks).hasSize(2);
        assertThat(h.api.commandQueue).isEmpty();
    }

    @Test void pollerIsNeverLeftBusy() {
        EngineHarness h = started();
        CapturingLog log = new CapturingLog();
        List<Runnable> deferred = new ArrayList<>();
        Scheduler deferring = new Scheduler() {
            @Override public void sync(Runnable task) { task.run(); }
            @Override public void async(Runnable task) { deferred.add(task); }
            @Override public Task repeatSync(Runnable task, Duration every) { return () -> { }; }
            @Override public Task repeatAsync(Runnable task, Duration every) { return () -> { }; }
        };
        boolean[] throwInRun = {false};
        CommandPoller poller = new CommandPoller(h.api, "minecraft", deferring, runs -> {
            if (throwInRun[0]) throw new IllegalStateException("x");
            return runs.stream().map(CommandRun::id).toList();
        }, acked -> { }, log.logger, Clock.systemUTC(), () -> { });

        // vazio
        poller.run();
        assertThat(poller.busy()).isFalse();
        // erro de rede / http / 429
        h.api.commandsError = new IOException("reset");
        poller.run();
        assertThat(poller.busy()).isFalse();
        h.api.commandsError = new ApiException(500, "{}", 0);
        poller.run();
        assertThat(poller.busy()).isFalse();
        h.api.commandsError = new ApiException(429, "{}", 0);
        poller.run();
        assertThat(poller.busy()).isFalse();
        assertThat(log.warnings).anyMatch(w -> w.contains("429"));
    }

    @Test void pollerReleasesBusyAfterRunFailureAckAndSchedulerFailure() {
        EngineHarness h = started();
        CapturingLog log = new CapturingLog();
        List<Runnable> deferred = new ArrayList<>();
        boolean[] asyncFails = {false};
        Scheduler deferring = new Scheduler() {
            @Override public void sync(Runnable task) { task.run(); }
            @Override public void async(Runnable task) {
                if (asyncFails[0]) throw new IllegalStateException("disabled");
                deferred.add(task);
            }
            @Override public Task repeatSync(Runnable task, Duration every) { return () -> { }; }
            @Override public Task repeatAsync(Runnable task, Duration every) { return () -> { }; }
        };
        boolean[] throwInRun = {true};
        List<List<String>> acked = new ArrayList<>();
        CommandPoller poller = new CommandPoller(h.api, "minecraft", deferring, runs -> {
            if (throwInRun[0]) throw new IllegalStateException("x");
            return runs.stream().map(CommandRun::id).toList();
        }, acked::add, log.logger, Clock.systemUTC(), () -> { });
        h.api.commandQueue.add(run("grun_1", null, "PURCHASED", "say a", STEVE, true));

        poller.run(); // runSync lança
        assertThat(poller.busy()).isFalse();

        throwInRun[0] = false;
        poller.run(); // ack agendado: busy até o ack terminar
        assertThat(poller.busy()).isTrue();
        poller.run(); // ignorado enquanto busy
        assertThat(h.api.commandCalls.get()).isEqualTo(2);
        deferred.remove(0).run();
        assertThat(poller.busy()).isFalse();
        assertThat(acked).containsExactly(List.of("grun_1"));

        h.api.commandQueue.add(run("grun_2", null, "PURCHASED", "say b", STEVE, true));
        asyncFails[0] = true;
        poller.run(); // agendar o ack falha
        assertThat(poller.busy()).isFalse();

        Scheduler syncFails = new Scheduler() {
            @Override public void sync(Runnable task) { throw new IllegalStateException("disabled"); }
            @Override public void async(Runnable task) { task.run(); }
            @Override public Task repeatSync(Runnable task, Duration every) { return () -> { }; }
            @Override public Task repeatAsync(Runnable task, Duration every) { return () -> { }; }
        };
        CommandPoller p2 = new CommandPoller(h.api, "minecraft", syncFails, runs -> List.of(), a -> { },
                log.logger, Clock.systemUTC(), () -> { });
        p2.run();
        assertThat(p2.busy()).isFalse();
    }

    @Test void rateLimitPausesCommandPolling() {
        EngineHarness h = started();
        h.api.commandsError = new ApiException(429, "{}", 30);
        pollCommands(h);
        h.api.commandsError = null;
        h.api.commandQueue.add(run("grun_1", null, "PURCHASED", "say a", STEVE, true));
        pollCommands(h);
        assertThat(h.commands.ran).isEmpty();
        h.clock.advance(Duration.ofSeconds(31));
        pollCommands(h);
        assertThat(h.commands.ran).containsExactly("say a");
    }

    @Test void localPixStillExpiresInGameMode() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.clock.advance(Duration.ofMinutes(13));
        h.engine.expireLocally();
        assertThat(p.state).isEqualTo(State.EXPIRED);
    }

    @Test void stopCancelsGameTasks() {
        EngineHarness h = started();
        h.engine.stop();
        assertThat(h.scheduler.activeRepeating()).isZero();
        int calls = h.api.catalogCalls.get();
        h.engine.reloadCatalog();
        assertThat(h.api.catalogCalls.get()).isEqualTo(calls);
    }

    @Test void serverKeyModeSendsNoChannel() {
        EngineHarness h = new EngineHarness(dir);
        h.api.gameProducts.add(new Product("vip", "VIP", 1990, List.of()));
        h.startServerKey(); // catálogo carrega no start (async imediato)
        h.engine.buy(EngineHarness.STEVE, "Steve", "vip").join();
        assertThat(h.api.gamePurchases).singleElement().extracting(FakeApi.GamePurchaseCall::channel).isNull();
        assertThat(h.engine.snapshot().channel()).isNull();
    }

    @Test void simulateUsesTheGameRouteWithAServerKey() {
        EngineHarness h = new EngineHarness(dir);
        h.startServerKey();
        h.engine.simulate("charge_1").join();
        assertThat(h.api.simulatedGame).containsExactly("charge_1");
        assertThat(h.api.simulated).isEmpty();
    }

    @Test void legacyGameModeStillSimulatesThroughCharge() {
        EngineHarness h = new EngineHarness(dir);
        h.start("game");
        h.engine.simulate("charge_1").join();
        assertThat(h.api.simulated).containsExactly("charge_1");
        assertThat(h.api.simulatedGame).isEmpty();
    }

    @Test void revokedKeyWarnsOnceAndStopsPolling() {
        EngineHarness h = new EngineHarness(dir);
        h.api.commandsError = new ApiException(401, "{}", 0);
        h.startServerKey();
        for (int i = 0; i < 5; i++) h.scheduler.runRepeating();
        assertThat(h.log.warnings).filteredOn(DeliveryEngine.REVOKED_MESSAGE::equals).hasSize(1);
        assertThat(h.api.commandCalls.get()).isEqualTo(1);
        assertThat(h.engine.revoked()).isTrue();
    }

    @Test void catalog403AlsoCountsAsRevoked() {
        EngineHarness h = new EngineHarness(dir);
        h.api.catalogError = new ApiException(403, "{}", 0);
        h.startServerKey();
        assertThat(h.engine.revoked()).isTrue();
        assertThat(h.log.warnings).filteredOn(DeliveryEngine.REVOKED_MESSAGE::equals).hasSize(1);
    }

    @Test void otherApiErrorsDoNotRevoke() {
        EngineHarness h = new EngineHarness(dir);
        h.api.commandsError = new ApiException(500, "{}", 0);
        h.startServerKey();
        h.scheduler.runRepeating();
        h.scheduler.runRepeating();
        assertThat(h.engine.revoked()).isFalse();
        assertThat(h.api.commandCalls.get()).isEqualTo(2);
    }

    @Test void revocationNotifiesTheListenerOnce() {
        EngineHarness h = new EngineHarness(dir);
        var engine = h.build("game");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        engine.onRevoked(calls::incrementAndGet);
        engine.markRevoked();
        engine.markRevoked();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(h.log.warnings).containsExactly(DeliveryEngine.REVOKED_MESSAGE);
    }

    @Test void aStoppedEngineDoesNotRestartPairing() {
        EngineHarness h = new EngineHarness(dir);
        var engine = h.build("game");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        engine.onRevoked(calls::incrementAndGet);
        engine.stop();
        engine.markRevoked();        // 401 de uma requisição que já estava no ar quando o motor foi trocado
        assertThat(calls.get()).isZero();
    }
}
