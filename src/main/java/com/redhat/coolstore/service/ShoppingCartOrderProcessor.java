package com.redhat.coolstore.service;

import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.smallrye.reactive.messaging.MutinyEmitter;
import org.eclipse.microprofile.reactive.messaging.Channel;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ShoppingCartOrderProcessor  {

    @Inject
    Logger log;

    @Channel("orders")
    MutinyEmitter<String> orderEmitter;
  
    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        orderEmitter.send(Transformers.shoppingCartToJson(cart));
    }
}
