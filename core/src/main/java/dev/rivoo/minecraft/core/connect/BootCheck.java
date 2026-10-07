package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Na subida com server.key: o motor já roda; isto só confirma de qual servidor é a chave.
 * Bloqueante (thread assíncrona); `mainThread` aplica a info na thread do jogo.
 */
public final class BootCheck {
    private BootCheck() {}

    public static void run(RivooApi api, DeliveryEngine bootEngine, EngineHolder holder, Logger log,
                           Executor mainThread) {
        try {
            ServerInfo info = api.gameServer();
            // Um rivoo secret rodado enquanto isto esperava já trocou motor e info: não sobrescrever.
            mainThread.execute(() -> holder.swapInfoIf(bootEngine, info));
            log.info(SecretConnector.connectedMessage(info));
        } catch (ApiException e) {
            // Chave revogada (401/403) ou sem servidor (404): o aviso de chave revogada, uma vez só.
            if (e.httpStatus == 401 || e.httpStatus == 403 || e.httpStatus == 404) bootEngine.markRevoked();
            else log.warning("Não foi possível confirmar a chave agora: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warning("Não foi possível confirmar a chave agora: " + e.getMessage());
        }
    }
}
