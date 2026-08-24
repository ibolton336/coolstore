package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
@Transactional
public class OrderServiceMDB { 

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Incoming("orders")
	public void processOrder(String orderJson) {
		System.out.println("\nMessage recd !");
		System.out.println("Received order: " + orderJson);
		Order order = Transformers.jsonToOrder(orderJson);
		System.out.println("Order object is " + order);
		orderService.save(order);
		order.getItemList().forEach(orderItem -> {
			catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
		});
	}

}
