package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.platform.CommandRunner;

import java.util.ArrayList;
import java.util.List;

public final class RecordingCommands implements CommandRunner {
    public final List<String> ran = new ArrayList<>();
    /** When true the server "does not recognize" the command. */
    public boolean fail;
    /** When set, the game answers with this error (a vanilla command that failed). */
    public String gameError;

    @Override public String run(String command) {
        ran.add(command);
        if (fail) return "O servidor não reconheceu o comando: " + command;
        return gameError;
    }
}
