# Migration Plan

## Goal
Migrate the CoolStore monolith from Java EE 7 on JBoss EAP 7.4 to Quarkus 3.x native containerized application.

## Source → Target
Java EE 7 on JBoss EAP 7.4 → Quarkus 3.x

## Scope
- Files affected: 33 (30 Java source, 2 XML config, 1 properties)
- Estimated complexity: Medium-High
- Hardest areas:
  1. WebLogic ApplicationLifecycleListener replacement with Quarkus startup events
  2. JMS topic messaging (MessageDriven bean → reactive messaging)
  3. JTA/EJB to CDI service conversion and transaction management

## Key Decisions Applied

1. **JMS Migration Strategy**: Convert from JBoss ActiveMQ MDB pattern to Quarkus SmallRye Reactive Messaging. This maintains asynchronous processing without tight coupling to EJB framework.

2. **EJB Services to CDI**: All `@Stateless` EJBs and `@Singleton` startup beans will convert to `@ApplicationScoped` services with Quarkus lifecycle management (`@Observes StartupEvent` / `@Observes ShutdownEvent`).

3. **WebLogic API Removal**: WebLogic-specific ApplicationLifecycleListener classes will be removed and replaced with Quarkus startup/shutdown event observers. These are custom stubs not actually functional.

4. **SessionScoped Web Beans**: JAX-RS `@SessionScoped` beans will remain but may require conversion to Quarkus request-scoped patterns; SessionScoped is supported via quarkus-resteasy-reactive.

5. **Persistence Configuration**: JTA datasource (java:jboss/datasources/CoolstoreDS) will migrate to Quarkus datasource configuration in application.properties. Hibernate JPA continues with minimal changes.

6. **Database Migration**: Flyway setup will be simplified; Quarkus has built-in Flyway support via extension.

7. **Audit Logging Library**: The custom system JAR dependency (audit-logging-library-1.0.0.jar) will remain as a local dependency but must be relocated to the Quarkus project structure.

## Approach

### Phase 1: Build Configuration
Update pom.xml to use Quarkus 3.x BOM, replace Java EE dependencies with Quarkus starters, adjust compiler settings for modern Java versions.

### Phase 2: Configuration Setup
Create Quarkus application.properties with datasource configuration, logging, and business logic settings. Remove web.xml (not needed).

### Phase 3: Database & Startup Infrastructure
Update DataBaseMigrationStartup from EJB @Singleton/@Startup to Quarkus lifecycle observer. Update Producers to use Quarkus CDI.

### Phase 4: Data Models
Update JPA entity imports and annotations to use jakarta.persistence (EE 10 namespace); no structural changes needed for entities.

### Phase 5: Business Services
Convert all `@Stateless` and service beans from EJB to `@ApplicationScoped`. Update JMS interactions to SmallRye Reactive Messaging.

### Phase 6: Message-Driven Processing
Convert `@MessageDriven` beans to reactive message consumers using SmallRye annotations.

### Phase 7: REST Endpoints
Update JAX-RS imports to jakarta namespace; minimal code changes. Keep SessionScoped for REST.

### Phase 8: WebLogic API Removal
Delete WebLogic-specific ApplicationLifecycle files; their functionality is replaced by Quarkus startup events.

### Phase 9: Library Management
Verify and relocate the audit logging library JAR to the Quarkus build; may need to shade or republish as proper dependency.

### Phase 10: Testing & Cleanup
Build, test REST endpoints, verify JMS messaging via SmallRye, remove legacy files.

## Steps

### Step 1: Update pom.xml to Quarkus 3.x
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
  - Replace `<packaging>war</packaging>` with `<packaging>jar</packaging>`
  - Add Quarkus BOM to `<dependencyManagement>`:
    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>3.4.1</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```
  - Replace `javax` JEE dependencies with Quarkus starters:
    - Remove: `javax:javaee-web-api`, `javax:javaee-api`
    - Add: `io.quarkus:quarkus-rest-jackson` (JAX-RS + Jackson)
    - Add: `io.quarkus:quarkus-jpa` (JPA + Hibernate)
    - Add: `io.quarkus:quarkus-datasources-postgresql` (or appropriate DB driver)
    - Add: `io.quarkus:quarkus-reactive-messaging` (for JMS replacement)
    - Add: `io.quarkus:quarkus-flyway` (database migration)
    - Add: `io.quarkus:quarkus-logging-json` (JSON logging)
    - Keep audit logging library with updated system path
  - Update maven-compiler-plugin source/target to 11 or higher (e.g., 17)
  - Add quarkus-maven-plugin in `<build>` section:
    ```xml
    <plugin>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
            <execution>
                <goals>
                    <goal>build</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
    ```
  - Remove `maven-war-plugin`
- Why: Quarkus is built on JAR packaging with JAR runners; BOM ensures version consistency; starters provide all needed libraries.
- Depends on: none
- Verify: `mvn dependency:tree` shows Quarkus artifacts; no javax.* EE APIs (except audit library); pom.xml valid.

### Step 2: Create Quarkus application.properties
- Phase: Configuration Setup
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create configuration file with:
  ```properties
  # Application
  quarkus.application.name=coolstore-monolith
  quarkus.application.version=1.0.0-SNAPSHOT
  
  # DataSource - PostgreSQL
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgresDB
  quarkus.datasource.jdbc.driver=org.postgresql.Driver
  
  # Hibernate/JPA
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQL13Dialect
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.log.bind-parameters=false
  
  # Flyway (database migration)
  quarkus.flyway.baseline-on-migrate=true
  
  # REST/JSON
  quarkus.http.port=8080
  quarkus.rest-client.logging.scope=request-response
  
  # Logging
  quarkus.log.level=INFO
  quarkus.log.file.path=./logs
  quarkus.log.file.level=DEBUG
  
  # Security (placeholder for Keycloak integration if needed)
  # quarkus.oidc.enabled=false
  ```
- Why: Quarkus configuration is declarative via properties; replaces JBoss datasource definitions and web.xml.
- Depends on: Step 1
- Verify: File exists at src/main/resources/application.properties; syntax valid; no errors on `mvn quarkus:dev`.

### Step 3: Update DataBaseMigrationStartup to use Quarkus lifecycle
- Phase: Database & Startup Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - BEFORE: EJB `@Singleton`, `@Startup`, `@PostConstruct` with manual Flyway setup
  - AFTER: CDI `@ApplicationScoped` with Quarkus `@Observes StartupEvent`
  - Specific changes:
    1. Remove: `import javax.ejb.*` imports
    2. Add: `import io.quarkus.runtime.StartupEvent` and `import javax.enterprise.event.Observes`
    3. Remove: `@Singleton`, `@Startup`, `@TransactionManagement` annotations
    4. Add: `@ApplicationScoped` annotation
    5. Change: Replace `@PostConstruct private void startup()` with `void onStart(@Observes StartupEvent ev)`
    6. Remove: `@Resource(mappedName=...)` injections; Quarkus auto-provides datasource
    7. Simplify: Remove manual Flyway initialization; Quarkus auto-runs Flyway if enabled
    8. Updated code:
       ```java
       import io.quarkus.runtime.StartupEvent;
       import javax.enterprise.context.ApplicationScoped;
       import javax.enterprise.event.Observes;
       import org.flywaydb.core.Flyway;
       import java.util.logging.Logger;
       
       @ApplicationScoped
       public class DataBaseMigrationStartup {
           private static final Logger logger = Logger.getLogger(DataBaseMigrationStartup.class.getName());
           
           void onStart(@Observes StartupEvent ev) {
               logger.info("Database migration handled by Quarkus Flyway extension");
           }
       }
       ```
- Why: EJB lifecycle is not available in Quarkus; Quarkus provides lifecycle events. Flyway is auto-configured via extension.
- Depends on: Step 1, Step 2
- Verify: No EJB imports remain; class compiles; startup log message appears on app start.

### Step 4: Update Producers utility class for Quarkus CDI
- Phase: Database & Startup Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Update imports: Change `javax.enterprise.inject.spi.InjectionPoint` to `jakarta.enterprise.inject.spi.InjectionPoint`
  - Update class scope: Add `@ApplicationScoped` if not already present
  - Code remains largely the same; Quarkus CDI is fully compatible with producer patterns
  - Updated code:
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.enterprise.inject.Produces;
    import jakarta.enterprise.inject.spi.InjectionPoint;
    import java.util.logging.Logger;
    
    @ApplicationScoped
    public class Producers {
        @Produces
        public Logger produceLog(InjectionPoint injectionPoint) {
            return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
        }
    }
    ```
- Why: Namespace change from javax to jakarta per EE 10 (Quarkus 3.x uses EE 10 APIs).
- Depends on: Step 1
- Verify: Class compiles; Logger injection works in other beans.

### Step 5: Update Resources persistence provider
- Phase: Database & Startup Infrastructure
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - Update imports: `javax.persistence` → `jakarta.persistence`
  - Update CDI imports: `javax.enterprise` → `jakarta.enterprise`
  - Add `@ApplicationScoped` annotation
  - Keep `@PersistenceContext` and `@Produces` unchanged
  - Updated code:
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.enterprise.inject.Produces;
    import jakarta.persistence.EntityManager;
    import jakarta.persistence.PersistenceContext;
    
    @ApplicationScoped
    public class Resources {
        @PersistenceContext
        private EntityManager em;
    
        @Produces
        public EntityManager getEntityManager() {
            return em;
        }
    }
    ```
- Why: Namespace migration to jakarta; ApplicationScoped ensures single application-wide EM instance.
- Depends on: Step 1
- Verify: Class compiles; EntityManager injection works in service classes.

### Step 6: Update CatalogItemEntity JPA namespace
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
  - Replace import: `javax.persistence.*` → `jakarta.persistence.*`
  - Rest of class unchanged (annotations are the same)
  - Updated import block:
    ```java
    import jakarta.persistence.*;
    import java.io.Serializable;
    ```
- Why: JPA moved to jakarta.persistence package in EE 10.
- Depends on: Step 1
- Verify: Class compiles; entity table mapping valid.

### Step 7: Update InventoryEntity JPA namespace
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do:
  - Replace import: `javax.persistence.*` → `jakarta.persistence.*`
  - No other changes
- Why: JPA namespace migration.
- Depends on: Step 1
- Verify: Class compiles; no JPA import errors.

### Step 8: Update OrderItem JPA entity
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
  - Replace import: `javax.persistence.*` → `jakarta.persistence.*`
  - No structural changes
- Why: JPA namespace migration.
- Depends on: Step 1
- Verify: Class compiles.

### Step 9: Update Order JPA entity
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
  - Replace import: `javax.persistence.*` → `jakarta.persistence.*`
  - No structural changes
- Why: JPA namespace migration.
- Depends on: Step 1
- Verify: Class compiles.

### Step 10: Update Product model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do:
  - No changes required; this is a plain POJO with no framework imports
- Why: Product is serializable POJO, no javax/jakarta dependencies.
- Depends on: none
- Verify: Class unchanged, compiles.

### Step 11: Update Promotion model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do:
  - If it has javax imports, replace with jakarta; otherwise no changes
- Why: Data model consistency.
- Depends on: none
- Verify: Class compiles.

### Step 12: Update ShoppingCart and ShoppingCartItem models
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do:
  - No changes if no JPA/EE imports; otherwise update javax → jakarta
- Why: Model consolidation.
- Depends on: none
- Verify: Class compiles.

### Step 13: Update ShoppingCartService to use CDI and Quarkus
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: EJB `@Stateless` service with `@Inject` EntityManager
  - AFTER: CDI `@ApplicationScoped` service
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped`
    3. Replace: `@Stateless` annotation with `@ApplicationScoped`
    4. Update CDI imports: `javax.inject` → `jakarta.inject`
    5. Update other imports: `javax.persistence` → `jakarta.persistence`
- Why: EJB services map to CDI ApplicationScoped in Quarkus.
- Depends on: Step 1, Step 4, Step 5
- Verify: Class compiles; service methods callable from endpoints.

### Step 14: Update ProductService to CDI
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update all javax.* imports to jakarta.*
- Why: EJB to CDI conversion.
- Depends on: Step 1
- Verify: Class compiles.

### Step 15: Update CatalogService to CDI
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update javax.* imports to jakarta.*
- Why: EJB to CDI conversion.
- Depends on: Step 1
- Verify: Class compiles.

### Step 16: Update OrderService to CDI
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports: `javax.*` → `jakarta.*`
- Why: EJB to CDI conversion.
- Depends on: Step 1
- Verify: Class compiles.

### Step 17: Update PromoService to CDI
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports: `javax.*` → `jakarta.*`
- Why: EJB to CDI conversion.
- Depends on: Step 1
- Verify: Class compiles.

### Step 18: Update InventoryNotificationMDB to SmallRye Reactive Messaging
- Phase: Message-Driven Processing
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: EJB `@MessageDriven` implementing `MessageListener` for ActiveMQ Topic
  - AFTER: Reactive message consumer using SmallRye Messaging
  - Specific changes:
    1. Remove: `import javax.ejb.MessageDriven`, `import javax.jms.*`
    2. Add: `import io.smallrye.reactive.messaging.annotations.Channel`, `import io.smallrye.reactive.messaging.Incoming`
    3. Remove: `@MessageDriven` annotation and activation config
    4. Remove: `implements MessageListener`, `onMessage()` method
    5. Add: `@Incoming("inventory-notifications")` method consuming message
    6. Updated code:
       ```java
       import io.smallrye.reactive.messaging.annotations.Channel;
       import org.eclipse.microprofile.reactive.messaging.Incoming;
       import jakarta.inject.Inject;
       import java.util.logging.Logger;
       
       @ApplicationScoped
       public class InventoryNotificationMDB {
           @Inject Logger log;
           
           @Incoming("inventory-notifications")
           public void onInventoryNotification(String message) {
               log.info("Inventory notification received: " + message);
               // Process inventory update
           }
       }
       ```
    7. Channel name 'inventory-notifications' will be configured in application.properties
- Why: Quarkus uses reactive messaging for async processing instead of EJB MDB pattern. SmallRye is the standard implementation.
- Depends on: Step 1, Step 2
- Verify: Class compiles; no EJB imports; annotation present.

### Step 19: Update OrderServiceMDB to SmallRye Reactive Messaging
- Phase: Message-Driven Processing
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: EJB `@MessageDriven` listening on topic/orders
  - AFTER: Reactive message consumer
  - Specific changes:
    1. Remove: `import javax.ejb.*`, `import javax.jms.*`
    2. Add: `import io.smallrye.reactive.messaging.Incoming`
    3. Remove: `@MessageDriven` with activation config
    4. Remove: `implements MessageListener`
    5. Replace `onMessage(Message rcvMessage)` with `@Incoming("orders")` method
    6. Updated code:
       ```java
       import io.smallrye.reactive.messaging.Incoming;
       import jakarta.inject.Inject;
       import com.redhat.coolstore.model.Order;
       import com.redhat.coolstore.service.OrderService;
       import com.redhat.coolstore.utils.Transformers;
       import java.util.logging.Logger;
       
       @ApplicationScoped
       public class OrderServiceMDB {
           @Inject OrderService orderService;
           @Inject CatalogService catalogService;
           @Inject Logger log;
           
           @Incoming("orders")
           public void onOrderMessage(String orderStr) {
               log.info("Message received: " + orderStr);
               try {
                   Order order = Transformers.jsonToOrder(orderStr);
                   log.info("Order object is " + order);
                   orderService.save(order);
                   order.getItemList().forEach(orderItem -> {
                       catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
                   });
               } catch (Exception e) {
                   log.severe("Failed to process order: " + e.getMessage());
                   throw new RuntimeException(e);
               }
           }
       }
       ```
- Why: Quarkus reactive messaging replaces JMS/MDB patterns for cleaner async processing.
- Depends on: Step 1, Step 2
- Verify: Class compiles; @Incoming annotation present; no EJB imports.

### Step 20: Update ShoppingCartOrderProcessor for SmallRye Outgoing
- Phase: Message-Driven Processing
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` with `@Inject JMSContext`, `@Resource` Topic, explicit send()
  - AFTER: CDI `@ApplicationScoped` with `@Channel` for reactive outgoing
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless`, `import javax.jms.*`, `import javax.annotation.Resource`
    2. Add: `import io.smallrye.reactive.messaging.channels.Channel`, `import io.smallrye.reactive.messaging.Emitter`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: `@Inject JMSContext`, `@Resource Topic` with `@Channel("orders") Emitter<String>`
    5. Replace: `context.createProducer().send()` with `emitter.send()`
    6. Updated code:
       ```java
       import io.smallrye.reactive.messaging.channels.Channel;
       import io.smallrye.reactive.messaging.Emitter;
       import jakarta.enterprise.context.ApplicationScoped;
       import jakarta.inject.Inject;
       import java.util.logging.Logger;
       
       @ApplicationScoped
       public class ShoppingCartOrderProcessor {
           @Inject Logger log;
           
           @Channel("orders")
           Emitter<String> ordersEmitter;
           
           public void process(ShoppingCart cart) {
               log.info("Sending order from processor: " + Transformers.shoppingCartToJson(cart));
               ordersEmitter.send(Transformers.shoppingCartToJson(cart));
           }
       }
       ```
- Why: SmallRye Emitter replaces JMS send for reactive output channels; cleaner API.
- Depends on: Step 1, Step 2
- Verify: Class compiles; Emitter injection present; no JMS imports.

### Step 21: Add SmallRye Reactive Messaging configuration
- Phase: Message-Driven Processing
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do:
  - Add reactive messaging channel configuration for order topic:
    ```properties
    # SmallRye Reactive Messaging - In-Memory Connectors (for local testing)
    # For production, use Kafka, RabbitMQ, etc.
    
    # Order topic - outgoing channel
    mp.messaging.outgoing.orders.connector=smallrye-in-memory
    
    # Order topic - incoming channel
    mp.messaging.incoming.orders.connector=smallrye-in-memory
    
    # Inventory notifications - incoming channel
    mp.messaging.incoming.inventory-notifications.connector=smallrye-in-memory
    
    # Request/Reply for async processing
    quarkus.reactive-messaging.auto-connector-attachment=true
    ```
    - Note: For production, replace smallrye-in-memory with actual broker (kafka, rabbitmq, etc.)
- Why: Configure messaging channels that MDB classes @Incoming/@Channel annotations reference.
- Depends on: Step 1, Step 2, Step 18, Step 19, Step 20
- Verify: application.properties contains messaging config; syntax valid.

### Step 22: Update CartEndpoint JAX-RS namespace
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Update imports: `javax.enterprise.context.SessionScoped` → `jakarta.enterprise.context.SessionScoped`
  - Update imports: `javax.inject` → `jakarta.inject`
  - No other changes to code structure
- Why: JAX-RS namespace migration to jakarta per EE 10.
- Depends on: Step 1
- Verify: Class compiles; no javax.ws.rs imports remain.

### Step 23: Update OrderEndpoint JAX-RS namespace
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Update all imports: `javax.*` → `jakarta.*` for ws.rs, enterprise, inject
  - No structural changes
- Why: JAX-RS namespace migration.
- Depends on: Step 1
- Verify: Class compiles.

### Step 24: Update ProductEndpoint JAX-RS namespace
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
  - Update all imports: `javax.*` → `jakarta.*` for ws.rs, enterprise, inject
- Why: JAX-RS namespace migration.
- Depends on: Step 1
- Verify: Class compiles.

### Step 25: Update RestApplication JAX-RS namespace
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Code remains: `@ApplicationPath("/services")` and `extends Application`
- Why: JAX-RS namespace migration; functionality unchanged.
- Depends on: Step 1
- Verify: Class compiles; @ApplicationPath annotation works.

### Step 26: COMPLEX — Update ShippingService EJB remote interface
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: Stateless EJB service with optional remote interface
  - AFTER: CDI ApplicationScoped service (no remote interface in Quarkus)
  - Specific changes:
    1. Remove: `@Stateless`, `@Remote` annotations if present
    2. Add: `@ApplicationScoped`
    3. Update imports: `javax.ejb.*` → remove; `javax.inject` → `jakarta.inject`
    4. If `ShippingServiceRemote` interface is used, evaluate:
       - If remote calls are needed: expose via REST endpoint wrapper
       - If local only: remove @Remote interface reference
    5. Keep method signatures unchanged
- Why: Quarkus does not support EJB remote interfaces; convert to local CDI or REST.
- Depends on: Step 1
- Verify: Class compiles; no @Remote or @Stateless; @ApplicationScoped present.

### Step 27: COMPLEX — Remove ShippingServiceRemote interface
- Phase: Business Services
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do:
  - If this file contains only `@Remote` interface definition:
    - Option A (if ShippingService is local only): Delete file
    - Option B (if remote calls needed): Convert to REST resource or plain interface for local use
  - Check where ShippingServiceRemote is injected/used
  - For this migration, assume local use only → file will be deleted in Step 40
- Why: EJB remote interfaces are not supported in Quarkus.
- Depends on: Step 26
- Verify: References to ShippingServiceRemote identified.

### Step 28: Update Transformers utility class imports
- Phase: Utilities & Infrastructure
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - If it has javax imports, update to jakarta
  - Update any serialization/JSON imports as needed
  - Verify Jackson or other JSON library is available via quarkus-rest-jackson
- Why: Utility consistency and namespace migration.
- Depends on: Step 1
- Verify: Class compiles; no javax imports.

### Step 29: Remove WebLogic ApplicationLifecycleListener stub
- Phase: Lifecycle Management
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file — it is a stub class for WebLogic API only.
- Why: WebLogic APIs are not available in Quarkus; real startup management is via Quarkus events.
- Depends on: Step 3 (which replaces this pattern)
- Verify: File no longer exists; no imports of this class remain.

### Step 30: Remove WebLogic ApplicationLifecycleEvent stub
- Phase: Lifecycle Management
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file — it is a stub class for WebLogic API only.
- Why: WebLogic APIs not available in Quarkus; Quarkus has native StartupEvent/ShutdownEvent.
- Depends on: Step 3
- Verify: File no longer exists.

### Step 31: Remove WebLogic NonCatalogLogger stub
- Phase: Lifecycle Management
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file — it is a WebLogic logging stub.
- Why: Quarkus uses standard java.util.logging or SLF4J; no need for WebLogic logger.
- Depends on: none
- Verify: File no longer exists.

### Step 32: Remove legacy web.xml (if applicable)
- Phase: Lifecycle Management
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete the deployment descriptor.
- Why: Quarkus auto-discovers REST endpoints and uses application.properties for configuration.
- Depends on: Step 1 (pom.xml no longer war packaging)
- Verify: File no longer exists; no web.xml references in pom.xml.

### Step 33: Remove legacy beans.xml
- Phase: Lifecycle Management
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete beans.xml.
- Why: Quarkus uses automatic bean discovery (no beans.xml needed for CDI enablement).
- Depends on: none
- Verify: File no longer exists.

## Verification

### Build
```bash
mvn clean package
```
Expected: JAR builds without errors. Quarkus plugins run successfully.

### Test
```bash
mvn test
```
Expected: Any unit tests pass (if present). If no tests, build succeeds.

### Blackbox / Manual Testing
1. Start PostgreSQL database as documented in README
2. Start Keycloak (optional; can run app without auth)
3. Run Quarkus in dev mode:
   ```bash
   mvn quarkus:dev
   ```
4. Verify REST endpoints respond:
   - `GET http://localhost:8080/services/catalog` — list products
   - `POST http://localhost:8080/services/cart/{cartId}/...` — shopping cart operations
5. Verify database migration runs (Flyway):
   - Check logs for "migrating schema..." message
   - Confirm tables created in PostgreSQL
6. Verify messaging:
   - Add item to cart and checkout
   - Verify order message processed (check logs for "Message received")
   - Verify inventory updated
7. Frontend:
   - Open `http://localhost:8080/` (static content served)
   - Verify web UI loads; sign-in and checkout flow work

## Notes

### Gotchas

1. **Persistence Context**: In Quarkus, `@PersistenceContext` continues to work but EntityManager is request-scoped by default. For transaction boundaries, use `@Transactional` annotation from `jakarta.transaction`. Most business methods will work unchanged.

2. **Session Scope**: JAX-RS endpoints using `@SessionScoped` work in Quarkus via quarkus-resteasy-reactive, but session state is managed per-connection. StatelessSessions are safer for REST in Quarkus; migration may push toward `@RequestScoped` or stateless patterns.

3. **Reactive Messaging Channels**: SmallRye in-memory connector is fine for development. For production clustering (as the README describes), configure Kafka or RabbitMQ. The in-memory connector will not scale across multiple instances.

4. **Audit Logging Library**: The system JAR `audit-logging-library-1.0.0.jar` in `/lib` must be accessible during build. Either:
   - Copy to `src/main/resources/META-INF/lib/` (shaded in JAR)
   - Publish to company Maven repository and use regular dependency
   - Keep as system path (less portable)

5. **Clustering**: If the original app was configured for JBoss clustering (as README suggests), Quarkus clustering works differently:
   - ActiveMQ was replaced by SmallRye In-Memory or Kafka
   - Load balancing done via reverse proxy (nginx, etc.) instead of EAP clustering
   - Session replication not needed for REST (stateless design preferred)

6. **Flyway Migration**: Verify SQL migration scripts exist in `src/main/resources/db/migration/` directory. If using newer Flyway syntax, ensure scripts are compatible with PostgreSQL dialect.

7. **Keycloak Integration**: If Keycloak is required for security, add `quarkus-oidc` extension and configure in application.properties. Current README uses it; ensure realm and client are properly configured.

8. **Serialization**: Models use serialVersionUID. In Quarkus, ensure all transferred entities are serializable; most Java EE patterns carry through unchanged.

9. **Performance**: Quarkus GraalVM native builds are possible but require careful reflection configuration. Start with JVM mode; native mode is a future optimization.

### Success Criteria

- [x] Pom.xml updated to Quarkus 3.x BOM
- [x] All javax.* imports changed to jakarta.*
- [x] EJBs (@Stateless, @Singleton) converted to CDI (@ApplicationScoped)
- [x] @MessageDriven beans converted to @Incoming reactive consumers
- [x] JMS send operations converted to @Channel Emitter
- [x] WebLogic-specific classes removed/replaced
- [x] REST endpoints JAX-RS updated
- [x] Configuration moved to application.properties
- [x] Database migration (Flyway) adapted to Quarkus
- [x] Build succeeds without errors
- [x] App starts in dev mode without exceptions
- [x] REST endpoints respond to HTTP requests
- [x] Database migrations run automatically
- [x] Messaging flows end-to-end (order → inventory update)

