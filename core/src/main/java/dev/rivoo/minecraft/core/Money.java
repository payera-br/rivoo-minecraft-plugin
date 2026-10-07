package dev.rivoo.minecraft.core;

import java.util.Locale;

public final class Money {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Money() {}

    public static String format(int cents) {
        return String.format(PT_BR, "R$ %d,%02d", cents / 100, cents % 100);
    }
}
