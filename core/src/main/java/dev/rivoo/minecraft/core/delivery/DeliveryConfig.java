package dev.rivoo.minecraft.core.delivery;

import java.time.Duration;

/** @param mode "auto", "fulfillments" ou "status". */
public record DeliveryConfig(String channel, String mode, int expiresInSeconds, Duration queueInterval,
                             Duration statusInterval) {}
