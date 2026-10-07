package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.fixtures.MutableClock;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMigrationTest {
    @TempDir Path plugins;

    static final String PENDING_YML = """
            charge_a:
              player-id: 8667ba71-b85a-4004-af54-457a9734eed7
              player-name: Steve
              sku: vip
              amount: 1990
              br-code: '000201abc'
              url: https://pay/charge_a
              created-at: '2026-10-05T12:00:00Z'
              expires-at: '2026-10-05T12:10:00Z'
              state: PENDING
            charge_b:
              player-id: 8667ba71-b85a-4004-af54-457a9734eed7
              player-name: Steve
              sku: kit
              amount: 150
              br-code: ''
              url: ''
              created-at: '2026-10-05T11:00:00Z'
              expires-at: '2026-10-05T11:10:00Z'
              state: PAID
            """;

    @Test void migratesConfigAndPurchasesThenArchives() throws Exception {
        Path old = Files.createDirectories(plugins.resolve("RivooPoc"));
        Files.writeString(old.resolve("config.yml"), "rivoo:\n  api-key: cpk_sandbox_k\n");
        Files.writeString(old.resolve("pending.yml"), PENDING_YML);
        Path dataFolder = plugins.resolve("Rivoo");

        LegacyMigration m = new LegacyMigration(plugins, Logger.getLogger("t"));
        assertThat(m.copyConfig(dataFolder)).isTrue();
        assertThat(Files.readString(dataFolder.resolve("config.yml"))).contains("cpk_sandbox_k");

        PurchaseStore store = new PurchaseStore(dataFolder.resolve("purchases.json"), Logger.getLogger("t"),
                new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
        LegacyMigration.Result r = m.importPurchases(store);
        assertThat(r.imported()).isEqualTo(2);
        assertThat(r.complete()).isTrue();
        assertThat(store.get("charge_a")).map(p -> p.state).contains(State.PENDING);
        assertThat(store.get("charge_a")).map(p -> p.brCode).contains("000201abc");
        assertThat(store.get("charge_b")).map(p -> p.state).contains(State.PAID);

        m.archive();
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(plugins.resolve("RivooPoc.migrated/pending.yml"))).isTrue();
    }

    @Test void doesNotOverwriteExistingConfigAndIsNoOpWithoutLegacyFolder() throws Exception {
        Path dataFolder = Files.createDirectories(plugins.resolve("Rivoo"));
        Files.writeString(dataFolder.resolve("config.yml"), "new: true\n");
        Path old = Files.createDirectories(plugins.resolve("RivooPoc"));
        Files.writeString(old.resolve("config.yml"), "old: true\n");
        LegacyMigration m = new LegacyMigration(plugins, Logger.getLogger("t"));
        assertThat(m.copyConfig(dataFolder)).isFalse();
        assertThat(Files.readString(dataFolder.resolve("config.yml"))).isEqualTo("new: true\n");

        LegacyMigration none = new LegacyMigration(plugins.resolve("empty"), Logger.getLogger("t"));
        assertThat(none.copyConfig(dataFolder)).isFalse();
        PurchaseStore store = new PurchaseStore(dataFolder.resolve("purchases.json"), Logger.getLogger("t"),
                new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
        LegacyMigration.Result r = none.importPurchases(store);
        assertThat(r.imported()).isZero();
        assertThat(r.complete()).isTrue();
        none.archive();
    }

    @Test void malformedEntryIsIncompleteButValidOnesAreImported() throws Exception {
        Path old = Files.createDirectories(plugins.resolve("RivooPoc"));
        Files.writeString(old.resolve("pending.yml"), PENDING_YML + """
                charge_bad:
                  player-id: not-a-uuid
                  player-name: Steve
                  sku: vip
                  amount: 1
                  created-at: '2026-10-05T12:00:00Z'
                  expires-at: '2026-10-05T12:10:00Z'
                  state: PENDING
                """);
        PurchaseStore store = store(plugins.resolve("Rivoo/purchases.json"));
        LegacyMigration.Result r = new LegacyMigration(plugins, Logger.getLogger("t")).importPurchases(store);
        assertThat(r.imported()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.persisted()).isTrue();
        assertThat(r.complete()).isFalse();
        assertThat(store.get("charge_bad")).isEmpty();
    }

    @Test void failedSaveIsIncomplete() throws Exception {
        Path old = Files.createDirectories(plugins.resolve("RivooPoc"));
        Files.writeString(old.resolve("pending.yml"), PENDING_YML);
        Path file = plugins.resolve("Rivoo/purchases.json");
        Files.createDirectories(file.resolveSibling("purchases.json.tmp"));
        Files.writeString(file.resolveSibling("purchases.json.tmp").resolve("x"), "x");
        LegacyMigration.Result r = new LegacyMigration(plugins, Logger.getLogger("t")).importPurchases(store(file));
        assertThat(r.persisted()).isFalse();
        assertThat(r.complete()).isFalse();
    }

    @Test void importingTwiceIsIdempotent() throws Exception {
        Path old = Files.createDirectories(plugins.resolve("RivooPoc"));
        Files.writeString(old.resolve("pending.yml"), PENDING_YML);
        PurchaseStore store = store(plugins.resolve("Rivoo/purchases.json"));
        LegacyMigration m = new LegacyMigration(plugins, Logger.getLogger("t"));
        m.importPurchases(store);
        store.transition(store.get("charge_a").orElseThrow(), State.PENDING, State.PAID);
        assertThat(m.importPurchases(store).complete()).isTrue();
        assertThat(store.get("charge_a")).map(p -> p.state).contains(State.PAID);
        assertThat(store.inState(State.PAID)).hasSize(2);
        assertThat(store.inState(State.PENDING)).isEmpty();
    }

    private static PurchaseStore store(Path file) {
        return new PurchaseStore(file, Logger.getLogger("t"), new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
    }
}
