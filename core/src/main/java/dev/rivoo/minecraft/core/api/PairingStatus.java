package dev.rivoo.minecraft.core.api;

/** {@code GET /v1/agent/pairings/current}. claimed = o dono usou o código no painel. */
public record PairingStatus(boolean claimed, String connectionName, String projectName) {
    public static final PairingStatus PENDING = new PairingStatus(false, null, null);
}
