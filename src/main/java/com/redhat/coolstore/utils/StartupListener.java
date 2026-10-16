package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.logging.Logger;

@ApplicationScoped
public class StartupListener {
    
    private static final Logger log = Logger.getLogger(StartupListener.class.getName());
    
    void onStart(@Observes StartupEvent event) {
        log.info("AppListener(postStart)");
    }
    
    void onStop(@Observes ShutdownEvent event) {
        log.info("AppListener(preStop)");
    }
}
