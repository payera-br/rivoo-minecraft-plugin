package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.purchase.Purchase;

import java.util.List;

public sealed interface BuyResult {
    /** @param superseded Pix vencidos (na tolerância) do mesmo jogador que este substitui na tela. */
    record Created(Purchase purchase, Product product, List<Purchase> superseded) implements BuyResult {}

    record AlreadyPending(Purchase purchase, Product product) implements BuyResult {}

    record UnknownProduct(String sku) implements BuyResult {}

    record Failed(String message) implements BuyResult {}
}
