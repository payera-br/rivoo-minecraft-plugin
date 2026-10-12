package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.delivery.DeliveryConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.Map;

final class PaperConfig {
    private PaperConfig() {}

    /** Intervalos vêm do config.yml (opcionais); o resto é fixo do protocolo. */
    static DeliveryConfig delivery(FileConfiguration config, String agentVersion, Map<String, Object> details) {
        return new DeliveryConfig(
                Duration.ofSeconds(Math.max(1, config.getLong("delivery.interval-seconds", 2))),
                (int) Math.min(30, Math.max(0, config.getLong("delivery.wait-seconds", 10))),
                Duration.ofSeconds(Math.max(3, config.getLong("polling.interval-seconds", 3))),
                Duration.ofSeconds(60),
                agentVersion, details);
    }
}
