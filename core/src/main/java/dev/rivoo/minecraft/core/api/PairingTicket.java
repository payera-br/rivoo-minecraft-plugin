package dev.rivoo.minecraft.core.api;

import java.time.Instant;

/** Código de pareamento ({@code POST /game/pairings}). */
public record PairingTicket(String code, Instant expiresAt, int pollSeconds) {}
