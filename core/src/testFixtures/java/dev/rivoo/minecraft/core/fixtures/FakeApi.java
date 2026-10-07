package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.api.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** API em memória para testes. Cada lista registra as chamadas recebidas. */
public final class FakeApi implements RivooApi {
    public record CreateCall(int amount, String externalId, int expiresIn, String idempotencyKey, String channel) {}

    public final List<CreateCall> creates = new CopyOnWriteArrayList<>();
    public final List<List<String>> acks = new CopyOnWriteArrayList<>();
    public final List<String> statusCalls = new CopyOnWriteArrayList<>();
    public final List<String> simulated = new CopyOnWriteArrayList<>();
    public final Deque<Fulfillment> queue = new ArrayDeque<>();
    public final Map<String, ChargeStatus> statuses = new HashMap<>();
    public IOException createError;
    public IOException listError;
    public IOException ackError;
    public IOException statusError;
    public volatile int lastListLimit;
    public final java.util.concurrent.atomic.AtomicInteger listCalls = new java.util.concurrent.atomic.AtomicInteger();
    public Instant expiresAt = Instant.parse("2026-10-05T12:10:00Z");
    /** Quando true, createPixCharge bloqueia até release() — simula a API lenta. */
    public volatile boolean holdCreates;
    private final Object gate = new Object();
    private int seq;

    @Override
    public Charge createPixCharge(int amountCents, String externalId, int expiresIn, String idempotencyKey,
                                  String fulfillmentChannel) throws IOException, InterruptedException {
        creates.add(new CreateCall(amountCents, externalId, expiresIn, idempotencyKey, fulfillmentChannel));
        synchronized (gate) {
            while (holdCreates) gate.wait();
        }
        if (createError != null) throw createError;
        String id = "charge_" + (++seq);
        return new Charge(id, "000201br-" + id, "https://pay/" + id, expiresAt);
    }

    public void release() {
        synchronized (gate) {
            holdCreates = false;
            gate.notifyAll();
        }
    }

    @Override
    public ChargeStatus getStatus(String chargeId) throws IOException {
        statusCalls.add(chargeId);
        if (statusError != null) throw statusError;
        return statuses.getOrDefault(chargeId, new ChargeStatus("OPEN", "PENDING"));
    }

    @Override
    public FulfillmentPage listFulfillments(String channel, int limit) throws IOException {
        lastListLimit = limit;
        listCalls.incrementAndGet();
        if (listError != null) throw listError;
        List<Fulfillment> page = new ArrayList<>(queue).subList(0, Math.min(limit, queue.size()));
        return new FulfillmentPage(List.copyOf(page), queue.size() > limit);
    }

    @Override
    public List<String> ack(List<String> chargeIds) throws IOException {
        acks.add(List.copyOf(chargeIds));
        if (ackError != null) throw ackError;
        queue.removeIf(f -> chargeIds.contains(f.chargeId()));
        return List.copyOf(chargeIds);
    }

    @Override
    public void simulatePayment(String chargeId) {
        simulated.add(chargeId);
    }

    // ---- modo game ----
    public ServerInfo serverInfo = new ServerInfo("gsrv_1", "Survival", "Rivoo Craft", true);
    public IOException gameServerError;
    public final java.util.concurrent.atomic.AtomicInteger gameServerCalls = new java.util.concurrent.atomic.AtomicInteger();
    public final List<String> simulatedGame = new CopyOnWriteArrayList<>();

    @Override
    public ServerInfo gameServer() throws IOException {
        gameServerCalls.incrementAndGet();
        if (gameServerError != null) throw gameServerError;
        return serverInfo;
    }

    @Override
    public void simulateGamePurchase(String chargeId) {
        simulatedGame.add(chargeId);
    }

    public record GamePurchaseCall(String channel, String sku, UUID playerId, String playerName, String idempotencyKey) {}

    public final List<GamePurchaseCall> gamePurchases = new CopyOnWriteArrayList<>();
    /** Preço que a "API" cobra por sku; sem entrada, o preço do gameProducts. */
    public final Map<String, Integer> gamePrices = new HashMap<>();
    public IOException gamePurchaseError;

    @Override
    public GamePurchase createGamePurchase(String channel, String sku, UUID playerId, String playerName,
                                           String idempotencyKey) throws IOException {
        gamePurchases.add(new GamePurchaseCall(channel, sku, playerId, playerName, idempotencyKey));
        if (gamePurchaseError != null) throw gamePurchaseError;
        Integer price = gamePrices.get(sku);
        if (price == null) {
            price = gameProducts.stream().filter(p -> p.sku().equals(sku)).findFirst()
                    .map(dev.rivoo.minecraft.core.catalog.Product::price)
                    .orElseThrow(() -> new ApiException(404, "Pacote não encontrado", 0));
        }
        String id = "charge_" + (++seq);
        return new GamePurchase(new Charge(id, "000201br-" + id, "https://pay/" + id, expiresAt), sku, price);
    }

    public final List<dev.rivoo.minecraft.core.catalog.Product> gameProducts = new CopyOnWriteArrayList<>();
    public final java.util.concurrent.atomic.AtomicInteger catalogCalls = new java.util.concurrent.atomic.AtomicInteger();
    public final List<CommandRun> commandQueue = new CopyOnWriteArrayList<>();
    public final List<List<String>> commandAcks = new CopyOnWriteArrayList<>();
    public final java.util.concurrent.atomic.AtomicInteger commandCalls = new java.util.concurrent.atomic.AtomicInteger();
    public IOException catalogError;
    public IOException commandsError;
    public IOException commandAckError;

    @Override
    public List<dev.rivoo.minecraft.core.catalog.Product> gameCatalog(String channel) throws IOException {
        catalogCalls.incrementAndGet();
        if (catalogError != null) throw catalogError;
        return List.copyOf(gameProducts);
    }

    @Override
    public CommandPage listCommands(String channel, int limit) throws IOException {
        commandCalls.incrementAndGet();
        if (commandsError != null) throw commandsError;
        List<CommandRun> page = commandQueue.subList(0, Math.min(limit, commandQueue.size()));
        return new CommandPage(List.copyOf(page), commandQueue.size() > limit);
    }

    @Override
    public List<String> ackCommands(List<String> runIds) throws IOException {
        commandAcks.add(List.copyOf(runIds));
        if (commandAckError != null) throw commandAckError;
        commandQueue.removeIf(r -> runIds.contains(r.id()));
        return List.copyOf(runIds);
    }

    public void pay(String chargeId, String externalId, int amount) {
        queue.add(new Fulfillment(chargeId, externalId, amount));
        statuses.put(chargeId, new ChargeStatus("COMPLETED", "PAID"));
    }
}
