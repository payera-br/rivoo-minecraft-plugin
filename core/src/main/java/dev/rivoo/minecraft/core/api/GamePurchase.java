package dev.rivoo.minecraft.core.api;

/** Modo game: cobrança criada pela Rivoo a partir do pacote. {@code price} (centavos) é o preço cobrado. */
public record GamePurchase(Charge charge, String sku, int price) {}
