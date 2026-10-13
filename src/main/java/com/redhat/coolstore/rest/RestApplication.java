package com.redhat.coolstore.rest;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

// This class is not required in Quarkus but kept for explicit path configuration
@ApplicationPath("/services")
public class RestApplication extends Application {

}
