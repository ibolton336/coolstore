package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

// JMS messaging disabled - commented out
// import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class OrderServiceMDB {

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	// JMS messaging disabled - @Incoming commented out
	// @Incoming("orders")
	@Transactional
	public void process(String orderStr) {
		System.out.println("\nMessage recd !");
		System.out.println("Received order: " + orderStr);
		Order order = Transformers.jsonToOrder(orderStr);
		System.out.println("Order object is " + order);
		orderService.save(order);
		order.getItemList().forEach(orderItem -> {
			catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
		});
	}

}
