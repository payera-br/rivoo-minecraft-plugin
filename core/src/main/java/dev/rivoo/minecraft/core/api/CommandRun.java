package dev.rivoo.minecraft.core.api;

/**
 * Comando pronto da fila do módulo game (GET /game/commands).
 *
 * @param trigger    PURCHASED, REMOVED ou REFUNDED.
 * @param playerRef  "mc:&lt;uuid&gt;".
 */
public record CommandRun(String id, String chargeId, String trigger, String command, String playerRef,
                         String playerName, boolean requireOnline) {
    public boolean purchased() {
        return "PURCHASED".equals(trigger);
    }
}
