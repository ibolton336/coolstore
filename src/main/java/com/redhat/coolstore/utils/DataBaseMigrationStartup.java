package com.redhat.coolstore.utils;

import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.runtime.Startup;

/**
 * Created by tqvarnst on 2017-04-04.
 */
@ApplicationScoped
@Startup
public class DataBaseMigrationStartup {

    @Inject
    Logger logger;

    @PostConstruct
    void startup() {
        logger.info("Application started - Flyway migration handled by Quarkus");
    }

}
