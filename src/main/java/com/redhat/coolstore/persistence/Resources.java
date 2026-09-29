package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * This class previously provided an EntityManager producer but is no longer needed.
 * Quarkus provides EntityManager injection natively.
 * Kept as a placeholder for potential future resource producers.
 */
@ApplicationScoped
public class Resources {
    // EntityManager is now provided directly by Quarkus
    // No producer needed
}
