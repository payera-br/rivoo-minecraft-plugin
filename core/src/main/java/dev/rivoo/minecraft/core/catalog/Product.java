package dev.rivoo.minecraft.core.catalog;

import java.util.List;

/** Produto do catálogo local: preço em centavos e comandos de console executados na entrega. */
public record Product(String sku, String name, int price, List<String> commands) {
    public Product {
        commands = List.copyOf(commands);
    }

    /** Compra paga de um SKU que saiu do catálogo: entrega sem comandos, sem quebrar. */
    public static Product fallback(String sku, int amount) {
        return new Product(sku, sku, amount, List.of());
    }
}
