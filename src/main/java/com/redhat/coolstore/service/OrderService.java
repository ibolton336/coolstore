package com.redhat.coolstore.service;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.exception.AuditLoggingException;
import com.enterprise.audit.logging.service.FileSystemAuditLogger;
import com.redhat.coolstore.model.Order;
import java.util.List;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class OrderService {

  @Inject
  EntityManager em;

  @Transactional
  public void save(Order order) {
    em.persist(order);
  }

  public List<Order> getOrders() {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Order> criteria = cb.createQuery(Order.class);
    Root<Order> member = criteria.from(Order.class);
    criteria.select(member);
    return em.createQuery(criteria).getResultList();
  }

  public Order getOrderById(long id) {
    return em.find(Order.class, id);
  }

  private FileSystemAuditLogger auditLogger;

  @PostConstruct
  public void init() throws AuditLoggingException {
    // Initialize audit logger
    AuditConfiguration config = new AuditConfiguration();
    config.setLogDirectory("./device-inventory-audit-logs");
    config.setAutoCreateDirectory(true);
    auditLogger = new FileSystemAuditLogger(config);

  }

  @PreDestroy
  public void cleanup() throws AuditLoggingException {
    if (auditLogger != null) {
      auditLogger.close();
    }
  }

}
