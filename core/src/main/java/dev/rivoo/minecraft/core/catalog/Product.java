package dev.rivoo.minecraft.core.catalog;

import java.util.List;

/**
 * Produto do catálogo: preço em centavos. {@code commands} fica vazio: os comandos vêm prontos da fila
 * da Rivoo.
 */
public record Product(String sku, String name, int price, List<String> commands) {
    public Product {
        commands = List.copyOf(commands);
    }

    /** Compra de um SKU que saiu do catálogo: mostra o sku como nome, sem quebrar. */
    public static Product fallback(String sku, int amount) {
        return new Product(sku, sku, amount, List.of());
    }
}
