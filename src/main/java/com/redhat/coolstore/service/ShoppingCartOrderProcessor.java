package com.redhat.coolstore.service;

import java.util.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
// JMS not configured - commented out for build
// import jakarta.jms.JMSContext;
// import jakarta.jms.Topic;

import com.redhat.coolstore.model.ShoppingCart;
import com.redhat.coolstore.utils.Transformers;

@ApplicationScoped
public class ShoppingCartOrderProcessor  {

    @Inject
    Logger log;

    // JMS not configured - commented out for build
    // @Inject
    // private transient JMSContext context;

    // @Resource(lookup = "java:/topic/orders")
    // private Topic ordersTopic;

    
  
    public void  process(ShoppingCart cart) {
        log.info("Order processing requested (JMS disabled) - cart total: " + cart.getCartTotal());
        // JMS disabled - would send: context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
    }



}
