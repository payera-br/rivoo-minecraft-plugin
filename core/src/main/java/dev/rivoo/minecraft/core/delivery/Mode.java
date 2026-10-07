package dev.rivoo.minecraft.core.delivery;

/**
 * FULFILLMENTS: fila de entregas da Rivoo. STATUS: polling por cobrança (API sem a fila).
 * GAME: catálogo e comandos vindos do módulo game da Rivoo (POC).
 */
public enum Mode { FULFILLMENTS, STATUS, GAME }
