package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class OrderServiceMDB { 

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Incoming("orders-in-order-service")
	public CompletionStage<Void> onMessage(String orderStr) {
		try {
			System.out.println("\nMessage recd !");
			System.out.println("Received order: " + orderStr);
			Order order = Transformers.jsonToOrder(orderStr);
			System.out.println("Order object is " + order);
			orderService.save(order);
			order.getItemList().forEach(orderItem -> {
				catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
			});
			return CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

}
