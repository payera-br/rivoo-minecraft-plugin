package dev.rivoo.minecraft.paper.commands;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.connect.EngineHolder;
import dev.rivoo.minecraft.core.connect.KeyFile;
import dev.rivoo.minecraft.core.connect.PairingFlow;
import dev.rivoo.minecraft.core.connect.SecretConnector;
import dev.rivoo.minecraft.core.fixtures.CapturingLog;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import dev.rivoo.minecraft.core.fixtures.FakePairingApi;
import dev.rivoo.minecraft.paper.RivooPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import org.bukkit.command.RemoteConsoleCommandSender;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommandTest {
    @TempDir Path dir;
    ServerMock server;
    EngineHarness h;
    AdminCommand command;
    PlayerMock steve;

    EngineHolder holder;
    SecretConnector connector;
    FakePairingApi pairingApi;
    PairingFlow pairing;
    List<String> announced = new ArrayList<>();
    CapturingLog log;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        log = new CapturingLog(); // depois do mock: ele reinicia o LogManager e tira os handlers
        steve = new PlayerMock(server, "Steve", EngineHarness.STEVE);
        server.addPlayer(steve);
        h = new EngineHarness(dir);
        holder = new EngineHolder();
        holder.swap(h.build("fulfillments"), null);
        connector = new SecretConnector(k -> h.api, new KeyFile(dir.resolve("server.key")),
                (api, info) -> holder.swap(h.build("game"), info));
        pairingApi = new FakePairingApi();
        pairing = new PairingFlow(pairingApi, connector,
                new PairingFlow.ServerFacts("0.3.0", "1.21.4", "Rivoo Craft", 25565), h.clock, log.logger,
                announced::add, () -> "s".repeat(32));
        command = new AdminCommand(holder, h.store, connector, pairing, Runnable::run, log.logger);
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void pendingShowsModeAndCounts() {
        h.engine.buy(EngineHarness.STEVE, "Steve", "vip").join();
        command.onCommand(steve, null, "rivoo", new String[]{"pendentes"});
        assertThat(steve.nextMessage()).isEqualTo("Modo de entrega: fulfillments (canal minecraft)");
        assertThat(steve.nextMessage()).isEqualTo("Pendentes: 1 | Pagas aguardando entrega: 0");
        assertThat(steve.nextMessage()).startsWith(" PENDING charge_1 Steve vip");
    }

    @Test void simulateUsesOwnLatestPending() {
        h.engine.buy(EngineHarness.STEVE, "Steve", "vip").join();
        command.onCommand(steve, null, "rivoo", new String[]{"simular"});
        assertThat(h.api.simulated).containsExactly("charge_1");
    }

    @Test void simulateWithoutPendingExplains() {
        command.onCommand(steve, null, "rivoo", new String[]{"simular"});
        assertThat(steve.nextMessage()).isEqualTo("Nenhum Pix pendente seu. Use /rivoo simular <chargeId>.");
    }

    @Test void usage() {
        command.onCommand(steve, null, "rivoo", new String[]{});
        assertThat(steve.nextMessage()).isEqualTo("Uso: /rivoo <status|simular [chargeId]|secret <chave>|connect>");
    }

    @Test void secretFromAPlayerIsRefusedAndNeverTouchesTheApi() {
        command.onCommand(steve, null, "rivoo", new String[]{"secret", "cpk_sandbox_abc"});
        assertThat(steve.nextMessage()).isEqualTo(AdminCommand.CONSOLE_ONLY);
        assertThat(h.api.gameServerCalls.get()).isZero();
    }

    @Test void secretFromConsoleConnectsAndSwaps() {
        var console = server.getConsoleSender();
        var before = holder.engine().orElseThrow();
        command.onCommand(console, null, "rivoo", new String[]{"secret", "cpk_sandbox_abc"});
        assertThat(((org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) console).nextMessage())
                .isEqualTo("Conectado: Survival · projeto Rivoo Craft · modo TESTE");
        assertThat(holder.engine()).isPresent().get().isNotSameAs(before);
        assertThat(holder.info()).contains(h.api.serverInfo);
    }

    @Test void rejectedSecretKeepsTheEngine() {
        h.api.gameServerError = new ApiException(401, "{}", 0);
        var before = holder.engine().orElseThrow();
        var console = (org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender();
        command.onCommand(console, null, "rivoo", new String[]{"secret", "cpk_sandbox_abc"});
        assertThat(console.nextMessage()).isEqualTo(SecretConnector.REJECTED);
        assertThat(holder.engine()).contains(before);
    }

    @Test void secretOverRconAnswersBeforeTheSessionEndsAndLogsTheResult() {
        List<String> rcon = new ArrayList<>();
        List<Runnable> later = new ArrayList<>();
        command = new AdminCommand(holder, h.store, connector, pairing, later::add, log.logger);
        command.onCommand(rcon(rcon), null, "rivoo", new String[]{"secret", "cpk_sandbox_abc"});
        // RCON só devolve o que foi enviado durante onCommand; o resultado chega depois.
        assertThat(rcon).containsExactly(AdminCommand.VALIDATING);
        later.forEach(Runnable::run);
        assertThat(log.messages).containsExactly("Conectado: Survival · projeto Rivoo Craft · modo TESTE");
        assertThat(String.join("\n", log.messages)).doesNotContain("cpk_sandbox_abc");
    }

    @Test void consoleSecretRepliesToTheConsoleOnly() {
        var console = (org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender();
        command.onCommand(console, null, "rivoo", new String[]{"secret", "cpk_sandbox_abc"});
        assertThat(console.nextMessage()).isEqualTo("Conectado: Survival · projeto Rivoo Craft · modo TESTE");
        assertThat(log.messages).isEmpty();
    }

    private static RemoteConsoleCommandSender rcon(List<String> sent) {
        return (RemoteConsoleCommandSender) Proxy.newProxyInstance(AdminCommandTest.class.getClassLoader(),
                new Class<?>[]{RemoteConsoleCommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof String m) {
                        sent.add(m);
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test void secretWithoutKeyShowsUsage() {
        var console = (org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender();
        command.onCommand(console, null, "rivoo", new String[]{"secret"});
        assertThat(console.nextMessage()).isEqualTo("Uso: rivoo secret <chave> (no console)");
    }

    @Test void statusWithoutEngineExplainsHowToConnect() {
        holder.stop();
        command.onCommand(steve, null, "rivoo", new String[]{"status"});
        assertThat(steve.nextMessage()).isEqualTo(RivooPlugin.NOT_CONNECTED);
    }

    @Test void statusShowsServerAndKeyMode() {
        holder.swap(h.build("game"), new ServerInfo("s", "Survival", "Rivoo Craft", true));
        command.onCommand(steve, null, "rivoo", new String[]{"status"});
        assertThat(steve.nextMessage()).isEqualTo("Servidor: Survival · projeto Rivoo Craft · modo TESTE");
    }

    @Test void connectFromAPlayerIsRefused() {
        command.onCommand(steve, null, "rivoo", new String[]{"connect"});
        assertThat(steve.nextMessage()).isEqualTo(AdminCommand.CONSOLE_ONLY);
        assertThat(pairing.active()).isFalse();
    }

    @Test void connectFromConsoleStartsPairing() {
        var console = (org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender();
        command.onCommand(console, null, "rivoo", new String[]{"connect"});
        assertThat(console.nextMessage()).isEqualTo(AdminCommand.PAIRING_STARTED);
        assertThat(pairing.active()).isTrue();
        pairing.tick();
        assertThat(pairingApi.startedSecrets).hasSize(1);
    }

    @Test void statusWithoutAStoreShowsThePairingCode() {
        holder.stop();
        pairing.start();
        pairing.tick();
        command.onCommand(steve, null, "rivoo", new String[]{"status"});
        assertThat(steve.nextMessage()).isEqualTo("Aguardando pareamento: código K7QM-2XPA (expira em 10 min)");
    }
}
