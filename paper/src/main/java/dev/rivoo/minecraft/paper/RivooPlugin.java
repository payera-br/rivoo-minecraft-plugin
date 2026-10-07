package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.RivooClient;
import dev.rivoo.minecraft.core.catalog.Catalog;
import dev.rivoo.minecraft.core.connect.BootCheck;
import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.connect.KeyFile;
import dev.rivoo.minecraft.core.connect.PairingFlow;
import dev.rivoo.minecraft.core.connect.SecretConnector;
import dev.rivoo.minecraft.core.delivery.DeliveryConfig;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import dev.rivoo.minecraft.paper.commands.AdminCommand;
import dev.rivoo.minecraft.paper.commands.BuyCommand;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
import dev.rivoo.minecraft.paper.hud.PixHud;
import dev.rivoo.minecraft.paper.listener.PlayerListener;
import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.paper.map.MapGuard;
import dev.rivoo.minecraft.paper.map.QrMaps;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Monta as peças. Nenhuma regra de negócio aqui: o fluxo de dinheiro está no core. */
public final class RivooPlugin extends JavaPlugin {
    public static final String NOT_CONNECTED =
            "Loja não conectada. Veja o código de pareamento no console (rivoo connect gera outro) ou rode: rivoo secret <chave>";

    private final EngineHolder holder = new EngineHolder();
    private PairingFlow pairing;

    @Override
    public void onEnable() {
        LegacyMigration migration = new LegacyMigration(getDataFolder().toPath().getParent(), getLogger());
        try {
            if (migration.copyConfig(getDataFolder().toPath())) reloadConfig();
        } catch (java.io.IOException e) {
            getLogger().warning("Migração: falha ao copiar config.yml: " + e.getMessage());
        }
        saveDefaultConfig();

        KeyFile keyFile = new KeyFile(getDataFolder().toPath().resolve("server.key"));
        Optional<String> serverKey;
        try {
            serverKey = keyFile.read();
        } catch (java.io.IOException e) {
            getLogger().severe("Falha lendo plugins/Rivoo/server.key: " + e.getMessage());
            serverKey = Optional.empty();
        }
        String legacyKey = getConfig().getString("rivoo.api-key", "");
        boolean legacy = serverKey.isEmpty() && !legacyKey.isBlank() && !legacyKey.startsWith("COLOQUE");

        PurchaseStore store = new PurchaseStore(getDataFolder().toPath().resolve("purchases.json"), getLogger(),
                Clock.systemUTC());
        try {
            store.load();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage() + " Plugin desativado.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LegacyMigration.Result imported = migration.importPurchases(store);
        if (imported.complete()) {
            try {
                migration.archive();
            } catch (java.io.IOException e) {
                getLogger().warning("Migração: falha ao arquivar plugins/RivooPoc: " + e.getMessage());
            }
        } else {
            getLogger().severe("Migração incompleta: " + imported.skipped() + " compra(s) não importada(s)"
                    + (imported.persisted() ? "" : " / falha ao gravar purchases.json")
                    + ". plugins/RivooPoc mantido; corrija e reinicie.");
        }
        Catalog catalog = PaperConfig.catalog(getConfig(), !legacy);
        PixHud hud = new PixHud(this);
        QrMaps maps = new QrMaps(new NamespacedKey(this, "charge-id"), store,
                p -> catalog.find(p.sku).map(Product::name).orElse(p.sku));
        PaperPurchaseView view = new PaperPurchaseView(getServer(), hud, maps, store);

        SecretConnector connector = new SecretConnector(this::clientFor, keyFile,
                (api, info) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (pairing != null) pairing.stop();
                    holder.swap(engineFor(api, PaperConfig.serverKeyDelivery(getConfig()), store, catalog, view), info);
                }));
        pairing = new PairingFlow(new RivooClient(apiUrl(), null, getPluginMeta().getVersion()), connector,
                new PairingFlow.ServerFacts(getPluginMeta().getVersion(), Bukkit.getMinecraftVersion(),
                        PlainTextComponentSerializer.plainText().serialize(getServer().motd()), getServer().getPort()),
                Clock.systemUTC(), getLogger(), this::announce, PairingFlow::randomSecret);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, pairing::tick, 20L, 20L);

        BuyCommand buy = new BuyCommand(holder, catalog, view);
        Objects.requireNonNull(getCommand("comprar")).setExecutor(buy);
        Objects.requireNonNull(getCommand("comprar")).setTabCompleter(buy);
        AdminCommand admin = new AdminCommand(holder, store, connector, pairing,
                task -> Bukkit.getScheduler().runTaskAsynchronously(this, task), getLogger());
        Objects.requireNonNull(getCommand("rivoo")).setExecutor(admin);
        Objects.requireNonNull(getCommand("rivoo")).setTabCompleter(admin);
        MapGuard guard = new MapGuard(maps, store);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, holder, store, maps, hud, view, guard), this);
        Bukkit.getScheduler().runTaskTimer(this, view::tickSecond, 20L, 20L);

        getServer().getPluginManager().registerEvents(maps, this);
        getServer().getPluginManager().registerEvents(guard, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> guard.sweepOnline(getServer()), 600L, 600L);
        maps.restoreAll();

        if (serverKey.isPresent()) {
            RivooApi api = clientFor(serverKey.get());
            DeliveryEngine boot = engineFor(api, PaperConfig.serverKeyDelivery(getConfig()), store, catalog, view);
            holder.swap(boot, null);
            if (serverKey.get().startsWith("cpk_sandbox_")) {
                getLogger().warning("Chave SANDBOX: Pix simulado, use /rivoo simular para pagar.");
            }
            // Só informativo: o motor já roda. Revogada → o aviso de chave revogada, uma vez.
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> BootCheck.run(api, boot, holder, getLogger(),
                    task -> Bukkit.getScheduler().runTask(this, task)));
        } else if (legacy) {
            if (legacyKey.startsWith("cpk_sandbox_")) {
                getLogger().warning("Chave SANDBOX: cobranças em devMode, use /rivoo simular para pagar.");
            }
            holder.swap(engineFor(clientFor(legacyKey), PaperConfig.delivery(getConfig()), store, catalog, view), null);
        } else {
            pairing.start();
        }
        getLogger().info("Rivoo ativo. Pendentes: " + store.inState(State.PENDING).size());
    }

    private String apiUrl() {
        return getConfig().getString("rivoo.api-url", "https://api.rivoopay.com");
    }

    private RivooApi clientFor(String key) {
        return new RivooClient(apiUrl(), key, getPluginMeta().getVersion());
    }

    /** Console + ops online: quem vê o console também sabe para qual loja o servidor foi. */
    private void announce(String message) {
        getLogger().info(message);
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().stream()
                .filter(Player::isOp).forEach(p -> p.sendMessage(message)));
    }

    private DeliveryEngine engineFor(RivooApi api, DeliveryConfig config, PurchaseStore store, Catalog catalog,
                                     PaperPurchaseView view) {
        DeliveryEngine engine = new DeliveryEngine(api, store, catalog, new PaperScheduler(this),
                new PaperPlayers(getServer()), cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd), view,
                config, getLogger(), Clock.systemUTC());
        // Chave revogada (no polling ou na checagem de subida): o pareamento recomeça sozinho.
        engine.onRevoked(pairing::startIfIdle);
        return engine;
    }

    @Override
    public void onDisable() {
        if (pairing != null) pairing.stop();
        holder.stop();
    }
}
