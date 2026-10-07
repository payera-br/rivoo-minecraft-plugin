package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Migra a instalação do POC (plugins/RivooPoc) para plugins/Rivoo uma única vez. */
final class LegacyMigration {
    private final Path legacy;
    private final Logger log;

    /** Resultado da importação; só é seguro arquivar quando {@link #complete()}. */
    record Result(int imported, int skipped, boolean persisted) {
        boolean complete() {
            return persisted && skipped == 0;
        }
    }

    LegacyMigration(Path pluginsDir, Logger log) {
        this.legacy = pluginsDir.resolve("RivooPoc");
        this.log = log;
    }

    boolean copyConfig(Path dataFolder) throws IOException {
        Path from = legacy.resolve("config.yml");
        Path to = dataFolder.resolve("config.yml");
        if (!Files.exists(from) || Files.exists(to)) return false;
        Files.createDirectories(dataFolder);
        Files.copy(from, to);
        log.info("Migração: config.yml copiado de RivooPoc.");
        return true;
    }

    Result importPurchases(PurchaseStore store) {
        Path file = legacy.resolve("pending.yml");
        if (!Files.exists(file)) return new Result(0, 0, true);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file.toFile());
        List<Purchase> imported = new ArrayList<>();
        int skipped = 0;
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) {
                skipped++;
                log.warning("Migração: entrada " + id + " ignorada (não é uma seção)");
                continue;
            }
            try {
                imported.add(new Purchase(id,
                        UUID.fromString(s.getString("player-id")),
                        s.getString("player-name"),
                        s.getString("sku"),
                        s.getInt("amount"),
                        s.getString("br-code", ""),
                        s.getString("url", ""),
                        Instant.parse(s.getString("created-at")),
                        Instant.parse(s.getString("expires-at")),
                        State.valueOf(s.getString("state")),
                        null));
            } catch (RuntimeException e) {
                skipped++;
                log.warning("Migração: compra " + id + " ignorada (" + e.getMessage() + ")");
            }
        }
        boolean persisted = store.addAll(imported);
        log.info("Migração: " + imported.size() + " compra(s) lida(s) de pending.yml, " + skipped + " ignorada(s).");
        return new Result(imported.size(), skipped, persisted);
    }

    void archive() throws IOException {
        if (!Files.exists(legacy)) return;
        Path target = legacy.resolveSibling("RivooPoc.migrated");
        if (Files.exists(target)) target = legacy.resolveSibling("RivooPoc.migrated-" + System.currentTimeMillis());
        Files.move(legacy, target);
        log.info("Migração concluída. Originais em plugins/" + target.getFileName() + " (remova o jar antigo rivoo-minecraft-poc-*.jar).");
    }
}
