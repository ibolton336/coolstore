package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * This class previously produced an EntityManager using @PersistenceContext and @Produces.
 * In Quarkus, EntityManager is automatically available for injection when hibernate-orm is configured.
 * The @Produces pattern for EntityManager is no longer needed or recommended.
 * Simply inject EntityManager with @Inject where needed.
 */
@ApplicationScoped
public class Resources {
    // EntityManager is now injected directly in services using @Inject
    // No producer method needed in Quarkus
}
