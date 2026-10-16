# Migration Plan

## Goal
Migrate a Java EE 7 monolith application from JBoss EAP 7.4 to Quarkus 3, transforming it from a WAR-packaged application to a cloud-native Quarkus application.

## Source → Target
Java EE 7 (JBoss EAP 7.4) → Quarkus 3

## Scope
- Files affected: 43
  - 29 Java source files
  - 5 configuration files (pom.xml, persistence.xml, beans.xml, web.xml, 2 SQL files)
  - 9 files to modify/transform
  - 3 files to delete (WebLogic stub classes, legacy descriptors)
  - 2 files to create (application.properties, import.sql)
- Estimated complexity: High
- Hardest areas:
  1. JMS Message-Driven Beans (OrderServiceMDB, InventoryNotificationMDB) - require significant restructuring to use Quarkus Messaging
  2. Remote EJB lookups (ShoppingCartService calling ShippingService) - need to be replaced with CDI
  3. WebLogic lifecycle listener (StartupListener) - requires replacement with Quarkus lifecycle events

## Key Decisions Applied
1. **JMS Migration Strategy**: Migrate from Java EE JMS Message-Driven Beans to Quarkus Reactive Messaging with SmallRye. Use `@Incoming` annotations instead of `@MessageDriven`. This aligns with Quarkus's reactive programming model.

2. **Remote EJB Replacement**: Replace remote EJB lookups with local CDI injection. The ShippingService will be called directly via `@Inject` rather than JNDI lookup. This is appropriate since the application is a monolith and all components run in the same JVM.

3. **Datasource Configuration**: Move from JNDI datasource (`java:jboss/datasources/CoolstoreDS`) to Quarkus datasource configuration in application.properties. Use the default datasource name with `quarkus.datasource.*` properties.

4. **Flyway Migration**: Keep Flyway for database migrations but migrate to Quarkus Flyway extension configuration rather than programmatic initialization.

5. **Audit Logging Library**: Upgrade from v1.0.0 to v2.0.0, which requires changing from builder pattern to Java 21 record instantiation, switching from FileSystemAuditLogger to StreamableAuditLogger with TCP, and replacing synchronous `logEvent()` with asynchronous `logEventAsync()`.

6. **Lifecycle Events**: Replace WebLogic ApplicationLifecycleListener with Quarkus lifecycle events (`@Observes StartupEvent`).

7. **Java Version**: Upgrade from Java 8 to Java 21 (required by audit-logging-library v2.0.0 and Quarkus 3).

## Approach
The migration follows a dependency-ordered, bottom-up approach across five phases:

**Phase 1 - Build Configuration**: Transform Maven POM from Java EE WAR packaging to Quarkus with BOM and extensions. Update Java version to 21 and upgrade audit-logging-library dependency to v2.0.0.

**Phase 2 - Configuration Files**: Create Quarkus application.properties with datasource, Hibernate, and Flyway configuration. Remove legacy Java EE deployment descriptors (persistence.xml, web.xml, beans.xml - CDI is enabled by default in Quarkus). Delete WebLogic stub classes.

**Phase 3 - Data Layer**: Update JPA entities (simple annotation changes), transform the EntityManager producer from `@PersistenceContext`/`@Produces` to `@Inject`, and convert Flyway initialization from programmatic EJB Singleton to Quarkus extension configuration.

**Phase 4 - Service Layer**: 
- Replace `@Stateless` with `@ApplicationScoped`
- Replace `@Stateful` with `@SessionScoped` 
- Remove `@Remote` and JNDI lookups
- Convert JMS Message-Driven Beans to Quarkus Reactive Messaging
- Replace WebLogic lifecycle listener with Quarkus lifecycle events
- Update audit logging to use v2.0.0 APIs (currently not present in code, but rules indicate it may be added)

**Phase 5 - REST Layer**: Update JAX-RS endpoints to remove Java EE-specific patterns and ensure compatibility with Quarkus RESTEasy. Update application class from `@ApplicationPath` to Quarkus configuration.

**Phase 6 - Verification**: Build, test, and run the migrated application to ensure all functionality works correctly.

## Steps

### Step 1: Transform Maven POM to Quarkus
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
  1. Change packaging from `war` to `jar`
  2. Add Quarkus BOM and platform imports:
     ```xml
     <properties>
         <quarkus.version>3.8.0</quarkus.version>
         <maven.compiler.source>21</maven.compiler.source>
         <maven.compiler.target>21</maven.compiler.target>
     </properties>
     <dependencyManagement>
         <dependencies>
             <dependency>
                 <groupId>io.quarkus</groupId>
                 <artifactId>quarkus-bom</artifactId>
                 <version>${quarkus.version}</version>
                 <type>pom</type>
                 <scope>import</scope>
             </dependency>
         </dependencies>
     </dependencyManagement>
     ```
  3. Replace Java EE dependencies with Quarkus extensions:
     - Remove: `javaee-web-api`, `javaee-api`, `jboss-jms-api_2.0_spec`, `jboss-rmi-api_1.0_spec`
     - Add: `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-resteasy-reactive-jackson`, `quarkus-messaging-jms`, `quarkus-artemis-jms`, `quarkus-flyway`, `quarkus-arc`
  4. Update Flyway dependency to Quarkus Flyway extension
  5. Update audit-logging-library from v1.0.0 to v2.0.0:
     ```xml
     <dependency>
         <groupId>com.enterprise</groupId>
         <artifactId>audit-logging-library</artifactId>
         <version>2.0.0</version>
         <scope>system</scope>
         <systemPath>${project.basedir}/lib/audit-logging-library-2.0.0.jar</systemPath>
     </dependency>
     ```
  6. Replace maven-war-plugin with quarkus-maven-plugin:
     ```xml
     <plugin>
         <groupId>io.quarkus</groupId>
         <artifactId>quarkus-maven-plugin</artifactId>
         <version>${quarkus.version}</version>
         <executions>
             <execution>
                 <goals>
                     <goal>build</goal>
                 </goals>
             </execution>
         </executions>
     </plugin>
     ```
- Why: Quarkus uses a different dependency model (BOM + extensions) and packaging model (JAR with embedded runtime). Java 21 is required for audit-logging-library v2.0.0.
- Depends on: none
- Verify: `mvn clean compile` succeeds with Quarkus dependencies resolved

### Step 2: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do:
  ```properties
  # Datasource configuration (replaces persistence.xml JNDI datasource)
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
  
  # Hibernate configuration
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.log.format-sql=true
  quarkus.hibernate-orm.jdbc.statement-batch-size=0
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  quarkus.flyway.baseline-on-migrate=true
  quarkus.flyway.locations=classpath:db/migration
  
  # JMS/Messaging configuration
  quarkus.artemis.url=tcp://localhost:61616
  quarkus.artemis.username=admin
  quarkus.artemis.password=admin
  
  # RESTEasy configuration
  quarkus.resteasy.path=/services
  
  # HTTP configuration
  quarkus.http.port=8080
  
  # Development mode settings
  %dev.quarkus.hibernate-orm.log.sql=true
  ```
- Why: Quarkus uses centralized application.properties for all configuration rather than multiple XML files
- Depends on: Step 1
- Verify: File exists with all required properties for datasource, Hibernate, Flyway, and messaging

### Step 3: Delete persistence.xml
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Remove this file completely - configuration moved to application.properties
- Why: Quarkus uses application.properties for persistence configuration, not persistence.xml
- Depends on: Step 2
- Verify: File no longer exists, grep shows no references

### Step 4: Delete web.xml
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Remove this file completely - Quarkus doesn't use deployment descriptors
- Why: Quarkus is not a traditional Java EE container and doesn't use web.xml
- Depends on: Step 2
- Verify: File no longer exists

### Step 5: Delete beans.xml
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Remove this file completely - CDI is enabled by default in Quarkus
- Why: Quarkus enables CDI automatically without requiring beans.xml
- Depends on: Step 2
- Verify: File no longer exists

### Step 6: Delete WebLogic ApplicationLifecycleEvent stub
- Phase: Configuration Files
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Remove this WebLogic stub class entirely
- Why: WebLogic-specific APIs are not needed in Quarkus
- Depends on: Step 2
- Verify: File no longer exists

### Step 7: Delete WebLogic ApplicationLifecycleListener stub
- Phase: Configuration Files
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Remove this WebLogic stub class entirely
- Why: WebLogic-specific APIs are not needed in Quarkus
- Depends on: Step 2
- Verify: File no longer exists

### Step 8: Delete WebLogic NonCatalogLogger stub
- Phase: Configuration Files
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Remove this WebLogic stub class entirely
- Why: WebLogic-specific APIs are not needed in Quarkus
- Depends on: Step 2
- Verify: File no longer exists

### Step 9: Update CatalogItemEntity
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.persistence.*` to `jakarta.persistence.*`
  - No other changes needed - JPA entity structure remains compatible
- Why: Quarkus 3 uses Jakarta EE 10, which moved from javax to jakarta namespace
- Depends on: Step 1
- Verify: No compilation errors, imports use jakarta.persistence

### Step 10: Update InventoryEntity
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.persistence.*` to `jakarta.persistence.*`
  - No other changes needed
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Imports use jakarta.persistence

### Step 11: Update Order
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.persistence.*` to `jakarta.persistence.*`
  - No other changes needed
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Imports use jakarta.persistence

### Step 12: Update OrderItem
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.persistence.*` to `jakarta.persistence.*`
  - No other changes needed
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Imports use jakarta.persistence

### Step 13: Update Product
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do:
  - No `javax.persistence` imports present - no changes needed
  - Verify it's a plain POJO
- Why: Not a JPA entity
- Depends on: Step 1
- Verify: File compiles without changes

### Step 14: Update Promotion
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do:
  - No `javax.persistence` imports present - no changes needed
  - Verify it's a plain POJO
- Why: Not a JPA entity
- Depends on: Step 1
- Verify: File compiles without changes

### Step 15: Update ShoppingCart
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do:
  - No `javax.persistence` imports present - no changes needed
  - Verify it's a plain POJO
- Why: Not a JPA entity
- Depends on: Step 1
- Verify: File compiles without changes

### Step 16: Update ShoppingCartItem
- Phase: Data Layer - Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do:
  - No `javax.persistence` imports present - no changes needed
  - Verify it's a plain POJO
- Why: Not a JPA entity
- Depends on: Step 1
- Verify: File compiles without changes

### Step 17: COMPLEX - Replace EntityManager producer
- Phase: Data Layer - Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - BEFORE:
    ```java
    @Dependent
    public class Resources {
        @PersistenceContext
        private EntityManager em;
        
        @Produces
        public EntityManager getEntityManager() {
            return em;
        }
    }
    ```
  - AFTER:
    ```java
    // DELETE THIS ENTIRE CLASS
    ```
  - Specific changes:
    1. Delete the entire Resources.java file - EntityManager is managed by Quarkus
    2. In all consuming classes (services), EntityManager is now injected directly with `@Inject`
- Why: Quarkus automatically manages EntityManager lifecycle and provides it for injection. Using `@Produces` on EntityManager is illegal in Quarkus. The custom rule persistence-to-quarkus-00010 requires replacing `@PersistenceContext` with `@Inject`, and rule persistence-to-quarkus-00011 prohibits `@Produces` on EntityManager.
- Depends on: Step 2, Steps 9-16
- Verify: Resources.java is deleted, all EntityManager injections in services use `@Inject jakarta.persistence.EntityManager`

### Step 18: Remove DataBaseMigrationStartup
- Phase: Data Layer - Database Migration
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: DELETE
- What to do: Remove this entire file - Flyway is now managed by Quarkus Flyway extension configured in application.properties
- Why: Quarkus Flyway extension handles database migration automatically at startup when `quarkus.flyway.migrate-at-start=true`
- Depends on: Step 2
- Verify: File deleted, Flyway configured in application.properties

### Step 19: Update Producers for Logger
- Phase: Service Layer - Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.enterprise.inject.*` to `jakarta.enterprise.inject.*`
  - Update import from `javax.enterprise.inject.spi.InjectionPoint` to `jakarta.enterprise.inject.spi.InjectionPoint`
  - Add `@ApplicationScoped` annotation to the class
  - Keep the Logger producer method as-is
- Why: Jakarta namespace migration and proper CDI bean scope in Quarkus
- Depends on: Step 1
- Verify: Imports use jakarta.enterprise, class has @ApplicationScoped

### Step 20: COMPLEX - Replace WebLogic lifecycle listener with Quarkus events
- Phase: Service Layer - Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
  - BEFORE:
    ```java
    import weblogic.application.ApplicationLifecycleEvent;
    import weblogic.application.ApplicationLifecycleListener;
    import javax.inject.Inject;
    import java.util.logging.Logger;
    
    public class StartupListener extends ApplicationLifecycleListener {
        @Inject Logger log;
        
        @Override
        public void postStart(ApplicationLifecycleEvent evt) {
            log.info("AppListener(postStart)");
        }
        
        @Override
        public void preStop(ApplicationLifecycleEvent evt) {
            log.info("AppListener(preStop)");
        }
    }
    ```
  - AFTER:
    ```java
    package com.redhat.coolstore.utils;
    
    import io.quarkus.runtime.StartupEvent;
    import io.quarkus.runtime.ShutdownEvent;
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.enterprise.event.Observes;
    import java.util.logging.Logger;
    
    @ApplicationScoped
    public class StartupListener {
        
        private static final Logger log = Logger.getLogger(StartupListener.class.getName());
        
        void onStart(@Observes StartupEvent event) {
            log.info("AppListener(postStart)");
        }
        
        void onStop(@Observes ShutdownEvent event) {
            log.info("AppListener(preStop)");
        }
    }
    ```
  - Specific changes:
    1. Remove: `weblogic.application.*` imports and extension of ApplicationLifecycleListener
    2. Add: `io.quarkus.runtime.StartupEvent`, `io.quarkus.runtime.ShutdownEvent`, `jakarta.enterprise.event.Observes`
    3. Add: `@ApplicationScoped` annotation to class
    4. Replace: `postStart` method with `onStart(@Observes StartupEvent event)`
    5. Replace: `preStop` method with `onStop(@Observes ShutdownEvent event)`
    6. Change Logger injection to static field initialization
- Why: WebLogic lifecycle APIs are not available in Quarkus. Quarkus uses CDI event observation for lifecycle hooks.
- Depends on: Steps 6, 7
- Verify: No weblogic imports, uses Quarkus lifecycle events, compiles successfully

### Step 21: Update Transformers
- Phase: Service Layer - Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - Update imports from `javax.json.*` to `jakarta.json.*`
  - No other changes needed to the transformation logic
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Imports use jakarta.json

### Step 22: Update CatalogService
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports: `javax.ejb.Stateless` → remove, add `jakarta.enterprise.context.ApplicationScoped`
  - Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
  - Update imports: `javax.persistence.*` → `jakarta.persistence.*`
  - EntityManager injection remains `@Inject EntityManager em;` (no longer injected from Resources producer)
- Why: Quarkus uses CDI `@ApplicationScoped` instead of EJB `@Stateless`. EntityManager is now provided directly by Quarkus.
- Depends on: Step 17
- Verify: Uses @ApplicationScoped, jakarta imports, EntityManager injection with @Inject

### Step 23: Update ProductService
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports: `javax.ejb.Stateless` → remove, add `jakarta.enterprise.context.ApplicationScoped`
  - Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
  - No EntityManager used - only service injection
- Why: Replace EJB with CDI
- Depends on: Step 1
- Verify: Uses @ApplicationScoped, jakarta imports

### Step 24: Update PromoService
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports for CDI and Jakarta namespaces
- Why: Replace EJB with CDI
- Depends on: Step 1
- Verify: Uses @ApplicationScoped, jakarta imports

### Step 25: Update OrderService
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports: Jakarta namespaces for CDI, inject, and persistence
  - Update EntityManager injection to `@Inject` (no longer from Resources producer)
- Why: Replace EJB with CDI, EntityManager now provided by Quarkus
- Depends on: Step 17
- Verify: Uses @ApplicationScoped, jakarta imports, EntityManager with @Inject

### Step 26: COMPLEX - Convert ShoppingCartService from Stateful to SessionScoped
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateful` with JNDI EJB lookup
  - AFTER: `@SessionScoped` with direct CDI injection
  - Specific changes:
    1. Replace `@Stateful` with `@SessionScoped`
    2. Update imports: Remove `javax.ejb.Stateful`, `javax.naming.*`
    3. Add imports: `jakarta.enterprise.context.SessionScoped`, `jakarta.inject.Inject`
    4. Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
    5. Remove the `lookupShippingServiceRemote()` method entirely
    6. Add field: `@Inject ShippingService shippingService;`
    7. Replace all calls to `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
    8. Replace all calls to `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
- Why: Quarkus doesn't support stateful EJBs or remote JNDI lookups. Session state is managed by CDI `@SessionScoped`. Direct injection replaces JNDI lookup.
- Depends on: Step 27
- Verify: Uses @SessionScoped, no JNDI lookups, ShippingService injected with @Inject

### Step 27: COMPLEX - Remove Remote EJB from ShippingService
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless @Remote public class ShippingService implements ShippingServiceRemote`
  - AFTER: `@ApplicationScoped public class ShippingService implements ShippingServiceRemote`
  - Specific changes:
    1. Remove `@Stateless` and `@Remote` annotations
    2. Add `@ApplicationScoped` annotation
    3. Keep the `implements ShippingServiceRemote` (interface still useful for typing)
    4. Update imports: remove `javax.ejb.*`, add `jakarta.enterprise.context.ApplicationScoped`
- Why: Remote EJB is not needed for a monolith. All services run in the same JVM and can use local CDI injection.
- Depends on: Step 1
- Verify: Uses @ApplicationScoped, no @Remote or @Stateless, compiles successfully

### Step 28: Update ShippingServiceRemote interface
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do:
  - No changes needed - keep as plain Java interface
  - Remove any EJB-related imports if present
- Why: Interface can remain for typing purposes, but no EJB annotations needed
- Depends on: Step 1
- Verify: Plain Java interface, no EJB imports

### Step 29: Update ShoppingCartOrderProcessor
- Phase: Service Layer - Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - Replace `@Stateless` with `@ApplicationScoped`
  - Update imports to Jakarta namespaces
  - Update JMS usage if present (likely uses JMS to send to topic)
- Why: Replace EJB with CDI
- Depends on: Step 1
- Verify: Uses @ApplicationScoped, jakarta imports

### Step 30: COMPLEX - Convert OrderServiceMDB to Quarkus Messaging
- Phase: Service Layer - Messaging
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Java EE Message-Driven Bean with `@MessageDriven`
    ```java
    @MessageDriven(name = "OrderServiceMDB", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "topic/orders"),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge")})
    public class OrderServiceMDB implements MessageListener {
        @Inject OrderService orderService;
        @Inject CatalogService catalogService;
        
        @Override
        public void onMessage(Message rcvMessage) { ... }
    }
    ```
  - AFTER: Quarkus Reactive Messaging with SmallRye
    ```java
    package com.redhat.coolstore.service;
    
    import org.eclipse.microprofile.reactive.messaging.Incoming;
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.inject.Inject;
    import com.redhat.coolstore.model.Order;
    import com.redhat.coolstore.utils.Transformers;
    
    @ApplicationScoped
    public class OrderServiceMDB {
        
        @Inject
        OrderService orderService;
        
        @Inject
        CatalogService catalogService;
        
        @Incoming("orders")
        public void onMessage(String orderStr) {
            System.out.println("\nMessage recd !");
            System.out.println("Received order: " + orderStr);
            Order order = Transformers.jsonToOrder(orderStr);
            System.out.println("Order object is " + order);
            orderService.save(order);
            order.getItemList().forEach(orderItem -> {
                catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
            });
        }
    }
    ```
  - Configuration in application.properties:
    ```properties
    mp.messaging.incoming.orders.connector=smallrye-jms
    mp.messaging.incoming.orders.destination=topic/orders
    mp.messaging.incoming.orders.destination-type=topic
    ```
  - Specific changes:
    1. Remove: `@MessageDriven`, `MessageListener` interface, JMS Message API usage
    2. Add: `@ApplicationScoped`, `@Incoming("orders")` annotation
    3. Replace: `onMessage(Message rcvMessage)` with `onMessage(String orderStr)` - SmallRye handles deserialization
    4. Remove: JMS exception handling and message type checking - simplified to direct String parameter
    5. Update: All imports to jakarta namespace
- Why: Quarkus uses Reactive Messaging (MicroProfile Messaging) instead of JMS MDBs. This provides better performance and simpler programming model.
- Depends on: Steps 22, 25
- Verify: No @MessageDriven, uses @Incoming, configured in application.properties, compiles successfully

### Step 31: COMPLEX - Convert InventoryNotificationMDB to Quarkus Messaging
- Phase: Service Layer - Messaging
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Manual JMS subscription with WebLogic JNDI
    ```java
    public class InventoryNotificationMDB implements MessageListener {
        private final static String JNDI_FACTORY = "weblogic.jndi.WLInitialContextFactory";
        private final static String JMS_FACTORY = "TCF";
        private final static String TOPIC = "topic/orders";
        
        @Override
        public void onMessage(Message rcvMessage) { ... }
        
        public void init() throws NamingException, JMSException { ... }
    }
    ```
  - AFTER: Quarkus Reactive Messaging
    ```java
    package com.redhat.coolstore.service;
    
    import org.eclipse.microprofile.reactive.messaging.Incoming;
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.inject.Inject;
    import com.redhat.coolstore.model.Order;
    import com.redhat.coolstore.utils.Transformers;
    
    @ApplicationScoped
    public class InventoryNotificationMDB {
        
        private static final int LOW_THRESHOLD = 50;
        
        @Inject
        private CatalogService catalogService;
        
        @Incoming("orders")
        public void onMessage(String orderStr) {
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
    }
    ```
  - Configuration uses same channel as OrderServiceMDB in application.properties (already added in Step 30)
  - Specific changes:
    1. Remove: All WebLogic JNDI code, `init()` and `close()` methods, manual JMS connection setup
    2. Add: `@ApplicationScoped`, `@Incoming("orders")` annotation
    3. Replace: `onMessage(Message rcvMessage)` with `onMessage(String orderStr)`
    4. Remove: JMS exception handling and imports
    5. Update: imports to jakarta namespace
- Why: Remove WebLogic-specific JNDI and JMS code. Quarkus Reactive Messaging provides declarative message consumption.
- Depends on: Step 22, Step 30
- Verify: No WebLogic/JNDI code, uses @Incoming, compiles successfully

### Step 32: Update RestApplication
- Phase: REST Layer - Application
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Keep the `@ApplicationPath("/services")` annotation OR remove it and use `quarkus.resteasy.path=/services` in application.properties (already set in Step 2)
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Simplest: Remove the class entirely since application.properties already defines the path
- Why: Quarkus RESTEasy can configure the path via application.properties, making the Application class optional
- Depends on: Step 2
- Verify: Either class uses jakarta.ws.rs or class is deleted with path in application.properties

### Step 33: Update CartEndpoint
- Phase: REST Layer - Endpoints
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - Replace `@SessionScoped` with proper scope management (keep @SessionScoped)
  - Update imports: `javax.enterprise.context.SessionScoped` → `jakarta.enterprise.context.SessionScoped`
  - Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - No logic changes needed
- Why: Jakarta namespace migration
- Depends on: Step 26
- Verify: Uses jakarta imports, compiles successfully

### Step 34: Update OrderEndpoint
- Phase: REST Layer - Endpoints
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Update imports for any other javax.* to jakarta.*
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Uses jakarta imports

### Step 35: Update ProductEndpoint
- Phase: REST Layer - Endpoints
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
  - Update imports: `javax.inject.Inject` → `jakarta.inject.Inject`
  - Update imports: `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Update imports for any other javax.* to jakarta.*
- Why: Jakarta namespace migration
- Depends on: Step 1
- Verify: Uses jakarta imports

## Verification

### Build
```bash
mvn clean package
```
Expected outcome: Successful build producing a Quarkus JAR file in `target/` directory (e.g., `target/quarkus-app/`)

### Test
```bash
mvn test
```
Note: Currently tests are skipped (`maven.test.skip=true`). If tests exist and are enabled, run the above command.

### Blackbox Testing

Based on the README.md instructions, verify the migrated application works:

1. **Start PostgreSQL database**:
   ```bash
   podman run --name myPostgresDb \
      -p 5432:5432 \
      -e POSTGRES_USER=postgresUser \
      -e POSTGRES_PASSWORD=postgresPW \
      -e POSTGRES_DB=postgresDB \
      -d postgres
   ```

2. **Configure and start Keycloak** (follow README instructions):
   - Start Keycloak on port 8081
   - Import realm-export.json
   - Create test user (e.g., user1)

3. **Configure JMS broker** (Artemis for Quarkus):
   ```bash
   # Download and start Apache Artemis or use embedded Artemis in dev mode
   # Quarkus will connect to Artemis instead of JBoss messaging
   ```

4. **Run the Quarkus application**:
   ```bash
   mvn quarkus:dev
   ```
   Or in production mode:
   ```bash
   java -jar target/quarkus-app/quarkus-run.jar
   ```

5. **Verify application startup**:
   - Application starts successfully on http://127.0.0.1:8080
   - Flyway migrations execute (check logs)
   - No errors in startup logs

6. **Test core business flows**:
   - Navigate to http://127.0.0.1:8080
   - Verify coolstore UI loads
   - Click "Sign in" and login with Keycloak user
   - Browse products
   - Add items to cart
   - Complete checkout process
   - Verify messages are processed (check logs for "Message recd !" and "Received order:")
   - Verify inventory updates (check database or logs)

7. **Verify REST endpoints**:
   ```bash
   # Get products
   curl http://127.0.0.1:8080/services/products
   
   # Get cart
   curl http://127.0.0.1:8080/services/cart/123
   ```

8. **Check health and metrics** (if Quarkus health extension added):
   ```bash
   curl http://127.0.0.1:8080/q/health
   ```

## Notes

### Critical Migration Points

1. **JMS/Messaging**: The migration from Java EE JMS MDBs to Quarkus Reactive Messaging is the most complex change. Both `OrderServiceMDB` and `InventoryNotificationMDB` subscribe to the same topic (`topic/orders`). Verify that both consumers receive messages correctly. May need to configure separate consumer groups or use competing consumers pattern if ordering is required.

2. **Session State**: The `ShoppingCartService` is `@Stateful` in Java EE, migrated to `@SessionScoped` in Quarkus. This assumes HTTP session state is sufficient. For cloud-native deployments, consider:
   - Using Quarkus session persistence with Redis
   - Converting to stateless with client-side state management
   - Using distributed caching (Infinispan)

3. **Remote EJB Elimination**: The `ShippingService` remote EJB lookup is replaced with local CDI injection. This is valid for a monolith but means the service cannot be called remotely after migration. If remote access is needed in the future, expose it as a REST endpoint instead.

4. **Flyway Migration**: Database migration scripts in `src/main/resources/db/migration/` should work as-is, but verify:
   - Scripts are SQL compatible with PostgreSQL
   - Version numbers are correct (V1_1, V1_2, etc.)
   - Baseline version matches existing database state if migrating a running system

5. **WebLogic Cleanup**: All WebLogic-specific code (ApplicationLifecycleListener, JNDI factory references) is removed. Verify no hidden dependencies on WebLogic APIs remain in the codebase.

6. **Audit Logging Library**: The custom rules indicate an audit logging library upgrade from v1.0.0 to v2.0.0. However, no usage of this library was found in the current codebase. If audit logging is added in the future:
   - Use `AuditEvent` record directly (not builder pattern)
   - Use `StreamableAuditLogger` with TCP (not FileSystemAuditLogger)
   - Use `logEventAsync()` (not synchronous `logEvent()`)

7. **Java Version**: Upgrade to Java 21 is required by the audit-logging-library v2.0.0. Ensure:
   - CI/CD pipelines use Java 21
   - Container base images use Java 21
   - IDE and developer environments use Java 21

8. **Transaction Management**: JPA transactions are managed by Quarkus automatically. The `@Transactional` annotation (from `jakarta.transaction`) can be added to methods requiring transaction boundaries if not already handled by default.

9. **Clustering**: The original application supported clustering with JBoss EAP HA mode. Quarkus clustering requires different configuration:
   - Session replication: Use Quarkus Infinispan for distributed caching
   - JMS topic subscribers: Configure consumer groups in Reactive Messaging
   - Load balancing: Use external load balancer (e.g., Kubernetes Service)

10. **Static Resources**: The `src/main/webapp/` directory contains frontend assets (bower_components, index.jsp, etc.). Quarkus serves static resources from `src/main/resources/META-INF/resources/`. Move static content:
    - `src/main/webapp/` → `src/main/resources/META-INF/resources/`
    - Keep JSP files working or migrate to modern frontend framework

### Performance Considerations

- Quarkus has faster startup time and lower memory footprint than JBoss EAP
- Reactive Messaging may perform better than traditional JMS MDBs
- Monitor application metrics after migration to compare performance

### Rollback Plan

If migration issues occur:
- Keep the original Java EE codebase in a separate branch
- Maintain the ability to redeploy to JBoss EAP 7.4
- Test thoroughly in staging environment before production migration
