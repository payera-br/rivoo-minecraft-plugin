package dev.rivoo.minecraft.paper.map;

import com.google.zxing.common.BitMatrix;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapFont;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Tema "Cartógrafo": pergaminho com bordas gastas, esmeraldas (a moeda dos aldeões) no título e
 * o QR num painel de quartzo com módulos cor de ardósia. Contraste alto e módulos quadrados são
 * inegociáveis — o enfeite fica fora do QR e da zona de silêncio. O layout se adapta ao tamanho
 * do QR: se não couber com módulos de 2px, o título sai antes de o QR encolher.
 * Contextual: cada jogador tem o próprio canvas, e só o comprador vê o QR; os demais veem
 * "Pix de outro jogador" e uma compra encerrada vira "Pix encerrado".
 */
public final class QrRenderer extends MapRenderer {
    public enum Kind { QR, OTHER_PLAYER, CLOSED }

    private static final int MAP_SIZE = 128;
    private static final Color PAPER = new Color(222, 205, 160);
    private static final Color PAPER_DARK = new Color(190, 168, 120);
    private static final Color EDGE = new Color(120, 86, 48);
    private static final Color INK = new Color(30, 90, 45);
    private static final Color QUARTZ = new Color(236, 232, 224);
    private static final Color SLATE = new Color(40, 40, 46);
    private static final Color EMERALD_LIGHT = new Color(110, 230, 140);
    private static final Color EMERALD = new Color(20, 160, 70);
    private static final Color EMERALD_DARK = new Color(10, 95, 45);
    private static final int QUIET = 2; // módulos de margem clara em volta do QR
    private static final int FONT_H = 8;
    private static final String[] GEM = {
            "...#...",
            "..##o..",
            ".##oo#.",
            "##oo###",
            ".#o###.",
            "..###..",
            "...#...",
    };

    private final BitMatrix matrix;
    private final String title;
    private final UUID owner;
    private final BooleanSupplier active;
    // Por canvas (um novo por jogador a cada login), com chaves fracas: nada de cache velho nem crescimento.
    private final Map<MapCanvas, Kind> drawn = Collections.synchronizedMap(new WeakHashMap<>());

    /** @param matrix QR do brCode, ou null quando não há brCode: o mapa mostra sempre "Pix encerrado". */
    public QrRenderer(BitMatrix matrix, String title, UUID owner, BooleanSupplier active) {
        super(true);
        this.matrix = matrix;
        this.title = title;
        this.owner = owner;
        this.active = active;
    }

    public Kind kindFor(UUID viewer) {
        if (matrix == null || !active.getAsBoolean()) return Kind.CLOSED;
        return viewer.equals(owner) ? Kind.QR : Kind.OTHER_PLAYER;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        Kind kind = kindFor(player.getUniqueId());
        if (drawn.get(canvas) == kind) return;
        switch (kind) {
            case QR -> drawQr(canvas);
            case OTHER_PLAYER -> drawNotice(canvas, "Pix de outro", "jogador");
            case CLOSED -> drawNotice(canvas, "Pix", "encerrado");
        }
        drawn.put(canvas, kind);
    }

    /** Papel com as bordas gastas e duas linhas centralizadas em tinta verde. */
    private static void drawNotice(MapCanvas canvas, String line1, String line2) {
        fill(canvas, 0, 0, MAP_SIZE, MAP_SIZE, PAPER);
        raggedEdges(canvas);
        MapFont font = MinecraftFont.Font;
        drawText(canvas, line1, (MAP_SIZE - font.getWidth(line1)) / 2, 52, INK);
        drawText(canvas, line2, (MAP_SIZE - font.getWidth(line2)) / 2, 64, INK);
    }

    private void drawQr(MapCanvas canvas) {
        fill(canvas, 0, 0, MAP_SIZE, MAP_SIZE, PAPER);
        raggedEdges(canvas);

        int modules = matrix.getWidth() + QUIET * 2;
        boolean showTitle = true;
        int top = 0;
        int avail = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            showTitle = attempt == 0;
            top = 5 + (showTitle ? FONT_H + 3 : 0);
            avail = MAP_SIZE - 5 - top;
            if (avail / modules >= 2) break;
        }
        int scale = Math.max(1, avail / modules);
        int size = modules * scale;
        int ox = (MAP_SIZE - size) / 2;
        int oy = top + (avail - size) / 2;

        // moldura do painel + cantos de esmeralda, tudo fora da zona de silêncio
        fill(canvas, ox - 1, oy - 1, size + 2, size + 2, PAPER_DARK);
        fill(canvas, ox, oy, size, size, QUARTZ);
        emeraldCorners(canvas, ox - 1, oy - 1, size + 2);
        int qrOrigin = QUIET * scale;
        for (int mx = 0; mx < matrix.getWidth(); mx++) {
            for (int my = 0; my < matrix.getHeight(); my++) {
                if (matrix.get(mx, my)) {
                    fill(canvas, ox + qrOrigin + mx * scale, oy + qrOrigin + my * scale, scale, scale, SLATE);
                }
            }
        }

        if (showTitle) {
            MapFont font = MinecraftFont.Font;
            int textW = font.getWidth(title);
            int total = 7 + 3 + textW + 3 + 7;
            int x = (MAP_SIZE - total) / 2;
            gem(canvas, x, 5);
            drawText(canvas, title, x + 10, 5, INK);
            gem(canvas, x + 10 + textW + 3, 5);
        }
    }

    /** Borda de pergaminho gasta: profundidade irregular, mas determinística (sem flicker). */
    private static void raggedEdges(MapCanvas canvas) {
        for (int i = 0; i < MAP_SIZE; i++) {
            int a = 1 + Math.floorMod(i * 7 + (i / 5) * 13, 3);
            int b = 1 + Math.floorMod(i * 11 + (i / 4) * 5, 3);
            fill(canvas, i, 0, 1, a, EDGE);
            fill(canvas, i, a, 1, 1, PAPER_DARK);
            fill(canvas, i, MAP_SIZE - b, 1, b, EDGE);
            fill(canvas, i, MAP_SIZE - b - 1, 1, 1, PAPER_DARK);
            fill(canvas, 0, i, b, 1, EDGE);
            fill(canvas, b, i, 1, 1, PAPER_DARK);
            fill(canvas, MAP_SIZE - a, i, a, 1, EDGE);
            fill(canvas, MAP_SIZE - a - 1, i, 1, 1, PAPER_DARK);
        }
    }

    private static void emeraldCorners(MapCanvas canvas, int x, int y, int size) {
        int len = 6;
        int end = x + size - 1;
        int bottom = y + size - 1;
        fill(canvas, x, y, len, 1, EMERALD); fill(canvas, x, y, 1, len, EMERALD);
        fill(canvas, end - len + 1, y, len, 1, EMERALD); fill(canvas, end, y, 1, len, EMERALD);
        fill(canvas, x, bottom, len, 1, EMERALD); fill(canvas, x, bottom - len + 1, 1, len, EMERALD);
        fill(canvas, end - len + 1, bottom, len, 1, EMERALD); fill(canvas, end, bottom - len + 1, 1, len, EMERALD);
    }

    private static void gem(MapCanvas canvas, int x, int y) {
        for (int row = 0; row < GEM.length; row++) {
            for (int col = 0; col < GEM[row].length(); col++) {
                char c = GEM[row].charAt(col);
                if (c == '.') continue;
                Color color = c == 'o' ? EMERALD_LIGHT : (row >= 4 ? EMERALD_DARK : EMERALD);
                fill(canvas, x + col, y + row, 1, 1, color);
            }
        }
    }

    private static void fill(MapCanvas canvas, int x, int y, int w, int h, Color color) {
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (i >= 0 && j >= 0 && i < MAP_SIZE && j < MAP_SIZE) canvas.setPixelColor(i, j, color);
            }
        }
    }

    /** Texto com a fonte bitmap do próprio Minecraft, desenhado pixel a pixel na cor pedida. */
    private static void drawText(MapCanvas canvas, String text, int x, int y, Color color) {
        MapFont font = MinecraftFont.Font;
        if (!font.isValid(text)) text = text.replaceAll("[^ -~]", "?");
        for (char ch : text.toCharArray()) {
            MapFont.CharacterSprite sprite = font.getChar(ch);
            if (sprite == null) continue;
            for (int row = 0; row < sprite.getHeight(); row++) {
                for (int col = 0; col < sprite.getWidth(); col++) {
                    if (sprite.get(row, col)) fill(canvas, x + col, y + row, 1, 1, color);
                }
            }
            x += sprite.getWidth() + 1;
        }
    }
}
