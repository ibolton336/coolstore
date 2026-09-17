package com.redhat.coolstore.persistence;

import javax.enterprise.context.Dependent;

@Dependent
public class Resources {
    // In Quarkus, EntityManager is automatically available for injection
    // when datasource is properly configured in application.properties.
    // The @Produces EntityManager pattern is not needed and should be removed.
    // Services can directly @Inject EntityManager.
}
