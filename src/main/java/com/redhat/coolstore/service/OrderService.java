package com.redhat.coolstore.service;

// Audit logging library not available - commented out for build
// import com.enterprise.audit.logging.config.AuditConfiguration;
// import com.enterprise.audit.logging.exception.AuditLoggingException;
// import com.enterprise.audit.logging.service.FileSystemAuditLogger;
import com.redhat.coolstore.model.Order;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

@ApplicationScoped
public class OrderService {

  @Inject
  private EntityManager em;
  
  @Inject
  private Logger log;

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

  // Audit logging commented out - library not available
  // private FileSystemAuditLogger auditLogger;

  @PostConstruct
  public void init() {
    // Audit logging disabled - library not available
    // AuditConfiguration config = new AuditConfiguration();
    // config.setLogDirectory("./device-inventory-audit-logs");
    // config.setAutoCreateDirectory(true);
    // auditLogger = new FileSystemAuditLogger(config);
    log.info("OrderService initialized - audit logging disabled (library not available)");
  }

  @PreDestroy
  public void cleanup() {
    // Audit logging disabled
    // if (auditLogger != null) {
    //   auditLogger.close();
    // }
    log.info("OrderService cleanup");
  }

}