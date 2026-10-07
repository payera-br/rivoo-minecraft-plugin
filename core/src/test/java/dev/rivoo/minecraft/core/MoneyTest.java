package dev.rivoo.minecraft.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {
    @Test void formatsCentsInBrazilianReal() {
        assertThat(Money.format(1990)).isEqualTo("R$ 19,90");
        assertThat(Money.format(100)).isEqualTo("R$ 1,00");
        assertThat(Money.format(5)).isEqualTo("R$ 0,05");
        assertThat(Money.format(123456)).isEqualTo("R$ 1234,56");
    }
}
