package dev.rivoo.minecraft.core.api;

/** Compra esperando entrega para um jogador ({@code GET /v1/agent/pending}). */
public record Pending(String product, int requiredSlots) { }
