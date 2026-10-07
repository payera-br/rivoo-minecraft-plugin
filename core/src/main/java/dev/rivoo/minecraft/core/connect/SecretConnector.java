package dev.rivoo.minecraft.core.connect;

import dev.rivoo.minecraft.core.api.ApiException;
import dev.rivoo.minecraft.core.api.RivooApi;
import dev.rivoo.minecraft.core.api.ServerInfo;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** rivoo secret: valida a chave na Rivoo, grava e só então troca o motor. Bloqueante. */
public final class SecretConnector {
    public static final String REJECTED = "Chave inválida ou revogada; gere outra no painel.";
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
        return "Conectado: " + info.serverName() + " · projeto " + info.projectName()
                + " · modo " + (info.sandbox() ? "TESTE" : "PRODUÇÃO");
    }

    public Result connect(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.startsWith("cpk_sandbox_") && !key.startsWith("cpk_live_")) return new Rejected();
        RivooApi api = apiFor.apply(key);
        ServerInfo info;
        try {
            info = api.gameServer();
        } catch (ApiException e) {
            if (e.httpStatus == 401 || e.httpStatus == 403 || e.httpStatus == 404) return new Rejected();
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
