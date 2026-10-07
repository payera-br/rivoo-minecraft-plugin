package dev.rivoo.minecraft.core.api;

/** {@code GET /game/pairings/status}. connected = o dono usou o código no painel. */
public record PairingStatus(boolean connected, boolean sandbox, String companyName, String projectName,
                            String serverName) {
    public static final PairingStatus PENDING = new PairingStatus(false, false, null, null, null);
}
