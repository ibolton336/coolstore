package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Dependent
public class Resources {
    // In Quarkus, EntityManager is automatically available for @Inject
    // This producer is not needed and causes ambiguous dependency issues
    // Keeping the class for potential future use, but removing the producer
}
