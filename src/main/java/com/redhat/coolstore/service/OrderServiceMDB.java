package com.redhat.coolstore.service;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;
import java.util.logging.Logger;

@ApplicationScoped
public class OrderServiceMDB {

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Inject
	Logger log;

	@Incoming("orders")
	@Blocking
	public void onOrderMessage(String orderStr) {
		log.info("Message received: " + orderStr);
		try {
			Order order = Transformers.jsonToOrder(orderStr);
			log.info("Order object is " + order);
			orderService.save(order);
			order.getItemList().forEach(orderItem -> {
				catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
			});
		} catch (Exception e) {
			log.severe("Failed to process order: " + e.getMessage());
			throw new RuntimeException(e);
		}
	}

}