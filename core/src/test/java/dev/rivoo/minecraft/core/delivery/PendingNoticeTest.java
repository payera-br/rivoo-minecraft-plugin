package dev.rivoo.minecraft.core.delivery;

import dev.rivoo.minecraft.core.api.Pending;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingNoticeTest {
    @Test
    void oneItemWithoutRequiredSlots() {
        assertThat(PendingNotice.lines(List.of(new Pending("VIP", 0)), 0))
                .containsExactly("§eVocê tem VIP esperando.");
    }

    @Test
    void itemNeedingMoreSlotsThanFreeAsksToFreeThem() {
        assertThat(PendingNotice.lines(List.of(new Pending("Kit", 5)), 2))
                .containsExactly("§eVocê tem Kit esperando. Libere 5 espaços no inventário.");
    }

    @Test
    void itemWithEnoughFreeSlotsHasNoExtraAsk() {
        assertThat(PendingNotice.lines(List.of(new Pending("Kit", 5)), 5))
                .containsExactly("§eVocê tem Kit esperando.");
    }

    @Test
    void fiveItemsShowThreeLinesAndTheRest() {
        List<Pending> five = List.of(new Pending("A", 0), new Pending("B", 0), new Pending("C", 0),
                new Pending("D", 0), new Pending("E", 0));
        assertThat(PendingNotice.lines(five, 36)).containsExactly(
                "§eVocê tem A esperando.", "§eVocê tem B esperando.", "§eVocê tem C esperando.",
                "§eE mais 2 itens.");
    }

    @Test
    void emptyListHasNoLines() {
        assertThat(PendingNotice.lines(List.of(), 10)).isEmpty();
    }
}
