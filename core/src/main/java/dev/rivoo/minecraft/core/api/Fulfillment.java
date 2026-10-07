package dev.rivoo.minecraft.core.api;

/** Item da fila de entregas: cobrança paga e ainda sem ack. */
public record Fulfillment(String chargeId, String externalId, int amount) {}
