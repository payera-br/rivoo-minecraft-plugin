package dev.rivoo.minecraft.core.connect;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** plugins/Rivoo/server.key: a chave do servidor, legível só pelo dono do processo. */
public final class KeyFile {
    private final Path path;

    public KeyFile(Path path) {
        this.path = path;
    }

    public Optional<String> read() throws IOException {
        if (!Files.exists(path)) return Optional.empty();
        String key = Files.readString(path).trim();
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    public void write(String key) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        // Criado já rw------- (nunca legível por outros, nem por um instante); sobra de crash sai antes.
        Files.deleteIfExists(tmp);
        FileAttribute<?>[] ownerOnly = Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Files.createFile(tmp, ownerOnly);
        Files.writeString(tmp, key.trim() + "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
