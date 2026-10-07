package dev.rivoo.minecraft.core.purchase;

import java.time.Instant;
import java.util.UUID;

public final class Purchase {
    public final String chargeId;
    public final UUID playerId;
    public final String playerName;
    public final String sku;
    public final int amount;
    public final String brCode;
    public final String url;
    public final Instant createdAt;
    public final Instant expiresAt;
    public volatile State state;
    /** Id do mapa do Minecraft que mostra o QR desta compra; null até a primeira exibição. */
    public volatile Integer mapId;

    public Purchase(String chargeId, UUID playerId, String playerName, String sku, int amount, String brCode,
                    String url, Instant createdAt, Instant expiresAt, State state, Integer mapId) {
        this.chargeId = chargeId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.sku = sku;
        this.amount = amount;
        this.brCode = brCode;
        this.url = url;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = state;
        this.mapId = mapId;
    }
}
