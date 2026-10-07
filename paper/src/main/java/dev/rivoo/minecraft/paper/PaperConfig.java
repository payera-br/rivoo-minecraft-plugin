package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.delivery.DeliveryConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class PaperConfig {
    private PaperConfig() {}

    static Catalog catalog(FileConfiguration config, boolean serverKey) {
        List<Product> products = new ArrayList<>();
        // Modo game (chave de servidor ou delivery.mode: game): o catálogo vem da Rivoo.
        if (serverKey || "game".equals(config.getString("delivery.mode", "auto"))) return new Catalog(products);
        ConfigurationSection section = config.getConfigurationSection("products");
        if (section != null) {
            for (String sku : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(sku);
                if (s == null) continue;
                products.add(new Product(sku, s.getString("name", sku), s.getInt("price"), s.getStringList("commands")));
            }
        }
        return new Catalog(products);
    }

    static DeliveryConfig delivery(FileConfiguration config) {
        return new DeliveryConfig(
                config.getString("rivoo.channel", "minecraft"),
                config.getString("delivery.mode", "auto"),
                config.getInt("rivoo.expires-in", 600),
                Duration.ofSeconds(Math.max(3, config.getLong("delivery.interval-seconds", 5))),
                Duration.ofSeconds(Math.max(3, config.getLong("polling.interval-seconds", 3))));
    }

    /** Chave de servidor: sempre game, sem canal (a API resolve o servidor pela chave). */
    static DeliveryConfig serverKeyDelivery(FileConfiguration config) {
        return new DeliveryConfig(null, "game",
                config.getInt("rivoo.expires-in", 600),
                Duration.ofSeconds(Math.max(3, config.getLong("delivery.interval-seconds", 3))),
                Duration.ofSeconds(Math.max(3, config.getLong("polling.interval-seconds", 3))));
    }
}
