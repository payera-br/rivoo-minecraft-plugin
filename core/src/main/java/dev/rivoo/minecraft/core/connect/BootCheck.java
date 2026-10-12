package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Na subida com server.key: o motor já roda; isto só confirma de qual conexão é a chave.
 * Bloqueante (thread assíncrona); `mainThread` aplica a info na thread do jogo.
 */
public final class BootCheck {
    private BootCheck() {}

    public static void run(RivooApi api, DeliveryEngine bootEngine, EngineHolder holder, Logger log,
                           Executor mainThread, String pluginVersion) {
        try {
            ServerInfo info = api.connection();
            // Um rivoo secret rodado enquanto isto esperava já trocou motor e info: não sobrescrever.
            mainThread.execute(() -> holder.swapInfoIf(bootEngine, info));
            log.info(SecretConnector.connectedMessage(info));
            warnIfOutdated(info, pluginVersion, log);
        } catch (ApiException e) {
            // Chave revogada ou conexão removida: o aviso de chave revogada, uma vez só.
            if (e.unauthorized()) bootEngine.markRevoked();
            else log.warning("Não foi possível confirmar a chave agora: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Não foi possível confirmar a chave agora: " + e.getMessage());
        }
    }

    /** A Rivoo informa a versão mínima do plugin: abaixo dela, avisa o operador (não bloqueia nada). */
    public static void warnIfOutdated(ServerInfo info, String pluginVersion, Logger log) {
        if (info.outdated(pluginVersion)) {
            log.warning("Esta versão do plugin (" + pluginVersion + ") é anterior à mínima aceita pela Rivoo ("
                    + info.minAgentVersion() + "). Atualize o plugin.");
        }
    }
}
