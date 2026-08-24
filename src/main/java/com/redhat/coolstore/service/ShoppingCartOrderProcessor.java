package com.redhat.coolstore.service;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ShoppingCartOrderProcessor {

    @Inject
    Logger log;

    @Inject
    @Channel("orders")
    Emitter<String> ordersEmitter;

    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        try {
            String orderJson = Transformers.shoppingCartToJson(cart);
            ordersEmitter.send(orderJson);
            log.info("Order sent successfully");
        } catch (Exception e) {
            log.error("Error sending order to topic", e);
            throw new RuntimeException("Failed to send order", e);
        }
    }

}
