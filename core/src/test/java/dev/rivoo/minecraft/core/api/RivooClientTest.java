package dev.rivoo.minecraft.core.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RivooClientTest {
    private HttpServer server;
    private RivooClient client;
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile String lastBody;
    private volatile String lastQuery;

    private void route(String path, int status, String body, Map<String, String> headers) {
        server.createContext(path, ex -> {
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.get(0)));
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastQuery = ex.getRequestURI().getQuery();
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new RivooClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "cpk_sandbox_x");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void createsPixChargeWithHeadersAndChannel() throws Exception {
        route("/charge/pix", 201, """
                {"id":"charge_1","brCode":"000201","url":"https://pay/charge_1","expiresAt":"2026-10-05T12:00:00Z"}""", Map.of());
        Charge c = client.createPixCharge(1990, "mc:u:vip", 600, "idem-1", "minecraft");
        assertThat(c).isEqualTo(new Charge("charge_1", "000201", "https://pay/charge_1", Instant.parse("2026-10-05T12:00:00Z")));
        assertThat(lastHeaders).containsEntry("x-api-key", "cpk_sandbox_x").containsEntry("idempotency-key", "idem-1");
        assertThat(lastHeaders.get("user-agent")).isEqualTo("RivooMinecraft/0.2");
        assertThat(lastBody).contains("\"amount\":1990", "\"externalId\":\"mc:u:vip\"", "\"expiresIn\":600", "\"fulfillmentChannel\":\"minecraft\"");
    }

    @Test void omitsChannelInStatusMode() throws Exception {
        route("/charge/pix", 201, """
                {"id":"c","brCode":"b","url":"u","expiresAt":"2026-10-05T12:00:00Z"}""", Map.of());
        client.createPixCharge(100, "mc:u:vip", 600, "k", null);
        assertThat(lastBody).doesNotContain("fulfillmentChannel");
    }

    @Test void createsGamePurchaseWithoutAnyAmount() throws Exception {
        route("/game/purchases", 201, """
                {"status":201,"message":"ok","data":{"id":"charge_9","status":"PROCESSING","amount":1990,"brCode":"000201",
                 "url":"https://pay/charge_9","expiresAt":"2026-10-05T12:00:00Z","sku":"vip","price":1990}}""", Map.of());
        java.util.UUID steve = java.util.UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        GamePurchase gp = client.createGamePurchase("minecraft", "vip", steve, "Steve", "idem-9");
        assertThat(gp).isEqualTo(new GamePurchase(
                new Charge("charge_9", "000201", "https://pay/charge_9", Instant.parse("2026-10-05T12:00:00Z")), "vip", 1990));
        assertThat(lastHeaders).containsEntry("x-api-key", "cpk_sandbox_x").containsEntry("idempotency-key", "idem-9");
        assertThat(lastBody).isEqualTo(
                "{\"channel\":\"minecraft\",\"sku\":\"vip\",\"player\":{\"uuid\":\"8667ba71-b85a-4004-af54-457a9734eed7\",\"name\":\"Steve\"}}");
        assertThat(lastBody).doesNotContain("amount", "price", "total");
    }

    @Test void unwrapsVersionedEnvelope() throws Exception {
        route("/charge/pix", 201, """
                {"status":201,"message":"ok","data":{"id":"c","brCode":"b","url":"u","expiresAt":"2026-10-05T12:00:00+00:00"}}""", Map.of());
        assertThat(client.createPixCharge(100, "x", 600, "k", null).id()).isEqualTo("c");
    }

    @Test void listsAndAcksFulfillments() throws Exception {
        route("/fulfillments", 200, """
                {"data":[{"chargeId":"c1","externalId":"mc:u:vip","amount":1990},{"chargeId":"c2","externalId":null}],"hasMore":true}""", Map.of());
        FulfillmentPage page = client.listFulfillments("mine craft", 50);
        assertThat(lastQuery).isEqualTo("channel=mine+craft&limit=50");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.entries()).containsExactly(new Fulfillment("c1", "mc:u:vip", 1990), new Fulfillment("c2", null, 0));

        route("/fulfillments/ack", 200, """
                {"acked":["c1"],"alreadyAcked":["c2"],"rejected":[{"chargeId":"c3","reason":"NOT_PAID"}]}""", Map.of());
        assertThat(client.ack(List.of("c1", "c2", "c3"))).containsExactly("c1", "c2");
        assertThat(lastBody).isEqualTo("{\"chargeIds\":[\"c1\",\"c2\",\"c3\"]}");
    }

    @Test void readsStatus() throws Exception {
        route("/charge/c1/status", 200, """
                {"status":"OPEN","paymentStatus":"RECIEVED"}""", Map.of());
        ChargeStatus s = client.getStatus("c1");
        assertThat(s.paid()).isTrue();
        assertThat(s.dead()).isFalse();
        assertThat(new ChargeStatus("CANCELED", "PENDING").dead()).isTrue();
        assertThat(new ChargeStatus("COMPLETED", null).paid()).isTrue();
    }

    @Test void simulatesPayment() throws Exception {
        route("/charge/c1/simulate-payment", 200, "{}", Map.of());
        client.simulatePayment("c1");
        assertThat(lastHeaders).containsEntry("x-api-key", "cpk_sandbox_x");
    }

    @Test void errorsCarryStatusAndRetryAfter() {
        route("/fulfillments", 429, "{\"message\":\"slow down\"}", Map.of("Retry-After", "17"));
        assertThatThrownBy(() -> client.listFulfillments("m", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.httpStatus).isEqualTo(429);
                    assertThat(e.retryAfterSeconds).isEqualTo(17);
                    assertThat(e.rateLimited()).isTrue();
                });
    }

    @Test void notFoundAndUnauthorizedAreApiExceptions() {
        route("/fulfillments", 404, "{}", Map.of());
        assertThatThrownBy(() -> client.listFulfillments("m", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus).isEqualTo(404));
        route("/charge/pix", 401, "{}", Map.of("Retry-After", "abc"));
        assertThatThrownBy(() -> client.createPixCharge(1, "x", 1, "k", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.httpStatus).isEqualTo(401);
                    assertThat(e.retryAfterSeconds).isZero();
                });
    }

    @Test void invalidJsonIsAnIOException() {
        route("/charge/c1/status", 200, "<html>", Map.of());
        assertThatThrownBy(() -> client.getStatus("c1")).isInstanceOf(IOException.class);
    }

    @Test void readsGameCatalogFromAllCategories() throws Exception {
        route("/game/catalog", 200, """
                {"project":{"name":"Rivoo Craft","currency":"BRL"},"categories":[
                 {"id":"gcat_1","name":"VIP","packages":[{"sku":"vip","name":"VIP","price":1990,"icon":"DIAMOND"},
                   {"sku":"kit","price":150},{"name":"sem sku","price":1}]},
                 {"id":"gcat_2","name":"Vazia"}]}""", Map.of());
        List<dev.rivoo.minecraft.core.catalog.Product> products = client.gameCatalog("minecraft");
        assertThat(lastQuery).isEqualTo("channel=minecraft");
        assertThat(lastHeaders).containsEntry("x-api-key", "cpk_sandbox_x");
        assertThat(products).containsExactly(
                new dev.rivoo.minecraft.core.catalog.Product("vip", "VIP", 1990, List.of()),
                new dev.rivoo.minecraft.core.catalog.Product("kit", "kit", 150, List.of()));
    }

    @Test void emptyGameCatalogWithoutCategories() throws Exception {
        route("/game/catalog", 200, "{\"project\":{}}", Map.of());
        assertThat(client.gameCatalog("minecraft")).isEmpty();
    }

    @Test void listsAndAcksGameCommands() throws Exception {
        route("/game/commands", 200, """
                {"data":[{"id":"grun_1","chargeId":"charge_1","trigger":"PURCHASED","command":"give Steve diamond 5",
                  "playerRef":"mc:8667ba71-b85a-4004-af54-457a9734eed7","playerName":"Steve","requireOnline":true},
                 {"id":"grun_2","chargeId":"charge_1","trigger":"PURCHASED","command":"say x","playerRef":"mc:u",
                  "playerName":null,"requireOnline":false}],"hasMore":false}""", Map.of());
        CommandPage page = client.listCommands("minecraft", 100);
        assertThat(lastQuery).isEqualTo("channel=minecraft&limit=100");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.runs()).containsExactly(
                new CommandRun("grun_1", "charge_1", "PURCHASED", "give Steve diamond 5",
                        "mc:8667ba71-b85a-4004-af54-457a9734eed7", "Steve", true),
                new CommandRun("grun_2", "charge_1", "PURCHASED", "say x", "mc:u", null, false));
        assertThat(page.runs().get(0).purchased()).isTrue();

        route("/game/commands/ack", 200, """
                {"done":["grun_1"],"alreadyDone":["grun_2"],"rejected":[{"id":"grun_nope","reason":"NOT_FOUND"}]}""", Map.of());
        assertThat(client.ackCommands(List.of("grun_1", "grun_2", "grun_nope"))).containsExactly("grun_1", "grun_2");
        assertThat(lastBody).isEqualTo("{\"runIds\":[\"grun_1\",\"grun_2\",\"grun_nope\"]}");
    }

    @Test void readsGameServerAndSendsPluginVersion() throws Exception {
        client = new RivooClient("http://127.0.0.1:" + server.getAddress().getPort(), "cpk_sandbox_x", "0.3.0");
        route("/game/server", 200, """
                {"server":{"id":"gsrv_1","name":"Survival"},"project":{"id":"gproj_1","name":"Rivoo Craft"},"environment":"sandbox"}""", Map.of());
        assertThat(client.gameServer()).isEqualTo(new ServerInfo("gsrv_1", "Survival", "Rivoo Craft", true));
        assertThat(lastHeaders).containsEntry("x-rivoo-plugin", "0.3.0").containsEntry("x-api-key", "cpk_sandbox_x");
    }

    @Test void liveEnvironmentIsNotSandbox() throws Exception {
        route("/game/server", 200, """
                {"server":{"id":"s","name":"S"},"project":{"id":"p","name":"P"},"environment":"live"}""", Map.of());
        assertThat(client.gameServer().sandbox()).isFalse();
    }

    @Test void gameServer401IsAnApiException() {
        route("/game/server", 401, "{\"message\":\"Invalid API key\"}", Map.of());
        assertThatThrownBy(() -> client.gameServer()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.httpStatus).isEqualTo(401));
    }

    @Test void nullChannelIsOmittedEverywhere() throws Exception {
        route("/game/catalog", 200, "{\"categories\":[]}", Map.of());
        client.gameCatalog(null);
        assertThat(lastQuery).isNull();
        route("/game/commands", 200, "{\"data\":[],\"hasMore\":false}", Map.of());
        client.listCommands(null, 100);
        assertThat(lastQuery).isEqualTo("limit=100");
        route("/game/purchases", 201, """
                {"id":"c","brCode":"b","url":"u","expiresAt":"2026-10-05T12:00:00Z","sku":"vip","price":1990}""", Map.of());
        client.createGamePurchase(null, "vip", java.util.UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), "Steve", "k");
        assertThat(lastBody).doesNotContain("channel");
    }

    @Test void simulatesThroughTheGameRoute() throws Exception {
        route("/game/purchases/charge_1/simulate", 200, "{\"id\":\"charge_1\"}", Map.of());
        client.simulateGamePurchase("charge_1");
        assertThat(lastHeaders).containsEntry("x-api-key", "cpk_sandbox_x");
    }

    @Test void noPluginHeaderWithoutVersion() throws Exception {
        route("/game/catalog", 200, "{\"categories\":[]}", Map.of());
        client.gameCatalog("minecraft");
        assertThat(lastHeaders).doesNotContainKey("x-rivoo-plugin");
    }

    @Test void startsPairingWithoutAnyKey() throws Exception {
        route("/game/pairings", 201, """
                {"code":"K7QM-2XPA","expiresAt":"2026-10-06T21:10:00.000Z","pollSeconds":5}""", Map.of());
        PairingTicket t = client.startPairing("abcdefghijklmnopqrstuvwxyz012345", "0.3.0", "1.21.4", "Rivoo Craft", 25565);
        assertThat(t).isEqualTo(new PairingTicket("K7QM-2XPA", Instant.parse("2026-10-06T21:10:00Z"), 5));
        assertThat(lastHeaders).doesNotContainKey("x-api-key");
        assertThat(lastBody).contains("\"secret\":\"abcdefghijklmnopqrstuvwxyz012345\"", "\"version\":\"0.3.0\"",
                "\"mcVersion\":\"1.21.4\"", "\"motd\":\"Rivoo Craft\"", "\"port\":25565");
    }

    @Test void pairingStatusPendingAndConnected() throws Exception {
        route("/game/pairings/status", 200, """
                {"status":"connected","environment":"live","company":{"name":"Rivoo"},
                 "project":{"id":"gproj_1","name":"Rivoo Craft"},"server":{"id":"gsrv_1","name":"Survival"}}""", Map.of());
        assertThat(client.pairingStatus("abcdefghijklmnopqrstuvwxyz012345"))
                .isEqualTo(new PairingStatus(true, false, "Rivoo", "Rivoo Craft", "Survival"));
        assertThat(lastHeaders).containsEntry("x-rivoo-pairing", "abcdefghijklmnopqrstuvwxyz012345")
                .doesNotContainKey("x-api-key");
    }

    @Test void pairingStatusPending() throws Exception {
        route("/game/pairings/status", 200, "{\"status\":\"pending\"}", Map.of());
        assertThat(client.pairingStatus("abcdefghijklmnopqrstuvwxyz012345")).isEqualTo(PairingStatus.PENDING);
    }

    @Test void expiredPairingIsA404() {
        route("/game/pairings/status", 404, "{}", Map.of());
        assertThatThrownBy(() -> client.pairingStatus("abcdefghijklmnopqrstuvwxyz012345"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus).isEqualTo(404));
    }

    @Test void readsTheNamedThrottlerRetryAfter() {
        route("/game/pairings", 429, "{}", Map.of("Retry-After-long", "42", "Retry-After-short", "1"));
        assertThatThrownBy(() -> client.startPairing("abcdefghijklmnopqrstuvwxyz012345", "0.3.0", null, null, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.retryAfterSeconds).isEqualTo(42));
    }

    @Test void startPairingCutsTheMotdTo100() throws Exception {
        route("/game/pairings", 201, """
                {"code":"K7QM-2XPA","expiresAt":"2026-10-06T21:10:00.000Z","pollSeconds":5}""", Map.of());
        client.startPairing("abcdefghijklmnopqrstuvwxyz012345", "0.3.0", null, "m".repeat(500), 0);
        assertThat(lastBody).contains("\"motd\":\"" + "m".repeat(100) + "\"").doesNotContain("m".repeat(101));
    }
}
