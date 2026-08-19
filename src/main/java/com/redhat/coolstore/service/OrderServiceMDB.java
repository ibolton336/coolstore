package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class OrderServiceMDB {

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Incoming("orders")
	public void onMessage(String orderMessage) {
		System.out.println("\nMessage received!");
		try {
			System.out.println("Received order: " + orderMessage);
			Order order = Transformers.jsonToOrder(orderMessage);
			System.out.println("Order object is " + order);
			orderService.save(order);
			order.getItemList().forEach(orderItem -> {
				catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
			});
		} catch (Exception e) {
			System.err.println("Error processing order message: " + e.getMessage());
			throw new RuntimeException(e);
		}
	}

}
