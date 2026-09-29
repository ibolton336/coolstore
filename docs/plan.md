# Migration Plan

## Goal
Migrate the CoolStore Java EE 7 monolith application to Quarkus 3, including the web frontend.

## Source → Target
Java EE 7 (JBoss EAP 7.4 / WebLogic) → Quarkus 3

## Scope
- Files affected: 45
- Estimated complexity: High
- Hardest areas:
  1. JMS/MDB to MicroProfile Reactive Messaging conversion
  2. Remote EJB to direct CDI injection
  3. WebLogic-specific code removal (ApplicationLifecycleListener, JNDI lookups)

## Key Decisions Applied
- **Database target:** H2 in-memory database (chosen by the user for development simplicity; no external database required)
- **Flyway handling:** Replace custom EJB-based Flyway startup with Quarkus Flyway extension (quarkus-flyway)
- **Session scope:** CartEndpoint uses `@SessionScoped` which requires `quarkus-undertow` extension in Quarkus
- **JMS replacement:** Use SmallRye Reactive Messaging with in-memory connector for the orders topic

## Approach

### Phase 1: Build Configuration
Convert pom.xml from Java EE WAR to Quarkus JAR packaging. Add Quarkus BOM, plugins, and required extensions. Remove Java EE dependencies.

### Phase 2: Configuration
Create Quarkus application.properties for datasource (H2), Hibernate, and messaging configuration. Delete persistence.xml and web.xml.

### Phase 3: Models/Entities
Update all JPA entities to use jakarta.persistence namespace. Update @GeneratedValue strategies for Hibernate 6.

### Phase 4: Persistence
Convert Resources.java from @PersistenceContext/@Produces pattern to simple @Inject.

### Phase 5: Services
Convert EJB services to CDI beans:
- @Stateless → @ApplicationScoped
- @Stateful → @SessionScoped (or @ApplicationScoped where appropriate)
- @Singleton/@Startup → @ApplicationScoped with @Startup
- Add @Transactional where needed for EntityManager operations
- Remove JNDI lookups, use direct CDI injection

### Phase 6: Messaging
Convert JMS MDBs to MicroProfile Reactive Messaging:
- Replace @MessageDriven with @ApplicationScoped + @Incoming
- Replace JMS Topic producer with @Outgoing/@Channel emitter
- Remove JMS/JNDI code from InventoryNotificationMDB

### Phase 7: REST/API
Update REST endpoints:
- javax.ws.rs → jakarta.ws.rs
- javax.enterprise → jakarta.enterprise
- javax.inject → jakarta.inject
- RestApplication can be simplified (JAX-RS activation is optional in Quarkus)

### Phase 8: Utils
- Convert DataBaseMigrationStartup to use Quarkus Flyway extension (delete the class)
- Convert StartupListener to Quarkus @Observes StartupEvent
- Update Producers and Transformers to jakarta namespace

### Phase 9: Frontend
- Move all static content from src/main/webapp to src/main/resources/META-INF/resources
- Convert index.jsp to index.html (remove JSP session code)
- Convert health.jsp to health.html (or use Quarkus health extension)
- Move bower_components, app/, partials/, JSON files

### Phase 10: Cleanup
Delete obsolete files:
- WebLogic stub classes (weblogic.* package)
- WEB-INF/beans.xml (Quarkus handles CDI automatically)
- WEB-INF/web.xml
- persistence.xml (configuration moved to application.properties)
- ShippingServiceRemote interface (remote EJB not needed)

## Steps

### Step 1: Migrate pom.xml to Quarkus
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - Change packaging from `war` to `jar`
    - Update Java version from 1.8 to 17
    - Add Quarkus BOM in dependencyManagement
    - Add Quarkus Maven plugin
    - Remove javax:javaee-web-api and javax:javaee-api dependencies
    - Remove org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec
    - Remove org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec
    - Remove system-scoped audit-logging-library dependency
    - Add Quarkus extensions:
      - quarkus-resteasy-reactive-jackson (REST)
      - quarkus-hibernate-orm (JPA)
      - quarkus-jdbc-h2 (H2 database)
      - quarkus-flyway (database migrations)
      - quarkus-smallrye-reactive-messaging (messaging)
      - quarkus-undertow (for @SessionScoped support)
      - quarkus-arc (CDI - included transitively)
    - Update flyway-core to version compatible with Quarkus (or remove if using quarkus-flyway)
    - Add Maven Compiler plugin with Java 17 and -parameters
    - Add Maven Surefire plugin
- Why: Quarkus requires different build configuration and dependencies than Java EE
- Depends on: none
- Verify: `mvn clean compile` succeeds

### Step 2: Create Quarkus application.properties
- Phase: Configuration
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with:
    ```properties
    # Datasource configuration (H2 in-memory)
    quarkus.datasource.db-kind=h2
    quarkus.datasource.username=sa
    quarkus.datasource.password=sa
    quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_DELAY=-1

    # Hibernate ORM
    quarkus.hibernate-orm.database.generation=drop-and-create
    quarkus.hibernate-orm.log.sql=false
    quarkus.hibernate-orm.sql-load-script=import.sql

    # Flyway (if using migrations)
    quarkus.flyway.migrate-at-start=true

    # Reactive Messaging - In-memory connector for orders topic
    mp.messaging.incoming.orders-in.connector=smallrye-in-memory
    mp.messaging.outgoing.orders-out.connector=smallrye-in-memory
    mp.messaging.incoming.orders-in.merge=true
    ```
- Why: Quarkus uses application.properties for centralized configuration
- Depends on: Step 1
- Verify: File exists with required properties

### Step 3: COMPLEX — Migrate CatalogItemEntity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
    - BEFORE: `import javax.persistence.*`
    - AFTER: `import jakarta.persistence.*`
    - Specific changes:
        1. Replace all `javax.persistence` imports with `jakarta.persistence`
        2. Review @Id strategy - current uses default (assigned), keep as-is since itemId is a String
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.persistence imports remain

### Step 4: Migrate InventoryEntity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.persistence imports remain

### Step 5: Migrate Order
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace `javax.persistence` imports with `jakarta.persistence` if present; check for any javax.* imports
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 6: Migrate OrderItem
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` equivalents
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 7: Migrate Product
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` equivalents
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 8: Migrate Promotion
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` equivalents
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 9: Migrate ShoppingCart
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` equivalents (javax.json if present)
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 10: Migrate ShoppingCartItem
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` equivalents
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 11: COMPLEX — Migrate Resources.java (EntityManager producer)
- Phase: Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        @PersistenceContext
        private EntityManager em;

        @Produces
        public EntityManager getEntityManager() {
            return em;
        }
        ```
    - AFTER:
        ```java
        // Delete this class entirely, or convert to just hold a simple @Inject EntityManager if other code needs it
        // In Quarkus, just use @Inject EntityManager directly where needed
        ```
    - Specific changes:
        1. Remove `@PersistenceContext` annotation
        2. Remove `@Produces` annotation (Quarkus auto-produces EntityManager)
        3. Replace `javax.enterprise.context.Dependent` with `jakarta.enterprise.context.Dependent`
        4. Replace `javax.enterprise.inject.Produces` with removal (not needed)
        5. Replace `javax.persistence.*` with `jakarta.persistence.*`
        6. Consider deleting the class entirely since Quarkus handles EntityManager injection
- Why: Quarkus automatically produces EntityManager when datasource is configured; @Produces on EntityManager is illegal
- Depends on: Step 2
- Verify: No @PersistenceContext or @Produces on EntityManager

### Step 12: COMPLEX — Migrate CatalogService
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with `@Inject EntityManager`
    - AFTER: `@ApplicationScoped` CDI bean with `@Inject EntityManager` and `@Transactional` on write methods
    - Specific changes:
        1. Replace `javax.ejb.Stateless` with `jakarta.enterprise.context.ApplicationScoped`
        2. Replace `javax.inject.Inject` with `jakarta.inject.Inject`
        3. Replace `javax.persistence.*` with `jakarta.persistence.*`
        4. Add `@Transactional` to `updateInventoryItems()` method (uses em.merge)
        5. Add import `jakarta.transaction.Transactional`
- Why: EJBs not supported in Quarkus; must use CDI beans with explicit transaction boundaries
- Depends on: Step 11
- Verify: grep for @Stateless returns nothing; @Transactional on methods using em.merge/persist

### Step 13: COMPLEX — Migrate OrderService
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - BEFORE: Likely @Stateless EJB
    - AFTER: @ApplicationScoped CDI bean with @Transactional on write methods
    - Specific changes:
        1. Replace `@Stateless` with `@ApplicationScoped`
        2. Replace all `javax.*` imports with `jakarta.*` equivalents
        3. Add `@Transactional` to save/persist methods
- Why: EJBs not supported in Quarkus
- Depends on: Step 11
- Verify: No EJB annotations remain

### Step 14: Migrate ProductService
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Replace EJB annotation (@Stateless) with @ApplicationScoped
    - Replace all `javax.*` imports with `jakarta.*`
- Why: EJBs not supported in Quarkus
- Depends on: Step 11
- Verify: No EJB annotations remain

### Step 15: Migrate PromoService
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Replace EJB annotation with @ApplicationScoped if present
    - Replace all `javax.*` imports with `jakarta.*`
- Why: EJBs not supported in Quarkus
- Depends on: Step 11
- Verify: No EJB annotations remain

### Step 16: COMPLEX — Migrate ShippingService (Remote EJB)
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        @Stateless
        @Remote
        public class ShippingService implements ShippingServiceRemote {
        ```
    - AFTER:
        ```java
        @ApplicationScoped
        public class ShippingService {
        ```
    - Specific changes:
        1. Remove `@Remote` annotation
        2. Replace `@Stateless` with `@ApplicationScoped`
        3. Remove `implements ShippingServiceRemote` (interface will be deleted)
        4. Replace `javax.ejb.*` with `jakarta.enterprise.context.ApplicationScoped`
- Why: Remote EJBs not supported in Quarkus; convert to local CDI bean
- Depends on: Step 1
- Verify: No @Remote or @Stateless annotations

### Step 17: COMPLEX — Migrate ShoppingCartService (Stateful EJB with JNDI lookup)
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        @Stateful
        public class ShoppingCartService {
            ...
            private static ShippingServiceRemote lookupShippingServiceRemote() {
                // JNDI lookup code
            }
        }
        ```
    - AFTER:
        ```java
        @ApplicationScoped
        public class ShoppingCartService {
            @Inject
            ShippingService shippingService;
            ...
            // Remove lookupShippingServiceRemote() method entirely
            // Replace calls to lookupShippingServiceRemote() with shippingService
        }
        ```
    - Specific changes:
        1. Replace `@Stateful` with `@ApplicationScoped`
        2. Remove all JNDI-related imports (javax.naming.*)
        3. Remove lookupShippingServiceRemote() method
        4. Add `@Inject ShippingService shippingService` field
        5. Replace `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
        6. Replace `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
        7. Replace all `javax.*` imports with `jakarta.*`
- Why: JNDI lookups and remote EJB not supported in Quarkus; use CDI injection
- Depends on: Step 16
- Verify: No JNDI code; no @Stateful; uses @Inject for ShippingService

### Step 18: COMPLEX — Migrate ShoppingCartOrderProcessor (JMS Topic Producer)
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        @Stateless
        public class ShoppingCartOrderProcessor {
            @Inject
            private transient JMSContext context;

            @Resource(lookup = "java:/topic/orders")
            private Topic ordersTopic;

            public void process(ShoppingCart cart) {
                context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
            }
        }
        ```
    - AFTER:
        ```java
        @ApplicationScoped
        public class ShoppingCartOrderProcessor {
            @Inject
            @Channel("orders-out")
            Emitter<String> ordersEmitter;

            public void process(ShoppingCart cart) {
                ordersEmitter.send(Transformers.shoppingCartToJson(cart));
            }
        }
        ```
    - Specific changes:
        1. Replace `@Stateless` with `@ApplicationScoped`
        2. Remove JMS imports (javax.jms.*)
        3. Remove `@Resource` Topic injection
        4. Remove JMSContext injection
        5. Add `import org.eclipse.microprofile.reactive.messaging.Channel`
        6. Add `import org.eclipse.microprofile.reactive.messaging.Emitter`
        7. Add `@Inject @Channel("orders-out") Emitter<String> ordersEmitter`
        8. Replace JMS send with `ordersEmitter.send(...)`
        9. Replace all `javax.*` with `jakarta.*`
- Why: JMS not supported in Quarkus; use MicroProfile Reactive Messaging
- Depends on: Step 2
- Verify: No JMS code; uses Emitter with @Channel

### Step 19: COMPLEX — Migrate OrderServiceMDB
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        @MessageDriven(name = "OrderServiceMDB", activationConfig = {...})
        public class OrderServiceMDB implements MessageListener {
            @Override
            public void onMessage(Message rcvMessage) {...}
        }
        ```
    - AFTER:
        ```java
        @ApplicationScoped
        public class OrderServiceMDB {
            @Inject
            OrderService orderService;

            @Inject
            CatalogService catalogService;

            @Incoming("orders-in")
            public void onMessage(String orderJson) {
                System.out.println("\nMessage recd !");
                System.out.println("Received order: " + orderJson);
                Order order = Transformers.jsonToOrder(orderJson);
                System.out.println("Order object is " + order);
                orderService.save(order);
                order.getItemList().forEach(orderItem -> {
                    catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
                });
            }
        }
        ```
    - Specific changes:
        1. Remove `@MessageDriven` annotation and all activation config
        2. Add `@ApplicationScoped`
        3. Remove `implements MessageListener`
        4. Remove all JMS imports (javax.jms.*, javax.ejb.*)
        5. Add `import org.eclipse.microprofile.reactive.messaging.Incoming`
        6. Change method signature from `onMessage(Message)` to `onMessage(String)`
        7. Remove TextMessage casting - receive String directly
        8. Replace all `javax.*` with `jakarta.*`
- Why: MDBs not supported in Quarkus; use MicroProfile Reactive Messaging @Incoming
- Depends on: Step 2, Step 13
- Verify: No @MessageDriven; has @Incoming("orders-in")

### Step 20: COMPLEX — Migrate InventoryNotificationMDB
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: WebLogic JMS with JNDI lookups, TopicConnection, TopicSession
    - AFTER: MicroProfile Reactive Messaging
    - Specific changes:
        1. Remove all JNDI code (InitialContext, Hashtable, env)
        2. Remove TopicConnection, TopicSession, TopicSubscriber fields
        3. Remove init() and close() methods
        4. Remove WebLogic-specific constants (JNDI_FACTORY, etc.)
        5. Add `@ApplicationScoped`
        6. Add `import org.eclipse.microprofile.reactive.messaging.Incoming`
        7. Change onMessage to:
            ```java
            @Incoming("orders-in")
            public void onMessage(String orderJson) {
                System.out.println("received message inventory");
                Order order = Transformers.jsonToOrder(orderJson);
                order.getItemList().forEach(orderItem -> {
                    int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId()).getInventory().getQuantity();
                    int new_quantity = old_quantity - orderItem.getQuantity();
                    if (new_quantity < LOW_THRESHOLD) {
                        System.out.println("Inventory for item " + orderItem.getProductId() + " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                    }
                });
            }
            ```
        8. Remove implements MessageListener
        9. Remove all JMS imports
        10. Replace `javax.inject` with `jakarta.inject`
- Why: WebLogic JMS/JNDI not supported; use MicroProfile Reactive Messaging
- Depends on: Step 2
- Verify: No JMS/JNDI code; has @Incoming

### Step 21: Migrate Transformers
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace any `javax.*` imports with `jakarta.*` (likely javax.json → jakarta.json)
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 22: Migrate Producers (Logger producer)
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.*` with `jakarta.enterprise.*`
    - Replace `javax.inject.*` with `jakarta.inject.*`
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 23: COMPLEX — Migrate StartupListener (WebLogic to Quarkus)
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE:
        ```java
        import weblogic.application.ApplicationLifecycleEvent;
        import weblogic.application.ApplicationLifecycleListener;

        public class StartupListener extends ApplicationLifecycleListener {
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
        import io.quarkus.runtime.ShutdownEvent;
        import io.quarkus.runtime.StartupEvent;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;
        import jakarta.inject.Inject;
        import java.util.logging.Logger;

        @ApplicationScoped
        public class StartupListener {
            @Inject
            Logger log;

            void onStart(@Observes StartupEvent ev) {
                log.info("AppListener(postStart)");
            }

            void onStop(@Observes ShutdownEvent ev) {
                log.info("AppListener(preStop)");
            }
        }
        ```
    - Specific changes:
        1. Remove WebLogic imports
        2. Remove `extends ApplicationLifecycleListener`
        3. Add `@ApplicationScoped`
        4. Add Quarkus lifecycle imports
        5. Replace `postStart()` with `onStart(@Observes StartupEvent)`
        6. Replace `preStop()` with `onStop(@Observes ShutdownEvent)`
        7. Replace `javax.inject` with `jakarta.inject`
- Why: WebLogic ApplicationLifecycleListener not available in Quarkus; use Quarkus lifecycle events
- Depends on: Step 1
- Verify: No weblogic imports; uses @Observes StartupEvent/ShutdownEvent

### Step 24: Delete DataBaseMigrationStartup
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: DELETE
- What to do: Delete this file — Quarkus Flyway extension handles database migration automatically when quarkus.flyway.migrate-at-start=true is set in application.properties
- Why: @Singleton @Startup EJB pattern replaced by Quarkus Flyway extension; manual Flyway initialization not needed
- Depends on: Step 2
- Verify: File no longer exists

### Step 25: Migrate CartEndpoint
- Phase: REST
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.SessionScoped` with `jakarta.enterprise.context.SessionScoped`
    - Replace `javax.inject.Inject` with `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` with `jakarta.ws.rs.*`
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1, Step 17
- Verify: No javax.* imports remain

### Step 26: Migrate OrderEndpoint
- Phase: REST
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.RequestScoped` with `jakarta.enterprise.context.RequestScoped`
    - Replace `javax.inject.Inject` with `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` with `jakarta.ws.rs.*`
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 27: Migrate ProductEndpoint
- Phase: REST
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.*` with `jakarta.enterprise.*`
    - Replace `javax.inject.*` with `jakarta.inject.*`
    - Replace `javax.ws.rs.*` with `jakarta.ws.rs.*`
- Why: Quarkus 3 requires Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 28: Migrate RestApplication
- Phase: REST
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace `javax.ws.rs.ApplicationPath` with `jakarta.ws.rs.ApplicationPath`
    - Replace `javax.ws.rs.core.Application` with `jakarta.ws.rs.core.Application`
    - Note: In Quarkus, this class is optional but can be kept to set the root path
- Why: Quarkus 3 requires Jakarta EE namespace; JAX-RS activation is automatic
- Depends on: Step 1
- Verify: No javax.* imports remain

### Step 29: Convert index.jsp to index.html
- Phase: Frontend
- File: src/main/resources/META-INF/resources/index.html
- Action: CREATE
- What to do: Create index.html with the same content as index.jsp but:
    - Remove JSP directive `<% request.getSession(true); %>` at the top
    - Keep all HTML, script, and link tags unchanged
    - Paths remain the same (e.g., `/bower_components/...`, `/app/...`)
- Why: JSP not supported in Quarkus; static HTML works with Quarkus static resources
- Depends on: Step 1
- Verify: File exists without JSP code

### Step 30: Convert health.jsp to health.html
- Phase: Frontend
- File: src/main/resources/META-INF/resources/health.html
- Action: CREATE
- What to do: Create health.html with content `1` (same as health.jsp)
    - Alternatively, use Quarkus SmallRye Health extension for proper health checks
- Why: JSP not supported in Quarkus
- Depends on: Step 1
- Verify: File exists

### Step 31: Move app/app.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/app.js
- Action: CREATE
- What to do: Copy src/main/webapp/app/app.js to src/main/resources/META-INF/resources/app/app.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 32: Move app/controllers/controllers.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/controllers/controllers.js
- Action: CREATE
- What to do: Copy from src/main/webapp/app/controllers/controllers.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 33: Move app/css/coolstore.css
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/css/coolstore.css
- Action: CREATE
- What to do: Copy from src/main/webapp/app/css/coolstore.css
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 34: Move app/directives/header.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/directives/header.js
- Action: CREATE
- What to do: Copy from src/main/webapp/app/directives/header.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 35: Move app/routes/routes.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/routes/routes.js
- Action: CREATE
- What to do: Copy from src/main/webapp/app/routes/routes.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 36: Move app/services/cart.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/services/cart.js
- Action: CREATE
- What to do: Copy from src/main/webapp/app/services/cart.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 37: Move app/services/catalog.js
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/services/catalog.js
- Action: CREATE
- What to do: Copy from src/main/webapp/app/services/catalog.js
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 38: Move app/imgs directory
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app/imgs/
- Action: CREATE
- What to do: Copy entire src/main/webapp/app/imgs/ directory to src/main/resources/META-INF/resources/app/imgs/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All image files exist at new location

### Step 39: Move partials directory
- Phase: Frontend
- File: src/main/resources/META-INF/resources/partials/
- Action: CREATE
- What to do: Copy entire src/main/webapp/partials/ directory (cart.html, header.html, home.html) to src/main/resources/META-INF/resources/partials/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All partial HTML files exist at new location

### Step 40: Move bower_components directory
- Phase: Frontend
- File: src/main/resources/META-INF/resources/bower_components/
- Action: CREATE
- What to do: Copy entire src/main/webapp/bower_components/ directory to src/main/resources/META-INF/resources/bower_components/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All bower components exist at new location

### Step 41: Move coolstore.json
- Phase: Frontend
- File: src/main/resources/META-INF/resources/coolstore.json
- Action: CREATE
- What to do: Copy src/main/webapp/coolstore.json to src/main/resources/META-INF/resources/coolstore.json
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 42: Move keycloak.json
- Phase: Frontend
- File: src/main/resources/META-INF/resources/keycloak.json
- Action: CREATE
- What to do: Copy src/main/webapp/keycloak.json to src/main/resources/META-INF/resources/keycloak.json
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists at new location

### Step 43: Delete ShippingServiceRemote interface
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this file — remote EJB interface no longer needed
- Why: Remote EJBs not supported in Quarkus; ShippingService is now a local CDI bean
- Depends on: Step 16, Step 17
- Verify: File no longer exists

### Step 44: Delete weblogic stub classes
- Phase: Cleanup
- File: src/main/java/weblogic/
- Action: DELETE
- What to do: Delete entire src/main/java/weblogic/ directory including:
    - weblogic/application/ApplicationLifecycleEvent.java
    - weblogic/application/ApplicationLifecycleListener.java
    - weblogic/i18n/logging/NonCatalogLogger.java
- Why: WebLogic stubs no longer needed after migration
- Depends on: Step 23
- Verify: Directory no longer exists

### Step 45: Delete persistence.xml
- Phase: Cleanup
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file — configuration moved to application.properties
- Why: Quarkus uses application.properties for persistence configuration
- Depends on: Step 2
- Verify: File no longer exists

### Step 46: Delete WEB-INF/beans.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file — Quarkus handles CDI automatically
- Why: beans.xml content ignored in Quarkus; not needed
- Depends on: Step 1
- Verify: File no longer exists

### Step 47: Delete WEB-INF/web.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file — not needed in Quarkus
- Why: Quarkus does not use deployment descriptors
- Depends on: Step 1
- Verify: File no longer exists

### Step 48: Delete src/main/webapp directory
- Phase: Cleanup
- File: src/main/webapp/
- Action: DELETE
- What to do: Delete entire src/main/webapp/ directory after all content has been moved to src/main/resources/META-INF/resources/
- Why: Quarkus does not use webapp directory; all static content now in META-INF/resources
- Depends on: Steps 29-42, Step 46, Step 47
- Verify: Directory no longer exists

## Verification
- Build: `mvn clean compile`
- Test: `mvn test` (if tests exist)
- Blackbox:
  1. Start the application: `mvn quarkus:dev`
  2. Open http://localhost:8080 in browser
  3. Verify the CoolStore storefront loads with product catalog
  4. Add items to cart and verify cart functionality
  5. Test checkout process (note: messaging will use in-memory channel)

## Notes
- **H2 Database:** The H2 in-memory database means data is not persisted between restarts. For production, switch to PostgreSQL by changing datasource configuration.
- **Session Scope:** The CartEndpoint uses @SessionScoped which requires the quarkus-undertow extension. Consider migrating to a different session management approach for better Quarkus compatibility.
- **Reactive Messaging:** The in-memory connector is used for development. For production with message persistence, configure a real message broker (Kafka, AMQP, etc.).
- **Keycloak:** The keycloak.json file is moved but Keycloak integration may need additional Quarkus OIDC configuration in application.properties.
- **Flyway:** Ensure migration scripts exist in src/main/resources/db/migration/ or set up initial data with import.sql.
- **bower_components:** Consider modernizing the frontend build to use npm/webpack instead of bower (deprecated).
