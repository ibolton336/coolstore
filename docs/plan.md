# Migration Plan

## Goal
Migrate the CoolStore Java EE 7 monolith application from JBoss EAP to Quarkus 3, including the AngularJS web front end.

## Source → Target
Java EE 7 / JBoss EAP 7.4 → Quarkus 3 / Jakarta EE 10

## Scope
- Files affected: 54
- Estimated complexity: High
- Hardest areas: JMS Message-Driven Beans conversion, EJB Remote interface removal, WebLogic proprietary code removal

## Key Decisions Applied
- **Database**: Use in-memory H2 database for development (human decision: user chose H2 over PostgreSQL)
- **JMS replacement**: Convert JMS MDBs to Quarkus SmallRye Reactive Messaging with in-memory connector for simplicity
- **EJB Remote**: Remove EJB Remote interface pattern and use direct CDI injection for ShippingService
- **Session scope**: Convert @SessionScoped CartEndpoint to @ApplicationScoped with cart stored by ID in a map

## Approach
1. **Phase 1 - Build Config**: Convert pom.xml to Quarkus BOM with required extensions; create application.properties
2. **Phase 2 - Models**: Update JPA entities with Jakarta imports and fix @GeneratedValue for Hibernate 6
3. **Phase 3 - Persistence**: Remove CDI EntityManager producer (Quarkus injects directly), configure Flyway
4. **Phase 4 - Services**: Convert EJB beans to CDI, replace MDBs with reactive messaging, remove WebLogic code
5. **Phase 5 - REST API**: Update JAX-RS endpoints to Jakarta, fix session handling
6. **Phase 6 - Utils**: Convert startup listener to Quarkus lifecycle, update Flyway integration
7. **Phase 7 - Configuration**: Update XML namespace configurations
8. **Phase 8 - Frontend**: Move static content to META-INF/resources, convert JSPs to static HTML
9. **Phase 9 - Cleanup**: Delete obsolete files (WebLogic stubs, Java EE descriptors)

## Steps

### Step 1: Convert Maven build to Quarkus
- Phase: Build Config
- File: pom.xml
- Action: MODIFY
- What to do:
    - BEFORE: Java EE 7 dependencies (javax:javaee-web-api, javax:javaee-api, jboss-jms-api, jboss-rmi-api), war packaging, Java 8
    - AFTER: Quarkus 3.x BOM, quarkus-maven-plugin, Jakarta EE 10 dependencies
    - Specific changes:
        1. Add Quarkus BOM (io.quarkus.platform:quarkus-bom:3.8.0)
        2. Replace javax dependencies with Quarkus extensions:
           - quarkus-hibernate-orm
           - quarkus-jdbc-h2
           - quarkus-resteasy-jackson
           - quarkus-smallrye-reactive-messaging
           - quarkus-flyway
           - quarkus-arc
        3. Add quarkus-maven-plugin
        4. Update Java version from 1.8 to 17
        5. Remove system-scoped audit-logging-library dependency (incompatible)
        6. Remove jboss-rmi-api dependency (EJB Remote not needed)
- Why: Quarkus uses different dependency management and build process
- Depends on: none
- Verify: `mvn quarkus:dev` starts without dependency resolution errors

### Step 2: Create Quarkus application.properties
- Phase: Build Config
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with:
    ```properties
    # Datasource - H2 in-memory (per human decision)
    quarkus.datasource.db-kind=h2
    quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstoredb;DB_CLOSE_DELAY=-1
    quarkus.datasource.username=sa
    quarkus.datasource.password=
    
    # Hibernate
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.log.sql=false
    
    # Flyway
    quarkus.flyway.migrate-at-start=true
    quarkus.flyway.baseline-on-migrate=true
    
    # Reactive Messaging - in-memory connector for orders topic
    mp.messaging.incoming.orders.connector=smallrye-in-memory
    mp.messaging.outgoing.orders-out.connector=smallrye-in-memory
    mp.messaging.outgoing.orders-out.merge=true
    ```
- Why: Quarkus configures datasource, JPA, and messaging via properties instead of XML
- Depends on: Step 1
- Verify: File exists with required properties

### Step 3: Migrate CatalogItemEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax.persistence imports remain

### Step 4: Migrate InventoryEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace `javax.persistence` and `javax.xml` imports with `jakarta.persistence` and `jakarta.xml`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 5: COMPLEX — Migrate Order entity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
    - BEFORE: `javax.persistence` imports, `@GeneratedValue` without explicit strategy
    - AFTER: `jakarta.persistence` imports, explicit sequence generator
    - Specific changes:
        1. Replace all `javax.persistence` imports with `jakarta.persistence`
        2. Change `@GeneratedValue` to `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")`
        3. Add `@SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 1)`
- Why: Hibernate 6 changed implicit sequence naming; explicit generator ensures compatibility
- Depends on: Step 1
- Verify: No javax imports remain; entity compiles with explicit sequence

### Step 6: COMPLEX — Migrate OrderItem entity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
    - BEFORE: `javax.persistence` imports, `@GeneratedValue` without explicit strategy
    - AFTER: `jakarta.persistence` imports, explicit sequence generator
    - Specific changes:
        1. Replace all `javax.persistence` imports with `jakarta.persistence`
        2. Change `@GeneratedValue` to `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orderitem_seq")`
        3. Add `@SequenceGenerator(name = "orderitem_seq", sequenceName = "orderitem_seq", allocationSize = 1)`
- Why: Hibernate 6 changed implicit sequence naming
- Depends on: Step 1
- Verify: No javax imports remain

### Step 7: Migrate Product model
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: No javax imports to change (model is plain POJO)
- Why: Verify model is clean
- Depends on: Step 1
- Verify: Compiles without changes

### Step 8: Migrate Promotion model
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: No javax imports to change (model is plain POJO)
- Why: Verify model is clean
- Depends on: Step 1
- Verify: Compiles without changes

### Step 9: Migrate ShoppingCart model
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace `javax.enterprise` import with `jakarta.enterprise`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 10: Migrate ShoppingCartItem model
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: No javax imports to change (model is plain POJO)
- Why: Verify model is clean
- Depends on: Step 1
- Verify: Compiles without changes

### Step 11: COMPLEX — Remove Resources EntityManager producer
- Phase: Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - BEFORE: CDI producer that creates EntityManager via @PersistenceContext
    - AFTER: Empty class or delete (Quarkus injects EntityManager directly)
    - Specific changes:
        1. Replace `javax.enterprise` and `javax.persistence` imports with `jakarta` equivalents
        2. Remove @Produces method - Quarkus provides EntityManager directly
        3. Keep class with @Dependent annotation for potential future use, or mark for deletion
- Why: Quarkus handles EntityManager injection automatically via quarkus-hibernate-orm
- Depends on: Step 1, Step 2
- Verify: No compilation errors; services still receive EntityManager

### Step 12: COMPLEX — Migrate CatalogService from EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB, `javax` imports
    - AFTER: `@ApplicationScoped` CDI bean, `jakarta` imports
    - Specific changes:
        1. Replace `javax.inject` with `jakarta.inject`
        2. Replace `javax.persistence` with `jakarta.persistence`
        3. Replace `javax.ejb.Stateless` with `jakarta.enterprise.context.ApplicationScoped`
        4. Add `@Transactional` annotation for write methods (updateInventoryItems)
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1, Step 11
- Verify: No EJB imports remain; class is @ApplicationScoped

### Step 13: COMPLEX — Convert InventoryNotificationMDB to reactive messaging
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Manual JMS with WebLogic JNDI factory, TopicSubscriber
    - AFTER: SmallRye Reactive Messaging @Incoming
    - Specific changes:
        1. Remove all JMS imports and WebLogic JNDI code
        2. Replace `javax.inject` with `jakarta.inject`
        3. Remove implements MessageListener
        4. Remove init(), close(), getInitialContext() methods
        5. Replace onMessage with:
           ```java
           @Incoming("orders")
           public CompletionStage<Void> processOrder(String orderJson) {
               // process order
               return CompletableFuture.completedFuture(null);
           }
           ```
        6. Add `@ApplicationScoped` annotation
- Why: Quarkus uses SmallRye Reactive Messaging instead of JMS MDBs
- Depends on: Step 1, Step 2
- Verify: No JMS imports remain; @Incoming annotation present

### Step 14: COMPLEX — Migrate OrderService from EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB, `javax` imports
    - AFTER: `@ApplicationScoped` CDI bean, `jakarta` imports, `@Transactional`
    - Specific changes:
        1. Replace `javax.annotation` with `jakarta.annotation`
        2. Replace `javax.ejb.Stateless` with `jakarta.enterprise.context.ApplicationScoped`
        3. Replace `javax.inject` with `jakarta.inject`
        4. Replace `javax.persistence` with `jakarta.persistence`
        5. Add `@Transactional` on methods that modify data
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1
- Verify: No EJB imports remain

### Step 15: COMPLEX — Convert OrderServiceMDB to reactive messaging
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: `@MessageDriven` EJB MDB with JMS MessageListener
    - AFTER: CDI bean with SmallRye `@Incoming`
    - Specific changes:
        1. Remove `@MessageDriven` and all activation config
        2. Remove `javax.ejb` and `javax.jms` imports
        3. Replace `javax.inject` with `jakarta.inject`
        4. Add `@ApplicationScoped` annotation
        5. Replace `onMessage(Message)` with:
           ```java
           @Incoming("orders")
           @Transactional
           public CompletionStage<Void> onMessage(String orderJson) {
               Order order = Transformers.jsonToOrder(orderJson);
               orderService.save(order);
               order.getItemList().forEach(orderItem -> {
                   catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
               });
               return CompletableFuture.completedFuture(null);
           }
           ```
- Why: Quarkus uses SmallRye Reactive Messaging instead of JMS MDBs
- Depends on: Step 1, Step 2
- Verify: No JMS/EJB imports remain; @Incoming annotation present

### Step 16: COMPLEX — Migrate ProductService from EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB, `javax` imports
    - AFTER: `@ApplicationScoped` CDI bean, `jakarta` imports
    - Specific changes:
        1. Replace `javax.ejb.Stateless` with `jakarta.enterprise.context.ApplicationScoped`
        2. Replace `javax.inject` with `jakarta.inject`
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 1
- Verify: No EJB imports remain

### Step 17: Migrate PromoService from EJB to CDI
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - BEFORE: `javax.enterprise` imports
    - AFTER: `jakarta.enterprise` imports
    - Specific changes:
        1. Replace `javax.enterprise` with `jakarta.enterprise`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 18: COMPLEX — Migrate ShippingService - remove EJB Remote
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless @Remote` EJB implementing ShippingServiceRemote
    - AFTER: `@ApplicationScoped` CDI bean, no remote interface
    - Specific changes:
        1. Remove `@Remote` annotation
        2. Remove `implements ShippingServiceRemote`
        3. Replace `javax.ejb.Remote` and `javax.ejb.Stateless` with `jakarta.enterprise.context.ApplicationScoped`
- Why: Quarkus does not support EJB Remote; use direct CDI injection
- Depends on: Step 1
- Verify: No EJB imports remain; implements clause removed

### Step 19: Delete ShippingServiceRemote interface
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this file — EJB Remote interfaces not supported in Quarkus
- Why: Quarkus does not support EJB Remote; callers inject ShippingService directly
- Depends on: Step 18
- Verify: File no longer exists

### Step 20: COMPLEX — Migrate ShoppingCartOrderProcessor - JMS to reactive messaging
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with `@Resource` JMS Topic and JMSContext
    - AFTER: `@ApplicationScoped` CDI bean with `@Channel` Emitter
    - Specific changes:
        1. Remove `javax.ejb.Stateless` and `javax.annotation.Resource`
        2. Remove `javax.jms` imports
        3. Replace `javax.inject` with `jakarta.inject`
        4. Add `jakarta.enterprise.context.ApplicationScoped`
        5. Replace JMSContext and Topic with:
           ```java
           @Inject
           @Channel("orders-out")
           Emitter<String> ordersEmitter;
           ```
        6. Replace `context.createProducer().send(...)` with:
           ```java
           ordersEmitter.send(Transformers.shoppingCartToJson(cart));
           ```
- Why: Quarkus uses SmallRye Reactive Messaging instead of JMS
- Depends on: Step 1, Step 2
- Verify: No JMS imports remain; Emitter injection present

### Step 21: COMPLEX — Migrate ShoppingCartService - remove JNDI lookup
- Phase: Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateful` EJB with JNDI lookup for ShippingServiceRemote
    - AFTER: `@ApplicationScoped` CDI bean with direct ShippingService injection
    - Specific changes:
        1. Replace `javax.ejb.Stateful` with `jakarta.enterprise.context.ApplicationScoped`
        2. Replace `javax.inject` and `javax.naming` with `jakarta.inject`
        3. Remove JNDI imports and lookupShippingServiceRemote() method
        4. Add `@Inject ShippingService shippingService;`
        5. Replace `lookupShippingServiceRemote()` calls with `shippingService`
        6. Add `private Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();` for cart storage
        7. Update getShoppingCart to use the map
- Why: Quarkus does not support EJB Remote/JNDI; use CDI injection
- Depends on: Step 1, Step 18, Step 19
- Verify: No EJB/JNDI imports remain; ShippingService injected directly

### Step 22: COMPLEX — Migrate DataBaseMigrationStartup - use Quarkus Flyway
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Singleton @Startup` EJB with manual Flyway execution
    - AFTER: Delete or empty - Quarkus runs Flyway automatically via extension
    - Specific changes:
        1. Remove all EJB annotations (@Singleton, @Startup, @TransactionManagement)
        2. Remove @Resource DataSource injection
        3. Remove @PostConstruct startup() method
        4. Class can be deleted or converted to empty placeholder
- Why: Quarkus Flyway extension handles migration automatically via application.properties
- Depends on: Step 1, Step 2
- Verify: Class empty or deleted; Flyway runs via Quarkus extension

### Step 23: Migrate Producers utility
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do: Replace `javax.enterprise` imports with `jakarta.enterprise`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 24: COMPLEX — Migrate StartupListener - remove WebLogic dependency
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE: Extends WebLogic ApplicationLifecycleListener
    - AFTER: Uses Quarkus StartupEvent
    - Specific changes:
        1. Remove `weblogic.application` imports
        2. Remove extends ApplicationLifecycleListener
        3. Replace `javax.inject` with `jakarta.inject`
        4. Add `@ApplicationScoped` annotation
        5. Replace postStart/preStop with:
           ```java
           void onStart(@Observes StartupEvent ev) {
               log.info("Application started");
           }
           void onStop(@Observes ShutdownEvent ev) {
               log.info("Application stopping");
           }
           ```
- Why: WebLogic lifecycle listeners not supported; use Quarkus CDI events
- Depends on: Step 1
- Verify: No weblogic imports remain; Quarkus events used

### Step 25: Migrate Transformers utility
- Phase: Utils
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace `javax.json` imports with `jakarta.json`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 26: COMPLEX — Migrate CartEndpoint - fix session scope
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - BEFORE: `@SessionScoped` JAX-RS endpoint
    - AFTER: `@ApplicationScoped` with cart lookup by ID
    - Specific changes:
        1. Replace `javax.enterprise` with `jakarta.enterprise`
        2. Replace `javax.inject` with `jakarta.inject`
        3. Replace `javax.ws` with `jakarta.ws`
        4. Change `@SessionScoped` to `@ApplicationScoped`
        5. Remove `implements Serializable`
- Why: Quarkus REST endpoints typically use @ApplicationScoped; cart state managed by ShoppingCartService
- Depends on: Step 1, Step 21
- Verify: No javax imports remain; @ApplicationScoped annotation

### Step 27: Migrate OrderEndpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace `javax.enterprise`, `javax.inject`, `javax.ws` imports with jakarta equivalents
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 28: Migrate ProductEndpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace `javax.enterprise`, `javax.inject`, `javax.ws` imports with jakarta equivalents
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 29: Migrate RestApplication
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do: Replace `javax.ws` imports with `jakarta.ws`
- Why: Jakarta EE 10 uses jakarta namespace
- Depends on: Step 1
- Verify: No javax imports remain

### Step 30: Update persistence.xml namespace
- Phase: Configuration
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do:
    - Replace `http://xmlns.jcp.org/xml/ns/persistence` with `https://jakarta.ee/xml/ns/persistence`
    - Replace `persistence_2_1.xsd` with `persistence_3_0.xsd`
    - Update version attribute from `2.1` to `3.0`
    - Remove jta-data-source element (Quarkus configures via properties)
    - Replace `javax.persistence` property names with standard Hibernate ones
- Why: Jakarta EE 10 requires updated namespace and schema
- Depends on: Step 1, Step 2
- Verify: File uses jakarta namespace and version 3.0

### Step 31: Update beans.xml namespace
- Phase: Configuration
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do:
    - Replace `http://xmlns.jcp.org/xml/ns/javaee` with `https://jakarta.ee/xml/ns/jakartaee`
    - Replace `beans_1_1.xsd` with `beans_3_0.xsd`
    - Update version if present to `3.0`
- Why: Jakarta EE 10 requires updated namespace
- Depends on: Step 1
- Verify: File uses jakarta namespace

### Step 32: Move beans.xml to META-INF
- Phase: Configuration
- File: src/main/resources/META-INF/beans.xml
- Action: CREATE
- What to do: Copy updated beans.xml from src/main/webapp/WEB-INF/ to src/main/resources/META-INF/
- Why: Quarkus reads beans.xml from META-INF in resources, not WEB-INF
- Depends on: Step 31
- Verify: beans.xml exists in src/main/resources/META-INF/

### Step 33: Create static index.html from index.jsp
- Phase: Frontend
- File: src/main/resources/META-INF/resources/index.html
- Action: CREATE
- What to do: Convert index.jsp to static index.html:
    - Remove `<% request.getSession(true); %>` JSP scriptlet
    - Keep all HTML, CSS, and JavaScript references
    - Update paths from `/bower_components/` to `bower_components/` (relative)
    - Update paths from `/app/` to `app/` (relative)
    - Update paths from `/partials/` to `partials/` (relative)
- Why: Quarkus serves static content from META-INF/resources; JSP not supported
- Depends on: Step 1
- Verify: index.html is valid HTML without JSP tags

### Step 34: Create static health.html
- Phase: Frontend
- File: src/main/resources/META-INF/resources/health.html
- Action: CREATE
- What to do: Create simple health page returning OK or use Quarkus health extension
- Why: JSP not supported; simple health check page
- Depends on: Step 1
- Verify: health.html exists

### Step 35: Move app directory to META-INF/resources
- Phase: Frontend
- File: src/main/resources/META-INF/resources/app
- Action: CREATE
- What to do: Copy entire src/main/webapp/app/ directory to src/main/resources/META-INF/resources/app/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All JS, CSS, and image files accessible at /app/*

### Step 36: Move partials directory to META-INF/resources
- Phase: Frontend
- File: src/main/resources/META-INF/resources/partials
- Action: CREATE
- What to do: Copy entire src/main/webapp/partials/ directory to src/main/resources/META-INF/resources/partials/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: HTML partials accessible at /partials/*

### Step 37: Move bower_components to META-INF/resources
- Phase: Frontend
- File: src/main/resources/META-INF/resources/bower_components
- Action: CREATE
- What to do: Copy entire src/main/webapp/bower_components/ directory to src/main/resources/META-INF/resources/bower_components/
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: All vendor JS/CSS files accessible at /bower_components/*

### Step 38: Move coolstore.json to META-INF/resources
- Phase: Frontend
- File: src/main/resources/META-INF/resources/coolstore.json
- Action: CREATE
- What to do: Copy src/main/webapp/coolstore.json to src/main/resources/META-INF/resources/coolstore.json
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: coolstore.json accessible at /coolstore.json

### Step 39: Move keycloak.json to META-INF/resources
- Phase: Frontend
- File: src/main/resources/META-INF/resources/keycloak.json
- Action: CREATE
- What to do: Copy src/main/webapp/keycloak.json to src/main/resources/META-INF/resources/keycloak.json
- Why: Quarkus serves static content from META-INF/resources
- Depends on: Step 1
- Verify: keycloak.json accessible at /keycloak.json

### Step 40: Update Flyway migration script for H2
- Phase: Configuration
- File: src/main/resources/db/migration/V1_1__CreateSchema.sql
- Action: MODIFY
- What to do: Update PostgreSQL-specific syntax to H2-compatible:
    - Change `int4` to `int`
    - Change `int8` to `bigint`
    - Change `float8` to `double`
    - Change `text` to `clob` or `varchar(4000)`
    - Ensure sequence syntax is H2-compatible
- Why: H2 database (per human decision) has different SQL dialect than PostgreSQL
- Depends on: Step 1, Step 2
- Verify: Schema script runs without errors on H2

### Step 41: Delete WebLogic ApplicationLifecycleEvent stub
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file — WebLogic stub no longer needed
- Why: WebLogic code removed; Quarkus uses CDI events
- Depends on: Step 24
- Verify: File no longer exists

### Step 42: Delete WebLogic ApplicationLifecycleListener stub
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file — WebLogic stub no longer needed
- Why: WebLogic code removed; Quarkus uses CDI events
- Depends on: Step 24
- Verify: File no longer exists

### Step 43: Delete WebLogic NonCatalogLogger stub
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file — WebLogic stub no longer needed
- Why: WebLogic logging not used; Quarkus uses JBoss Logging / JUL
- Depends on: Step 24
- Verify: File no longer exists

### Step 44: Delete weblogic package directory
- Phase: Cleanup
- File: src/main/java/weblogic
- Action: DELETE
- What to do: Delete entire weblogic directory after stub files removed
- Why: All WebLogic code migrated or removed
- Depends on: Step 41, Step 42, Step 43
- Verify: Directory no longer exists

### Step 45: Delete old webapp WEB-INF directory
- Phase: Cleanup
- File: src/main/webapp/WEB-INF
- Action: DELETE
- What to do: Delete entire WEB-INF directory (beans.xml moved, web.xml not needed)
- Why: Quarkus does not use WEB-INF; configuration moved to resources
- Depends on: Step 31, Step 32
- Verify: Directory no longer exists

### Step 46: Delete old index.jsp
- Phase: Cleanup
- File: src/main/webapp/index.jsp
- Action: DELETE
- What to do: Delete this file — replaced by static index.html
- Why: JSP not supported in Quarkus; converted to static HTML
- Depends on: Step 33
- Verify: File no longer exists

### Step 47: Delete old health.jsp
- Phase: Cleanup
- File: src/main/webapp/health.jsp
- Action: DELETE
- What to do: Delete this file — replaced by static health.html
- Why: JSP not supported in Quarkus
- Depends on: Step 34
- Verify: File no longer exists

### Step 48: Delete old webapp app directory
- Phase: Cleanup
- File: src/main/webapp/app
- Action: DELETE
- What to do: Delete after content moved to META-INF/resources/app
- Why: Static content now served from META-INF/resources
- Depends on: Step 35
- Verify: Directory no longer exists

### Step 49: Delete old webapp partials directory
- Phase: Cleanup
- File: src/main/webapp/partials
- Action: DELETE
- What to do: Delete after content moved to META-INF/resources/partials
- Why: Static content now served from META-INF/resources
- Depends on: Step 36
- Verify: Directory no longer exists

### Step 50: Delete old webapp bower_components directory
- Phase: Cleanup
- File: src/main/webapp/bower_components
- Action: DELETE
- What to do: Delete after content moved to META-INF/resources/bower_components
- Why: Static content now served from META-INF/resources
- Depends on: Step 37
- Verify: Directory no longer exists

### Step 51: Delete old webapp coolstore.json
- Phase: Cleanup
- File: src/main/webapp/coolstore.json
- Action: DELETE
- What to do: Delete after moved to META-INF/resources
- Why: Static content now served from META-INF/resources
- Depends on: Step 38
- Verify: File no longer exists

### Step 52: Delete old webapp keycloak.json
- Phase: Cleanup
- File: src/main/webapp/keycloak.json
- Action: DELETE
- What to do: Delete after moved to META-INF/resources
- Why: Static content now served from META-INF/resources
- Depends on: Step 39
- Verify: File no longer exists

### Step 53: Delete old webapp directory
- Phase: Cleanup
- File: src/main/webapp
- Action: DELETE
- What to do: Delete entire webapp directory after all content migrated
- Why: Quarkus uses META-INF/resources for static content
- Depends on: Step 45, Step 46, Step 47, Step 48, Step 49, Step 50, Step 51, Step 52
- Verify: Directory no longer exists

### Step 54: Delete lib directory with audit-logging jars
- Phase: Cleanup
- File: lib
- Action: DELETE
- What to do: Delete lib directory containing system-scoped JARs
- Why: System-scoped dependencies incompatible with Quarkus; remove or replace with proper Maven dependency
- Depends on: Step 1
- Verify: Directory no longer exists

## Verification
- Build: `mvn clean compile`
- Test: (no tests in project)
- Blackbox: 
    1. Start application: `mvn quarkus:dev`
    2. Open http://localhost:8080 in browser
    3. Verify product catalog loads on home page
    4. Add item to cart and verify cart updates
    5. Checkout and verify order processing (check logs for message handling)

## Notes
- **H2 Database**: Per human decision, using H2 in-memory database. Data resets on each restart. For production, configure PostgreSQL in application.properties.
- **JMS to Reactive Messaging**: The two MDBs (OrderServiceMDB, InventoryNotificationMDB) both subscribe to the same "orders" topic. In SmallRye Reactive Messaging, both will receive messages via the in-memory connector.
- **Session State**: Original CartEndpoint was @SessionScoped. Since Quarkus RESTEasy does not support session scope the same way, cart state is maintained by ShoppingCartService using a map keyed by cartId.
- **EJB Remote Removal**: ShippingService was exposed via EJB Remote interface and looked up via JNDI. This is replaced with direct CDI injection.
- **Audit Library**: The system-scoped audit-logging-library dependency is removed. If needed, publish to a Maven repository or inline the required functionality.
- **Flyway**: The manual Flyway startup bean is removed. Quarkus Flyway extension runs migrations automatically based on application.properties configuration.
