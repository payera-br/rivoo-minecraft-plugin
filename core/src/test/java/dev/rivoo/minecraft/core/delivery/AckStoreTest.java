package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.AckResult;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AckStoreTest {
    @TempDir Path dir;

    private static final AckStore.Saver FAILING = (file, data) -> { throw new IOException("read-only file system"); };

    @Test
    void putReturnsTrueAndPersistsWhenTheFileIsWritten() {
        Path file = dir.resolve("acks.json");
        AckStore store = new AckStore(file, new CapturingLog().logger);

        assertThat(store.put(AckResult.ok("r1"))).isTrue();

        assertThat(new AckStore(file, new CapturingLog().logger).get("r1")).isPresent();
    }

    @Test
    void putReturnsFalseAndLogsSevereWhenTheFileCannotBeSaved() {
        CapturingLog log = new CapturingLog();
        AckStore store = new AckStore(dir.resolve("acks.json"), log.logger, FAILING);

        assertThat(store.put(AckResult.ok("r1"))).isFalse();

        assertThat(log.warnings).hasSize(1);
        assertThat(store.healthy()).isFalse();
        // the result is still kept in memory so it can be acked
        assertThat(store.get("r1")).isPresent();
    }

    @Test
    void failedSaveLeavesNoTmpFileAndKeepsTheOldFile() throws IOException {
        Path file = dir.resolve("acks.json");
        AckStore ok = new AckStore(file, new CapturingLog().logger);
        ok.put(AckResult.ok("r1"));
        String before = Files.readString(file);

        AckStore store = new AckStore(file, new CapturingLog().logger, FAILING);
        store.put(AckResult.ok("r2"));

        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(Files.exists(dir.resolve("acks.json.tmp"))).isFalse();
    }

    @Test
    void realUnwritablePathReturnsFalse() throws IOException {
        // a regular file where the parent directory should be: createDirectories fails on any OS/user
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        AckStore store = new AckStore(blocker.resolve("acks.json"), new CapturingLog().logger);

        assertThat(store.put(AckResult.ok("r1"))).isFalse();
        assertThat(store.all()).extracting(AckResult::id).isEqualTo(List.of("r1"));
    }

    @Test
    void realSaverFailingInsideTheMoveCleansUpTheTmpFile() throws IOException {
        // the target is a non-empty directory: the tmp is written, then the move fails
        Path target = dir.resolve("acks.json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("x"), "x");
        AckStore store = new AckStore(target, new CapturingLog().logger);

        assertThat(store.put(AckResult.ok("r1"))).isFalse();

        assertThat(Files.exists(dir.resolve("acks.json.tmp"))).isFalse();
    }

    @Test
    void becomesHealthyAgainWhenASaveWorks() {
        boolean[] fail = {true};
        AckStore store = new AckStore(dir.resolve("acks.json"), new CapturingLog().logger,
                (file, data) -> { if (fail[0]) throw new IOException("disk"); });

        assertThat(store.put(AckResult.ok("r1"))).isFalse();
        assertThat(store.retrySave()).isFalse();
        assertThat(store.healthy()).isFalse();
        fail[0] = false;
        assertThat(store.retrySave()).isTrue();
        assertThat(store.healthy()).isTrue();
    }
}
