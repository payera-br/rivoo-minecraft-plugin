package dev.rivoo.minecraft.core.platform;

import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.purchase.Purchase;

/** O que o jogador vê. Sempre chamado na thread do jogo. */
public interface PurchaseView {
    /** Comandos já executados; jogador online. */
    void paid(Purchase purchase, Product product);

    /** @param replacedByNewer o jogador já tem um Pix mais novo ativo (não avisar da expiração). */
    void expired(Purchase purchase, boolean replacedByNewer);

    /** Pago com o jogador offline: esconder o que estiver na tela; entrega no próximo login. */
    void paidWhileOffline(Purchase purchase);
}
