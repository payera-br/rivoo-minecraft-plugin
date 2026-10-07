package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.fixtures.FakeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretConnectorTest {
    @TempDir Path dir;
    FakeApi api = new FakeApi();
    List<String> keysAsked = new ArrayList<>();
    List<ServerInfo> connected = new ArrayList<>();
    KeyFile keyFile;
    SecretConnector connector;

    @BeforeEach void setUp() {
        keyFile = new KeyFile(dir.resolve("server.key"));
        connector = new SecretConnector(k -> { keysAsked.add(k); return api; }, keyFile,
                (RivooApi a, ServerInfo info) -> connected.add(info));
    }

    @Test void validKeyIsSavedAndConnects() throws Exception {
        var r = connector.connect("  cpk_sandbox_abc\n");
        assertThat(r).isEqualTo(new SecretConnector.Connected(api.serverInfo));
        assertThat(keysAsked).containsExactly("cpk_sandbox_abc");
        assertThat(keyFile.read()).contains("cpk_sandbox_abc");
        assertThat(connected).containsExactly(api.serverInfo);
        assertThat(SecretConnector.connectedMessage(api.serverInfo))
                .isEqualTo("Conectado: Survival · projeto Rivoo Craft · modo TESTE");
    }

    @Test void liveSaysProducao() {
        assertThat(SecretConnector.connectedMessage(new ServerInfo("s", "S", "P", false)))
                .isEqualTo("Conectado: S · projeto P · modo PRODUÇÃO");
    }

    @Test void notAKeyIsRejectedWithoutCallingTheApi() throws Exception {
        assertThat(connector.connect("hello")).isEqualTo(new SecretConnector.Rejected());
        assertThat(keysAsked).isEmpty();
        assertThat(keyFile.read()).isEmpty();
    }

    @Test void unauthorizedKeepsThePreviousKey() throws Exception {
        keyFile.write("cpk_sandbox_old");
        api.gameServerError = new ApiException(401, "{}", 0);
        assertThat(connector.connect("cpk_sandbox_new")).isEqualTo(new SecretConnector.Rejected());
        assertThat(keyFile.read()).contains("cpk_sandbox_old");
        assertThat(connected).isEmpty();
    }

    @Test void companyKeyIs404AndRejected() {
        api.gameServerError = new ApiException(404, "{}", 0);
        assertThat(connector.connect("cpk_live_company")).isEqualTo(new SecretConnector.Rejected());
    }

    @Test void networkFailureChangesNothing() throws Exception {
        api.gameServerError = new IOException("connection refused");
        assertThat(connector.connect("cpk_sandbox_new")).isEqualTo(new SecretConnector.Unreachable());
        assertThat(keyFile.read()).isEmpty();
        assertThat(connected).isEmpty();
    }
}
