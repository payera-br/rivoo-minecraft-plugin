package dev.rivoo.minecraft.core.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Resposta de erro da API: RFC 9457 ({@code {type,title,status,code,detail,errors}}). */
public final class ApiException extends IOException {
    public final int httpStatus;
    public final long retryAfterSeconds;
    /** {@code code} do problem (ex.: product_not_found); null se o corpo não for um problem. */
    public final String code;
    /** {@code title} do problem, em português e mostrável ao jogador; null se ausente. */
    public final String title;

    public ApiException(int httpStatus, String body, long retryAfterSeconds) {
        super("HTTP " + httpStatus + ": " + body);
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
        JsonObject problem = parse(body);
        this.code = field(problem, "code");
        this.title = field(problem, "title");
    }

    public boolean rateLimited() {
        return httpStatus == 429;
    }

    /** Chave revogada, de conexão removida ou inválida. */
    public boolean unauthorized() {
        return httpStatus == 401 || httpStatus == 403;
    }

    private static JsonObject parse(String body) {
        try {
            return body == null ? null : JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String field(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
