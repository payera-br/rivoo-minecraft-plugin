package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.delivery.DeliveryConfig;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Motor com dependências falsas. Relógio começa em 2026-10-05T12:00:00Z; Pix vence 10 min depois. */
public final class EngineHarness {
    public static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    public static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");

    public final FakeApi api = new FakeApi();
    public final CapturingLog log = new CapturingLog();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
    public final ManualScheduler scheduler = new ManualScheduler();
    public final FakePlayers players = new FakePlayers().online(STEVE, "Steve");
    public final RecordingCommands commands = new RecordingCommands();
    public final RecordingView view = new RecordingView();
    public final Catalog catalog = new Catalog(List.of(
            new Product("vip", "VIP Mensal", 1990, List.of("give {player} diamond 5", "lp user {uuid} parent add vip")),
            new Product("kit", "Kit Inicial", 150, List.of("give {player} bread 16"))));
    public final PurchaseStore store;
    public final Path dir;
    public DeliveryEngine engine;

    public EngineHarness(Path dir) {
        this.dir = dir;
        this.store = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("test"), clock);
        store.load();
    }

    public DeliveryEngine start(String mode) {
        engine = new DeliveryEngine(api, store, catalog, scheduler, players, commands, view,
                new DeliveryConfig("minecraft", mode, 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                log.logger, clock);
        api.expiresAt = clock.instant().plusSeconds(600);
        engine.start();
        return engine;
    }

    /** Modo chave de servidor: game, sem canal (a API resolve o servidor pela chave). */
    public DeliveryEngine startServerKey() {
        engine = new DeliveryEngine(api, store, catalog, scheduler, players, commands, view,
                new DeliveryConfig(null, "game", 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                log.logger, clock);
        api.expiresAt = clock.instant().plusSeconds(600);
        engine.start();
        return engine;
    }

    /** Como start, sem iniciar (para quem controla o start, ex.: EngineHolder). */
    public DeliveryEngine build(String mode) {
        engine = new DeliveryEngine(api, store, catalog, scheduler, players, commands, view,
                new DeliveryConfig("minecraft", mode, 600, Duration.ofSeconds(5), Duration.ofSeconds(3)),
                log.logger, clock);
        api.expiresAt = clock.instant().plusSeconds(600);
        return engine;
    }
}
