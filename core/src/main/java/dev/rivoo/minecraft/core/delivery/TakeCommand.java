package dev.rivoo.minecraft.core.delivery;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code rivoo:take <jogador> <material> <quantidade>}: retira do jogador exatamente a quantidade ou nada.
 * Quantidade de 1 a 2304 (36 pilhas de 64); material como {@code minecraft:diamond} ou só {@code diamond}.
 */
record TakeCommand(String player, String material, int quantity) {
    static final String PREFIX = "rivoo:take ";
    static final int MAX_QUANTITY = 2304;
    private static final Pattern MATERIAL = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    static boolean matches(String command) {
        return command != null && command.startsWith(PREFIX);
    }

    /** Vazio se o comando está malformado (nada deve ser removido). */
    static Optional<TakeCommand> parse(String command) {
        if (!matches(command)) return Optional.empty();
        String[] parts = command.trim().split("\\s+");
        if (parts.length != 4) return Optional.empty();
        String material = parts[2].toLowerCase(java.util.Locale.ROOT);
        if (!MATERIAL.matcher(material).matches()) return Optional.empty();
        int quantity;
        try {
            quantity = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (quantity < 1 || quantity > MAX_QUANTITY) return Optional.empty();
        if (!material.contains(":")) material = "minecraft:" + material;
        return Optional.of(new TakeCommand(parts[1], material, quantity));
    }
}
