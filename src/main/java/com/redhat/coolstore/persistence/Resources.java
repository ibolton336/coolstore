package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@Dependent
public class Resources {

    @Inject
    private EntityManager em;
}
