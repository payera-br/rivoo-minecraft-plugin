package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ServerInfo;
import dev.rivoo.minecraft.core.delivery.DeliveryEngine;

import java.util.Optional;

/** Motor atual, trocável sem reiniciar (rivoo secret). Chamar swap/stop na thread do jogo. */
public final class EngineHolder {
    private volatile DeliveryEngine engine;
    private volatile ServerInfo info;

    public Optional<DeliveryEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** Servidor da chave atual; vazio no modo legado (chave de empresa) ou antes de conectar. */
    public Optional<ServerInfo> info() {
        return Optional.ofNullable(info);
    }

    public synchronized void swap(DeliveryEngine next, ServerInfo nextInfo) {
        DeliveryEngine old = engine;
        engine = next;
        info = nextInfo;
        if (old != null) old.stop();
        if (next != null) next.start();
    }

    /** Confirma a info do servidor sem trocar o motor (validação feita depois do start). */
    public void swapInfo(ServerInfo nextInfo) {
        info = nextInfo;
    }

    /** swapInfo só se o motor ainda for `expected` (resposta atrasada de um motor já trocado). */
    public synchronized void swapInfoIf(DeliveryEngine expected, ServerInfo nextInfo) {
        if (engine == expected) info = nextInfo;
    }

    public void stop() {
        swap(null, null);
    }
}
