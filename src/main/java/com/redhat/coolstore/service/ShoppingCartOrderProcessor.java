package com.redhat.coolstore.service;

import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.smallrye.reactive.messaging.channels.Channel;
import io.smallrye.reactive.messaging.Emitter;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ShoppingCartOrderProcessor  {

    @Inject
    Logger log;

    @Channel("orders")
    Emitter<String> ordersEmitter;

    public void  process(ShoppingCart cart) {
        log.info("Sending order from processor: " + Transformers.shoppingCartToJson(cart));
        ordersEmitter.send(Transformers.shoppingCartToJson(cart));
    }

}
