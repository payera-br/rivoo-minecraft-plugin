package dev.rivoo.minecraft.core.fixtures;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Logger isolado: `warnings` guarda WARNING ou acima, `messages` guarda tudo. */
public final class CapturingLog {
    public final Logger logger = Logger.getLogger("capture-" + UUID.randomUUID());
    public final List<String> warnings = new CopyOnWriteArrayList<>();
    public final List<String> messages = new CopyOnWriteArrayList<>();

    public CapturingLog() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                messages.add(r.getMessage());
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }
}
