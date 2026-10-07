package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.Fulfillment;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.FakeApi;
import dev.rivoo.minecraft.core.fixtures.ManualScheduler;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentPollerTest {
    final FakeApi api = new FakeApi();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
    final CapturingLog log = new CapturingLog();

    FulfillmentPoller poller(List<String> ackable) {
        return new FulfillmentPoller(api, "minecraft", new ManualScheduler(), entries -> ackable, log.logger, clock);
    }

    void fillQueue(int n) {
        for (int i = 0; i < n; i++) api.queue.add(new Fulfillment("c" + i, "loja-web-" + i, 100));
    }

    List<String> stuckWarnings() {
        return log.warnings.stream().filter(m -> m.contains("parada")).toList();
    }

    @Test void requestsPagesOf100() {
        poller(List.of()).run();
        assertThat(api.lastListLimit).isEqualTo(100);
    }

    @Test void fullPageWithNothingAckableWarnsOncePerInterval() {
        fillQueue(101);
        FulfillmentPoller poller = poller(List.of());

        poller.run();
        assertThat(stuckWarnings()).hasSize(1);
        assertThat(stuckWarnings().get(0)).contains("100");

        clock.advance(Duration.ofSeconds(5));
        poller.run();
        assertThat(stuckWarnings()).hasSize(1); // não repete a cada ciclo

        clock.advance(Duration.ofMinutes(5));
        poller.run();
        assertThat(stuckWarnings()).hasSize(2);
    }

    @Test void noWarningWhenThereIsNoMoreAfterThePage() {
        fillQueue(3);
        poller(List.of()).run();
        assertThat(stuckWarnings()).isEmpty();
    }

    @Test void noWarningWhenSomethingWasAcked() {
        fillQueue(101);
        poller(List.of("c0")).run();
        assertThat(stuckWarnings()).isEmpty();
        assertThat(api.acks).containsExactly(List.of("c0"));
    }
}
