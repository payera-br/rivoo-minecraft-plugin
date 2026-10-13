package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.CommandRunner;

import java.util.ArrayList;
import java.util.List;

public final class RecordingCommands implements CommandRunner {
    public final List<String> ran = new ArrayList<>();

    @Override public boolean runAsConsole(String command) { ran.add(command); return true; }
}
