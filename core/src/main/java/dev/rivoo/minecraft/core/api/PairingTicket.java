package dev.rivoo.minecraft.core.api;

import java.time.Instant;

/** Código de pareamento ({@code POST /v1/agent/pairings}), já no formato ABCD-EFGH. */
public record PairingTicket(String code, Instant expiresAt, int pollSeconds) {}
