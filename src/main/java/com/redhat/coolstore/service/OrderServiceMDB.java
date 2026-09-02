package com.redhat.coolstore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.redhat.coolstore.model.Order;
import com.redhat.coolstore.utils.Transformers;

import java.util.logging.Logger;

@ApplicationScoped
public class OrderServiceMDB {

	@Inject
	Logger log;

	@Inject
	OrderService orderService;

	@Inject
	CatalogService catalogService;

	@Incoming("orders-in")
	@Transactional
	public void onOrder(String orderStr) {
		log.info("Received order: " + orderStr);
		Order order = Transformers.jsonToOrder(orderStr);
		log.info("Order object is " + order);
		orderService.save(order);
		order.getItemList().forEach(orderItem -> {
			catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
		});
	}

}