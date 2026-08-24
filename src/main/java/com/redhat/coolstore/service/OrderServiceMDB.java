package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class OrderServiceMDB {

	@Inject
	Logger log;

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Incoming("orders")
	public void onMessage(String message) {
		log.info("\nMessage recd !");
		try {
			log.info("Received order: " + message);
			Order order = Transformers.jsonToOrder(message);
			log.info("Order object is " + order);
			orderService.save(order);
			order.getItemList().forEach(orderItem -> {
				catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
			});
		} catch (Exception e) {
			log.error("Error processing order message", e);
			throw new RuntimeException(e);
		}
	}

}
