package dev.rivoo.minecraft.core.api;

/**
 * Resultado de um comando executado, para o ack: ok, ou falhou com o motivo (aparece no painel como
 * "Entrega falhou", com o botão de tentar de novo).
 */
public record AckResult(String id, boolean ok, String error) {
    public static AckResult ok(String id) {
        return new AckResult(id, true, null);
    }

    public static AckResult failed(String id, String error) {
        String reason = error == null || error.isBlank() ? "Falhou sem mensagem" : error;
        return new AckResult(id, false, reason.length() > 500 ? reason.substring(0, 500) : reason);
    }
}
