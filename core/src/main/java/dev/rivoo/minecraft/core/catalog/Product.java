package dev.rivoo.minecraft.core.catalog;

import java.util.List;

/**
 * Produto do catálogo: preço em centavos. {@code commands} fica vazio: os comandos vêm prontos da fila
 * da Rivoo. {@code icon} é o "Ícone" do produto no painel: um item do Minecraft (ex.: diamond_sword)
 * vira o ícone no menu do /comprar; outra coisa (URL, emoji) ou nada usa o item padrão.
 */
public record Product(String sku, String name, int price, List<String> commands, String icon) {
    public Product {
        commands = List.copyOf(commands);
    }

    public Product(String sku, String name, int price, List<String> commands) {
        this(sku, name, price, commands, null);
    }

    /** Compra de um SKU que saiu do catálogo: mostra o sku como nome, sem quebrar. */
    public static Product fallback(String sku, int amount) {
        return new Product(sku, sku, amount, List.of());
    }
}
