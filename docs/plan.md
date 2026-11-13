# Migration Plan

## Goal
Migrate a Java EE 7 WAR application (coolstore-monolith) to Quarkus 3 with Java 21, PostgreSQL persistence, ActiveMQ Artemis messaging, Flyway schema management, and Keycloak OIDC authentication.

## Source → Target
Java EE 7 (WAR on JBoss/WebLogic) → Quarkus 3.x (Java 21)

## Scope
- Files affected: 40
- Estimated complexity: High
- Hardest areas:
  1. Message-driven beans (2 MDBs with different patterns - WebLogic JNDI and standard @MessageDriven)
  2. EJB remote interfaces and JNDI lookups requiring CDI conversion
  3. Programmatic Flyway initialization to declarative Quarkus configuration

## Key Decisions Applied

From `.konveyor/interrogation.md`:

- **D1: Source framework matches prompt** — Confirmed (code) — Java EE 7 application to Quarkus 3
- **D2: Java release** — B: Java 21 (human) — Sets compiler to 21, runtime to Java 21
- **D3: Production database** — PostgreSQL (code) — Already configured
- **D4: Schema management** — Flyway (code) — Keep Flyway, migrate to quarkus-flyway
- **D5: Messaging broker** — A: AMQP 1.0 broker (ActiveMQ Artemis) (human) — Convert MDBs to SmallRye Reactive Messaging
- **D6: View layer** — C: Go API-only (human) — Move static content to META-INF/resources, create index.html, add /health endpoint
- **D7: Authentication and authorization** — OpenID Connect via Keycloak (code) — Already integrated
- **D8: Callers of remote EJB interfaces** — ASSUMPTION: A: No other application calls them (assumed) — Remove remote interfaces, convert to CDI
- **D9: Application-server APIs and vendored libraries** — ASSUMPTION: A: Replace with standard logging (assumed) — Replace audit-logging-library with SLF4J

## Approach

**Phase 1: Build Configuration**
- Convert pom.xml from Java EE WAR to Quarkus with BOM, essential extensions (hibernate-orm-panache, jdbc-postgresql, flyway, smallrye-reactive-messaging-amqp, oidc, resteasy-reactive-jackson), Java 21 compiler settings
- Create application.properties with datasource, Flyway, AMQP broker, and OIDC configuration

**Phase 2: Configuration Files**
- Delete persistence.xml (replaced by application.properties)
- Delete beans.xml (CDI enabled by default in Quarkus)
- Delete web.xml (no longer needed)

**Phase 3: Utility/Infrastructure**
- Convert DataBaseMigrationStartup from programmatic Flyway to configuration-based
- Convert StartupListener from WebLogic lifecycle to Quarkus lifecycle events
- Update Producers.java for Quarkus CDI
- Keep Transformers.java (no changes needed)

**Phase 4: Persistence Layer**
- Update Resources.java for Quarkus EntityManager production
- Update all JPA entities for Quarkus (minimal changes)

**Phase 5: Service Layer**
- Convert all @Stateless/@Stateful EJBs to @ApplicationScoped CDI beans
- Remove @Remote annotation and ShippingServiceRemote interface
- Convert JNDI lookup to CDI @Inject in ShoppingCartService
- Replace audit-logging-library with SLF4J in OrderService
- Convert both MDBs (OrderServiceMDB and InventoryNotificationMDB) to SmallRye Reactive Messaging @Incoming methods

**Phase 6: REST Layer**
- Convert JAX-RS Application to Quarkus REST
- Update REST endpoints for Quarkus RESTEasy Reactive
- Add /health endpoint

**Phase 7: View/Frontend**
- Move webapp static content to META-INF/resources
- Convert index.jsp to index.html
- Convert keycloak.json to application.properties

**Phase 8: Cleanup**
- Delete WebLogic stub classes
- Delete health.jsp (replaced by /health endpoint)
- Delete keycloak.json (migrated to properties)

## Steps

### Step 1: Convert pom.xml to Quarkus
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - Replace Java EE dependencies with Quarkus 3 BOM and extensions
    - Set Java compiler source/target to 21
    - Add Quarkus extensions: quarkus-hibernate-orm-panache, quarkus-jdbc-postgresql, quarkus-flyway, quarkus-smallrye-reactive-messaging-amqp, quarkus-oidc, quarkus-resteasy-reactive-jackson, quarkus-arc
    - Change packaging from war to jar
    - Replace maven-war-plugin with quarkus-maven-plugin
    - Remove system-scoped audit-logging-library dependency
    - Update Flyway version to Quarkus-compatible (managed by BOM)
- Why: Quarkus uses different dependency model and packaging
- Depends on: none
- Verify: `mvn dependency:tree` shows Quarkus BOM dependencies, no Java EE dependencies

### Step 2: Create application.properties
- Phase: Build Configuration
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with:
    - Datasource configuration (quarkus.datasource.db-kind=postgresql, jdbc.url, username, password)
    - Flyway configuration (quarkus.flyway.baseline-on-migrate=true, migrate-at-start=true)
    - AMQP broker configuration (mp.messaging.connector.smallrye-amqp.host/port, incoming channel for topic/orders)
    - OIDC configuration (quarkus.oidc.auth-server-url, client-id from keycloak.json, application-type=web-app)
    - Hibernate configuration (quarkus.hibernate-orm.database.generation=none, log.sql=false)
- Why: Quarkus uses application.properties instead of XML descriptors
- Depends on: Step 1
- Verify: File exists with all required properties

### Step 3: Update Resources.java for Quarkus EntityManager
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - BEFORE: @PersistenceContext EntityManager with @Produces
    - AFTER: Remove @Produces, inject EntityManager directly in services
    - OR: Change to @ApplicationScoped with @PersistenceContext and keep @Produces
- Why: Quarkus CDI handles EntityManager differently, can inject directly
- Depends on: Step 1, Step 2
- Verify: `grep -n "@Produces" src/main/java/com/redhat/coolstore/persistence/Resources.java`

### Step 4: Update CatalogItemEntity.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace javax.persistence imports with jakarta.persistence imports
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: `grep -n "jakarta.persistence" src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java`

### Step 5: Update InventoryEntity.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace javax.persistence imports with jakarta.persistence imports
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: `grep -n "jakarta.persistence" src/main/java/com/redhat/coolstore/model/InventoryEntity.java`

### Step 6: Update Order.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace javax.persistence imports with jakarta.persistence imports
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: `grep -n "jakarta.persistence" src/main/java/com/redhat/coolstore/model/Order.java`

### Step 7: Update OrderItem.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace javax.persistence imports with jakarta.persistence imports
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: `grep -n "jakarta.persistence" src/main/java/com/redhat/coolstore/model/OrderItem.java`

### Step 8: Update Producers.java for Quarkus CDI
- Phase: Utility/Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do: Replace javax.enterprise.inject imports with jakarta.enterprise.inject imports
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: `grep -n "jakarta.enterprise" src/main/java/com/redhat/coolstore/utils/Producers.java`

### Step 9: COMPLEX — Convert DataBaseMigrationStartup to Quarkus Flyway
- Phase: Utility/Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: DELETE
- What to do: Delete this file — Flyway migration now handled by Quarkus configuration in application.properties
- Why: Quarkus quarkus-flyway extension manages migrations declaratively via configuration (quarkus.flyway.migrate-at-start=true), no programmatic initialization needed
- Depends on: Step 2
- Verify: File deleted, Flyway configuration in application.properties

### Step 10: COMPLEX — Convert StartupListener from WebLogic to Quarkus lifecycle
- Phase: Utility/Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE: extends ApplicationLifecycleListener with @Override postStart/preStop methods
    - AFTER: @ApplicationScoped class with @Observes io.quarkus.runtime.StartupEvent and @Observes io.quarkus.runtime.ShutdownEvent
    - Specific changes:
        1. Remove: extends ApplicationLifecycleListener, weblogic.application imports
        2. Add: @ApplicationScoped annotation, import io.quarkus.runtime.StartupEvent/ShutdownEvent
        3. Replace: postStart(ApplicationLifecycleEvent evt) → void onStart(@Observes StartupEvent evt)
        4. Replace: preStop(ApplicationLifecycleEvent evt) → void onStop(@Observes ShutdownEvent evt)
- Why: Quarkus uses CDI observer pattern for lifecycle events, WebLogic ApplicationLifecycleListener not available
- Depends on: Step 1
- Verify: `grep -n "@Observes.*StartupEvent" src/main/java/com/redhat/coolstore/utils/StartupListener.java`

### Step 11: Convert CatalogService to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
    - Replace javax.persistence imports with jakarta.persistence
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "@ApplicationScoped" src/main/java/com/redhat/coolstore/service/CatalogService.java && ! grep -n "@Stateless"`

### Step 12: COMPLEX — Convert OrderService to CDI and replace audit library
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
    - Replace javax.persistence imports with jakarta.persistence
    - Replace javax.annotation imports with jakarta.annotation
    - Remove audit-logging-library imports (com.enterprise.audit.logging.*)
    - Add SLF4J Logger: import org.slf4j.Logger; import org.slf4j.LoggerFactory;
    - In @PostConstruct init(): Remove FileSystemAuditLogger initialization, add: logger.info("Order service initialized - audit logging to standard logger")
    - In @PreDestroy cleanup(): Remove auditLogger.close(), can be empty or removed
    - Remove private FileSystemAuditLogger auditLogger field
- Why: Quarkus uses CDI instead of EJB; system-scoped vendored library replaced with standard SLF4J logging
- Depends on: Step 1
- Verify: `grep -n "ApplicationScoped" src/main/java/com/redhat/coolstore/service/OrderService.java && ! grep -n "FileSystemAuditLogger"`

### Step 13: Convert ProductService to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "@ApplicationScoped" src/main/java/com/redhat/coolstore/service/ProductService.java`

### Step 14: Convert PromoService to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "@ApplicationScoped" src/main/java/com/redhat/coolstore/service/PromoService.java`

### Step 15: COMPLEX — Convert ShippingService to CDI and remove @Remote
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - Replace @Stateless with @ApplicationScoped
    - Replace @Remote annotation — delete it
    - Remove: javax.ejb.Remote and javax.ejb.Stateless imports
    - Add: jakarta.enterprise.context.ApplicationScoped import
    - Remove: implements ShippingServiceRemote (class becomes: public class ShippingService)
    - Keep methods calculateShipping() and calculateShippingInsurance() as-is
- Why: Quarkus uses CDI instead of EJB; no remote interfaces needed per D8 decision
- Depends on: Step 1
- Verify: `grep -n "@ApplicationScoped" src/main/java/com/redhat/coolstore/service/ShippingService.java && ! grep -n "@Remote"`

### Step 16: COMPLEX — Convert ShoppingCartService to CDI and remove JNDI lookup
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - Replace @Stateful with @ApplicationScoped (or @RequestScoped if session state needed per request)
    - Replace javax.ejb.Stateful with jakarta.enterprise.context.ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
    - Add @Inject ShippingService shippingService field
    - Remove lookupShippingServiceRemote() method entirely
    - Replace all calls to lookupShippingServiceRemote().calculateShipping() with shippingService.calculateShipping()
    - Replace all calls to lookupShippingServiceRemote().calculateShippingInsurance() with shippingService.calculateShippingInsurance()
    - Remove: javax.naming.* imports, java.util.Hashtable import
- Why: Quarkus uses CDI @Inject instead of JNDI lookups; no remote interfaces per D8 decision
- Depends on: Step 15
- Verify: `grep -n "@Inject.*ShippingService" src/main/java/com/redhat/coolstore/service/ShoppingCartService.java && ! grep -n "lookupShippingServiceRemote"`

### Step 17: Convert ShoppingCartOrderProcessor to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
    - Replace javax.jms imports with jakarta.jms
- Why: Quarkus uses CDI instead of EJB and Jakarta namespace
- Depends on: Step 1
- Verify: `grep -n "@ApplicationScoped" src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java`

### Step 18: COMPLEX — Convert OrderServiceMDB to SmallRye Reactive Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: @MessageDriven with @ActivationConfigProperty, implements MessageListener
    - AFTER: @ApplicationScoped class with @Incoming method
    - Specific changes:
        1. Remove: @MessageDriven annotation and all @ActivationConfigProperty
        2. Remove: implements MessageListener
        3. Add: @ApplicationScoped annotation
        4. Add: import org.eclipse.microprofile.reactive.messaging.Incoming
        5. Replace onMessage(Message rcvMessage) with:
           ```java
           @Incoming("orders")
           public void processOrder(String orderStr) {
               System.out.println("Received order: " + orderStr);
               Order order = Transformers.jsonToOrder(orderStr);
               System.out.println("Order object is " + order);
               orderService.save(order);
               order.getItemList().forEach(orderItem -> {
                   catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
               });
           }
           ```
        6. Remove: javax.ejb, javax.jms imports
        7. Keep: @Inject OrderService, @Inject CatalogService
- Why: Quarkus uses SmallRye Reactive Messaging instead of MDBs; AMQP channel "orders" configured in application.properties
- Depends on: Step 2
- Verify: `grep -n "@Incoming" src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java && ! grep -n "@MessageDriven"`

### Step 19: COMPLEX — Convert InventoryNotificationMDB to SmallRye Reactive Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: implements MessageListener with manual WebLogic JNDI TopicConnection setup
    - AFTER: @ApplicationScoped class with @Incoming method
    - Specific changes:
        1. Add: @ApplicationScoped annotation
        2. Add: import org.eclipse.microprofile.reactive.messaging.Incoming
        3. Replace onMessage(Message rcvMessage) with:
           ```java
           @Incoming("orders-inventory")
           public void processInventoryNotification(String orderStr) {
               System.out.println("received message inventory");
               Order order = Transformers.jsonToOrder(orderStr);
               order.getItemList().forEach(orderItem -> {
                   int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId()).getInventory().getQuantity();
                   int new_quantity = old_quantity - orderItem.getQuantity();
                   if (new_quantity < LOW_THRESHOLD) {
                       System.out.println("Inventory for item " + orderItem.getProductId() + " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                   } else {
                       orderItem.setQuantity(new_quantity);
                   }
               });
           }
           ```
        4. Remove: implements MessageListener
        5. Remove: All JNDI/WebLogic code (JNDI_FACTORY, JMS_FACTORY, init(), close(), getInitialContext())
        6. Remove: TopicConnection, TopicSession, TopicSubscriber fields
        7. Remove: javax.jms, javax.naming, javax.rmi imports
        8. Keep: @Inject CatalogService, LOW_THRESHOLD constant
- Why: Quarkus uses SmallRye Reactive Messaging instead of manual JMS setup; WebLogic JNDI not available
- Depends on: Step 2
- Verify: `grep -n "@Incoming" src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java && ! grep -n "weblogic.jndi"`

### Step 20: Convert RestApplication to Quarkus
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace javax.ws.rs imports with jakarta.ws.rs
    - Keep @ApplicationPath("/services")
    - Keep extends Application
- Why: Quarkus 3 uses Jakarta namespace for JAX-RS
- Depends on: Step 1
- Verify: `grep -n "jakarta.ws.rs" src/main/java/com/redhat/coolstore/rest/RestApplication.java`

### Step 21: Update CartEndpoint for Quarkus RESTEasy Reactive
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace javax.ws.rs imports with jakarta.ws.rs
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
- Why: Quarkus uses Jakarta namespace and CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "jakarta.ws.rs" src/main/java/com/redhat/coolstore/rest/CartEndpoint.java`

### Step 22: Update OrderEndpoint for Quarkus RESTEasy Reactive
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace javax.ws.rs imports with jakarta.ws.rs
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
- Why: Quarkus uses Jakarta namespace and CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "jakarta.ws.rs" src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java`

### Step 23: Update ProductEndpoint for Quarkus RESTEasy Reactive
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace javax.ws.rs imports with jakarta.ws.rs
    - Replace javax.ejb.Stateless with jakarta.enterprise.context.ApplicationScoped
    - Replace @Stateless with @ApplicationScoped
    - Replace javax.inject imports with jakarta.inject
- Why: Quarkus uses Jakarta namespace and CDI instead of EJB
- Depends on: Step 1
- Verify: `grep -n "jakarta.ws.rs" src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java`

### Step 24: Create HealthEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/HealthEndpoint.java
- Action: CREATE
- What to do: Create simple REST endpoint:
    ```java
    package com.redhat.coolstore.rest;
    
    import jakarta.ws.rs.GET;
    import jakarta.ws.rs.Path;
    import jakarta.ws.rs.Produces;
    import jakarta.ws.rs.core.MediaType;
    
    @Path("/health")
    public class HealthEndpoint {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String health() {
            return "1";
        }
    }
    ```
- Why: Replace health.jsp with REST endpoint per D6 decision
- Depends on: Step 1
- Verify: File exists, `curl /services/health` returns "1"

### Step 25: Move webapp static content to META-INF/resources
- Phase: View/Frontend
- File: src/main/resources/META-INF/resources
- Action: CREATE
- What to do:
    - Create directory src/main/resources/META-INF/resources
    - Copy src/main/webapp/app/* to src/main/resources/META-INF/resources/app/
    - Copy src/main/webapp/bower_components/* to src/main/resources/META-INF/resources/bower_components/
    - Copy src/main/webapp/partials/* to src/main/resources/META-INF/resources/partials/
    - Copy src/main/webapp/coolstore.json to src/main/resources/META-INF/resources/coolstore.json
- Why: Quarkus serves static resources from META-INF/resources, not webapp
- Depends on: Step 1
- Verify: Directory exists with all static content

### Step 26: Convert index.jsp to index.html
- Phase: View/Frontend
- File: src/main/resources/META-INF/resources/index.html
- Action: CREATE
- What to do: Create index.html from index.jsp content, removing JSP scriptlet:
    - Remove: <% request.getSession(true); %>
    - Keep all other HTML content as-is
- Why: Quarkus doesn't support JSP; convert to static HTML per D6 decision
- Depends on: Step 25
- Verify: File exists, loads in browser

### Step 27: Delete persistence.xml
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file — persistence configuration moved to application.properties
- Why: Quarkus uses application.properties instead of persistence.xml
- Depends on: Step 2
- Verify: File deleted

### Step 28: Delete beans.xml
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file — CDI enabled by default in Quarkus
- Why: Quarkus enables CDI by default, beans.xml not needed
- Depends on: Step 1
- Verify: File deleted

### Step 29: Delete web.xml
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file — no deployment descriptor needed
- Why: Quarkus doesn't use web.xml deployment descriptors
- Depends on: Step 1
- Verify: File deleted

### Step 30: Delete ApplicationLifecycleEvent.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this WebLogic stub class — no longer needed
- Why: Converted to Quarkus lifecycle events in Step 10
- Depends on: Step 10
- Verify: File deleted

### Step 31: Delete ApplicationLifecycleListener.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this WebLogic stub class — no longer needed
- Why: Converted to Quarkus lifecycle events in Step 10
- Depends on: Step 10
- Verify: File deleted

### Step 32: Delete NonCatalogLogger.java
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this empty WebLogic stub class
- Why: Empty stub, not used in application
- Depends on: none
- Verify: File deleted

### Step 33: Delete ShippingServiceRemote.java
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete remote interface — no longer needed
- Why: Converted to direct CDI injection per D8 decision
- Depends on: Step 15, Step 16
- Verify: File deleted

### Step 34: Delete health.jsp
- Phase: Cleanup
- File: src/main/webapp/health.jsp
- Action: DELETE
- What to do: Delete this file — replaced by REST /health endpoint
- Why: Replaced with HealthEndpoint per D6 decision
- Depends on: Step 24
- Verify: File deleted

### Step 35: Delete index.jsp
- Phase: Cleanup
- File: src/main/webapp/index.jsp
- Action: DELETE
- What to do: Delete this file — replaced by index.html in META-INF/resources
- Why: Converted to static HTML per D6 decision
- Depends on: Step 26
- Verify: File deleted

### Step 36: Delete keycloak.json
- Phase: Cleanup
- File: src/main/webapp/keycloak.json
- Action: DELETE
- What to do: Delete this file — configuration migrated to application.properties
- Why: Quarkus OIDC uses application.properties configuration
- Depends on: Step 2
- Verify: File deleted

### Step 37: Delete WEB-INF directory
- Phase: Cleanup
- File: src/main/webapp/WEB-INF
- Action: DELETE
- What to do: Delete entire WEB-INF directory (already emptied by Steps 28-29)
- Why: No longer needed in Quarkus JAR packaging
- Depends on: Step 28, Step 29
- Verify: Directory deleted

### Step 38: Delete webapp app directory
- Phase: Cleanup
- File: src/main/webapp/app
- Action: DELETE
- What to do: Delete directory — content moved to META-INF/resources
- Why: Static content moved per Step 25
- Depends on: Step 25
- Verify: Directory deleted

### Step 39: Delete webapp bower_components directory
- Phase: Cleanup
- File: src/main/webapp/bower_components
- Action: DELETE
- What to do: Delete directory — content moved to META-INF/resources
- Why: Static content moved per Step 25
- Depends on: Step 25
- Verify: Directory deleted

### Step 40: Delete webapp partials directory
- Phase: Cleanup
- File: src/main/webapp/partials
- Action: DELETE
- What to do: Delete directory — content moved to META-INF/resources
- Why: Static content moved per Step 25
- Depends on: Step 25
- Verify: Directory deleted

## Verification

- Build: `mvn clean package -DskipTests` (or `mvn clean compile` for incremental verification)
- Test: Tests are disabled in original pom.xml (maven.test.skip=true), so no test command
- Blackbox: 
  1. Start PostgreSQL: `podman run --rm --name postgres -e POSTGRES_USER=coolstore -e POSTGRES_PASSWORD=coolstore -e POSTGRES_DB=postgresDB -p 5432:5432 postgres:13`
  2. Start ActiveMQ Artemis broker: `podman run --rm --name artemis -e ARTEMIS_USER=admin -e ARTEMIS_PASSWORD=admin -p 61616:61616 -p 8161:8161 quay.io/artemis-cloud/activemq-artemis-broker:latest`
  3. Start Keycloak and import realm: `podman run --rm --name keycloak -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin -p 8081:8080 -v $(pwd)/realm-export.json:/opt/keycloak/data/import/realm.json quay.io/keycloak/keycloak:20.0.5 start-dev --import-realm`
  4. Start application: `mvn quarkus:dev`
  5. Verify frontend loads: Open http://localhost:8080 — should display Cool Store catalog
  6. Verify health endpoint: `curl http://localhost:8080/services/health` — should return "1"
  7. Verify REST API: `curl http://localhost:8080/services/products` — should return product list
  8. Add item to cart via UI and checkout — verifies messaging to topic/orders
  9. Check logs for "Received order" and "received message inventory" — verifies both MDB conversions working

## Notes

### Assumptions flagged
- **D8**: Remote EJB interfaces assumed not called by other apps — if this is wrong and another application does call ShippingServiceRemote, that integration will break. Solution: expose calculateShipping/calculateShippingInsurance as REST endpoints.
- **D9**: Audit logging library replaced with SLF4J — audit trail format changes from dedicated files (./device-inventory-audit-logs) to standard application logs. If file-based audit with specific format required, need to configure separate log appender.

### Migration order notes
The phase order follows dependency flow:
1. Build config must be first (enables all other changes)
2. Models/persistence before services (services depend on models)
3. Services before REST endpoints (REST depends on services)
4. Frontend after REST (frontend calls REST)
5. Cleanup last (after all migrations complete)

### Quarkus configuration notes
- AMQP connector in application.properties needs two separate incoming channels: "orders" for OrderServiceMDB and "orders-inventory" for InventoryNotificationMDB — both subscribe to the same topic/orders but process independently
- quarkus.oidc configuration requires matching the realm/client-id from keycloak.json
- Flyway baseline-on-migrate=true handles existing schema from V1_1/V1_2 scripts

### State management consideration
ShoppingCartService was @Stateful in Java EE, converted to @ApplicationScoped. Original code has `private ShoppingCart cart = new ShoppingCart()` instance field with comment "Each user can have multiple shopping carts". In Quarkus:
- @ApplicationScoped = singleton shared across all requests (WRONG for user carts)
- Should use @RequestScoped or session-scoped storage
- Recommend: Change to @RequestScoped and store cart in HTTP session or database

This is a design issue to address during execution — flag for review.

### WebLogic/JBoss portability
Original app had both WebLogic stubs (ApplicationLifecycleListener, WLInitialContextFactory) and JBoss references (WildFlyInitialContextFactory). Migration removes all app-server-specific code in favor of Quarkus portability.
