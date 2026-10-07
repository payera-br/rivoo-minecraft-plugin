package dev.rivoo.minecraft.core.api;

public record ChargeStatus(String status, String paymentStatus) {
    public boolean paid() {
        // O backend também trata RECIEVED (sic) como pago.
        return "PAID".equals(paymentStatus) || "RECIEVED".equals(paymentStatus) || "COMPLETED".equals(status);
    }

    public boolean dead() {
        return "EXPIRED".equals(paymentStatus) || "EXPIRED".equals(status) || "CANCELED".equals(status);
    }
}
