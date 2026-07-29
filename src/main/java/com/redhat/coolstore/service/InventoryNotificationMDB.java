package com.redhat.coolstore.service;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class InventoryNotificationMDB {

    private static final int LOW_THRESHOLD = 50;

    @Inject
    private CatalogService catalogService;

    @Incoming("orders-in-inventory")
    public CompletionStage<Void> onMessage(String orderStr) {
        try {
            System.out.println("received message inventory");
            Order order = Transformers.jsonToOrder(orderStr);
            order.getItemList().forEach(orderItem -> {
                int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId()).getInventory().getQuantity();
                int new_quantity = old_quantity - orderItem.getQuantity();
                if (new_quantity < LOW_THRESHOLD) {
                    System.out.println("Inventory for item " + orderItem.getProductId() + " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                } else {
                    orderItem.setQuantity(new_quantity);
                }
            });
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

}
