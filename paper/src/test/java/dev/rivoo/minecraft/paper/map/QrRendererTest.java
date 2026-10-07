package dev.rivoo.minecraft.paper.map;

import dev.rivoo.minecraft.core.api.Charge;
import dev.rivoo.minecraft.core.fixtures.MutableClock;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapFont;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.awt.Color;
import java.awt.Image;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class QrRendererTest {
    static final Color SLATE = new Color(40, 40, 46);

    /** Canvas falso: guarda os pixels e conta as chamadas a setPixelColor. */
    @SuppressWarnings("removal")
    static final class FakeCanvas implements MapCanvas {
        final Color[][] px = new Color[128][128];
        int calls;

        @Override public void setPixelColor(int x, int y, Color c) { px[x][y] = c; calls++; }
        @Override public Color getPixelColor(int x, int y) { return px[x][y]; }
        long count(Color c) {
            long n = 0;
            for (Color[] col : px) for (Color v : col) if (java.util.Objects.equals(c, v)) n++;
            return n;
        }
        boolean drawnAnything() { return count(null) < 128L * 128L; }
        @Override public MapView getMapView() { throw new UnsupportedOperationException(); }
        @Override public MapCursorCollection getCursors() { throw new UnsupportedOperationException(); }
        @Override public void setCursors(MapCursorCollection cursors) { throw new UnsupportedOperationException(); }
        @Override public Color getBasePixelColor(int x, int y) { throw new UnsupportedOperationException(); }
        @Override public void setPixel(int x, int y, byte color) { throw new UnsupportedOperationException(); }
        @Override public byte getPixel(int x, int y) { throw new UnsupportedOperationException(); }
        @Override public byte getBasePixel(int x, int y) { throw new UnsupportedOperationException(); }
        @Override public void drawImage(int x, int y, Image image) { throw new UnsupportedOperationException(); }
        @Override public void drawText(int x, int y, MapFont font, String text) { throw new UnsupportedOperationException(); }
    }

    @TempDir Path dir;
    ServerMock server;
    PurchaseStore store;
    PlayerMock steve;
    PlayerMock alex;
    Purchase p;
    QrRenderer renderer;
    MapView view;

    @BeforeEach void setUp() throws Exception {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("Rivoo");
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        store = new PurchaseStore(dir.resolve("purchases.json"), Logger.getLogger("t"),
                new MutableClock(Instant.parse("2026-10-05T12:00:00Z")));
        QrMaps maps = new QrMaps(new NamespacedKey(plugin, "charge-id"), store, x -> "VIP");
        p = store.newPurchase(new Charge("c1", "00020101021226", "u", Instant.parse("2026-10-05T12:10:00Z")),
                steve.getUniqueId(), "Steve", "vip", 1990);
        store.add(p);
        view = ((MapMeta) maps.itemFor(steve, p, "VIP").getItemMeta()).getMapView();
        renderer = (QrRenderer) view.getRenderers().get(0);
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void ownerSeesQrOthersSeeNotice() {
        FakeCanvas mine = new FakeCanvas();
        FakeCanvas theirs = new FakeCanvas();
        renderer.render(view, mine, steve);
        renderer.render(view, theirs, alex);
        assertThat(mine.count(SLATE)).isPositive();
        assertThat(theirs.drawnAnything()).isTrue();
        assertThat(theirs.count(SLATE)).isZero();
    }

    @Test void closingPurchaseRedrawsOnSameCanvas() {
        FakeCanvas canvas = new FakeCanvas();
        renderer.render(view, canvas, steve);
        assertThat(canvas.count(SLATE)).isPositive();
        store.transition(p, State.PENDING, State.EXPIRED);
        renderer.render(view, canvas, steve);
        assertThat(canvas.count(SLATE)).isZero();
    }

    @Test void newCanvasForSamePlayerIsDrawn() {
        renderer.render(view, new FakeCanvas(), steve);
        FakeCanvas relog = new FakeCanvas();
        renderer.render(view, relog, steve);
        assertThat(relog.count(SLATE)).isPositive();
    }

    @Test void sameCanvasSameKindIsNotRedrawn() {
        FakeCanvas canvas = new FakeCanvas();
        renderer.render(view, canvas, steve);
        int calls = canvas.calls;
        assertThat(calls).isPositive();
        renderer.render(view, canvas, steve);
        assertThat(canvas.calls).isEqualTo(calls);
    }
}
