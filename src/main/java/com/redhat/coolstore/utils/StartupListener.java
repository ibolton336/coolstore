package com.redhat.coolstore.utils;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.logging.Logger;

@ApplicationScoped
public class StartupListener {

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent evt) {
        log.info("AppListener(postStart)");
    }

    void onStop(@Observes ShutdownEvent evt) {
        log.info("AppListener(preStop)");
    }

}
