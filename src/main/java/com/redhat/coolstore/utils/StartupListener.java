package com.redhat.coolstore.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;

import jakarta.inject.Inject;
import java.util.logging.Logger;

@ApplicationScoped
public class StartupListener {

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent event) {
        log.info("AppListener(postStart)");
    }

    void onStop(@Observes ShutdownEvent event) {
        log.info("AppListener(preStop)");
    }

}

