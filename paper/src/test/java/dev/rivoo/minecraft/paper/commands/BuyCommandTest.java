package dev.rivoo.minecraft.paper.commands;

import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.paper.hud.PaperPurchaseView;
import dev.rivoo.minecraft.paper.hud.PixHud;
import dev.rivoo.minecraft.paper.map.QrMaps;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuyCommandTest {
    @TempDir Path dir;
    ServerMock server;
    EngineHarness h;
    BuyCommand command;
    EngineHolder holder;
    PlayerMock steve;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("Rivoo");
        steve = new PlayerMock(server, "Steve", EngineHarness.STEVE);
        server.addPlayer(steve);
        h = new EngineHarness(dir);
        holder = new EngineHolder();
        holder.swap(h.build("fulfillments"), null);
        QrMaps maps = new QrMaps(new NamespacedKey(plugin, "charge-id"), h.store, p -> p.sku);
        PaperPurchaseView view = new PaperPurchaseView(server, new PixHud(plugin), maps, h.store);
        command = new BuyCommand(holder, h.catalog, view);
        server.getCommandMap(); // garante o mapa de comandos inicializado
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    String drain(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = p.nextMessage()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    @Test void consoleIsRefused() {
        command.onCommand(server.getConsoleSender(), null, "comprar", new String[]{"vip"});
        assertThat(server.getConsoleSender().nextMessage()).isEqualTo("Somente jogadores.");
    }

    @Test void noArgsListsProducts() {
        command.onCommand(steve, null, "comprar", new String[]{});
        String out = drain(steve);
        assertThat(out).contains("Produtos disponíveis:", "VIP Mensal - R$ 19,90", "Kit Inicial - R$ 1,50");
        assertThat(h.api.creates).isEmpty();
    }

    @Test void buyGivesMapAndPaymentInfo() {
        command.onCommand(steve, null, "comprar", new String[]{"vip"});
        String out = drain(steve);
        assertThat(out).contains("Gerando Pix de R$ 19,90...", "Pix de R$ 19,90 - expira em", "Segure o mapa para ver o QR Code.");
        assertThat(steve.getInventory().getItemInMainHand().getType()).isEqualTo(org.bukkit.Material.FILLED_MAP);
        assertThat(h.api.creates).hasSize(1);
    }

    @Test void secondBuyReshowsOpenPix() {
        command.onCommand(steve, null, "comprar", new String[]{"vip"});
        drain(steve);
        command.onCommand(steve, null, "comprar", new String[]{"kit"});
        assertThat(drain(steve)).contains("Você já tem um Pix aguardando pagamento:");
        assertThat(h.api.creates).hasSize(1);
    }

    @Test void failureIsReported() {
        h.api.createError = new java.io.IOException("down");
        command.onCommand(steve, null, "comprar", new String[]{"vip"});
        assertThat(drain(steve)).contains("Não foi possível gerar o Pix. Tente novamente.");
    }

    @Test void tabCompletesSkus() {
        assertThat(command.onTabComplete(steve, null, "comprar", new String[]{""})).containsExactly("vip", "kit");
    }

    @Test void notConfiguredWithoutEngine() {
        holder.stop();
        command.onCommand(steve, null, "comprar", new String[]{"vip"});
        assertThat(steve.nextMessage()).isEqualTo(BuyCommand.NOT_CONFIGURED);
    }
}
