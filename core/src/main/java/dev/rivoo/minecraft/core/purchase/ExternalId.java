package dev.rivoo.minecraft.core.purchase;

import java.util.Optional;
import java.util.UUID;

/**
 * externalId das cobranças deste plugin: "mc:&lt;uuid&gt;:&lt;sku&gt;" ou, no modo game,
 * "mc:&lt;uuid&gt;:&lt;sku&gt;:&lt;nick&gt;" (a API usa o nick para {player}). O sku não tem ':'.
 *
 * @param playerName null quando ausente.
 */
public record ExternalId(UUID player, String sku, String playerName) {
    private static final String PREFIX = "mc";

    public static ExternalId of(UUID player, String sku) {
        return new ExternalId(player, sku, null);
    }

    public static ExternalId of(UUID player, String sku, String playerName) {
        return new ExternalId(player, sku, playerName);
    }

    public String value() {
        String base = PREFIX + ":" + player + ":" + sku;
        return playerName == null || playerName.isEmpty() ? base : base + ":" + playerName;
    }

    public static Optional<ExternalId> parse(String raw) {
        if (raw == null) return Optional.empty();
        String[] parts = raw.split(":", -1);
        if (parts.length < 3 || parts.length > 4 || !parts[0].equals(PREFIX) || parts[2].isEmpty()) {
            return Optional.empty();
        }
        String nick = parts.length == 4 && !parts[3].isEmpty() ? parts[3] : null;
        try {
            return Optional.of(new ExternalId(UUID.fromString(parts[1]), parts[2], nick));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
