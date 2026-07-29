package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

@Dependent
public class Resources {

    @Inject
    private EntityManager em;

    @Produces
    public EntityManager getEntityManager() {
        return em;
    }
}
