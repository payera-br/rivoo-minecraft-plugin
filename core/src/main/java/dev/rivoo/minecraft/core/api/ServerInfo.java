package dev.rivoo.minecraft.core.api;

/**
 * Conexão dona da chave ({@code GET /v1/agent/connection}): o servidor como aparece no painel.
 *
 * @param minAgentVersion versão mínima do plugin que a Rivoo aceita; null se a API não informar.
 */
public record ServerInfo(String serverId, String serverName, String projectName, String minAgentVersion) {
    /** true se {@code version} (ex.: 1.0.0) é mais antiga que a mínima. Versão ilegível nunca bloqueia. */
    public boolean outdated(String version) {
        if (minAgentVersion == null || version == null) return false;
        String[] a = version.split("[.-]");
        String[] b = minAgentVersion.split("[.-]");
        for (int i = 0; i < Math.min(3, Math.max(a.length, b.length)); i++) {
            int x = part(a, i);
            int y = part(b, i);
            if (x < 0 || y < 0) return false;
            if (x != y) return x < y;
        }
        return false;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
