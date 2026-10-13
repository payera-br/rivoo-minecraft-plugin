package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.purchase.Purchase;

import java.util.List;

/** @param catalogLoaded false até a primeira carga do catálogo da Rivoo. */
public record Snapshot(boolean catalogLoaded, int products, List<Purchase> pending) {}
