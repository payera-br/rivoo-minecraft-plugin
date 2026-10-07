package dev.rivoo.minecraft.core.catalog;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogTest {
    private final Catalog catalog = new Catalog(List.of(
            new Product("vip", "VIP Mensal", 1990, List.of("say {player}")),
            new Product("kit", "Kit Inicial", 150, List.of())));

    @Test void findsBySku() {
        assertThat(catalog.find("vip")).map(Product::name).contains("VIP Mensal");
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test void keepsInsertionOrder() {
        assertThat(catalog.skus()).containsExactly("vip", "kit");
        assertThat(catalog.all()).extracting(Product::sku).containsExactly("vip", "kit");
    }

    @Test void fallbackUsesSkuAsNameAndNoCommands() {
        Product p = Product.fallback("old", 500);
        assertThat(p.name()).isEqualTo("old");
        assertThat(p.price()).isEqualTo(500);
        assertThat(p.commands()).isEmpty();
    }
}
