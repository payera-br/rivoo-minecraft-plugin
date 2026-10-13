package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.RivooClient;
import dev.rivoo.minecraft.core.api.ServerInfo;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * rivoo secret (chave rck_ criada no painel) e o fim do pareamento: valida a chave na Rivoo
 * ({@code GET /v1/agent/connection}), grava e só então troca o motor. Bloqueante.
 */
public final class SecretConnector {
    public static final String REJECTED = "Chave inválida ou revogada (a chave começa com rck_); gere outra no painel.";
    public static final String UNREACHABLE = "Não foi possível falar com a Rivoo; nada mudou.";

    public sealed interface Result permits Connected, Rejected, Unreachable {}
    public record Connected(ServerInfo info) implements Result {}
    public record Rejected() implements Result {}
    public record Unreachable() implements Result {}

    private final Function<String, RivooApi> apiFor;
    private final KeyFile keyFile;
    private final BiConsumer<RivooApi, ServerInfo> onConnected;

    public SecretConnector(Function<String, RivooApi> apiFor, KeyFile keyFile,
                           BiConsumer<RivooApi, ServerInfo> onConnected) {
        this.apiFor = apiFor;
        this.keyFile = keyFile;
        this.onConnected = onConnected;
    }

    public static String connectedMessage(ServerInfo info) {
        return "Conectado: " + info.serverName() + " · projeto " + info.projectName();
    }

    public Result connect(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.startsWith(RivooClient.KEY_PREFIX)) return new Rejected();
        RivooApi api = apiFor.apply(key);
        ServerInfo info;
        try {
            info = api.connection();
        } catch (ApiException e) {
            if (e.unauthorized()) return new Rejected();
            return new Unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unreachable();
        } catch (IOException e) {
            return new Unreachable();
        }
        try {
            keyFile.write(key);
        } catch (IOException e) {
            return new Unreachable();
        }
        onConnected.accept(api, info);
        return new Connected(info);
    }
}
