package dev.rivoo.minecraft.core.fixtures;

import dev.rivoo.minecraft.core.catalog.Product;
import dev.rivoo.minecraft.core.platform.PurchaseView;
import dev.rivoo.minecraft.core.purchase.Purchase;

import java.util.ArrayList;
import java.util.List;

public final class RecordingView implements PurchaseView {
    public final List<String> events = new ArrayList<>();

    @Override public void paid(Purchase p, Product product) { events.add("paid:" + p.chargeId + ":" + product.name()); }

    @Override public void expired(Purchase p, boolean replaced) { events.add("expired:" + p.chargeId + ":" + replaced); }

    @Override public void paidWhileOffline(Purchase p) { events.add("offline:" + p.chargeId); }
}
