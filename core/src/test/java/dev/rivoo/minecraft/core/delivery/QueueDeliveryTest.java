package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.rivoo.minecraft.core.fixtures.ManualScheduler;
import dev.rivoo.minecraft.core.platform.Scheduler;

import dev.rivoo.minecraft.core.api.Fulfillment;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.platform.Players;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static dev.rivoo.minecraft.core.fixtures.EngineHarness.ALEX;
import static dev.rivoo.minecraft.core.fixtures.EngineHarness.STEVE;
import static org.assertj.core.api.Assertions.assertThat;

class QueueDeliveryTest {
    @TempDir Path dir;

    EngineHarness started() {
        EngineHarness h = new EngineHarness(dir);
        h.start("fulfillments");
        return h;
    }

    Purchase buy(EngineHarness h, UUID player, String name, String sku) {
        return ((BuyResult.Created) h.engine.buy(player, name, sku).join()).purchase();
    }

    @Test void paidPurchaseIsDeliveredOnceAndAcked() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);

        h.scheduler.runRepeating();
        h.scheduler.runRepeating();

        assertThat(h.commands.ran).containsExactly("give Steve diamond 5", "lp user " + STEVE + " parent add vip");
        assertThat(h.view.events).containsExactly("paid:" + p.chargeId + ":VIP Mensal");
        assertThat(p.state).isEqualTo(State.DELIVERED);
        assertThat(h.api.acks).containsExactly(List.of(p.chargeId));
    }

    @Test void offlinePlayerIsNotAckedAndGetsItOnJoin() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.players.offline(STEVE);
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);

        h.scheduler.runRepeating();
        assertThat(h.commands.ran).isEmpty();
        assertThat(h.api.acks).isEmpty();
        assertThat(p.state).isEqualTo(State.PENDING);

        h.players.online(STEVE, "Steve");
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).hasSize(2);
        assertThat(h.api.acks).containsExactly(List.of(p.chargeId));
    }

    @Test void deliveredButNotAckedIsReAckedWithoutRunningCommandsAgain() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);
        h.api.ackError = new IOException("timeout");
        h.scheduler.runRepeating(); // entrega, ack falha
        h.api.ackError = null;
        h.scheduler.runRepeating(); // volta da fila: só ack
        assertThat(h.commands.ran).hasSize(2);
        assertThat(h.api.acks).hasSize(2);
        assertThat(h.api.queue).isEmpty();
    }

    @Test void unknownChargeIsRecoveredFromExternalId() {
        EngineHarness h = started();
        h.players.known(ALEX, "Alex").online(ALEX, "Alex");
        h.api.pay("charge_lost", "mc:" + ALEX + ":kit", 150);
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).containsExactly("give Alex bread 16");
        assertThat(h.store.get("charge_lost")).map(p -> p.state).contains(State.DELIVERED);
        assertThat(h.api.acks).containsExactly(List.of("charge_lost"));
    }

    @Test void foreignExternalIdIsIgnoredAndNotAcked() {
        EngineHarness h = started();
        h.api.pay("charge_web", "loja-web-123", 500);
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).isEmpty();
        assertThat(h.api.acks).isEmpty();
    }

    @Test void productRemovedFromCatalogStillDeliversAndAcks() {
        EngineHarness h = started();
        h.api.pay("charge_old", "mc:" + STEVE + ":removed", 990);
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).isEmpty();
        assertThat(h.view.events).containsExactly("paid:charge_old:removed");
        assertThat(h.api.acks).containsExactly(List.of("charge_old"));
    }

    @Test void placeholderUsesCurrentNameWhenKnown() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "kit");
        h.players.online(STEVE, "SteveRenamed");
        h.api.pay(p.chargeId, "mc:" + STEVE + ":kit", 150);
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).containsExactly("give SteveRenamed bread 16");
    }

    @Test void rateLimitPausesPolling() {
        EngineHarness h = started();
        h.api.listError = new ApiException(429, "{}", 30);
        h.scheduler.runRepeating();
        h.api.listError = null;
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).isEmpty(); // ainda em backoff
        h.clock.advance(Duration.ofSeconds(31));
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).hasSize(2);
    }

    @Test void networkErrorDoesNotStopPolling() {
        EngineHarness h = started();
        h.api.listError = new IOException("reset");
        h.scheduler.runRepeating();
        h.api.listError = null;
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void pendingExpiresLocallyAfterGraceAndLatePaymentStillDelivers() {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.clock.advance(Duration.ofSeconds(600 + 119));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.PENDING);
        h.clock.advance(Duration.ofSeconds(2));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.EXPIRED);
        assertThat(h.view.events).containsExactly("expired:" + p.chargeId + ":false");

        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void expiryOfOldPixIsSilentWhenANewerOneIsOpen() {
        EngineHarness h = started();
        Purchase old = buy(h, STEVE, "Steve", "vip");
        h.clock.advance(Duration.ofSeconds(601));
        h.api.expiresAt = h.clock.instant().plusSeconds(600);
        buy(h, STEVE, "Steve", "vip");
        h.clock.advance(Duration.ofSeconds(120));
        h.scheduler.runRepeating();
        assertThat(h.view.events).contains("expired:" + old.chargeId + ":true");
    }

    @Test void snapshotAndStopCancelTimers() {
        EngineHarness h = started();
        buy(h, STEVE, "Steve", "vip");
        Snapshot s = h.engine.snapshot();
        assertThat(s.mode()).isEqualTo(Mode.FULFILLMENTS);
        assertThat(s.channel()).isEqualTo("minecraft");
        assertThat(s.pending()).hasSize(1);
        assertThat(s.paid()).isEmpty();
        assertThat(h.scheduler.activeRepeating()).isPositive();
        h.engine.stop();
        assertThat(h.scheduler.activeRepeating()).isZero();
    }

    @Test void failedDiskWriteDoesNotAckNorRunCommands() throws IOException {
        EngineHarness h = started();
        Purchase p = buy(h, STEVE, "Steve", "vip");
        Files.createDirectory(dir.resolve("purchases.json.tmp"));
        h.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);

        h.scheduler.runRepeating();

        assertThat(h.commands.ran).isEmpty();
        assertThat(h.api.acks).isEmpty();
        assertThat(p.state).isEqualTo(State.PENDING);

        Files.delete(dir.resolve("purchases.json.tmp"));
        h.scheduler.runRepeating();

        assertThat(h.commands.ran).hasSize(2);
        assertThat(h.api.acks).containsExactly(List.of(p.chargeId));
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void throwingCommandDoesNotBlockPollingNorAck() {
        List<Runnable> syncTasks = new ArrayList<>();
        List<Runnable> asyncTasks = new ArrayList<>();
        ManualScheduler repeats = new ManualScheduler();
        Scheduler scheduler = new Scheduler() {
            @Override public void async(Runnable task) { asyncTasks.add(task); }
            @Override public void sync(Runnable task) { syncTasks.add(task); }
            @Override public Task repeatSync(Runnable task, Duration every) { return repeats.repeatSync(task, every); }
            @Override public Task repeatAsync(Runnable task, Duration every) { return repeats.repeatAsync(task, every); }
        };
        EngineHarness h = new EngineHarness(dir);
        List<String> ran = new ArrayList<>();
        DeliveryEngine engine = new DeliveryEngine(h.api, h.store, h.catalog, scheduler, h.players,
                cmd -> { ran.add(cmd); if (ran.size() == 1) throw new IllegalStateException("boom"); },
                h.view, new DeliveryConfig("minecraft", "fulfillments", 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                Logger.getLogger("test"), h.clock);
        engine.start();
        h.api.pay("charge_x", "mc:" + STEVE + ":vip", 1990);

        repeats.runRepeating(); // poll: lista e agenda sync
        syncTasks.remove(0).run(); // entrega: 1o comando lança
        assertThat(ran).hasSize(2); // o 2o comando rodou mesmo assim
        asyncTasks.remove(0).run(); // ack
        assertThat(h.api.acks).containsExactly(List.of("charge_x"));
    }

    @Test void pollerKeepsPollingWhenDeliveryThrows() {
        List<Runnable> syncTasks = new ArrayList<>();
        ManualScheduler repeats = new ManualScheduler();
        Scheduler scheduler = new Scheduler() {
            @Override public void async(Runnable task) { task.run(); }
            @Override public void sync(Runnable task) { syncTasks.add(task); }
            @Override public Task repeatSync(Runnable task, Duration every) { return repeats.repeatSync(task, every); }
            @Override public Task repeatAsync(Runnable task, Duration every) { return repeats.repeatAsync(task, every); }
        };
        EngineHarness h = new EngineHarness(dir);
        int[] lists = {0};
        RivooApi counting = (RivooApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RivooApi.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("listFulfillments")) lists[0]++;
                    try { return m.invoke(h.api, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        Players throwing = new Players() {
            @Override public boolean isOnline(UUID player) { throw new IllegalStateException("boom"); }
            @Override public Optional<String> name(UUID player) { return Optional.of("Steve"); }
        };
        DeliveryEngine engine = new DeliveryEngine(counting, h.store, h.catalog, scheduler, throwing, h.commands, h.view,
                new DeliveryConfig("minecraft", "fulfillments", 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                Logger.getLogger("test"), h.clock);
        engine.start();
        h.api.queue.add(new Fulfillment("c1", "mc:" + STEVE + ":vip", 1990));

        repeats.runRepeating();
        assertThat(lists[0]).isEqualTo(1);
        syncTasks.remove(0).run(); // deliverFromQueue lança
        repeats.runRepeating();
        assertThat(lists[0]).isEqualTo(2); // busy foi liberado
    }

    @Test void detectionCompletingAfterStopSchedulesNothing() {
        List<Runnable> asyncTasks = new ArrayList<>();
        List<Runnable> syncTasks = new ArrayList<>();
        ManualScheduler repeats = new ManualScheduler();
        Scheduler scheduler = new Scheduler() {
            @Override public void async(Runnable task) { asyncTasks.add(task); }
            @Override public void sync(Runnable task) { syncTasks.add(task); }
            @Override public Task repeatSync(Runnable task, Duration every) { return repeats.repeatSync(task, every); }
            @Override public Task repeatAsync(Runnable task, Duration every) { return repeats.repeatAsync(task, every); }
        };
        EngineHarness h = new EngineHarness(dir);
        DeliveryEngine engine = new DeliveryEngine(h.api, h.store, h.catalog, scheduler, h.players, h.commands, h.view,
                new DeliveryConfig("minecraft", "auto", 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                Logger.getLogger("test"), h.clock);
        engine.start();
        engine.stop();
        asyncTasks.forEach(Runnable::run);
        syncTasks.forEach(Runnable::run);
        assertThat(repeats.repeating).isEmpty();
        assertThat(engine.mode()).isNull();
    }

    /** Pago no modo status com o jogador offline; servidor reinicia já no modo fila. */
    Purchase paidOfflineInStatusMode() {
        EngineHarness h = new EngineHarness(dir);
        h.start("status");
        Purchase p = buy(h, STEVE, "Steve", "vip");
        h.players.offline(STEVE);
        h.api.statuses.put(p.chargeId, new dev.rivoo.minecraft.core.api.ChargeStatus("COMPLETED", "PAID"));
        h.scheduler.runRepeating();
        assertThat(p.state).isEqualTo(State.PAID);
        h.engine.stop();
        return p;
    }

    @Test void paidInStatusModeIsDeliveredOnJoinAfterRestartInQueueMode() {
        Purchase p = paidOfflineInStatusMode();
        EngineHarness restarted = new EngineHarness(dir);
        restarted.players.offline(STEVE);
        restarted.start("fulfillments");
        assertThat(restarted.commands.ran).isEmpty();

        restarted.players.online(STEVE, "Steve");
        restarted.engine.onJoin(STEVE);
        assertThat(restarted.commands.ran).hasSize(2);
        assertThat(restarted.store.get(p.chargeId)).map(x -> x.state).contains(State.DELIVERED);

        // a entrada da fila chega depois: só ack, sem rodar os comandos de novo
        restarted.api.pay(p.chargeId, "mc:" + STEVE + ":vip", 1990);
        restarted.scheduler.runRepeating();
        assertThat(restarted.commands.ran).hasSize(2);
        assertThat(restarted.api.acks).containsExactly(List.of(p.chargeId));
    }

    @Test void paidInStatusModeIsDeliveredOnQueueModeStartWhenOnline() {
        Purchase p = paidOfflineInStatusMode();
        EngineHarness restarted = new EngineHarness(dir);
        restarted.start("fulfillments");
        assertThat(restarted.commands.ran).hasSize(2);
        assertThat(restarted.store.get(p.chargeId)).map(x -> x.state).contains(State.DELIVERED);
    }

    @Test void onJoinInQueueModeDeliversRecoveredPaidPurchase() {
        EngineHarness h = started();
        h.players.known(ALEX, "Alex");
        h.api.pay("charge_lost", "mc:" + ALEX + ":kit", 150);
        h.scheduler.runRepeating(); // Alex offline: recuperada como PAID, sem ack
        assertThat(h.store.get("charge_lost")).map(x -> x.state).contains(State.PAID);
        assertThat(h.api.acks).isEmpty();

        h.players.online(ALEX, "Alex");
        h.engine.onJoin(ALEX);
        assertThat(h.commands.ran).containsExactly("give Alex bread 16");
        h.scheduler.runRepeating();
        assertThat(h.commands.ran).hasSize(1);
        assertThat(h.api.acks).containsExactly(List.of("charge_lost"));
    }
}
