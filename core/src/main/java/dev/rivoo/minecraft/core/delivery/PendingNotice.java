package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.Pending;

import java.util.ArrayList;
import java.util.List;

/** Linhas do aviso ao entrar no servidor: o que está esperando pelo jogador. Pura. */
public final class PendingNotice {
    static final int MAX_LINES = 3;

    private PendingNotice() { }

    /** Uma linha por item (até 3), mais "E mais k itens." se sobrar; vazia sem pendentes. */
    public static List<String> lines(List<Pending> pending, int freeSlots) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_LINES, pending.size()); i++) {
            Pending p = pending.get(i);
            String line = "§eVocê tem " + p.product() + " esperando.";
            if (p.requiredSlots() > freeSlots) line += " Libere " + p.requiredSlots() + " espaços no inventário.";
            out.add(line);
        }
        if (pending.size() > MAX_LINES) out.add("§eE mais " + (pending.size() - MAX_LINES) + " itens.");
        return out;
    }
}
