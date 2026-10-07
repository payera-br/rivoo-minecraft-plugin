package dev.rivoo.minecraft.core.api;

/** Servidor dono da chave ({@code GET /game/server}). sandbox = chave cpk_sandbox_ (Pix simulado). */
public record ServerInfo(String serverId, String serverName, String projectName, boolean sandbox) {}
