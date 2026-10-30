package dev.rivoo.minecraft.core.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rivoo.minecraft.core.catalog.Product;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cliente mínimo do protocolo do agente da Rivoo ({@code /v1/agent}). Chamadas bloqueantes: usar fora da main thread. */
public final class RivooClient implements RivooApi, PairingApi {
    /** Tipo da conexão na Rivoo. */
    public static final String TYPE = "MINECRAFT";
    /** Prefixo das chaves de conexão; a chave pareada é {@code rck_<segredo>}. */
    public static final String KEY_PREFIX = "rck_";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String pluginVersion;

    public RivooClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    public RivooClient(String baseUrl, String apiKey, String pluginVersion) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.pluginVersion = pluginVersion;
    }

    /** sha256 em hex minúsculo, como a Rivoo compara os hashes do pareamento. */
    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** MOTD sem formatação (§x) nem caracteres de controle, até 100 caracteres (o que a Rivoo guarda). */
    public static String cleanMotd(String motd) {
        if (motd == null) return null;
        String clean = motd.replaceAll("§.", "").replaceAll("[\\x00-\\x1f\\x7f]", "");
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    // ---- pareamento (público) ----

    @Override
    public PairingTicket startPairing(String secret, String agentVersion, String gameVersion, String motd, int port)
            throws IOException, InterruptedException {
        JsonObject server = new JsonObject();
        if (gameVersion != null) server.addProperty("gameVersion", cut(gameVersion, 32));
        if (motd != null) server.addProperty("motd", cleanMotd(motd));
        if (port > 0 && port <= 65535) server.addProperty("port", port);
        JsonObject body = new JsonObject();
        body.addProperty("type", TYPE);
        body.addProperty("agentVersion", cut(agentVersion == null ? "dev" : agentVersion, 32));
        // O segredo só viaja na consulta de status; aqui vão o hash de busca e o hash da chave futura.
        body.addProperty("lookupHash", sha256Hex("pair:" + secret));
        body.addProperty("keyHash", sha256Hex(KEY_PREFIX + secret));
        body.add("server", server);
        JsonObject data = send(post("/v1/agent/pairings", body).build());
        return new PairingTicket(str(data, "code"), instant(str(data, "expiresAt")),
                data.has("pollSeconds") ? data.get("pollSeconds").getAsInt() : 5);
    }

    @Override
    public PairingStatus pairingStatus(String secret) throws IOException, InterruptedException {
        JsonObject data = send(request("/v1/agent/pairings/current").header("Authorization", "Pairing " + secret)
                .GET().build());
        if (!"claimed".equals(str(data, "status"))) return PairingStatus.PENDING;
        return new PairingStatus(true, str(obj(data, "connection"), "name"), str(obj(data, "project"), "name"));
    }

    // ---- conexão (chave rck_) ----

    @Override
    public ServerInfo connection() throws IOException, InterruptedException {
        JsonObject data = send(authed("/v1/agent/connection").GET().build());
        JsonObject connection = obj(data, "connection");
        return new ServerInfo(str(connection, "id"), str(connection, "name"), str(obj(data, "project"), "name"),
                str(data, "minAgentVersion"));
    }

    @Override
    public void heartbeat(String agentVersion, Map<String, Object> details) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("agentVersion", cut(agentVersion == null ? "dev" : agentVersion, 32));
        if (details != null) body.add("details", GSON.toJsonTree(details));
        send(post("/v1/agent/heartbeat", body).header("Authorization", "Bearer " + apiKey).build());
    }

    @Override
    public List<Product> catalog() throws IOException, InterruptedException {
        return products(send(authed("/v1/agent/catalog").GET().build()));
    }

    /** Produtos da resposta do catálogo, na ordem das categorias. */
    static List<Product> products(JsonObject data) {
        List<Product> products = new ArrayList<>();
        if (!data.has("categories")) return products;
        for (JsonElement c : data.getAsJsonArray("categories")) {
            JsonObject cat = c.getAsJsonObject();
            if (!cat.has("products")) continue;
            for (JsonElement el : cat.getAsJsonArray("products")) {
                JsonObject o = el.getAsJsonObject();
                String sku = str(o, "sku");
                if (sku == null) continue;
                String name = str(o, "name");
                boolean giftable = o.has("giftable") && !o.get("giftable").isJsonNull() && o.get("giftable").getAsBoolean();
                products.add(new Product(sku, name == null ? sku : name, cents(obj(o, "price")), List.of(), str(o, "icon"), giftable));
            }
        }
        return products;
    }

    @Override
    public AgentOrder createOrder(String sku, UUID playerId, String playerName, String giftTo, String idempotencyKey)
            throws IOException, InterruptedException {
        JsonObject data = send(post("/v1/agent/orders", orderBody(sku, playerId, playerName, giftTo))
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .build());
        JsonObject order = obj(data, "order");
        JsonObject payment = obj(data, "payment");
        JsonObject next = obj(payment, "nextAction");
        boolean pix = next != null && "pix_qr".equals(str(next, "type")) && str(next, "brCode") != null;
        String expires = pix ? firstNonNull(str(next, "expiresAt"), str(payment, "expiresAt")) : null;
        return new AgentOrder(str(order, "id"), str(order, "status"), cents(obj(order, "total")),
                pix ? str(next, "brCode") : null, str(data, "checkoutUrl"), expires == null ? null : instant(expires));
    }

    @Override
    public String orderStatus(String orderId) throws IOException, InterruptedException {
        JsonObject data = send(authed("/v1/agent/orders/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8))
                .GET().build());
        return str(data, "status");
    }

    @Override
    public CommandPage pollCommands(int waitSeconds, int limit, List<OnlinePlayer> online)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("wait", waitSeconds);
        body.addProperty("limit", limit);
        JsonArray players = new JsonArray();
        for (OnlinePlayer p : online) {
            JsonObject o = new JsonObject();
            if (p.uuid() != null) o.addProperty("uuid", p.uuid().toString());
            o.addProperty("name", p.name());
            o.addProperty("freeSlots", Math.max(0, Math.min(36, p.freeSlots())));
            players.add(o);
        }
        body.add("players", players);
        // Long-poll: a requisição pode ficar aberta waitSeconds; o timeout tem que passar disso.
        JsonObject page = send(post("/v1/agent/commands", body)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT.plusSeconds(waitSeconds))
                .build());
        List<CommandRun> runs = new ArrayList<>();
        for (JsonElement el : page.getAsJsonArray("data")) {
            JsonObject o = el.getAsJsonObject();
            JsonObject recipient = obj(o, "recipient");
            runs.add(new CommandRun(str(o, "id"), str(o, "command"), uuid(str(recipient, "uuid")),
                    str(recipient, "name"),
                    !o.has("requireOnline") || o.get("requireOnline").getAsBoolean(),
                    o.has("requiredSlots") && !o.get("requiredSlots").isJsonNull()
                            ? o.get("requiredSlots").getAsInt() : 0));
        }
        List<CommandPage.SlotWait> needSlots = new ArrayList<>();
        if (page.has("needSlots") && page.get("needSlots").isJsonArray()) {
            for (JsonElement el : page.getAsJsonArray("needSlots")) {
                JsonObject o = el.getAsJsonObject();
                JsonObject recipient = obj(o, "recipient");
                needSlots.add(new CommandPage.SlotWait(uuid(str(recipient, "uuid")), str(recipient, "name"),
                        o.get("requiredSlots").getAsInt()));
            }
        }
        return new CommandPage(runs, page.has("hasMore") && page.get("hasMore").getAsBoolean(), needSlots);
    }

    @Override
    public List<String> ackCommands(List<AckResult> results) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        JsonArray list = new JsonArray();
        for (AckResult r : results) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("status", r.ok() ? "ok" : "failed");
            if (r.error() != null) o.addProperty("error", r.error());
            list.add(o);
        }
        body.add("results", list);
        JsonObject res = send(post("/v1/agent/commands/ack", body)
                .header("Authorization", "Bearer " + apiKey)
                .build());
        // ignored = já não estava pendente nesta conexão (ack repetido, cancelado): também sai da fila.
        List<String> done = new ArrayList<>();
        for (String key : List.of("acked", "ignored")) {
            if (res.has(key)) res.getAsJsonArray(key).forEach(e -> done.add(e.getAsString()));
        }
        return done;
    }

    // ---- HTTP ----

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "RivooMinecraft/" + (pluginVersion == null ? "dev" : pluginVersion));
    }

    private HttpRequest.Builder authed(String path) {
        return request(path).header("Authorization", "Bearer " + apiKey);
    }

    private HttpRequest.Builder post(String path, JsonObject body) {
        return request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    private JsonObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            long retryAfter = res.headers().firstValue("retry-after").map(RivooClient::parseLongOrZero).orElse(0L);
            throw new ApiException(res.statusCode(), res.body(), retryAfter);
        }
        if (res.statusCode() == 204 || res.body() == null || res.body().isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Resposta inválida da API (HTTP " + res.statusCode() + ")", e);
        }
    }

    /** Corpo do pedido: 1 unidade do sku, Pix; {@code giftTo} vai no item (o pacote é de outro jogador). */
    static JsonObject orderBody(String sku, UUID playerId, String playerName, String giftTo) {
        JsonObject item = new JsonObject();
        item.addProperty("sku", sku);
        item.addProperty("quantity", 1);
        if (giftTo != null) item.addProperty("giftTo", cut(giftTo, 32));
        JsonArray items = new JsonArray();
        items.add(item);
        // Com o UUID a Rivoo não consulta a Mojang: vale também em servidor offline-mode.
        JsonObject recipient = new JsonObject();
        recipient.addProperty("name", cut(playerName, 32));
        recipient.addProperty("uuid", playerId.toString());
        JsonObject body = new JsonObject();
        body.add("items", items);
        body.add("recipient", recipient);
        body.addProperty("method", "PIX");
        return body;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Money da API: {@code {amount, currency}} em centavos. */
    private static int cents(JsonObject money) {
        return money != null && money.has("amount") ? money.get("amount").getAsInt() : 0;
    }

    private static Instant instant(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static long parseLongOrZero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
