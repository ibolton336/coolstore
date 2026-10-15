package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.logging.Logger;

@ApplicationScoped
public class StartupHandler {
    
    @Inject
    Logger log;
    
    void onStart(@Observes StartupEvent ev) {
        log.info("Application started");
    }
    
    void onStop(@Observes ShutdownEvent ev) {
        log.info("Application stopped");
    }
}
