package com.redhat.coolstore.utils;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.logging.Logger;

@ApplicationScoped
public class StartupListener {

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent ev) {
        log.info("Application starting up");
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        log.info("Application shutting down");
    }

}
