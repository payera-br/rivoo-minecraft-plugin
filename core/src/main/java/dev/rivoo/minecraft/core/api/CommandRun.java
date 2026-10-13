package dev.rivoo.minecraft.core.api;

/**
 * Comando pronto da fila do módulo game (GET /game/commands).
 *
 * @param trigger    PURCHASED, REMOVED ou REFUNDED.
 * @param playerRef  "mc:&lt;uuid&gt;".
 * @param requiredSlots espaços livres no inventário antes de executar (0 = não exige; API antiga não manda).
 */
public record CommandRun(String id, String chargeId, String trigger, String command, String playerRef,
                         String playerName, boolean requireOnline, int requiredSlots) {
    public CommandRun(String id, String chargeId, String trigger, String command, String playerRef,
                      String playerName, boolean requireOnline) {
        this(id, chargeId, trigger, command, playerRef, playerName, requireOnline, 0);
    }

    public boolean purchased() {
        return "PURCHASED".equals(trigger);
    }
}
