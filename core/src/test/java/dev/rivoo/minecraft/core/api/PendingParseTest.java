package dev.rivoo.minecraft.core.api;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingParseTest {
    @Test
    void parsesItemsAndToleratesMissingFields() {
        var json = JsonParser.parseString("{\"data\":[{\"product\":\"VIP\",\"requiredSlots\":2},{\"product\":\"Kit\"},{\"requiredSlots\":1}]}").getAsJsonObject();
        assertThat(RivooClient.pendingOf(json)).isEqualTo(List.of(new Pending("VIP", 2), new Pending("Kit", 0)));
        assertThat(RivooClient.pendingOf(new com.google.gson.JsonObject())).isEmpty();
    }
}
