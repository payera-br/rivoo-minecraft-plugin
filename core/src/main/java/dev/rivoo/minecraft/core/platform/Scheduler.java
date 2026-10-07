package dev.rivoo.minecraft.core.platform;

import java.time.Duration;

/** Threads do servidor de jogo. sync = thread principal do jogo; async = fora dela. */
public interface Scheduler {
    void sync(Runnable task);

    void async(Runnable task);

    Task repeatSync(Runnable task, Duration every);

    Task repeatAsync(Runnable task, Duration every);

    interface Task {
        void cancel();
    }
}
