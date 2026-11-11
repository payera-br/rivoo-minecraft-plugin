package dev.rivoo.minecraft.core.platform;

public interface CommandRunner {
    /**
     * Thread do jogo. {@code null} = rodou; senão o motivo da falha: o servidor não reconheceu o
     * comando, ou um comando do próprio Minecraft respondeu com erro ("No player was found",
     * "Unknown item"…). Comando de plugin não tem resposta lida: roda pelo console como sempre.
     */
    String run(String command);
}
