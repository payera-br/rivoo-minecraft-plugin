package dev.rivoo.minecraft.core.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Catálogo de produtos da Rivoo. Substituído inteiro a cada recarga da API (leitura sem lock). */
public final class Catalog {
    private volatile Map<String, Product> bySku;

    public Catalog(List<Product> products) {
        this.bySku = index(products);
    }

    /** Troca o conteúdo de uma vez: quem lê vê o catálogo antigo ou o novo, nunca uma mistura. */
    public void replace(List<Product> products) {
        this.bySku = index(products);
    }

    private static Map<String, Product> index(List<Product> products) {
        Map<String, Product> map = new LinkedHashMap<>();
        products.forEach(p -> map.put(p.sku(), p));
        return map;
    }

    public Optional<Product> find(String sku) {
        return Optional.ofNullable(bySku.get(sku));
    }

    public List<Product> all() {
        return List.copyOf(bySku.values());
    }

    public List<String> skus() {
        return new ArrayList<>(bySku.keySet());
    }
}
