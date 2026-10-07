package dev.rivoo.minecraft.core.api;

import java.time.Instant;

public record Charge(String id, String brCode, String url, Instant expiresAt) {}
