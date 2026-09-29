# Migration Plan

## Goal
Migrate the CoolStore monolith application from Java EE 7 (JBoss EAP/WebLogic) to Quarkus 3, converting EJB/JMS patterns to CDI and SmallRye Reactive Messaging while preserving all business functionality and the web front end.

## Source → Target
Java EE 7 (JBoss EAP 7.4 / WebLogic) → Quarkus 3

## Scope
- Files affected: 44
- Estimated complexity: High
- Hardest areas:
  1. JMS Message-Driven Beans → SmallRye Reactive Messaging (OrderServiceMDB, InventoryNotificationMDB, ShoppingCartOrderProcessor)
  2. Stateful EJB with Remote lookup → CDI with direct injection (ShoppingCartService, ShippingService)
  3. WebLogic-specific startup listeners and JNDI patterns

## Key Decisions Applied
1. **JMS replacement**: Replace JMS Topics with SmallRye Reactive Messaging in-memory channels. The original app uses `topic/orders` for order processing - this will become a Reactive Messaging channel named `orders`.
2. **Session-scoped cart**: The `CartEndpoint` uses `@SessionScoped` which is problematic in Quarkus's stateless model. Decision: Keep `@SessionScoped` using `quarkus-undertow` extension which provides servlet session support, maintaining existing behavior.
3. **Remote EJB lookup removal**: `ShoppingCartService.lookupShippingServiceRemote()` uses JNDI to lookup `ShippingService`. Decision: Replace with direct CDI `@Inject` since both classes will be CDI beans in the same application.
4. **Flyway integration**: Keep Flyway but use Quarkus Flyway extension instead of manual EJB startup initialization.
5. **WebLogic stubs**: The `weblogic.*` package contains stub classes for WebLogic APIs. Decision: Delete these and refactor `StartupListener` to use Quarkus `@Startup`.
6. **Audit logging library**: The system-scoped `audit-logging-library` JAR will need to be installed to local Maven repo or converted to a proper dependency. Decision: Convert to a regular Maven dependency referencing the lib folder via a local repository configuration.

## Approach

### Phase 1: Build Configuration
Convert `pom.xml` from Java EE WAR packaging to Quarkus JAR packaging with appropriate Quarkus extensions for REST, CDI, JPA (Hibernate ORM with Panache), Reactive Messaging, and Flyway.

### Phase 2: Configuration Files
- Replace `persistence.xml` with Quarkus `application.properties` datasource configuration
- Delete `beans.xml` (not needed in Quarkus)
- Delete `web.xml` (not needed in Quarkus)
- Create `application.properties` with datasource, Hibernate, and Reactive Messaging configuration

### Phase 3: Data Models
Update all JPA entities and model classes to use Jakarta namespace (`jakarta.persistence.*` instead of `javax.persistence.*`).

### Phase 4: Persistence Layer
Convert `Resources.java` EntityManager producer to use `@PersistenceContext` with Jakarta namespace.

### Phase 5: Services
- Convert `@Stateless` EJBs to `@ApplicationScoped` CDI beans
- Convert `@Stateful` EJB to `@ApplicationScoped` (ShoppingCartService state moves to session via endpoint)
- Convert `@Singleton @Startup` to `@ApplicationScoped` with Quarkus `@Startup`
- Convert `@MessageDriven` to `@ApplicationScoped` with `@Incoming` channels
- Convert JMS producer (`ShoppingCartOrderProcessor`) to use `@Channel` Emitter
- Remove WebLogic JNDI lookups and Remote EJB patterns

### Phase 6: REST Endpoints
Update JAX-RS endpoints to use Jakarta namespace. Handle `@SessionScoped` cart endpoint compatibility.

### Phase 7: Utilities
- Convert `DataBaseMigrationStartup` - remove entirely, use Quarkus Flyway extension
- Convert `Producers` to use Jakarta namespace
- Convert/Remove `StartupListener` WebLogic lifecycle listener
- Update `Transformers` to use Jakarta JSON namespace

### Phase 8: Web Frontend
- Move static content from `src/main/webapp` to `src/main/resources/META-INF/resources`
- Convert `index.jsp` to `index.html` (remove JSP session creation, use static HTML)
- Convert `health.jsp` to static health endpoint or use Quarkus health extension
- Move all subdirectories (app/, bower_components/, partials/) to META-INF/resources

### Phase 9: Cleanup
- Delete WebLogic stub classes (`weblogic.*` package)
- Delete `ShippingServiceRemote` interface (no longer needed without Remote EJB)
- Delete obsolete WEB-INF directory

## Steps

### Step 1: Convert pom.xml to Quarkus build
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - BEFORE: Java EE 7 WAR with `javaee-web-api`, `javaee-api`, `jboss-jms-api`, old Flyway, `maven-war-plugin`
    - AFTER: Quarkus 3 JAR with Quarkus BOM, extensions for REST, Hibernate ORM, JDBC PostgreSQL, Flyway, Reactive Messaging, Undertow
    - Specific changes:
        1. Add Quarkus BOM (`io.quarkus.platform:quarkus-bom:3.8.0`) in dependencyManagement
        2. Change packaging from `war` to `jar`
        3. Remove: `javax:javaee-web-api`, `javax:javaee-api`, `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`, `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
        4. Add: `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-reactive-messaging-in-memory`, `quarkus-undertow` (for session support), `quarkus-arc`
        5. Update Flyway dependency to Quarkus-managed version (remove explicit version)
        6. Replace `maven-war-plugin` with `quarkus-maven-plugin`
        7. Update `maven-compiler-plugin` to Java 17
        8. Configure audit-logging-library as regular dependency with local repository
- Why: Quarkus requires its own build configuration and extensions
- Depends on: none
- Verify: `mvn validate` succeeds

### Step 2: Create Quarkus application.properties
- Phase: Configuration
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with:
    ```properties
    # Datasource configuration (PostgreSQL)
    quarkus.datasource.db-kind=postgresql
    quarkus.datasource.username=postgresUser
    quarkus.datasource.password=postgresPW
    quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgresDB
    
    # Hibernate ORM
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.log.sql=false
    quarkus.hibernate-orm.log.format-sql=true
    
    # Flyway
    quarkus.flyway.migrate-at-start=true
    quarkus.flyway.baseline-on-migrate=true
    
    # Reactive Messaging - in-memory connector for orders channel
    mp.messaging.outgoing.orders-out.connector=smallrye-in-memory
    mp.messaging.incoming.orders-in.connector=smallrye-in-memory
    
    # REST path
    quarkus.rest.path=/services
    quarkus.http.root-path=/
    ```
- Why: Quarkus uses application.properties instead of XML configuration files
- Depends on: Step 1
- Verify: File exists with required properties

### Step 3: Migrate CatalogItemEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace `javax.persistence.*` imports with `jakarta.persistence.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 4: Migrate InventoryEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace `javax.persistence.*` and `javax.xml.bind.*` imports with `jakarta.persistence.*` and `jakarta.xml.bind.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.persistence` or `javax.xml` imports remain

### Step 5: Migrate Order entity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace `javax.persistence.*` imports with `jakarta.persistence.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 6: Migrate OrderItem entity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace `javax.persistence.*` imports with `jakarta.persistence.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 7: Migrate ShoppingCart imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace `javax.enterprise.context.Dependent` with `jakarta.enterprise.context.Dependent`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.enterprise` imports remain

### Step 8: COMPLEX — Migrate Resources.java persistence producer
- Phase: Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - BEFORE: `javax.enterprise.*`, `javax.persistence.*` imports with `@Dependent` and `@PersistenceContext`
    - AFTER: `jakarta.enterprise.*`, `jakarta.persistence.*` imports
    - Specific changes:
        1. Replace `javax.enterprise.context.Dependent` → `jakarta.enterprise.context.Dependent`
        2. Replace `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
        3. Replace `javax.persistence.EntityManager` → `jakarta.persistence.EntityManager`
        4. Replace `javax.persistence.PersistenceContext` → `jakarta.persistence.PersistenceContext`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### Step 9: COMPLEX — Migrate CatalogService from Stateless EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with `javax.ejb.*`, `javax.persistence.*`, `javax.inject.*` imports
    - AFTER: `@ApplicationScoped` CDI bean with `jakarta.*` imports
    - Specific changes:
        1. Remove `import javax.ejb.Stateless`
        2. Replace `@Stateless` → `@ApplicationScoped` (add `import jakarta.enterprise.context.ApplicationScoped`)
        3. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        4. Replace `javax.persistence.*` → `jakarta.persistence.*`
- Why: EJBs are not supported in Quarkus; CDI must be used
- Depends on: Step 1, Step 8
- Verify: No `javax.ejb` imports remain, compiles with Quarkus

### Step 10: COMPLEX — Migrate OrderService from Stateless EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with `javax.ejb.*`, `javax.persistence.*`, `javax.inject.*`, `javax.annotation.*` imports
    - AFTER: `@ApplicationScoped` CDI bean with `jakarta.*` imports and Quarkus lifecycle
    - Specific changes:
        1. Remove `import javax.ejb.Stateless`
        2. Replace `@Stateless` → `@ApplicationScoped`
        3. Replace `javax.annotation.PostConstruct` → `jakarta.annotation.PostConstruct`
        4. Replace `javax.annotation.PreDestroy` → `jakarta.annotation.PreDestroy`
        5. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        6. Replace `javax.persistence.*` → `jakarta.persistence.*`
- Why: EJBs are not supported in Quarkus; CDI must be used
- Depends on: Step 1, Step 8
- Verify: No `javax.ejb` imports remain

### Step 11: COMPLEX — Migrate ProductService from Stateless EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with `javax.ejb.*`, `javax.inject.*` imports
    - AFTER: `@ApplicationScoped` CDI bean with `jakarta.*` imports
    - Specific changes:
        1. Remove `import javax.ejb.Stateless`
        2. Replace `@Stateless` → `@ApplicationScoped` (add `import jakarta.enterprise.context.ApplicationScoped`)
        3. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
- Why: EJBs are not supported in Quarkus; CDI must be used
- Depends on: Step 1
- Verify: No `javax.ejb` imports remain

### Step 12: Migrate PromoService imports
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do: Replace `javax.enterprise.context.ApplicationScoped` with `jakarta.enterprise.context.ApplicationScoped`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.enterprise` imports remain

### Step 13: COMPLEX — Migrate ShippingService from Stateless Remote EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless @Remote` EJB implementing `ShippingServiceRemote` with `javax.ejb.*` imports
    - AFTER: `@ApplicationScoped` CDI bean implementing `ShippingServiceRemote`
    - Specific changes:
        1. Remove `import javax.ejb.Remote`
        2. Remove `import javax.ejb.Stateless`
        3. Remove `@Remote` annotation
        4. Replace `@Stateless` → `@ApplicationScoped` (add `import jakarta.enterprise.context.ApplicationScoped`)
- Why: Remote EJBs are not supported in Quarkus; local CDI beans are used instead
- Depends on: Step 1
- Verify: No `javax.ejb` imports remain

### Step 14: COMPLEX — Migrate ShoppingCartService from Stateful EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateful` EJB with JNDI lookup for remote `ShippingService`, `javax.ejb.*`, `javax.inject.*`, `javax.naming.*` imports
    - AFTER: `@ApplicationScoped` CDI bean with direct `@Inject` of `ShippingService`
    - Specific changes:
        1. Remove `import javax.ejb.Stateful`
        2. Remove `import javax.naming.*` (Context, InitialContext, NamingException)
        3. Remove `import java.util.Hashtable`
        4. Replace `@Stateful` → `@ApplicationScoped` (add `import jakarta.enterprise.context.ApplicationScoped`)
        5. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        6. Add field: `@Inject ShippingService shippingService;`
        7. Delete the entire `lookupShippingServiceRemote()` method
        8. Replace calls to `lookupShippingServiceRemote()` with `shippingService`
- Why: Stateful EJBs and JNDI lookups are not supported in Quarkus
- Depends on: Step 1, Step 13
- Verify: No `javax.ejb` or `javax.naming` imports remain

### Step 15: COMPLEX — Convert ShoppingCartOrderProcessor JMS producer to Reactive Messaging
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB using `@Resource Topic` and `JMSContext` to send JMS messages
    - AFTER: `@ApplicationScoped` CDI bean using SmallRye Reactive Messaging `Emitter` to send messages
    - Specific changes:
        1. Remove: `import javax.ejb.Stateless`, `import javax.annotation.Resource`, `import javax.jms.JMSContext`, `import javax.jms.Topic`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import jakarta.inject.Inject`, `import org.eclipse.microprofile.reactive.messaging.Channel`, `import org.eclipse.microprofile.reactive.messaging.Emitter`
        3. Replace `@Stateless` → `@ApplicationScoped`
        4. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        5. Remove: `@Inject private transient JMSContext context;`
        6. Remove: `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;`
        7. Add: `@Inject @Channel("orders-out") Emitter<String> ordersEmitter;`
        8. Replace `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));` → `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: JMS is not supported in Quarkus; SmallRye Reactive Messaging is used instead
- Depends on: Step 1, Step 2
- Verify: No `javax.jms` or `javax.ejb` imports remain

### Step 16: COMPLEX — Convert OrderServiceMDB to Reactive Messaging consumer
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: `@MessageDriven` EJB implementing `MessageListener` with JMS `Message` handling
    - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` channel consumer
    - Specific changes:
        1. Remove: `import javax.ejb.ActivationConfigProperty`, `import javax.ejb.MessageDriven`, `import javax.jms.*`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import jakarta.inject.Inject`, `import org.eclipse.microprofile.reactive.messaging.Incoming`, `import io.smallrye.reactive.messaging.annotations.Blocking`
        3. Remove `@MessageDriven(...)` annotation entirely
        4. Add `@ApplicationScoped` annotation
        5. Remove `implements MessageListener`
        6. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        7. Replace method:
           ```java
           @Override
           public void onMessage(Message rcvMessage) {
               // JMS handling code
           }
           ```
           With:
           ```java
           @Incoming("orders-in")
           @Blocking
           public void onMessage(String orderJson) {
               System.out.println("\nMessage recd !");
               try {
                   System.out.println("Received order: " + orderJson);
                   Order order = Transformers.jsonToOrder(orderJson);
                   System.out.println("Order object is " + order);
                   orderService.save(order);
                   order.getItemList().forEach(orderItem -> {
                       catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
                   });
               } catch (Exception e) {
                   throw new RuntimeException(e);
               }
           }
           ```
- Why: Message-Driven Beans are not supported in Quarkus; Reactive Messaging is used
- Depends on: Step 1, Step 2, Step 9, Step 10
- Verify: No `javax.ejb` or `javax.jms` imports remain

### Step 17: COMPLEX — Convert InventoryNotificationMDB to Reactive Messaging consumer
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Class implementing `MessageListener` with WebLogic JNDI, JMS TopicConnection, manual init/close
    - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` channel consumer
    - Specific changes:
        1. Remove all imports except: `com.redhat.coolstore.model.Order`, `com.redhat.coolstore.utils.Transformers`
        2. Remove: `javax.jms.*`, `javax.naming.*`, `javax.rmi.PortableRemoteObject`, `java.util.Hashtable`
        3. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import jakarta.inject.Inject`, `import org.eclipse.microprofile.reactive.messaging.Incoming`, `import io.smallrye.reactive.messaging.annotations.Blocking`
        4. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        5. Add `@ApplicationScoped` annotation to class
        6. Remove `implements MessageListener`
        7. Remove all WebLogic JNDI constants: `JNDI_FACTORY`, `JMS_FACTORY`, `TOPIC`
        8. Remove all JMS fields: `tcon`, `tsession`, `tsubscriber`
        9. Remove methods: `init()`, `close()`, `getInitialContext()`
        10. Replace `onMessage(Message rcvMessage)` with:
            ```java
            @Incoming("orders-in")
            @Blocking
            public void onMessage(String orderJson) {
                try {
                    System.out.println("received message inventory");
                    Order order = Transformers.jsonToOrder(orderJson);
                    order.getItemList().forEach(orderItem -> {
                        int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId()).getInventory().getQuantity();
                        int new_quantity = old_quantity - orderItem.getQuantity();
                        if (new_quantity < LOW_THRESHOLD) {
                            System.out.println("Inventory for item " + orderItem.getProductId() + " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                        }
                    });
                } catch (Exception e) {
                    System.err.println("An exception occurred: " + e.getMessage());
                }
            }
            ```
- Why: JMS and WebLogic JNDI patterns are not supported in Quarkus
- Depends on: Step 1, Step 2, Step 9
- Verify: No `javax.jms`, `javax.naming`, `javax.rmi` imports remain

### Step 18: COMPLEX — Convert DataBaseMigrationStartup to use Quarkus Flyway
- Phase: Services
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Singleton @Startup` EJB with `@TransactionManagement` that manually calls Flyway
    - AFTER: Empty `@ApplicationScoped` bean with `@Startup` - Quarkus Flyway extension handles migration automatically
    - Specific changes:
        1. Remove all imports except logging
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import io.quarkus.runtime.Startup`, `import jakarta.annotation.PostConstruct`, `import jakarta.inject.Inject`
        3. Remove `@Singleton`, `@TransactionManagement`
        4. Add `@ApplicationScoped`, `@Startup`
        5. Remove `@Resource DataSource` field
        6. Replace `@PostConstruct startup()` method body with just logging:
           ```java
           @PostConstruct
           void startup() {
               logger.info("Application started - Flyway migration handled by Quarkus");
           }
           ```
- Why: Quarkus Flyway extension handles migrations automatically via configuration
- Depends on: Step 1, Step 2
- Verify: No `javax.ejb`, `org.flywaydb` imports remain (Flyway is configured, not programmatic)

### Step 19: Migrate Producers.java imports
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
    - Replace `javax.enterprise.inject.spi.InjectionPoint` → `jakarta.enterprise.inject.spi.InjectionPoint`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.enterprise` imports remain

### Step 20: COMPLEX — Convert StartupListener from WebLogic to Quarkus
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE: Extends `weblogic.application.ApplicationLifecycleListener` with `postStart`/`preStop` methods
    - AFTER: `@ApplicationScoped` CDI bean with Quarkus lifecycle observers
    - Specific changes:
        1. Remove: `import weblogic.application.ApplicationLifecycleEvent`, `import weblogic.application.ApplicationLifecycleListener`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import jakarta.enterprise.event.Observes`, `import io.quarkus.runtime.StartupEvent`, `import io.quarkus.runtime.ShutdownEvent`, `import jakarta.inject.Inject`
        3. Replace `javax.inject.Inject` → `jakarta.inject.Inject`
        4. Remove `extends ApplicationLifecycleListener`
        5. Add `@ApplicationScoped` annotation
        6. Replace methods:
           ```java
           void onStart(@Observes StartupEvent ev) {
               log.info("AppListener(postStart)");
           }
           
           void onStop(@Observes ShutdownEvent ev) {
               log.info("AppListener(preStop)");
           }
           ```
- Why: WebLogic lifecycle listeners are not supported in Quarkus
- Depends on: Step 1
- Verify: No `weblogic.*` imports remain

### Step 21: Migrate Transformers.java imports
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace `javax.json.*` imports with `jakarta.json.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No `javax.json` imports remain

### Step 22: Migrate CartEndpoint imports and annotations
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.SessionScoped` → `jakarta.enterprise.context.SessionScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1, Step 14
- Verify: No `javax.*` imports remain

### Step 23: Migrate OrderEndpoint imports
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.RequestScoped` → `jakarta.enterprise.context.RequestScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1, Step 10
- Verify: No `javax.*` imports remain

### Step 24: Migrate ProductEndpoint imports
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.RequestScoped` → `jakarta.enterprise.context.RequestScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1, Step 11
- Verify: No `javax.*` imports remain

### Step 25: Migrate RestApplication imports
- Phase: REST Endpoints
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace `javax.ws.rs.ApplicationPath` → `jakarta.ws.rs.ApplicationPath`
    - Replace `javax.ws.rs.core.Application` → `jakarta.ws.rs.core.Application`
- Why: Quarkus 3 uses Jakarta EE namespace (JAX-RS activation is optional in Quarkus but keeping for path config)
- Depends on: Step 1
- Verify: No `javax.ws` imports remain

### Step 26: Convert persistence.xml to Jakarta namespace
- Phase: Configuration
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do:
    - Replace `http://xmlns.jcp.org/xml/ns/persistence` → `https://jakarta.ee/xml/ns/persistence`
    - Replace `persistence_2_1.xsd` → `persistence_3_0.xsd`
    - Replace `version="2.1"` → `version="3.0"`
    - Replace `javax.persistence.schema-generation.database.action` → `jakarta.persistence.schema-generation.database.action`
    - Remove `<jta-data-source>java:jboss/datasources/CoolstoreDS</jta-data-source>` (configured in application.properties)
- Why: Quarkus 3 uses Jakarta EE persistence namespace
- Depends on: Step 2
- Verify: No `javax.persistence` or `xmlns.jcp.org` references remain

### Step 27: Convert index.jsp to static index.html
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/index.html
- Action: CREATE
- What to do: Create static HTML version of index.jsp:
    - Remove JSP scriptlet `<% request.getSession(true); %>`
    - Keep all HTML, CSS, and JavaScript references intact
    - This is pure static content that will be served by Quarkus
- Why: JSP is not supported in Quarkus; static HTML is used instead
- Depends on: Step 1
- Verify: File exists with valid HTML

### Step 28: Create static health endpoint
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/health.html
- Action: CREATE
- What to do: Create static HTML file with content `1` (same as health.jsp output)
- Why: JSP is not supported in Quarkus
- Depends on: Step 1
- Verify: File exists

### Step 29: Move app directory to META-INF/resources
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/app
- Action: CREATE
- What to do: Copy entire `src/main/webapp/app/` directory to `src/main/resources/META-INF/resources/app/`
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All files under app/ are present

### Step 30: Move bower_components directory to META-INF/resources
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/bower_components
- Action: CREATE
- What to do: Copy entire `src/main/webapp/bower_components/` directory to `src/main/resources/META-INF/resources/bower_components/`
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All bower component files are present

### Step 31: Move partials directory to META-INF/resources
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/partials
- Action: CREATE
- What to do: Copy entire `src/main/webapp/partials/` directory to `src/main/resources/META-INF/resources/partials/`
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All partial HTML files are present

### Step 32: Move coolstore.json to META-INF/resources
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/coolstore.json
- Action: CREATE
- What to do: Copy `src/main/webapp/coolstore.json` to `src/main/resources/META-INF/resources/coolstore.json`
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists with JSON configuration

### Step 33: Move keycloak.json to META-INF/resources
- Phase: Web Frontend
- File: src/main/resources/META-INF/resources/keycloak.json
- Action: CREATE
- What to do: Copy `src/main/webapp/keycloak.json` to `src/main/resources/META-INF/resources/keycloak.json`
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: File exists with Keycloak configuration

### Step 34: Delete ApplicationLifecycleEvent.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this WebLogic stub interface - no longer needed
- Why: WebLogic APIs are not used in Quarkus
- Depends on: Step 20
- Verify: File no longer exists

### Step 35: Delete ApplicationLifecycleListener.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this WebLogic stub class - no longer needed
- Why: WebLogic APIs are not used in Quarkus
- Depends on: Step 20
- Verify: File no longer exists

### Step 36: Delete NonCatalogLogger.java
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this WebLogic stub class - no longer needed
- Why: WebLogic APIs are not used in Quarkus
- Depends on: Step 1
- Verify: File no longer exists

### Step 37: Delete weblogic/application directory
- Phase: Cleanup
- File: src/main/java/weblogic/application
- Action: DELETE
- What to do: Delete empty weblogic/application directory after removing its contents
- Why: WebLogic package structure is not needed
- Depends on: Step 34, Step 35
- Verify: Directory no longer exists

### Step 38: Delete weblogic/i18n/logging directory
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging
- Action: DELETE
- What to do: Delete empty weblogic/i18n/logging directory after removing its contents
- Why: WebLogic package structure is not needed
- Depends on: Step 36
- Verify: Directory no longer exists

### Step 39: Delete weblogic directory tree
- Phase: Cleanup
- File: src/main/java/weblogic
- Action: DELETE
- What to do: Delete entire weblogic directory tree
- Why: WebLogic package structure is not needed
- Depends on: Step 37, Step 38
- Verify: Directory no longer exists

### Step 40: Delete beans.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete beans.xml - CDI is enabled by default in Quarkus
- Why: beans.xml descriptor content is ignored in Quarkus
- Depends on: Step 1
- Verify: File no longer exists

### Step 41: Delete web.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete web.xml - not needed in Quarkus
- Why: Quarkus does not use web.xml deployment descriptor
- Depends on: Step 1
- Verify: File no longer exists

### Step 42: Delete WEB-INF directory
- Phase: Cleanup
- File: src/main/webapp/WEB-INF
- Action: DELETE
- What to do: Delete empty WEB-INF directory
- Why: No longer needed after removing beans.xml and web.xml
- Depends on: Step 40, Step 41
- Verify: Directory no longer exists

### Step 43: Delete original webapp static content
- Phase: Cleanup
- File: src/main/webapp
- Action: DELETE
- What to do: Delete entire src/main/webapp directory after content moved to META-INF/resources
- Why: Static content has been moved to Quarkus location
- Depends on: Step 27, Step 28, Step 29, Step 30, Step 31, Step 32, Step 33, Step 42
- Verify: Directory no longer exists

### Step 44: Delete ShippingServiceRemote interface
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete the remote EJB interface - ShippingService is now a local CDI bean
- Why: Remote EJB interfaces are not used in Quarkus; direct CDI injection is used instead
- Depends on: Step 13, Step 14
- Verify: File no longer exists

## Verification
- Build: `mvn clean compile quarkus:build`
- Test: No automated tests exist in this project
- Blackbox:
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start the application: `mvn quarkus:dev`
  3. Navigate to http://localhost:8080
  4. Verify the CoolStore home page loads with product catalog
  5. Add items to cart and verify cart functionality
  6. Complete checkout and verify order processing (check console for "Message recd !" logs)

## Notes
- **Keycloak integration**: The existing Keycloak integration will need separate configuration if authentication is required. The `keycloak.json` is preserved for the frontend, but Quarkus uses `quarkus-oidc` extension for server-side auth.
- **Session scope limitations**: The `@SessionScoped` cart endpoint relies on servlet sessions. This works with `quarkus-undertow` but may need reconsideration for cloud-native deployments (consider moving to request-scoped with client-side cart storage).
- **Reactive Messaging broadcast**: Both `OrderServiceMDB` and `InventoryNotificationMDB` originally consumed from the same JMS topic. With SmallRye in-memory connector, the `orders-in` channel will broadcast to all consumers, preserving the original behavior.
- **Audit logging library**: The system-scoped JAR dependency needs proper Maven configuration. Consider publishing it to a Maven repository or including it as a multi-module project dependency.
- **Flyway migrations**: Existing SQL scripts in `src/main/resources/db/migration/` will be automatically picked up by Quarkus Flyway extension.
