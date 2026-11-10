package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.AckResult;
import dev.rivoo.minecraft.core.api.AgentOrder;
import dev.rivoo.minecraft.core.api.CommandPage;
import dev.rivoo.minecraft.core.api.CommandRun;
import dev.rivoo.minecraft.core.api.OnlinePlayer;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.FakePlayers;
import dev.rivoo.minecraft.core.fixtures.ManualScheduler;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import dev.rivoo.minecraft.core.fixtures.RecordingCommands;
import dev.rivoo.minecraft.core.fixtures.RecordingView;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryAcksTest {
    @TempDir Path dir;

    /** Fake API: the queue is whatever the test puts in {@code queue}; acks are recorded. */
    static final class FakeApi implements RivooApi {
        List<CommandRun> queue = List.of();
        final List<List<String>> acked = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        boolean ackFails;
        /** Fails only the n-th ack call (1-based); 0 = never. */
        int failCall;
        int ackCalls;

        @Override public ServerInfo connection() { throw new UnsupportedOperationException(); }
        @Override public void heartbeat(String v, Map<String, Object> d) { }
        @Override public List<Product> catalog() { return List.of(); }
        @Override public AgentOrder createOrder(String s, UUID p, String n, String g, String k) { throw new UnsupportedOperationException(); }
        @Override public String orderStatus(String id) { return "OPEN"; }
        @Override public CommandPage pollCommands(int w, int l, List<OnlinePlayer> o) {
            calls.add("poll");
            return new CommandPage(queue, false);
        }
        @Override public List<String> ackCommands(List<AckResult> results) throws IOException {
            calls.add("ack");
            ackCalls++;
            if (ackFails || ackCalls == failCall) throw new IOException("network down");
            // The API takes at most 100 results per request (400 otherwise).
            if (results.size() > 100) throw new IOException("400: results must contain no more than 100 elements");
            List<String> ids = results.stream().map(AckResult::id).toList();
            acked.add(ids);
            return ids;
        }
    }

    private record Fixture(DeliveryEngine engine, FakeApi api, RecordingCommands commands, CapturingLog log,
                           ManualScheduler scheduler) { }

    private Fixture fixture(AckStore.Saver saver) {
        CapturingLog log = new CapturingLog();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        FakeApi api = new FakeApi();
        RecordingCommands commands = new RecordingCommands();
        ManualScheduler scheduler = new ManualScheduler();
        AckStore acks = saver == null ? new AckStore(dir.resolve("acks.json"), log.logger)
                : new AckStore(dir.resolve("acks.json"), log.logger, saver);
        DeliveryConfig config = new DeliveryConfig(Duration.ofSeconds(1), 0, Duration.ofSeconds(5),
                Duration.ofSeconds(60), "test", Map.of());
        DeliveryEngine engine = new DeliveryEngine(api, new PurchaseStore(dir.resolve("p.json"), log.logger, clock),
                new Catalog(List.of()), scheduler, new FakePlayers(), commands, new RecordingView(), config, acks,
                log.logger, clock);
        return new Fixture(engine, api, commands, log, scheduler);
    }

    private static CommandRun run(String id, String command) {
        return new CommandRun(id, command, null, null, false, 0);
    }

    @Test
    void stopsRunningTheBatchWhenAcksCannotBeSaved() {
        Fixture f = fixture((file, data) -> { throw new IOException("read-only file system"); });

        List<AckResult> ackable = f.engine().runCommands(List.of(run("r1", "give a"), run("r2", "give b"), run("r3", "give c")));

        // the first one already ran (and is acked from memory); the rest stay in the queue
        assertThat(f.commands().ran).containsExactly("give a");
        assertThat(ackable).extracting(AckResult::id).containsExactly("r1");
        assertThat(f.log().severe).hasSize(1); // exactly one severe for the batch
    }

    @Test
    void aBrokenDiskKeepsLaterBatchesFromExecutingAnything() {
        boolean[] fail = {true};
        Fixture f = fixture((file, data) -> { if (fail[0]) throw new IOException("read-only file system"); });

        f.engine().runCommands(List.of(run("r1", "give a"), run("r2", "give b")));
        assertThat(f.commands().ran).containsExactly("give a");

        List<AckResult> second = f.engine().runCommands(List.of(run("r2", "give b"), run("r3", "give c")));
        assertThat(f.commands().ran).containsExactly("give a"); // nothing new ran
        assertThat(second).isEmpty();
        assertThat(f.log().severe).hasSize(2); // one per batch

        // an already-stored result still goes out for ack
        List<AckResult> third = f.engine().runCommands(List.of(run("r1", "give a")));
        assertThat(third).extracting(AckResult::id).containsExactly("r1");

        fail[0] = false; // disk is back: the next batch runs
        f.engine().runCommands(List.of(run("r2", "give b"), run("r3", "give c")));
        assertThat(f.commands().ran).containsExactly("give a", "give b", "give c");
    }

    @Test
    void runsTheWholeBatchWhenAcksAreSaved() {
        Fixture f = fixture(null);

        f.engine().runCommands(List.of(run("r1", "give a"), run("r2", "give b")));

        assertThat(f.commands().ran).containsExactly("give a", "give b");
    }

    @Test
    void storedAckOfARunThatNoLongerComesBackIsSentOnTheNextCycle() {
        Fixture f = fixture(null);
        f.api().ackFails = true;
        f.api().queue = List.of(run("r1", "give a"));
        f.engine().start();
        f.scheduler().runRepeating(); // cycle 1: runs r1, ack fails, stays stored
        assertThat(f.engine().acks.get("r1")).isPresent();
        f.api().acked.clear();
        f.api().calls.clear();

        // the API expired/canceled r1: it never comes back in the queue
        f.api().ackFails = false;
        f.api().queue = List.of();
        f.scheduler().runRepeating(); // cycle 2

        assertThat(f.api().acked).containsExactly(List.of("r1"));
        assertThat(f.engine().acks.get("r1")).isEmpty();
        assertThat(f.api().calls).containsSubsequence("ack", "poll"); // flush before polling
        assertThat(f.commands().ran).containsExactly("give a"); // never ran twice
    }

    @Test
    void pollingContinuesWhenTheFlushFails() {
        Fixture f = fixture(null);
        f.api().ackFails = true;
        f.api().queue = List.of(run("r1", "give a"));
        f.engine().start();
        f.scheduler().runRepeating();
        f.api().calls.clear();

        f.scheduler().runRepeating(); // flush fails, poll still happens

        assertThat(f.api().calls).contains("poll");
        assertThat(f.engine().acks.get("r1")).isPresent();
    }

    @Test
    void moreThanAHundredStoredAcksGoOutInChunksOfAHundred() {
        Fixture f = fixture(null);
        for (int i = 0; i < 250; i++) f.engine().acks.put(AckResult.ok("r" + i));

        f.engine().flushStoredAcks();

        assertThat(f.api().acked).extracting(List::size).containsExactly(100, 100, 50);
        assertThat(f.engine().acks.all()).isEmpty();
    }

    @Test
    void aFailedChunkStaysStoredAndTheOthersLand() {
        Fixture f = fixture(null);
        for (int i = 0; i < 250; i++) f.engine().acks.put(AckResult.ok("r" + i));
        f.api().failCall = 2;

        f.engine().flushStoredAcks();

        assertThat(f.api().acked).extracting(List::size).containsExactly(100, 50);
        assertThat(f.engine().acks.all()).hasSize(100);
        f.engine().flushStoredAcks(); // the next cycle sends what stayed
        assertThat(f.engine().acks.all()).isEmpty();
    }
}
