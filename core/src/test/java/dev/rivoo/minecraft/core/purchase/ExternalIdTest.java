package dev.rivoo.minecraft.core.purchase;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ExternalIdTest {
    private static final UUID ID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test void roundTrips() {
        ExternalId ext = ExternalId.of(ID, "vip");
        assertThat(ext.value()).isEqualTo("mc:8667ba71-b85a-4004-af54-457a9734eed7:vip");
        assertThat(ExternalId.parse(ext.value())).contains(ext);
    }

    @Test void fourthPartIsTheNick() {
        ExternalId ext = ExternalId.of(ID, "vip", "Steve");
        assertThat(ext.value()).isEqualTo("mc:" + ID + ":vip:Steve");
        assertThat(ExternalId.parse(ext.value())).contains(ext);
        assertThat(ExternalId.parse("mc:" + ID + ":vip")).map(ExternalId::playerName).isEmpty();
        assertThat(ExternalId.parse("mc:" + ID + ":vip:")).map(ExternalId::playerName).isEmpty();
        assertThat(ExternalId.of(ID, "vip", "").value()).isEqualTo("mc:" + ID + ":vip");
    }

    @Test void rejectsMoreThanFourParts() {
        assertThat(ExternalId.parse("mc:" + ID + ":a:b:c")).isEmpty();
    }

    @Test void rejectsForeignOrBrokenValues() {
        assertThat(ExternalId.parse(null)).isEmpty();
        assertThat(ExternalId.parse("shop:" + ID + ":vip")).isEmpty();
        assertThat(ExternalId.parse("mc:not-a-uuid:vip")).isEmpty();
        assertThat(ExternalId.parse("mc:" + ID)).isEmpty();
        assertThat(ExternalId.parse("mc:" + ID + ":")).isEmpty();
        assertThat(ExternalId.parse("mc:" + ID + "::Steve")).isEmpty();
    }
}
