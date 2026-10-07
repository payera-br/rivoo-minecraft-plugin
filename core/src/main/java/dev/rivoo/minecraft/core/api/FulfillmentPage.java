package dev.rivoo.minecraft.core.api;

import java.util.List;

public record FulfillmentPage(List<Fulfillment> entries, boolean hasMore) {}
