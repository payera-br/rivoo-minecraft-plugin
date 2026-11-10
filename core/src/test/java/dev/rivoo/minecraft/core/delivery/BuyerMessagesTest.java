package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.*;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.fixtures.*;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerMessagesTest {
    @TempDir Path dir;

    static final UUID ID = UUID.randomUUID();

    static final class Api implements RivooApi {
        List<Pending> pending = List.of();
        boolean pendingFails;
        Runnable duringFetch = () -> { };
        @Override public ServerInfo connection() { throw new UnsupportedOperationException(); }
        @Override public void heartbeat(String v, Map<String, Object> d) { }
        @Override public List<Product> catalog() { return List.of(); }
        @Override public AgentOrder createOrder(String s, UUID p, String n, String g, String k) { throw new UnsupportedOperationException(); }
        @Override public String orderStatus(String id) { return "OPEN"; }
        @Override public CommandPage pollCommands(int w, int l, List<OnlinePlayer> o) { return new CommandPage(List.of(), false); }
        @Override public List<String> ackCommands(List<AckResult> r) { return List.of(); }
        @Override public List<Pending> pending(UUID uuid, String name) throws IOException {
            duringFetch.run();
            if (pendingFails) throw new IOException("down");
            return pending;
        }
    }

    private record F(DeliveryEngine engine, Api api, FakePlayers players, RecordingCommands commands) { }

    private F fixture() {
        CapturingLog log = new CapturingLog();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        Api api = new Api();
        FakePlayers players = new FakePlayers().online(ID, "Steve").freeSlots(ID, 3);
        RecordingCommands commands = new RecordingCommands();
        DeliveryConfig config = new DeliveryConfig(Duration.ofSeconds(1), 0, Duration.ofSeconds(5),
                Duration.ofSeconds(60), "test", Map.of());
        DeliveryEngine engine = new DeliveryEngine(api, new PurchaseStore(dir.resolve("p.json"), log.logger, clock),
                new Catalog(List.of()), new ManualScheduler(), players, commands, new RecordingView(),
                config, new AckStore(dir.resolve("acks.json"), log.logger), log.logger, clock);
        return new F(engine, api, players, commands);
    }

    private static CommandRun run(String id, String cmd, String product) {
        return new CommandRun(id, cmd, ID, "Steve", true, 0, product);
    }

    @Test
    void arrivedMessageAfterSuccessfulRunWithProduct() {
        F f = fixture();
        f.engine().runCommands(List.of(run("r1", "give", "VIP")));
        assertThat(f.players().messages).containsExactly("§aSeu VIP chegou!");
    }

    @Test
    void noMessageWithoutProduct() {
        F f = fixture();
        f.engine().runCommands(List.of(run("r1", "lp remove", null)));
        assertThat(f.commands().ran).containsExactly("lp remove");
        assertThat(f.players().messages).isEmpty();
    }

    @Test
    void noMessageWhenTheCommandFailed() {
        F f = fixture();
        f.commands().fail = true;
        f.engine().runCommands(List.of(run("r1", "give", "VIP")));
        assertThat(f.players().messages).isEmpty();
    }

    @Test
    void joinNoticeListsPendingItems() {
        F f = fixture();
        f.api().pending = List.of(new Pending("VIP", 0));
        f.engine().announcePending(ID, "Steve");
        assertThat(f.players().messages).containsExactly("§eVocê tem VIP esperando.");
    }

    @Test
    void joinNoticeAsksForSlotsAndSilencesTheThrottledWarning() {
        F f = fixture();
        f.api().pending = List.of(new Pending("Kit", 9));
        f.engine().announcePending(ID, "Steve");
        f.engine().warnSlots(List.of(new CommandPage.SlotWait(ID, "Steve", 9)));
        assertThat(f.players().messages)
                .containsExactly("§eVocê tem Kit esperando. Libere 9 espaços no inventário.");
    }

    @Test
    void pendingFailureNeverBreaksTheJoin() {
        F f = fixture();
        f.api().pendingFails = true;
        f.engine().announcePending(ID, "Steve");
        assertThat(f.players().messages).isEmpty();
    }

    @Test
    void oneArrivedMessagePerPlayerAndProductInABatch() {
        F f = fixture();
        f.engine().runCommands(List.of(run("r1", "give a", "VIP"), run("r2", "give b", "VIP")));
        assertThat(f.players().messages).containsExactly("§aSeu VIP chegou!");
    }

    @Test
    void differentProductsEachGetTheirMessage() {
        F f = fixture();
        f.engine().runCommands(List.of(run("r1", "give a", "VIP"), run("r2", "give b", "Kit")));
        assertThat(f.players().messages).containsExactly("§aSeu VIP chegou!", "§aSeu Kit chegou!");
    }

    @Test
    void playerWhoLeftBeforeTheSendGetsNothing() {
        F f = fixture();
        f.api().pending = List.of(new Pending("VIP", 0));
        f.api().duringFetch = () -> f.players().offline(ID);
        f.engine().announcePending(ID, "Steve");
        assertThat(f.players().messages).isEmpty();
    }
}
