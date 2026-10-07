package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;
import dev.rivoo.minecraft.core.fixtures.EngineHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BootCheckTest {
    @TempDir Path dir;

    @Test void confirmsTheServerOfTheBootKey() {
        EngineHarness h = new EngineHarness(dir);
        EngineHolder holder = new EngineHolder();
        DeliveryEngine boot = h.build("game");
        holder.swap(boot, null);

        BootCheck.run(h.api, boot, holder, h.log.logger, Runnable::run);

        assertThat(holder.info()).contains(h.api.serverInfo);
        assertThat(h.log.messages).contains(SecretConnector.connectedMessage(h.api.serverInfo));
    }

    @Test void aLateAnswerNeverOverwritesAKeyConnectedMeanwhile() {
        EngineHarness a = new EngineHarness(dir.resolve("a"));
        EngineHarness b = new EngineHarness(dir.resolve("b"));
        EngineHolder holder = new EngineHolder();
        DeliveryEngine boot = a.build("game");
        holder.swap(boot, null);
        ServerInfo fromSecret = new ServerInfo("gsrv_b", "Lobby", "Rivoo Craft", false);
        holder.swap(b.build("game"), fromSecret); // rivoo secret <B> before the boot check returned

        BootCheck.run(a.api, boot, holder, a.log.logger, Runnable::run);

        assertThat(holder.info()).contains(fromSecret);
    }

    @Test void aRevokedBootKeyGivesTheRevokedWarningOnce() {
        EngineHarness h = new EngineHarness(dir);
        h.api.gameServerError = new ApiException(401, "{}", 0);
        EngineHolder holder = new EngineHolder();
        DeliveryEngine boot = h.build("game");
        holder.swap(boot, null);

        BootCheck.run(h.api, boot, holder, h.log.logger, Runnable::run);

        assertThat(boot.revoked()).isTrue();
        assertThat(h.log.warnings).containsExactly(DeliveryEngine.REVOKED_MESSAGE);
        assertThat(holder.info()).isEmpty();
    }

    @Test void aKeyWithoutServerIsInvalidToo() {
        EngineHarness h = new EngineHarness(dir);
        h.api.gameServerError = new ApiException(404, "{}", 0);
        EngineHolder holder = new EngineHolder();
        DeliveryEngine boot = h.build("game");
        holder.swap(boot, null);

        BootCheck.run(h.api, boot, holder, h.log.logger, Runnable::run);

        assertThat(boot.revoked()).isTrue();
    }

    @Test void aNetworkErrorKeepsTheEngineAndSaysSo() {
        EngineHarness h = new EngineHarness(dir);
        h.api.gameServerError = new IOException("connection refused");
        EngineHolder holder = new EngineHolder();
        DeliveryEngine boot = h.build("game");
        holder.swap(boot, null);

        BootCheck.run(h.api, boot, holder, h.log.logger, Runnable::run);

        assertThat(boot.revoked()).isFalse();
        assertThat(h.log.warnings).containsExactly("Não foi possível confirmar a chave agora: connection refused");
    }
}
