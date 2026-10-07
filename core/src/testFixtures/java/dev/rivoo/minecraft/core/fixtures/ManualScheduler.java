package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Executa sync/async na hora, na thread do teste; tarefas repetidas só rodam em runRepeating(). */
public final class ManualScheduler implements Scheduler {
    public record Repeating(Runnable task, Duration every, boolean async, boolean[] cancelled) {}

    public final List<Repeating> repeating = new ArrayList<>();

    @Override public void sync(Runnable task) { task.run(); }

    @Override public void async(Runnable task) { task.run(); }

    @Override public Task repeatSync(Runnable task, Duration every) { return add(task, every, false); }

    @Override public Task repeatAsync(Runnable task, Duration every) { return add(task, every, true); }

    private Task add(Runnable task, Duration every, boolean async) {
        boolean[] cancelled = {false};
        repeating.add(new Repeating(task, every, async, cancelled));
        return () -> cancelled[0] = true;
    }

    public void runRepeating() {
        for (Repeating r : List.copyOf(repeating)) if (!r.cancelled()[0]) r.task().run();
    }

    public long activeRepeating() {
        return repeating.stream().filter(r -> !r.cancelled()[0]).count();
    }
}
