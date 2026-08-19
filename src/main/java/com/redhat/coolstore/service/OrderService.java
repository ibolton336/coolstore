package com.redhat.coolstore.service;

import com.enterprise.audit.logging.config.AuditConfiguration;
import com.enterprise.audit.logging.exception.AuditLoggingException;
import com.enterprise.audit.logging.model.AuditEvent;
import com.enterprise.audit.logging.service.StreamableAuditLogger;
import com.redhat.coolstore.model.Order;
import java.time.LocalDateTime;
import java.util.List;

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

  private StreamableAuditLogger auditLogger;

  @PostConstruct
  public void init() throws AuditLoggingException {
    // Initialize audit logger with TCP streaming configuration
    AuditConfiguration config = new AuditConfiguration();
    config.setHost("localhost");
    config.setPort(9999);
    config.setAutoReconnect(true);
    auditLogger = new StreamableAuditLogger(config);
  }

  @PreDestroy
  public void cleanup() throws AuditLoggingException {
    if (auditLogger != null) {
      auditLogger.close();
    }
  }

  @Transactional
  public void save(Order order) {
    em.persist(order);
    
    // Log audit event asynchronously using new API
    try {
      AuditEvent event = new AuditEvent(
        "ORDER_CREATED",
        "system",
        LocalDateTime.now(),
        "Order created with ID: " + order.getOrderId(),
        "SUCCESS",
        null
      );
      auditLogger.logEventAsync(event);
    } catch (Exception e) {
      // Log but don't fail the transaction
      System.err.println("Failed to log audit event: " + e.getMessage());
    }
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

}
