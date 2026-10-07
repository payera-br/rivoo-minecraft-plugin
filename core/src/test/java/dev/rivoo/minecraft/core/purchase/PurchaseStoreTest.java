package dev.rivoo.minecraft.core.purchase;

import dev.rivoo.minecraft.core.api.Charge;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseStoreTest {
    static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    @TempDir Path dir;
    MutableClock clock;
    PurchaseStore store;

    @BeforeEach void setUp() {
        clock = new MutableClock(T0);
        store = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("test"), clock);
        store.load();
    }

    Purchase buy(String id, Instant expiresAt) {
        Purchase p = store.newPurchase(new Charge(id, "br-" + id, "url-" + id, expiresAt), STEVE, "Steve", "vip", 1990);
        store.add(p);
        return p;
    }

    @Test void newPurchaseIsPendingAndPersistsRoundTrip() {
        Purchase p = buy("c1", T0.plusSeconds(600));
        store.setMapId(p, 42);

        PurchaseStore reloaded = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"), clock);
        reloaded.load();
        Purchase r = reloaded.get("c1").orElseThrow();
        assertThat(r.state).isEqualTo(State.PENDING);
        assertThat(r.playerId).isEqualTo(STEVE);
        assertThat(r.playerName).isEqualTo("Steve");
        assertThat(r.sku).isEqualTo("vip");
        assertThat(r.amount).isEqualTo(1990);
        assertThat(r.brCode).isEqualTo("br-c1");
        assertThat(r.url).isEqualTo("url-c1");
        assertThat(r.createdAt).isEqualTo(T0);
        assertThat(r.expiresAt).isEqualTo(T0.plusSeconds(600));
        assertThat(r.mapId).isEqualTo(42);
        assertThat(reloaded.byMapId(42)).contains(r);
    }

    @Test void transitionIsCompareAndSet() {
        Purchase p = buy("c1", T0.plusSeconds(600));
        assertThat(store.transition(p, State.PENDING, State.PAID)).isTrue();
        assertThat(store.transition(p, State.PENDING, State.PAID)).isFalse();
        assertThat(store.markDelivered(p)).isTrue();
        assertThat(store.markDelivered(p)).isFalse();
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void inStateIsSortedByCreation() {
        buy("c2", T0.plusSeconds(600));
        clock.advance(Duration.ofSeconds(1));
        buy("c3", T0.plusSeconds(600));
        assertThat(store.inState(State.PENDING)).extracting(p -> p.chargeId).containsExactly("c2", "c3");
    }

    @Test void latestPendingIgnoresExpiredAndOtherPlayers() {
        buy("old", T0.plusSeconds(60));
        clock.advance(Duration.ofSeconds(1));
        buy("new", T0.plusSeconds(600));
        assertThat(store.latestPendingOf(STEVE)).map(p -> p.chargeId).contains("new");
        clock.advance(Duration.ofSeconds(700));
        assertThat(store.latestPendingOf(STEVE)).isEmpty();
        assertThat(store.latestPendingOf(UUID.randomUUID())).isEmpty();
    }

    @Test void recoverCreatesPaidPurchaseFromExternalId() {
        Purchase p = store.recover("c9", ExternalId.of(STEVE, "kit"), 150, "Steve");
        assertThat(p.state).isEqualTo(State.PAID);
        assertThat(p.sku).isEqualTo("kit");
        assertThat(store.get("c9")).contains(p);
    }

    @Test void addAllSkipsExistingIds() {
        Purchase p = buy("c1", T0.plusSeconds(600));
        store.addAll(List.of(new Purchase("c1", STEVE, "X", "kit", 1, "", "", T0, T0, State.EXPIRED, null),
                new Purchase("c2", STEVE, "Steve", "kit", 150, "b", "u", T0, T0, State.PENDING, null)));
        assertThat(store.get("c1")).contains(p);
        assertThat(store.get("c2")).isPresent();
    }

    @Test void addAllReturnsFalseWhenWriteFails() throws Exception {
        Files.createDirectory(dir.resolve("purchases.json.tmp"));
        Files.writeString(dir.resolve("purchases.json.tmp").resolve("x"), "x");
        assertThat(store.addAll(List.of(
                new Purchase("c2", STEVE, "Steve", "kit", 150, "b", "u", T0, T0, State.PENDING, null)))).isFalse();
    }

    @Test void addReturnsFalseAndRollsBackWhenWriteFails() throws Exception {
        Files.createDirectory(dir.resolve("purchases.json.tmp"));
        Files.writeString(dir.resolve("purchases.json.tmp").resolve("x"), "x");
        Purchase p = store.newPurchase(new Charge("c9", "br", "u", T0.plusSeconds(600)), STEVE, "Steve", "vip", 1990);
        assertThat(store.add(p)).isFalse();
        assertThat(store.get("c9")).isEmpty();
        assertThat(store.inState(State.PENDING)).isEmpty();
    }

    @Test void addReturnsTrueWhenSaved() {
        Purchase p = store.newPurchase(new Charge("c9", "br", "u", T0.plusSeconds(600)), STEVE, "Steve", "vip", 1990);
        assertThat(store.add(p)).isTrue();
        assertThat(store.get("c9")).contains(p);
    }

    @Test void addAllReturnsTrueWhenSaved() {
        assertThat(store.addAll(List.of(
                new Purchase("c2", STEVE, "Steve", "kit", 150, "b", "u", T0, T0, State.PENDING, null)))).isTrue();
    }

    @Test void corruptFileIsPreservedAndStoreStartsEmpty() throws Exception {
        Path file = dir.resolve("purchases.json");
        Files.writeString(file, "{\"version\":1,\"purchases\":[{\"chargeId\":");
        PurchaseStore s = new PurchaseStore(file, Logger.getLogger("t"), clock);
        s.load();
        assertThat(s.inState(State.PENDING)).isEmpty();
        assertThat(Files.exists(dir.resolve("purchases.json.corrupt-" + T0.toEpochMilli()))).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test void corruptFileThatCannotBePreservedStopsLoadAndKeepsTheOnlyCopy() throws Exception {
        Path file = dir.resolve("purchases.json");
        String content = "{\"version\":1,\"purchases\":[{\"chargeId\":";
        Files.writeString(file, content);
        Path target = dir.resolve("purchases.json.corrupt-" + T0.toEpochMilli());
        Files.createDirectory(target); // destino ocupado: o move falha
        Files.writeString(target.resolve("x"), "x");
        PurchaseStore s = new PurchaseStore(file, Logger.getLogger("t"), clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(s::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("purchases.json");
        assertThat(Files.readString(file)).isEqualTo(content);
    }

    @Test void saveLeavesNoTempFileBehind() throws Exception {
        buy("c1", T0.plusSeconds(600));
        try (var files = Files.list(dir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("purchases.json");
        }
    }

    @Test void transitionPersistsNewStateOnDisk() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        assertThat(store.transition(p, State.PENDING, State.PAID)).isTrue();

        PurchaseStore reloaded = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"), clock);
        reloaded.load();
        assertThat(reloaded.get("c1").orElseThrow().state).isEqualTo(State.PAID);
    }

    @Test void markDeliveredPersistsNewStateOnDisk() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        assertThat(store.markDelivered(p)).isTrue();

        PurchaseStore reloaded = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"), clock);
        reloaded.load();
        assertThat(reloaded.get("c1").orElseThrow().state).isEqualTo(State.DELIVERED);
    }

    @Test void transitionFailsAndRestoresStateWhenPersistenceFails() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        // Make the parent directory unable to write by creating a directory at the tmp path
        Path jsonFile = dir.resolve("purchases.json");
        Path tmpFile = dir.resolve("purchases.json.tmp");
        Files.createDirectory(tmpFile); // This prevents the tmp file from being written

        assertThat(store.transition(p, State.PENDING, State.PAID)).isFalse();
        assertThat(p.state).isEqualTo(State.PENDING); // State must be restored
    }

    @Test void markDeliveredFailsAndRestoresStateWhenPersistenceFails() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        // Make the parent directory unable to write by creating a directory at the tmp path
        Path tmpFile = dir.resolve("purchases.json.tmp");
        Files.createDirectory(tmpFile); // This prevents the tmp file from being written

        assertThat(store.markDelivered(p)).isFalse();
        assertThat(p.state).isEqualTo(State.PENDING); // State must be restored
    }

    @Test void markDeliveredIsAtomicUnderConcurrency() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        int threads = 8;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    if (store.markDelivered(p)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(p.state).isEqualTo(State.DELIVERED);
    }

    @Test void recoverOnExistingDeliveredChargeIdReturnsExistingAndKeepsState() throws Exception {
        Purchase p = buy("c1", T0.plusSeconds(600));
        assertThat(store.markDelivered(p)).isTrue();

        Purchase recovered = store.recover("c1", ExternalId.of(STEVE, "other-sku"), 999, "Steve");
        assertThat(recovered).isSameAs(p);
        assertThat(recovered.state).isEqualTo(State.DELIVERED);
        assertThat(recovered.sku).isEqualTo("vip"); // Original sku, not other-sku
    }
}
