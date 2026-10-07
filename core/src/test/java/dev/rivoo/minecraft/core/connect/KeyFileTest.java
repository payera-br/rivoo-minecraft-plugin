package dev.rivoo.minecraft.core.connect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class KeyFileTest {
    @TempDir Path dir;

    @Test void missingFileIsEmpty() throws Exception {
        assertThat(new KeyFile(dir.resolve("server.key")).read()).isEmpty();
    }

    @Test void writesTrimmedOwnerOnly() throws Exception {
        KeyFile f = new KeyFile(dir.resolve("server.key"));
        f.write("cpk_sandbox_abc");
        assertThat(f.read()).contains("cpk_sandbox_abc");
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("server.key"))))
                    .isEqualTo("rw-------");
        }
    }

    @Test void blankFileIsEmpty() throws Exception {
        Files.writeString(dir.resolve("server.key"), "  \n");
        assertThat(new KeyFile(dir.resolve("server.key")).read()).isEmpty();
    }

    @Test void overwriteKeepsOnlyTheLast() throws Exception {
        KeyFile f = new KeyFile(dir.resolve("server.key"));
        f.write("cpk_sandbox_a");
        f.write("cpk_live_b");
        assertThat(f.read()).contains("cpk_live_b");
    }

    @Test void aStaleWorldReadableTmpFromACrashIsReplacedOwnerOnly() throws Exception {
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return;
        Path tmp = dir.resolve("server.key.tmp");
        Files.writeString(tmp, "cpk_sandbox_old\n");
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
        KeyFile f = new KeyFile(dir.resolve("server.key"));
        f.write("cpk_sandbox_new");
        assertThat(f.read()).contains("cpk_sandbox_new");
        assertThat(Files.exists(tmp)).isFalse();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("server.key"))))
                .isEqualTo("rw-------");
    }
}
