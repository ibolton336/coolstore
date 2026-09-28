package com.redhat.coolstore.utils;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.logging.Logger;

/**
 * Application lifecycle listener - converted from WebLogic ApplicationLifecycleListener to Quarkus events.
 */
@ApplicationScoped
public class StartupListener {

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent evt) {
        log.info("AppListener(postStart)");
    }

    void onShutdown(@Observes ShutdownEvent evt) {
        log.info("AppListener(preStop)");
    }

}
