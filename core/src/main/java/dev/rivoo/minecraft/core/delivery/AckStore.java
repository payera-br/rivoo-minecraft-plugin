package dev.rivoo.minecraft.core.delivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rivoo.minecraft.core.api.AckResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Comandos já executados cujo ack ainda não foi confirmado pela Rivoo, em disco
 * ({@code plugins/Rivoo/acks.json}). Se o servidor reiniciar antes do ack, o comando não roda de novo:
 * volta da fila e só recebe o ack com o resultado guardado.
 */
public final class AckStore {
    private final Path file;
    private final Logger log;
    private final Map<String, AckResult> pending = new LinkedHashMap<>();

    public AckStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    public synchronized Optional<AckResult> get(String runId) {
        return Optional.ofNullable(pending.get(runId));
    }

    public synchronized List<AckResult> all() {
        return new ArrayList<>(pending.values());
    }

    /** Grava antes do ack: se a gravação falhar, o resultado fica só na memória (avisado no log). */
    public synchronized void put(AckResult result) {
        pending.put(result.id(), result);
        save();
    }

    public synchronized void forget(Collection<String> runIds) {
        if (runIds.isEmpty()) return;
        boolean changed = false;
        for (String id : runIds) changed |= pending.remove(id) != null;
        if (changed) save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : list) {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString();
                boolean ok = o.get("ok").getAsBoolean();
                String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
                pending.put(id, ok ? AckResult.ok(id) : AckResult.failed(id, error));
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Não foi possível ler " + file.getFileName() + " (" + e.getMessage() + "); seguindo sem ele.");
        }
    }

    private void save() {
        JsonArray list = new JsonArray();
        for (AckResult r : pending.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("ok", r.ok());
            if (r.error() != null) o.addProperty("error", r.error());
            list.add(o);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, list.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("Falha ao salvar " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
