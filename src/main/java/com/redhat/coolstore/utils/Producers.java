package com.redhat.coolstore.utils;

import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.jboss.logging.Logger;


public class Producers {

    Logger log = Logger.getLogger(Producers.class.getName());

    @Produces
    public Logger produceLog(InjectionPoint injectionPoint) {
        return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
    }

}
