package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Dependent
public class Resources {

    @PersistenceContext
    private EntityManager em;

    // Note: @Produces removed - Quarkus provides EntityManager beans automatically
    public EntityManager getEntityManager() {
        return em;
    }
}
