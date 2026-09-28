package com.redhat.coolstore.service;

import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ShoppingCartOrderProcessor  {

    @Inject
    Logger log;

    @Channel("orders")
    @Inject
    Emitter<String> ordersEmitter;

    public void  process(ShoppingCart cart) {
        log.info("Sending order from processor: " + Transformers.shoppingCartToJson(cart));
        ordersEmitter.send(Transformers.shoppingCartToJson(cart));
    }

}
