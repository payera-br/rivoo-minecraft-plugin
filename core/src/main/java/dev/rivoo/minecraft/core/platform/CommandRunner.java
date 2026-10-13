package dev.rivoo.minecraft.core.platform;

public interface CommandRunner {
    /** Thread do jogo. false = o servidor não reconheceu ou recusou o comando. */
    boolean runAsConsole(String command);
}
