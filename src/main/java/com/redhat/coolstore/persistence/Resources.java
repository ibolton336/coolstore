package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class Resources {

    @Inject
    EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }
}
