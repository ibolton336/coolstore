package com.redhat.coolstore.persistence;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.persistence.EntityManager;

@Dependent
public class Resources {

    @Inject
    private EntityManager em;

}
