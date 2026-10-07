package dev.rivoo.minecraft.paper.map;

import dev.rivoo.minecraft.core.api.Charge;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class QrMapsTest {
    @TempDir Path dir;
    ServerMock server;
    PurchaseStore store;
    QrMaps maps;
    PlayerMock steve;
    PlayerMock alex;
    Plugin plugin;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Rivoo");
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        store = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"),
                new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
        maps = new QrMaps(new NamespacedKey(plugin, "charge-id"), store, p -> "VIP");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    Purchase pending(UUID owner) {
        Purchase p = store.newPurchase(new Charge("c1", "00020101021226", "u", Instant.parse("2026-10-05T12:10:00Z")),
                owner, "Steve", "vip", 1990);
        store.add(p);
        return p;
    }

    QrRenderer rendererOf(ItemStack item) {
        MapRenderer r = ((MapMeta) item.getItemMeta()).getMapView().getRenderers().get(0);
        return (QrRenderer) r;
    }

    @Test void oneMapPerPurchaseIsReused() throws Exception {
        Purchase p = pending(steve.getUniqueId());
        ItemStack first = maps.itemFor(steve, p, "VIP");
        ItemStack second = maps.itemFor(steve, p, "VIP");
        int id = ((MapMeta) first.getItemMeta()).getMapView().getId();
        assertThat(((MapMeta) second.getItemMeta()).getMapView().getId()).isEqualTo(id);
        assertThat(p.mapId).isEqualTo(id);
        assertThat(maps.chargeIdOf(first)).contains("c1");
        assertThat(maps.isPixMap(first)).isTrue();
        assertThat(maps.isPixMap(new ItemStack(org.bukkit.Material.FILLED_MAP))).isFalse();
    }

    @Test void onlyOwnerSeesQrAndClosedPurchaseShowsClosed() throws Exception {
        Purchase p = pending(steve.getUniqueId());
        QrRenderer r = rendererOf(maps.itemFor(steve, p, "VIP"));
        assertThat(r.kindFor(steve.getUniqueId())).isEqualTo(QrRenderer.Kind.QR);
        assertThat(r.kindFor(alex.getUniqueId())).isEqualTo(QrRenderer.Kind.OTHER_PLAYER);
        store.transition(p, State.PENDING, State.EXPIRED);
        assertThat(r.kindFor(steve.getUniqueId())).isEqualTo(QrRenderer.Kind.CLOSED);
    }

    @Test void restoreAllReinstallsRendererAfterRestart() throws Exception {
        Purchase p = pending(steve.getUniqueId());
        ItemStack item = maps.itemFor(steve, p, "VIP");
        var view = ((MapMeta) item.getItemMeta()).getMapView();
        view.getRenderers().forEach(view::removeRenderer); // simula o restart: renderer não persiste

        maps.restoreAll();
        assertThat(view.getRenderers()).hasSize(1).first().isInstanceOf(QrRenderer.class);
    }

    @Test void restoreAllSkipsClosedPurchases() throws Exception {
        Purchase p = pending(steve.getUniqueId());
        var view = ((MapMeta) maps.itemFor(steve, p, "VIP").getItemMeta()).getMapView();
        store.transition(p, State.PENDING, State.DELIVERED);
        view.getRenderers().forEach(view::removeRenderer);

        maps.restoreAll();
        assertThat(view.getRenderers()).isEmpty(); // onMapInit cuida do mapa se ele for carregado
    }

    @Test void emptyBrCodeNeverShowsAQr() throws Exception {
        Purchase p = store.newPurchase(new Charge("c2", "", "u", Instant.parse("2026-10-05T12:10:00Z")),
                steve.getUniqueId(), "Steve", "vip", 1990);
        store.add(p);
        QrRenderer r = rendererOf(maps.itemFor(steve, p, "VIP"));
        assertThat(r.kindFor(steve.getUniqueId())).isEqualTo(QrRenderer.Kind.CLOSED);
        QrRendererTest.FakeCanvas canvas = new QrRendererTest.FakeCanvas();
        r.render(((MapMeta) maps.itemFor(steve, p, "VIP").getItemMeta()).getMapView(), canvas, steve);
        assertThat(canvas.drawnAnything()).isTrue();
        assertThat(canvas.count(QrRendererTest.SLATE)).isZero();
    }

    @Test void removeFromClearsOnlyThatCharge() throws Exception {
        Purchase p = pending(steve.getUniqueId());
        steve.getInventory().addItem(maps.itemFor(steve, p, "VIP"));
        maps.removeFrom(steve, "other");
        assertThat(steve.getInventory().contains(org.bukkit.Material.FILLED_MAP)).isTrue();
        maps.removeFrom(steve, "c1");
        assertThat(steve.getInventory().contains(org.bukkit.Material.FILLED_MAP)).isFalse();
    }
}
