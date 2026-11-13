# Migration Plan

## Goal
Migrate the CoolStore monolith from Java EE 7 to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4) → Quarkus 3

## Scope
- Files affected: 39
- Estimated complexity: High
- Hardest areas: JMS message-driven beans (ActiveMQ Artemis configuration), WebLogic application lifecycle migration to Quarkus observers, EJB to CDI conversion with Remote interface removal

## Key Decisions Applied

From `.konveyor/interrogation.md`:

- **D1: Confirm migration from Java EE to Quarkus 3** - Confirmed — migrate from Java EE 7 to Quarkus 3 (source: code)
- **D2: Java release** - B) Java 21 — a later long-term-support release (source: human)
- **D3: Production database** - PostgreSQL (source: code)
- **D4: Schema management** - Keep Flyway (source: code)
- **D5: Messaging broker** - A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics (source: human)
- **D6: View layer** - C) Go API-only — REST endpoints for what the pages do, with the user interface as separate work (source: human)
- **D7: Authentication and authorisation** - OpenID Connect with Keycloak (source: code)
- **ASSUMPTION D8: Callers of remote EJB interfaces** - A) No other application calls them — remove the remote interfaces (source: assumed)
- **ASSUMPTION D9: Application-server APIs and vendored libraries** - Replace weblogic.application.ApplicationLifecycleListener with Quarkus startup/shutdown observers, keep audit-logging-library as ordinary dependency, remove InventoryNotificationMDB (unused dead code) (source: assumed)
- **D10: Modules and packaging** - Single Quarkus application module (source: code)

## Approach

**Phase 1: Build Configuration**
- Replace Java EE dependencies with Quarkus BOM and extensions
- Update Java compiler to target Java 21
- Add Quarkus extensions: hibernate-orm-panache, jdbc-postgresql, flyway, artemis-jms, oidc, resteasy-reactive-jackson, smallrye-health
- Convert audit-logging-library from system-scoped to regular dependency
- Add Quarkus Maven plugin

**Phase 2: Configuration Files**
- Create Quarkus application.properties with datasource, Flyway, Artemis, and OIDC configuration
- Remove Java EE deployment descriptors (web.xml, persistence.xml)

**Phase 3: Models and Entities**
- Update JPA entities to use Quarkus/Hibernate conventions
- Remove javax.* imports, replace with jakarta.*

**Phase 4: Persistence Layer**
- Remove Resources.java EntityManager producer (Quarkus provides direct injection)

**Phase 5: Service Layer**
- Convert EJB @Stateless beans to CDI @ApplicationScoped
- Remove @Remote interface from ShippingService
- Replace WebLogic ApplicationLifecycleListener with Quarkus lifecycle observers
- Convert @MessageDriven bean to Quarkus JMS listener
- Remove dead code (InventoryNotificationMDB)
- Update service classes to use jakarta.* imports

**Phase 6: REST Layer**
- Update JAX-RS annotations to jakarta.*
- Update RestApplication to extend Application correctly
- Update @SessionScoped to use appropriate Quarkus scope

**Phase 7: View Layer Cleanup**
- Delete JSP files (index.jsp, health.jsp)
- Delete keycloak.json (replaced by OIDC configuration)
- Delete web.xml

**Phase 8: Utilities**
- Update Transformers, Producers, DataBaseMigrationStartup
- Remove StartupListener (replaced in Phase 5)

**Phase 9: WebLogic Vendored Classes**
- Delete weblogic package directory (no longer needed after lifecycle migration)

## Steps

### Step 1: Update pom.xml with Quarkus BOM and properties
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - Update maven.compiler.source and target to 21
    - Replace packaging from war to jar (Quarkus uber-jar)
    - Remove all Java EE dependencies (javaee-web-api, javaee-api, jboss-jms-api, jboss-rmi-api)
    - Add Quarkus BOM (io.quarkus.platform:quarkus-bom) in dependencyManagement
    - Add Quarkus Maven plugin (io.quarkus.platform:quarkus-maven-plugin)
    - Convert audit-logging-library from system scope to compile scope with proper dependency declaration
- Why: Quarkus requires its own BOM and build plugin, Java 21 per D2, and system-scoped dependencies are not portable
- Depends on: none
- Verify: `mvn help:effective-pom | grep -A 3 quarkus-bom` shows Quarkus BOM

### Step 2: Add Quarkus extensions to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - Add quarkus-hibernate-orm (JPA support)
    - Add quarkus-jdbc-postgresql (PostgreSQL driver per D3)
    - Add quarkus-flyway (schema management per D4)
    - Add quarkus-artemis-jms (messaging per D5)
    - Add quarkus-oidc (Keycloak authentication per D7)
    - Add quarkus-resteasy-reactive-jackson (REST + JSON)
    - Add quarkus-smallrye-health (health check endpoint)
    - Remove flyway-core 4.1.2 dependency (replaced by quarkus-flyway)
- Why: Quarkus extensions provide the framework capabilities to replace Java EE APIs
- Depends on: Step 1
- Verify: `mvn dependency:tree | grep quarkus` shows all required extensions

### Step 3: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with:
    - Datasource configuration (quarkus.datasource.db-kind=postgresql, jdbc.url, username, password) for PostgreSQL per D3
    - Flyway configuration (quarkus.flyway.migrate-at-start=true, locations=classpath:db/migration) per D4
    - Hibernate ORM configuration (quarkus.hibernate-orm.database.generation=none, log.sql=false) - schema managed by Flyway
    - Artemis JMS configuration (quarkus.artemis.url, username, password) per D5
    - OIDC configuration mapping from keycloak.json (quarkus.oidc.auth-server-url, client-id, credentials.secret) per D7
    - Application name and other Quarkus settings
- Why: Quarkus uses application.properties instead of Java EE XML descriptors
- Depends on: Step 2
- Verify: File exists with all required property groups (datasource, flyway, hibernate, artemis, oidc)

### Step 4: Update CatalogItemEntity.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all javax.persistence.* imports with jakarta.persistence.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax.persistence" src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java` returns nothing

### Step 5: Update InventoryEntity.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all javax.persistence.* imports with jakarta.persistence.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax.persistence" src/main/java/com/redhat/coolstore/model/InventoryEntity.java` returns nothing

### Step 6: Update Order.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace all javax.persistence.* imports with jakarta.persistence.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax.persistence" src/main/java/com/redhat/coolstore/model/Order.java` returns nothing

### Step 7: Update OrderItem.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace all javax.persistence.* imports with jakarta.persistence.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax.persistence" src/main/java/com/redhat/coolstore/model/OrderItem.java` returns nothing

### Step 8: Update Product.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all javax.* imports (if any) with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/model/Product.java` returns nothing

### Step 9: Update Promotion.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace all javax.* imports (if any) with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/model/Promotion.java` returns nothing

### Step 10: Update ShoppingCart.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all javax.* imports (if any) with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/model/ShoppingCart.java` returns nothing

### Step 11: Update ShoppingCartItem.java imports
- Phase: Models and Entities
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace all javax.* imports (if any) with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java` returns nothing

### Step 12: Remove Resources.java EntityManager producer
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete this file — Quarkus provides direct EntityManager injection via @Inject without needing a producer
- Why: Quarkus handles EntityManager lifecycle automatically
- Depends on: Step 3
- Verify: File no longer exists

### Step 13: COMPLEX — Convert CatalogService.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB annotation and javax.* imports
    - AFTER: Uses @ApplicationScoped CDI bean and jakarta.* imports
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace all javax.inject.* with jakarta.inject.*
        4. Replace all javax.persistence.* with jakarta.persistence.*
- Why: Quarkus uses CDI beans instead of EJBs
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/CatalogService.java` returns nothing

### Step 14: COMPLEX — Convert OrderService.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB, javax.* imports, and @PostConstruct/@PreDestroy for audit logger
    - AFTER: Uses @ApplicationScoped CDI, jakarta.* imports, and Quarkus lifecycle observers
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace `import javax.annotation.PostConstruct` and `PreDestroy` with `import jakarta.annotation.PostConstruct` and `jakarta.annotation.PreDestroy`
        4. Replace all javax.inject.* with jakarta.inject.*
        5. Replace all javax.persistence.* with jakarta.persistence.*
- Why: Quarkus uses CDI beans instead of EJBs, Jakarta namespace required
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/OrderService.java` returns nothing

### Step 15: COMPLEX — Convert ProductService.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB annotation and javax.* imports
    - AFTER: Uses @ApplicationScoped CDI bean and jakarta.* imports
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace all javax.inject.* with jakarta.inject.*
        4. Replace all javax.persistence.* with jakarta.persistence.*
- Why: Quarkus uses CDI beans instead of EJBs
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/ProductService.java` returns nothing

### Step 16: COMPLEX — Convert PromoService.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB annotation and javax.* imports
    - AFTER: Uses @ApplicationScoped CDI bean and jakarta.* imports
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace all javax.inject.* with jakarta.inject.*
- Why: Quarkus uses CDI beans instead of EJBs
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/PromoService.java` returns nothing

### Step 17: COMPLEX — Convert ShippingService.java and remove Remote interface
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless and @Remote EJB annotations, implements ShippingServiceRemote interface
    - AFTER: Uses @ApplicationScoped CDI bean, no remote interface
    - Specific changes:
        1. Remove `import javax.ejb.Remote`
        2. Remove `import javax.ejb.Stateless`
        3. Add `import jakarta.enterprise.context.ApplicationScoped`
        4. Replace `@Stateless` and `@Remote` with `@ApplicationScoped`
        5. Remove `implements ShippingServiceRemote` (class now standalone)
- Why: No external callers per D8, Quarkus uses CDI beans instead of EJBs
- Depends on: Step 12
- Verify: `grep -E "@Stateless|@Remote|implements ShippingServiceRemote|import javax" src/main/java/com/redhat/coolstore/service/ShippingService.java` returns nothing

### Step 18: Delete ShippingServiceRemote.java
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this file — no longer needed per D8 (no external callers)
- Why: Remote interface removed as no other applications call it
- Depends on: Step 17
- Verify: File no longer exists

### Step 19: COMPLEX — Convert ShoppingCartOrderProcessor.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB annotation and javax.* imports
    - AFTER: Uses @ApplicationScoped CDI bean and jakarta.* imports
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace all javax.inject.* with jakarta.inject.*
        4. Replace all javax.jms.* with jakarta.jms.*
- Why: Quarkus uses CDI beans instead of EJBs, Jakarta namespace required
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java` returns nothing

### Step 20: COMPLEX — Convert ShoppingCartService.java to Quarkus
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @Stateless EJB annotation and javax.* imports
    - AFTER: Uses @ApplicationScoped CDI bean and jakarta.* imports
    - Specific changes:
        1. Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Replace all javax.inject.* with jakarta.inject.*
        4. Replace all javax.persistence.* with jakarta.persistence.*
- Why: Quarkus uses CDI beans instead of EJBs
- Depends on: Step 12
- Verify: `grep -E "@Stateless|import javax" src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` returns nothing

### Step 21: COMPLEX — Convert OrderServiceMDB.java to Quarkus JMS listener
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses @MessageDriven EJB with @ActivationConfigProperty for topic/orders, javax.* imports
    - AFTER: Uses @JMSListener with Quarkus Artemis JMS, jakarta.* imports
    - Specific changes:
        1. Remove `import javax.ejb.ActivationConfigProperty`
        2. Remove `import javax.ejb.MessageDriven`
        3. Add `import io.quarkus.artemis.jms.JMSListener`
        4. Add `import jakarta.enterprise.context.ApplicationScoped`
        5. Replace `@MessageDriven(name = "OrderServiceMDB", activationConfig = {...})` with `@ApplicationScoped` and add `@JMSListener(destination = "topic/orders", destinationType = DestinationType.TOPIC)`
        6. Replace all javax.inject.* with jakarta.inject.*
        7. Replace all javax.jms.* with jakarta.jms.*
- Why: Quarkus uses different JMS listener mechanism per D5 (ActiveMQ Artemis)
- Depends on: Step 3
- Verify: `grep -E "@MessageDriven|import javax" src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` returns nothing, and `grep "@JMSListener" src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` finds the annotation

### Step 22: Delete InventoryNotificationMDB.java
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: DELETE
- What to do: Delete this file — dead code per D9 (no @MessageDriven annotation, WebLogic-specific initialization)
- Why: Unused dead code identified in interrogation
- Depends on: none
- Verify: File no longer exists

### Step 23: COMPLEX — Replace StartupListener.java with Quarkus lifecycle observers
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE: Extends weblogic.application.ApplicationLifecycleListener with postStart/preStop methods
    - AFTER: Uses Quarkus lifecycle events @Observes StartupEvent and ShutdownEvent
    - Specific changes:
        1. Remove `import weblogic.application.ApplicationLifecycleEvent`
        2. Remove `import weblogic.application.ApplicationLifecycleListener`
        3. Add `import io.quarkus.runtime.StartupEvent`
        4. Add `import io.quarkus.runtime.ShutdownEvent`
        5. Add `import jakarta.enterprise.event.Observes`
        6. Add `import jakarta.enterprise.context.ApplicationScoped`
        7. Replace class declaration from `extends ApplicationLifecycleListener` to `@ApplicationScoped` class
        8. Replace `public void postStart(ApplicationLifecycleEvent evt)` with `void onStart(@Observes StartupEvent evt)`
        9. Replace `public void preStop(ApplicationLifecycleEvent evt)` with `void onStop(@Observes ShutdownEvent evt)`
        10. Replace javax.inject.* with jakarta.inject.*
- Why: Quarkus uses CDI observers for lifecycle events instead of WebLogic listeners per D9
- Depends on: Step 2
- Verify: `grep -E "weblogic|import javax" src/main/java/com/redhat/coolstore/utils/StartupListener.java` returns nothing, and `grep "@Observes" src/main/java/com/redhat/coolstore/utils/StartupListener.java` finds two occurrences

### Step 24: Update Producers.java imports
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do: Replace all javax.* imports with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/utils/Producers.java` returns nothing

### Step 25: Update Transformers.java imports
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace all javax.* imports (if any) with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/utils/Transformers.java` returns nothing

### Step 26: Update DataBaseMigrationStartup.java to Quarkus
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - Replace all javax.* imports with jakarta.*
    - If using @Singleton, replace with @ApplicationScoped
    - If using @Startup, replace with @Observes StartupEvent pattern
- Why: Quarkus 3 uses Jakarta EE namespace and different startup mechanisms
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java` returns nothing

### Step 27: Update RestApplication.java imports
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do: Replace all javax.ws.rs.* imports with jakarta.ws.rs.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 2
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/rest/RestApplication.java` returns nothing

### Step 28: COMPLEX — Update CartEndpoint.java to Quarkus
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace all javax.enterprise.* with jakarta.enterprise.*
    - Replace all javax.inject.* with jakarta.inject.*
    - Replace all javax.ws.rs.* with jakarta.ws.rs.*
    - Note: @SessionScoped may need review — Quarkus REST endpoints typically use @RequestScoped or @ApplicationScoped, but if session state is needed, consider using a session-scoped CDI bean with the endpoint as @RequestScoped
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/rest/CartEndpoint.java` returns nothing

### Step 29: Update OrderEndpoint.java imports
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace all javax.* imports with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java` returns nothing

### Step 30: Update ProductEndpoint.java imports
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace all javax.* imports with jakarta.*
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep -n "import javax" src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java` returns nothing

### Step 31: Delete index.jsp
- Phase: View Layer Cleanup
- File: src/main/webapp/index.jsp
- Action: DELETE
- What to do: Delete this file — going API-only per D6
- Why: View layer removed from scope, UI will be deployed separately
- Depends on: Step 28, Step 29, Step 30
- Verify: File no longer exists

### Step 32: Delete health.jsp
- Phase: View Layer Cleanup
- File: src/main/webapp/health.jsp
- Action: DELETE
- What to do: Delete this file — Quarkus provides health endpoints via quarkus-smallrye-health extension
- Why: Quarkus health extension replaces JSP health page
- Depends on: Step 28, Step 29, Step 30
- Verify: File no longer exists

### Step 33: Delete keycloak.json
- Phase: View Layer Cleanup
- File: src/main/webapp/keycloak.json
- Action: DELETE
- What to do: Delete this file — configuration moved to application.properties per Step 3
- Why: Quarkus OIDC configuration in application.properties replaces keycloak.json per D7
- Depends on: Step 3
- Verify: File no longer exists

### Step 34: Delete web.xml
- Phase: View Layer Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file — Quarkus does not use deployment descriptors
- Why: Quarkus configures via annotations and application.properties
- Depends on: Step 3
- Verify: File no longer exists

### Step 35: Delete persistence.xml
- Phase: View Layer Cleanup
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file — configuration moved to application.properties per Step 3
- Why: Quarkus configures JPA via application.properties
- Depends on: Step 3
- Verify: File no longer exists

### Step 36: Delete weblogic ApplicationLifecycleEvent.java
- Phase: WebLogic Vendored Classes
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file — no longer needed after StartupListener migration in Step 23
- Why: WebLogic classes replaced with Quarkus lifecycle events
- Depends on: Step 23
- Verify: File no longer exists

### Step 37: Delete weblogic ApplicationLifecycleListener.java
- Phase: WebLogic Vendored Classes
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file — no longer needed after StartupListener migration in Step 23
- Why: WebLogic classes replaced with Quarkus lifecycle events
- Depends on: Step 23
- Verify: File no longer exists

### Step 38: Delete beans.xml
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file — Quarkus auto-discovers CDI beans, beans.xml optional and typically not needed
- Why: Quarkus enables CDI by default without requiring beans.xml
- Depends on: Step 3
- Verify: File no longer exists

### Step 39: Update application.properties with H2 dev profile
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do: Add %dev profile configuration for H2 database to enable easy local development, keeping PostgreSQL as default per D3
- Why: Developer experience - H2 for local dev/test, PostgreSQL for production
- Depends on: Step 3
- Verify: `grep "%dev.quarkus.datasource.db-kind=h2" src/main/resources/application.properties` finds the configuration

## Verification

- Build: `mvn clean package`
- Test: No tests present in the project (maven.test.skip=true in original pom.xml)
- Blackbox: 
  1. Start Quarkus in dev mode: `mvn quarkus:dev`
  2. Verify health endpoint responds: `curl http://localhost:8080/q/health`
  3. Verify REST endpoints are accessible: `curl http://localhost:8080/services/products` (should return product list)
  4. Verify cart operations: Use REST client to POST to `/services/cart/{cartId}/{itemId}/{quantity}` and verify response
  5. Configure PostgreSQL database (as per README but for Quarkus configuration in application.properties)
  6. Configure Keycloak realm and verify OIDC authentication works with REST endpoints
  7. Configure ActiveMQ Artemis broker and verify checkout process triggers JMS message to topic/orders
  8. Verify OrderServiceMDB processes messages and saves orders to database
  9. Check audit logs are created in ./device-inventory-audit-logs directory

## Notes

**Assumptions to validate:**
- **D8 (Remote EJB interfaces)**: Assumed no external callers of ShippingServiceRemote. If external callers exist, ShippingService will need to be exposed as a REST endpoint instead. Monitor for any connectivity errors after removing the remote interface.
- **D9 (audit-logging-library)**: Assumed the audit-logging-library-1.0.0.jar can function outside WebLogic. If it has WebLogic-specific dependencies, OrderService initialization may fail. Test the @PostConstruct init() method carefully.
- **D9 (InventoryNotificationMDB)**: Assumed this class is dead code. Verify no external configuration or deployment descriptor references this class.

**Session scope consideration (Step 28)**: CartEndpoint uses @SessionScoped which works differently in Quarkus. The endpoint should be tested carefully to ensure shopping cart state is maintained correctly across requests. Consider refactoring to use a separate @SessionScoped CDI bean for cart state management if issues arise.

**JMS topic configuration**: The application.properties for Artemis must include topic creation configuration matching "topic/orders" from the original setup. Ensure the broker is configured to create the topic automatically or pre-create it.

**Angular.js SPA**: The Angular.js application in src/main/webapp/app/ and bower_components/ remains in the repository but is out of scope per D6. Document that this needs separate deployment (e.g., via nginx or other web server) and will need to call the Quarkus REST API endpoints.

**Health endpoint**: The original health.jsp is replaced by Quarkus SmallRye Health at `/q/health` endpoint. Update any monitoring or load balancer configuration to use the new endpoint.

**Build artifact change**: The build output changes from ROOT.war to a Quarkus runner JAR. Deployment process changes from WAR deployment to running `java -jar target/quarkus-app/quarkus-run.jar` or using Quarkus native compilation.

**Migration order is critical**: Steps must be executed in order, particularly the import replacements (javax → jakarta) which affect all layers. The build configuration steps (1-3) must complete before attempting to modify source files.
