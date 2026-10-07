package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.purchase.Purchase;

import java.util.List;

/** @param mode null enquanto o modo auto ainda está decidindo. */
public record Snapshot(Mode mode, String channel, List<Purchase> pending, List<Purchase> paid) {}
