package dev.rivoo.minecraft.core.delivery;

import java.time.Duration;
import java.util.Map;

/**
 * @param commandWaitSeconds long-poll da fila de comandos (0..30): a API segura a consulta até ter comando.
 * @param orderInterval      intervalo entre consultas de status dos Pix abertos (uma por vez, em rodízio).
 * @param details            dados do servidor mandados no heartbeat (gameVersion, motd, port).
 */
public record DeliveryConfig(Duration commandInterval, int commandWaitSeconds, Duration orderInterval,
                             Duration heartbeatInterval, String agentVersion, Map<String, Object> details) {}
