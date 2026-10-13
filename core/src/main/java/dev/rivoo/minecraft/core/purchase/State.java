package dev.rivoo.minecraft.core.purchase;

/**
 * PENDING: Pix aberto. DELIVERED: pagamento confirmado (os comandos chegam pela fila da Rivoo).
 * EXPIRED: Pix vencido. PAID só existe em purchases.json de versões antes da 1.0.0.
 */
public enum State { PENDING, PAID, DELIVERED, EXPIRED }
