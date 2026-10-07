package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineHolderTest {
    @TempDir Path dir;

    @Test void swapStopsTheOldAndStartsTheNew() {
        EngineHarness a = new EngineHarness(dir.resolve("a"));
        EngineHarness b = new EngineHarness(dir.resolve("b"));
        EngineHolder holder = new EngineHolder();
        ServerInfo info = new ServerInfo("s", "S", "P", true);

        holder.swap(a.build("game"), info);
        holder.swap(b.build("game"), info);

        assertThat(a.engine.stoppedForTest()).isTrue();
        assertThat(holder.engine()).contains(b.engine);
        assertThat(holder.info()).contains(info);
    }

    @Test void emptyUntilConnected() {
        EngineHolder holder = new EngineHolder();
        assertThat(holder.engine()).isEmpty();
        assertThat(holder.info()).isEmpty();
    }

    @Test void stopClears() {
        EngineHarness a = new EngineHarness(dir.resolve("a"));
        EngineHolder holder = new EngineHolder();
        holder.swap(a.build("game"), null);
        holder.stop();
        assertThat(a.engine.stoppedForTest()).isTrue();
        assertThat(holder.engine()).isEmpty();
    }

    @Test void swapInfoKeepsTheEngine() {
        EngineHarness a = new EngineHarness(dir.resolve("a"));
        EngineHolder holder = new EngineHolder();
        holder.swap(a.build("game"), null);
        ServerInfo info = new ServerInfo("s", "S", "P", true);
        holder.swapInfo(info);
        assertThat(holder.engine()).contains(a.engine);
        assertThat(holder.info()).contains(info);
        assertThat(a.engine.stoppedForTest()).isFalse();
    }
}
