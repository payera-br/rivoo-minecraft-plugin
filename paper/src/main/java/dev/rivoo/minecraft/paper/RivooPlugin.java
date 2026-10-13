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
import dev.rivoo.minecraft.core.delivery.AckStore;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import dev.rivoo.minecraft.paper.commands.AdminCommand;
import dev.rivoo.minecraft.paper.commands.BuyCommand;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
import dev.rivoo.minecraft.paper.menu.ShopMenu;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        saveDefaultConfig();

        KeyFile keyFile = new KeyFile(getDataFolder().toPath().resolve("server.key"));
        Optional<String> serverKey;
        try {
            serverKey = keyFile.read();
        } catch (java.io.IOException e) {
            getLogger().severe("Falha lendo plugins/Rivoo/server.key: " + e.getMessage());
            serverKey = Optional.empty();
        }
        if (serverKey.isPresent() && !serverKey.get().startsWith(RivooClient.KEY_PREFIX)) {
            // Chave cpk_ das versões 0.x: a Rivoo não a aceita mais. Pareamento novo; o arquivo é sobrescrito.
            getLogger().warning("server.key é da versão antiga do plugin (antes da 1.0.0) e não vale na Rivoo atual."
                    + " Pareie de novo com o código abaixo.");
            serverKey = Optional.empty();
        }
        if (!getConfig().getString("rivoo.api-key", "").isBlank()) {
            getLogger().warning("rivoo.api-key no config.yml não é mais usada (chave de empresa). Pareie o servidor pelo código.");
        }

        PurchaseStore store = new PurchaseStore(getDataFolder().toPath().resolve("purchases.json"), getLogger(),
                Clock.systemUTC());
        try {
            store.load();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage() + " Plugin desativado.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Catalog catalog = new Catalog(List.of());
        PixHud hud = new PixHud(this);
        QrMaps maps = new QrMaps(new NamespacedKey(this, "charge-id"), store,
                p -> catalog.find(p.sku).map(Product::name).orElse(p.sku));
        PaperPurchaseView view = new PaperPurchaseView(getServer(), hud, maps, store);

        String version = getPluginMeta().getVersion();
        PairingFlow.ServerFacts facts = new PairingFlow.ServerFacts(version, Bukkit.getMinecraftVersion(),
                PlainTextComponentSerializer.plainText().serialize(getServer().motd()), getServer().getPort());
        SecretConnector connector = new SecretConnector(this::clientFor, keyFile,
                (api, info) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (pairing != null) pairing.stop();
                    BootCheck.warnIfOutdated(info, version, getLogger());
                    holder.swap(engineFor(api, facts, store, catalog, view), info);
                }));
        pairing = new PairingFlow(new RivooClient(apiUrl(), null, version), connector, facts,
                Clock.systemUTC(), getLogger(), this::announce, PairingFlow::randomSecret);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, pairing::tick, 20L, 20L);

        ShopMenu menu = new ShopMenu();
        getServer().getPluginManager().registerEvents(menu, this);
        BuyCommand buy = new BuyCommand(holder, catalog, view, menu);
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
            DeliveryEngine boot = engineFor(api, facts, store, catalog, view);
            holder.swap(boot, null);
            // Só informativo: o motor já roda. Revogada → o aviso de chave revogada, uma vez.
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> BootCheck.run(api, boot, holder, getLogger(),
                    task -> Bukkit.getScheduler().runTask(this, task), version));
        } else {
            pairing.start();
        }
        getLogger().info("Rivoo ativo. Pix abertos: " + store.inState(State.PENDING).size());
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

    private DeliveryEngine engineFor(RivooApi api, PairingFlow.ServerFacts facts, PurchaseStore store, Catalog catalog,
                                     PaperPurchaseView view) {
        // Mesmos campos que o pareamento manda: o painel mostra os dados do servidor.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gameVersion", facts.mcVersion());
        details.put("motd", RivooClient.cleanMotd(facts.motd()));
        if (facts.port() > 0) details.put("port", facts.port());
        DeliveryConfig config = PaperConfig.delivery(getConfig(), facts.pluginVersion(), details);
        DeliveryEngine engine = new DeliveryEngine(api, store, catalog, new PaperScheduler(this),
                new PaperPlayers(getServer()), cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd), view,
                config, new AckStore(getDataFolder().toPath().resolve("acks.json"), getLogger()), getLogger(),
                Clock.systemUTC());
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
