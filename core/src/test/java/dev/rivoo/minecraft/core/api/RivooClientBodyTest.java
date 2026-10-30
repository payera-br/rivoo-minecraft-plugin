package dev.rivoo.minecraft.core.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rivoo.minecraft.core.catalog.Product;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RivooClientBodyTest {
    private static final UUID ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test void orderWithoutGiftHasNoGiftTo() {
        JsonObject body = RivooClient.orderBody("vip", ID, "Ana", null);
        JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(item.has("giftTo")).isFalse();
        assertThat(body.getAsJsonObject("recipient").get("name").getAsString()).isEqualTo("Ana");
    }

    @Test void giftGoesOnTheItem() {
        JsonObject body = RivooClient.orderBody("vip", ID, "Ana", "Bia");
        JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(item.get("giftTo").getAsString()).isEqualTo("Bia");
        assertThat(body.getAsJsonObject("recipient").get("name").getAsString()).isEqualTo("Ana");
    }

    @Test void catalogReadsGiftable() {
        JsonObject data = JsonParser.parseString("""
                {"categories":[{"products":[
                  {"sku":"vip","name":"VIP","price":{"amount":1990},"giftable":true},
                  {"sku":"kit","name":"Kit","price":{"amount":990}}]}]}""").getAsJsonObject();
        List<Product> products = RivooClient.products(data);
        assertThat(products).extracting(Product::giftable).containsExactly(true, false);
    }
}
