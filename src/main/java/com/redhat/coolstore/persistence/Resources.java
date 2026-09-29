package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class Resources {
    
    @Produces
    EntityManager entityManager;
}
