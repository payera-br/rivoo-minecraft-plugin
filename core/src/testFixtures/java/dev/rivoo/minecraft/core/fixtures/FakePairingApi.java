package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.api.PairingApi;
import dev.rivoo.minecraft.core.api.PairingStatus;
import dev.rivoo.minecraft.core.api.PairingTicket;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pareamento em memória: cada pedido devolve o próximo código de {@link #codes}. */
public final class FakePairingApi implements PairingApi {
    public final List<String> startedSecrets = new CopyOnWriteArrayList<>();
    public final List<String> polledSecrets = new CopyOnWriteArrayList<>();
    public final Deque<String> codes = new ArrayDeque<>(List.of("K7QM-2XPA", "M8RT-3ZQB", "P9SV-4WRC"));
    public Instant expiresAt = Instant.parse("2026-10-05T12:10:00Z");
    public int pollSeconds = 5;
    public IOException startError;
    public IOException statusError;
    public volatile PairingStatus status = PairingStatus.PENDING;

    @Override
    public PairingTicket startPairing(String secret, String pluginVersion, String mcVersion, String motd, int port)
            throws IOException {
        startedSecrets.add(secret);
        if (startError != null) throw startError;
        return new PairingTicket(codes.removeFirst(), expiresAt, pollSeconds);
    }

    @Override
    public PairingStatus pairingStatus(String secret) throws IOException {
        polledSecrets.add(secret);
        if (statusError != null) throw statusError;
        return status;
    }
}
