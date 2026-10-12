package dev.rivoo.minecraft.core.purchase;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rivoo.minecraft.core.api.AgentOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Compras conhecidas pelo plugin (purchases.json): os Pix abertos e o mapa de cada um. A API não lista
 * os pedidos de um jogador, então é aqui que o plugin lembra o que está pendente depois de um restart.
 * Gravação atômica a cada mudança.
 */
public final class PurchaseStore {
    private static final int VERSION = 1;

    private final Map<String, Purchase> purchases = new ConcurrentHashMap<>();
    private final Path file;
    private final Logger log;
    private final Clock clock;

    public PurchaseStore(Path file, Logger log, Clock clock) {
        this.file = file;
        this.log = log;
        this.clock = clock;
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("purchases")) {
                Purchase p = fromJson(el.getAsJsonObject());
                purchases.put(p.chargeId, p);
            }
        } catch (IOException | RuntimeException e) {
            purchases.clear();
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt-" + clock.instant().toEpochMilli());
            try {
                Files.move(file, corrupt);
            } catch (IOException moveError) {
                // Seguir com o store vazio sobrescreveria a única cópia na próxima gravação.
                throw new IllegalStateException("Arquivo de compras ilegível (" + e.getMessage()
                        + ") e não foi possível preservá-lo em " + corrupt.getFileName() + " ("
                        + moveError.getMessage() + "). Corrija ou mova " + file + " e reinicie.", e);
            }
            log.severe("Arquivo de compras ilegível (" + e.getMessage() + "). Guardado em " + corrupt.getFileName()
                    + "; iniciando vazio. As entregas vêm da fila de comandos da Rivoo.");
        }
    }

    /** true se gravou; se a gravação falhar, a compra sai da memória também (nada de estado só em RAM). */
    public synchronized boolean add(Purchase p) {
        Purchase previous = purchases.put(p.chargeId, p);
        if (save()) return true;
        if (previous == null) purchases.remove(p.chargeId);
        else purchases.put(p.chargeId, previous);
        return false;
    }

    /** Adiciona as compras cujo id ainda não existe. true se o arquivo foi gravado com sucesso. */
    public synchronized boolean addAll(Collection<Purchase> all) {
        all.forEach(p -> purchases.putIfAbsent(p.chargeId, p));
        return save();
    }

    public Purchase newPurchase(AgentOrder o, UUID playerId, String playerName, String sku) {
        return new Purchase(o.id(), playerId, playerName, sku, o.amount(), o.brCode(), o.checkoutUrl(),
                clock.instant(), o.expiresAt(), State.PENDING, null);
    }

    /** Transição atômica. false se o estado atual não era {@code from}: é o que impede entrega dupla. */
    public synchronized boolean transition(Purchase p, State from, State to) {
        if (p.state != from) return false;
        State oldState = p.state;
        p.state = to;
        if (!save()) {
            p.state = oldState;
            return false;
        }
        return true;
    }

    /** Pago a partir de qualquer estado (o pagamento pode ser confirmado depois do vencimento local). */
    public synchronized boolean markDelivered(Purchase p) {
        if (p.state == State.DELIVERED) return false;
        State oldState = p.state;
        p.state = State.DELIVERED;
        if (!save()) {
            p.state = oldState;
            return false;
        }
        return true;
    }

    public synchronized void setMapId(Purchase p, int mapId) {
        p.mapId = mapId;
        save();
    }

    public Optional<Purchase> get(String chargeId) {
        return Optional.ofNullable(purchases.get(chargeId));
    }

    public Optional<Purchase> byMapId(int mapId) {
        return purchases.values().stream().filter(p -> p.mapId != null && p.mapId == mapId).findFirst();
    }

    public List<Purchase> inState(State state) {
        List<Purchase> out = new ArrayList<>();
        for (Purchase p : purchases.values()) if (p.state == state) out.add(p);
        out.sort(Comparator.comparing((Purchase p) -> p.createdAt).thenComparing(p -> p.chargeId));
        return out;
    }

    /** Pix aberto e ainda não vencido do jogador (o mais recente). Vencido na tolerância não conta. */
    public Optional<Purchase> latestPendingOf(UUID playerId) {
        Instant now = clock.instant();
        return inState(State.PENDING).stream()
                .filter(p -> p.playerId.equals(playerId) && p.expiresAt.isAfter(now))
                .reduce((a, b) -> b);
    }

    private synchronized boolean save() {
        JsonArray list = new JsonArray();
        purchases.values().stream().sorted(Comparator.comparing(p -> p.chargeId)).forEach(p -> list.add(toJson(p)));
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("purchases", list);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.severe("Falha ao salvar " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private static JsonObject toJson(Purchase p) {
        JsonObject o = new JsonObject();
        o.addProperty("chargeId", p.chargeId);
        o.addProperty("playerId", p.playerId.toString());
        o.addProperty("playerName", p.playerName);
        o.addProperty("sku", p.sku);
        o.addProperty("amount", p.amount);
        o.addProperty("brCode", p.brCode);
        o.addProperty("url", p.url);
        o.addProperty("createdAt", p.createdAt.toString());
        o.addProperty("expiresAt", p.expiresAt.toString());
        o.addProperty("state", p.state.name());
        if (p.mapId != null) o.addProperty("mapId", p.mapId);
        return o;
    }

    private static Purchase fromJson(JsonObject o) {
        return new Purchase(
                o.get("chargeId").getAsString(),
                UUID.fromString(o.get("playerId").getAsString()),
                o.has("playerName") && !o.get("playerName").isJsonNull() ? o.get("playerName").getAsString() : null,
                o.get("sku").getAsString(),
                o.get("amount").getAsInt(),
                o.get("brCode").getAsString(),
                o.get("url").getAsString(),
                Instant.parse(o.get("createdAt").getAsString()),
                Instant.parse(o.get("expiresAt").getAsString()),
                State.valueOf(o.get("state").getAsString()),
                o.has("mapId") ? o.get("mapId").getAsInt() : null);
    }
}
