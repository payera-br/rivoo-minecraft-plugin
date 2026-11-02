package dev.rivoo.minecraft.core.platform;

/** @param ok   retirou exatamente a quantidade pedida.
 *  @param have quantos o jogador tinha do item (só os itens comuns); nada foi removido quando !ok. */
public record TakeResult(boolean ok, int have) {}
