package dev.rivoo.minecraft.paper.map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.rivoo.minecraft.core.Money;
import dev.rivoo.minecraft.core.purchase.Purchase;
import dev.rivoo.minecraft.core.purchase.PurchaseStore;
import dev.rivoo.minecraft.core.purchase.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Mapa com o QR do Pix. Um mapa por compra (id gravado na compra), redesenhado após restart. O QR é
 * gerado do brCode para cada módulo cair em pixels inteiros do mapa 128x128.
 */
public final class QrMaps implements Listener {
    private final NamespacedKey chargeKey;
    private final PurchaseStore store;
    private final Function<Purchase, String> titleOf;

    public QrMaps(NamespacedKey chargeKey, PurchaseStore store, Function<Purchase, String> titleOf) {
        this.chargeKey = chargeKey;
        this.store = store;
        this.titleOf = titleOf;
    }

    public ItemStack itemFor(Player player, Purchase p, String title) throws WriterException {
        MapView view = p.mapId == null ? null : Bukkit.getMap(p.mapId);
        if (view == null) {
            view = Bukkit.createMap(player.getWorld());
            store.setMapId(p, view.getId());
        }
        install(view, p);

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        // Visual de item encantado: nome em aqua, brilho de encantamento e lore no estilo de livro.
        meta.displayName(Component.text("Pergaminho de Pix", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(title + " · " + Money.format(p.amount), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Escaneie com o app do banco", NamedTextColor.DARK_PURPLE)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.STRING, p.chargeId);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Depois de um restart os renderers somem: reinstala nos mapas de Pix ainda aberto. Mapas de compras
     * encerradas são tratados sob demanda por {@link #onMapInit} quando o servidor os carrega.
     */
    public void restoreAll() {
        for (Purchase p : store.inState(State.PENDING)) {
            if (p.mapId == null) continue;
            MapView view = Bukkit.getMap(p.mapId);
            if (view != null) safeInstall(view, p);
        }
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        store.byMapId(event.getMap().getId()).ifPresent(p -> safeInstall(event.getMap(), p));
    }

    private void safeInstall(MapView view, Purchase p) {
        try {
            install(view, p);
        } catch (WriterException | RuntimeException e) {
            Bukkit.getLogger().warning("[Rivoo] Falha ao redesenhar o mapa de " + p.chargeId + ": " + e.getMessage());
        }
    }

    private void install(MapView view, Purchase p) throws WriterException {
        // Sem brCode (compra recuperada pela fila) não há Pix para mostrar: nunca desenhar outro QR.
        BitMatrix matrix = p.brCode == null || p.brCode.isEmpty() ? null
                : new QRCodeWriter().encode(p.brCode, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                        EncodeHintType.MARGIN, 0,
                        EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L));
        view.getRenderers().forEach(view::removeRenderer);
        quietly(() -> view.setTrackingPosition(false));
        quietly(() -> view.setUnlimitedTracking(false));
        String chargeId = p.chargeId;
        view.addRenderer(new QrRenderer(matrix, "PIX - " + Money.format(p.amount), p.playerId,
                () -> store.get(chargeId).map(x -> x.state == State.PENDING).orElse(false)));
    }

    /** Ajustes cosméticos do mapa: se a implementação não os suporta (ex.: MockBukkit), seguimos sem eles. */
    private static void quietly(Runnable setter) {
        try {
            setter.run();
        } catch (RuntimeException e) {
            Bukkit.getLogger().log(java.util.logging.Level.FINE, "[Rivoo] Ajuste cosmético do mapa ignorado", e);
            // cosmético: o mapa funciona igual sem o ajuste. O MockBukkit lança UnimplementedOperationException
            // (que estende TestAbortedException, não UnsupportedOperationException), por isso RuntimeException.
        }
    }

    /** Chave dos mapas criados pelo POC (plugin RivooPoc), anteriores à migração. */
    private static final NamespacedKey LEGACY_CHARGE_KEY = NamespacedKey.fromString("rivoopoc:charge-id");

    public boolean isPixMap(ItemStack it) {
        return chargeIdOf(it).isPresent() || isLegacyPixMap(it);
    }

    /** Mapa de Pix do POC: o plugin novo não tem desenho para ele, então ele só pode ser removido. */
    public boolean isLegacyPixMap(ItemStack it) {
        if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(LEGACY_CHARGE_KEY, PersistentDataType.STRING);
    }

    public Optional<String> chargeIdOf(ItemStack it) {
        if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(it.getItemMeta().getPersistentDataContainer().get(chargeKey, PersistentDataType.STRING));
    }

    /** Remove do inventário (inclui mão secundária e cursor) os mapas da cobrança. */
    public void removeFrom(Player player, String chargeId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (chargeIdOf(contents[i]).filter(chargeId::equals).isPresent()) player.getInventory().setItem(i, null);
        }
        if (chargeIdOf(player.getItemOnCursor()).filter(chargeId::equals).isPresent()) player.setItemOnCursor(null);
    }
}
