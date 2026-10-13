package dev.rivoo.minecraft.core.api;

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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cliente mínimo da API Rivoo. Chamadas bloqueantes: usar fora da main thread. */
public final class RivooClient implements RivooApi, PairingApi {

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

    @Override
    public Charge createPixCharge(int amountCents, String externalId, int expiresIn, String idempotencyKey,
                                  String fulfillmentChannel) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("amount", amountCents);
        body.addProperty("externalId", externalId);
        body.addProperty("expiresIn", expiresIn);
        if (fulfillmentChannel != null) body.addProperty("fulfillmentChannel", fulfillmentChannel);

        HttpRequest req = request("/charge/pix")
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return charge(send(req));
    }

    @Override
    public GamePurchase createGamePurchase(String channel, String sku, UUID playerId, String playerName,
                                           String idempotencyKey) throws IOException, InterruptedException {
        JsonObject player = new JsonObject();
        player.addProperty("uuid", playerId.toString());
        player.addProperty("name", playerName);
        JsonObject body = new JsonObject();
        if (channel != null) body.addProperty("channel", channel);
        body.addProperty("sku", sku);
        body.add("player", player);

        JsonObject data = send(request("/game/purchases")
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
        String returnedSku = str(data, "sku");
        return new GamePurchase(charge(data), returnedSku == null ? sku : returnedSku, data.get("price").getAsInt());
    }

    private static Charge charge(JsonObject data) {
        return new Charge(
                data.get("id").getAsString(),
                data.get("brCode").getAsString(),
                data.get("url").getAsString(),
                OffsetDateTime.parse(data.get("expiresAt").getAsString()).toInstant());
    }

    @Override
    public ChargeStatus getStatus(String chargeId) throws IOException, InterruptedException {
        JsonObject data = send(request("/charge/" + chargeId + "/status").GET().build());
        return new ChargeStatus(str(data, "status"), str(data, "paymentStatus"));
    }

    @Override
    public FulfillmentPage listFulfillments(String channel, int limit) throws IOException, InterruptedException {
        String q = "?channel=" + URLEncoder.encode(channel, StandardCharsets.UTF_8) + "&limit=" + limit;
        JsonObject page = send(request("/fulfillments" + q).header("X-API-KEY", apiKey).GET().build());
        List<Fulfillment> entries = new ArrayList<>();
        for (JsonElement el : page.getAsJsonArray("data")) {
            JsonObject o = el.getAsJsonObject();
            entries.add(new Fulfillment(str(o, "chargeId"), str(o, "externalId"),
                    o.has("amount") ? o.get("amount").getAsInt() : 0));
        }
        return new FulfillmentPage(entries, page.has("hasMore") && page.get("hasMore").getAsBoolean());
    }

    @Override
    public List<String> ack(List<String> chargeIds) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        chargeIds.forEach(ids::add);
        body.add("chargeIds", ids);
        JsonObject res = send(request("/fulfillments/ack")
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
        List<String> done = new ArrayList<>();
        for (String key : List.of("acked", "alreadyAcked")) {
            if (res.has(key)) res.getAsJsonArray(key).forEach(e -> done.add(e.getAsString()));
        }
        return done;
    }

    @Override
    public void simulatePayment(String chargeId) throws IOException, InterruptedException {
        send(request("/charge/" + chargeId + "/simulate-payment")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    @Override
    public List<Product> gameCatalog(String channel) throws IOException, InterruptedException {
        JsonObject data = send(request("/game/catalog" + query(channel, null))
                .header("X-API-KEY", apiKey).GET().build());
        List<Product> products = new ArrayList<>();
        if (!data.has("categories")) return products;
        for (JsonElement c : data.getAsJsonArray("categories")) {
            JsonObject cat = c.getAsJsonObject();
            if (!cat.has("packages")) continue;
            for (JsonElement el : cat.getAsJsonArray("packages")) {
                JsonObject o = el.getAsJsonObject();
                String sku = str(o, "sku");
                if (sku == null) continue;
                String name = str(o, "name");
                products.add(new Product(sku, name == null ? sku : name, o.get("price").getAsInt(), List.of()));
            }
        }
        return products;
    }

    @Override
    public CommandPage listCommands(String channel, int limit) throws IOException, InterruptedException {
        String q = query(channel, "limit=" + limit);
        JsonObject page = send(request("/game/commands" + q).header("X-API-KEY", apiKey).GET().build());
        List<CommandRun> runs = new ArrayList<>();
        for (JsonElement el : page.getAsJsonArray("data")) {
            JsonObject o = el.getAsJsonObject();
            runs.add(new CommandRun(str(o, "id"), str(o, "chargeId"), str(o, "trigger"), str(o, "command"),
                    str(o, "playerRef"), str(o, "playerName"),
                    !o.has("requireOnline") || o.get("requireOnline").getAsBoolean(),
                    o.has("requiredSlots") && !o.get("requiredSlots").isJsonNull()
                            ? o.get("requiredSlots").getAsInt() : 0));
        }
        return new CommandPage(runs, page.has("hasMore") && page.get("hasMore").getAsBoolean());
    }

    @Override
    public List<String> ackCommands(List<String> runIds) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        runIds.forEach(ids::add);
        body.add("runIds", ids);
        JsonObject res = send(request("/game/commands/ack")
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
        List<String> done = new ArrayList<>();
        for (String key : List.of("done", "alreadyDone")) {
            if (res.has(key)) res.getAsJsonArray(key).forEach(e -> done.add(e.getAsString()));
        }
        return done;
    }

    @Override
    public ServerInfo gameServer() throws IOException, InterruptedException {
        JsonObject data = send(request("/game/server").header("X-API-KEY", apiKey).GET().build());
        JsonObject server = data.getAsJsonObject("server");
        JsonObject project = data.getAsJsonObject("project");
        return new ServerInfo(str(server, "id"), str(server, "name"), str(project, "name"),
                !"live".equals(str(data, "environment")));
    }

    @Override
    public void simulateGamePurchase(String chargeId) throws IOException, InterruptedException {
        send(request("/game/purchases/" + URLEncoder.encode(chargeId, StandardCharsets.UTF_8) + "/simulate")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    @Override
    public PairingTicket startPairing(String secret, String pluginVersion, String mcVersion, String motd, int port)
            throws IOException, InterruptedException {
        JsonObject plugin = new JsonObject();
        plugin.addProperty("version", pluginVersion == null ? "dev" : pluginVersion);
        JsonObject server = new JsonObject();
        if (mcVersion != null) server.addProperty("mcVersion", mcVersion);
        // A API corta em 100; mandar mais só arrisca recusa.
        if (motd != null) server.addProperty("motd", motd.length() > 100 ? motd.substring(0, 100) : motd);
        if (port > 0) server.addProperty("port", port);
        JsonObject body = new JsonObject();
        body.addProperty("secret", secret);
        body.add("plugin", plugin);
        body.add("server", server);
        JsonObject data = send(request("/game/pairings")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
        return new PairingTicket(str(data, "code"), OffsetDateTime.parse(str(data, "expiresAt")).toInstant(),
                data.has("pollSeconds") ? data.get("pollSeconds").getAsInt() : 5);
    }

    @Override
    public PairingStatus pairingStatus(String secret) throws IOException, InterruptedException {
        JsonObject data = send(request("/game/pairings/status").header("X-Rivoo-Pairing", secret).GET().build());
        if (!"connected".equals(str(data, "status"))) return PairingStatus.PENDING;
        return new PairingStatus(true, !"live".equals(str(data, "environment")),
                str(data.getAsJsonObject("company"), "name"),
                str(data.getAsJsonObject("project"), "name"),
                str(data.getAsJsonObject("server"), "name"));
    }

    /** "?channel=x&rest" ou "?rest"; sem canal (chave de servidor) o parâmetro some. */
    private static String query(String channel, String rest) {
        StringBuilder q = new StringBuilder();
        if (channel != null) q.append("channel=").append(URLEncoder.encode(channel, StandardCharsets.UTF_8));
        if (rest != null) q.append(q.isEmpty() ? "" : "&").append(rest);
        return q.isEmpty() ? "" : "?" + q;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "RivooMinecraft/0.2");
        if (pluginVersion != null) b.header("X-Rivoo-Plugin", pluginVersion);
        return b;
    }

    private JsonObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            // Throttlers nomeados mandam Retry-After-<nome> (ex.: Retry-After-long); vale o maior.
            long retryAfter = java.util.stream.Stream.of("retry-after", "retry-after-long", "retry-after-medium",
                            "retry-after-short", "x-ratelimit-reset-long")
                    .flatMap(h -> res.headers().firstValue(h).stream())
                    .mapToLong(RivooClient::parseLongOrZero).max().orElse(0L);
            throw new ApiException(res.statusCode(), res.body(), retryAfter);
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Resposta inválida da API (HTTP " + res.statusCode() + ")", e);
        }
        // Empresas na versão 2026-08-16 (e endpoints públicos) recebem envelope {status, message, data}.
        if (json.has("data") && json.get("data").isJsonObject() && json.has("message")) {
            return json.getAsJsonObject("data");
        }
        return json;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static long parseLongOrZero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
