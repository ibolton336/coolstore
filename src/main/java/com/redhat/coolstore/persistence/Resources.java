package com.redhat.coolstore.persistence;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Dependent
public class Resources {
    // EntityManager is automatically provided by Quarkus Hibernate ORM extension
    // No need for manual producer in Quarkus
}
