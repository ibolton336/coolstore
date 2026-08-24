# Java EE 7 to Quarkus 3 Migration Plan

## Application Overview

The **coolstore-monolith** is a Java EE 7 WAR application that provides a shopping cart and product catalog service. It uses:
- **JAX-RS** REST endpoints at `/services` base path
- **EJB 3.x** (Stateless, Stateful, MDB) for business logic
- **JPA 2.1** with Hibernate for persistence
- **JMS** with Topics for async messaging (order processing)
- **CDI** for dependency injection
- **Flyway** for database migration
- **WebLogic-specific** lifecycle listeners and JNDI lookups

The application currently packages as a WAR for deployment on an application server and must be migrated to Quarkus 3 as a standalone JAR application.

---

## Messaging Topology

The original application uses JMS Topics for asynchronous order processing. This topology must be preserved in the Quarkus migration using Eclipse MicroProfile Reactive Messaging.

| Producer | Broker Address | Consumers | Message Type | Purpose |
|----------|----------------|-----------|--------------|---------|
| `ShoppingCartOrderProcessor.process()` | `java:/topic/orders` → `topic/orders` | `OrderServiceMDB.onMessage()`, `InventoryNotificationMDB.onMessage()` | TextMessage (JSON) | Order checkout processing - Topic allows fan-out to multiple consumers |

**Topic Fan-Out Details:**
- **Producer**: `ShoppingCartOrderProcessor` sends order as JSON to `topic/orders`
- **Consumer 1**: `OrderServiceMDB` persists order to database and updates inventory quantities
- **Consumer 2**: `InventoryNotificationMDB` monitors inventory levels and logs warnings when below threshold (50)

**Quarkus Implementation:**
- Use **SmallRye Reactive Messaging** with in-memory connector for topic semantics
- Producer uses `@Channel` + `Emitter<String>` to publish messages
- Consumers use `@Incoming` with `broadcast=true` to receive all messages from topic

---

## Source Directory Disposition (src/main/webapp)

Quarkus uses JAR packaging, not WAR. The `src/main/webapp` directory contains:

**Content Analysis:**
- `WEB-INF/web.xml` - Application server deployment descriptor
- `WEB-INF/beans.xml` - CDI configuration
- `index.jsp`, `health.jsp` - JSP pages
- `coolstore.json`, `keycloak.json` - Configuration files
- `app/`, `partials/`, `bower_components/` - AngularJS frontend application

**Disposition:**
- **Static Resources**: Move `app/`, `partials/`, `bower_components/`, `*.json` to `src/main/resources/META-INF/resources/` for Quarkus static resource serving
- **JSP Files**: Convert `health.jsp` to Quarkus Health Check endpoint; `index.jsp` to static HTML or eliminate if frontend is served separately
- **WEB-INF/web.xml**: Delete (not used in Quarkus)
- **WEB-INF/beans.xml**: Delete (Quarkus uses different CDI discovery modes)

**Rationale**: Quarkus serves static content from `META-INF/resources` classpath location. The AngularJS SPA frontend can be served as static resources from the JAR, maintaining the monolithic deployment model while using JAR packaging.

---

## Migration Phases

### Phase 1: Build Configuration

**Objective**: Convert Maven WAR project to Quarkus JAR project with appropriate dependencies

**Changes Required:**

1. **POM Transformation**
   - Change `<packaging>war</packaging>` → `<packaging>jar</packaging>` (Konveyor: packaging change required)
   - Remove Java EE 7 dependencies (`javaee-web-api`, `javaee-api`)
   - Add Quarkus BOM and core extensions:
     - `io.quarkus:quarkus-bom` (import in dependencyManagement)
     - `io.quarkus:quarkus-arc` (CDI)
     - `io.quarkus:quarkus-resteasy-reactive-jackson` (JAX-RS + JSON)
     - `io.quarkus:quarkus-hibernate-orm-panache` or `quarkus-hibernate-orm` (JPA)
     - `io.quarkus:quarkus-jdbc-postgresql` or `quarkus-jdbc-h2` (JDBC driver)
     - `io.quarkus:quarkus-smallrye-reactive-messaging` (Reactive Messaging for JMS replacement)
     - `io.quarkus:quarkus-smallrye-reactive-messaging-in-memory` (In-memory connector for topic semantics)
     - `io.quarkus:quarkus-flyway` (Database migration)
   - Remove JMS-specific dependencies (`jboss-jms-api_2.0_spec`)
   - Update Flyway dependency to compatible version (5.x or later)
   - Configure `quarkus-maven-plugin` for build and dev mode
   - Handle system-scoped audit library dependency:
     - Option A: Install to local Maven repo and reference normally
     - Option B: Use `quarkus.class-loading.parent-first-artifacts` property
     - Option C: Convert to regular dependency if JAR is Maven-compatible

2. **Compiler Configuration**
   - Update Java version from 1.8 to 11 or 17 (Quarkus 3 minimum)
   - Update maven-compiler-plugin to 3.11+

**Files to Modify:**
- `pom.xml`

**Konveyor Issues Addressed:**
- Maven XML configuration changes

---

### Phase 2: Application Configuration

**Objective**: Replace Java EE XML configuration and JNDI resources with Quarkus `application.properties`

**Changes Required:**

1. **Datasource Configuration** (Konveyor: `jndi-to-quarkus-00002`, `jndi-to-quarkus-00001`)
   - Remove `META-INF/persistence.xml` JNDI datasource reference (`java:jboss/datasources/CoolstoreDS`)
   - Configure datasource in `application.properties`:
     ```properties
     quarkus.datasource.db-kind=postgresql
     quarkus.datasource.username=${DB_USERNAME:coolstore}
     quarkus.datasource.password=${DB_PASSWORD:coolstore}
     quarkus.datasource.jdbc.url=${DB_URL:jdbc:postgresql://localhost:5432/coolstore}
     quarkus.hibernate-orm.database.generation=none
     quarkus.hibernate-orm.log.sql=false
     quarkus.flyway.migrate-at-start=true
     ```
   - Remove `persistence.xml` or strip it to minimal entity listing

2. **JAX-RS Path Configuration** (Konveyor: `jaxrs-to-quarkus-00020`)
   - Remove `RestApplication` class extending `Application` (optional in Quarkus)
   - Set base path in `application.properties`:
     ```properties
     quarkus.resteasy.path=/services
     ```
   - **CRITICAL**: Must preserve `/services` base path for API compatibility

3. **Reactive Messaging Configuration** (Konveyor: `jms-to-reactive-quarkus-00020`, `00040`, `00050`)
   - Configure in-memory topic for order processing:
     ```properties
     mp.messaging.outgoing.orders.connector=smallrye-in-memory
     mp.messaging.incoming.orders-processor.connector=smallrye-in-memory
     mp.messaging.incoming.orders-inventory.connector=smallrye-in-memory
     mp.messaging.incoming.orders-processor.broadcast=true
     mp.messaging.incoming.orders-inventory.broadcast=true
     ```

4. **Hibernate Sequence Configuration** (Konveyor: `hibernate-00005`)
   - Add to `application.properties` to handle sequence naming changes:
     ```properties
     quarkus.hibernate-orm.database.generation=none
     ```
   - Ensure Flyway scripts create entity-specific sequences (`order_seq`, `order_item_seq`)

5. **Flyway Configuration**
   - Configure Flyway for Quarkus:
     ```properties
     quarkus.flyway.migrate-at-start=true
     quarkus.flyway.baseline-on-migrate=true
     quarkus.flyway.locations=classpath:db/migration
     ```

6. **CDI Configuration** (Konveyor: `cdi-to-quarkus-00030`)
   - Delete `WEB-INF/beans.xml` (Quarkus auto-discovers beans)
   - Ensure all CDI beans have appropriate scope annotations

**Files to Create:**
- `src/main/resources/application.properties`

**Files to Modify:**
- `src/main/resources/META-INF/persistence.xml` (simplify or delete)

**Files to Delete:**
- `src/main/webapp/WEB-INF/web.xml`
- `src/main/webapp/WEB-INF/beans.xml`

**Konveyor Issues Addressed:**
- `jndi-to-quarkus-00001` (InitialContext not supported)
- `jndi-to-quarkus-00002` (JNDI lookup() not supported)
- `jaxrs-to-quarkus-00020` (JAX-RS activation)
- `cdi-to-quarkus-00030` (beans.xml ignored)
- `hibernate-00005` (sequence naming)

---

### Phase 3: EJB to CDI Conversion

**Objective**: Convert EJB beans to CDI beans with appropriate scoping and transaction management

**Changes Required:**

1. **Stateless Session Beans** (Konveyor: `ee-to-quarkus-00000`, `ee-to-quarkus-00020`)
   
   **Files to Modify:**
   - `com.redhat.coolstore.service.CatalogService`
   - `com.redhat.coolstore.service.OrderService`
   - `com.redhat.coolstore.service.ProductService`
   - `com.redhat.coolstore.service.ShoppingCartOrderProcessor`
   - `com.redhat.coolstore.service.ShippingService`
   
   **Changes:**
   - Remove `@Stateless` annotation
   - Add `@ApplicationScoped` annotation
   - Add `@Transactional` annotation to methods that modify data:
     - `OrderService.save()`
     - `CatalogService.updateInventoryItems()`
   - Remove `@Remote` annotation from `ShippingService` (Konveyor: `remote-ejb-to-quarkus-00000`)
   - Remove `ShippingServiceRemote` interface or keep as plain interface
   - Convert JNDI lookup in `ShoppingCartService.lookupShippingServiceRemote()` to CDI `@Inject`

2. **Stateful Session Bean** (Konveyor: `ee-to-quarkus-00010`)
   
   **File to Modify:**
   - `com.redhat.coolstore.service.ShoppingCartService`
   
   **Changes:**
   - Remove `@Stateful` annotation
   - Evaluate stateful semantics: Shopping cart state is per-user session
   - Since `CartEndpoint` uses `@SessionScoped`, keep `ShoppingCartService` as `@ApplicationScoped` and rely on endpoint scope
   - Alternative: Make service `@SessionScoped` if it truly needs per-session state
   - Add `@Transactional` if methods persist data (currently none)
   - Convert JNDI lookups to CDI injection:
     - Remove `lookupShippingServiceRemote()` method
     - Add `@Inject ShippingService shippingService` field
     - Replace `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`

3. **Singleton Startup Bean** (Konveyor: lifecycle management)
   
   **File to Modify:**
   - `com.redhat.coolstore.utils.DataBaseMigrationStartup`
   
   **Changes:**
   - Remove `@Singleton`, `@Startup`, `@TransactionManagement` annotations
   - Remove entire class - Flyway migration will be handled by Quarkus extension automatically
   - Flyway Quarkus extension runs migrations at startup when `quarkus.flyway.migrate-at-start=true`
   - Remove `@Resource` datasource injection
   - Delete class completely

4. **Producer Methods** (Konveyor: `cdi-to-quarkus-00040`)
   
   **Files to Modify:**
   - `com.redhat.coolstore.persistence.Resources`
   - `com.redhat.coolstore.utils.Producers`
   
   **Changes:**
   - Option A: Keep `@Produces` annotations (still valid in Quarkus)
   - Option B: Remove `@Produces` and add scope/qualifier annotations
   - For `EntityManager` producer in `Resources`: Keep as-is or use `@PersistenceContext`
   - For `Logger` producer in `Producers`: Keep as-is (Quarkus supports Logger injection)

**Konveyor Issues Addressed:**
- `ee-to-quarkus-00000` (@Stateless replacement)
- `ee-to-quarkus-00010` (@Stateful replacement)
- `ee-to-quarkus-00020` (@Transactional requirement)
- `remote-ejb-to-quarkus-00000` (Remote EJB not supported)
- `cdi-to-quarkus-00040` (@Produces annotation)

---

### Phase 4: Messaging (JMS to Reactive Messaging)

**Objective**: Replace JMS Topics with MicroProfile Reactive Messaging channels preserving fan-out topology

**Changes Required:**

1. **Message Producer** (Konveyor: `jms-to-reactive-quarkus-00040`, `00050`)
   
   **File to Modify:**
   - `com.redhat.coolstore.service.ShoppingCartOrderProcessor`
   
   **Changes:**
   - Remove `@Resource(lookup = "java:/topic/orders")` Topic injection
   - Remove `@Inject JMSContext` injection
   - Add Reactive Messaging imports:
     ```java
     import org.eclipse.microprofile.reactive.messaging.Channel;
     import org.eclipse.microprofile.reactive.messaging.Emitter;
     import javax.inject.Inject;
     ```
   - Inject message emitter:
     ```java
     @Inject
     @Channel("orders")
     Emitter<String> ordersEmitter;
     ```
   - Replace JMS send logic:
     ```java
     // OLD: context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
     // NEW:
     ordersEmitter.send(Transformers.shoppingCartToJson(cart));
     ```
   - Keep `@ApplicationScoped` annotation (converted from `@Stateless`)

2. **Message Consumer 1: Order Processing** (Konveyor: `jms-to-reactive-quarkus-00010`, `00020`, `00050`)
   
   **File to Modify:**
   - `com.redhat.coolstore.service.OrderServiceMDB`
   
   **Changes:**
   - Remove `@MessageDriven` annotation and activation config properties
   - Remove `implements MessageListener`
   - Add `@ApplicationScoped` annotation
   - Rename `onMessage(Message rcvMessage)` to `processOrder(String orderJson)`
   - Add `@Incoming` annotation:
     ```java
     @Incoming("orders-processor")
     public void processOrder(String orderJson) {
         // Processing logic
     }
     ```
   - Simplify message handling:
     ```java
     // OLD: Extract String from TextMessage
     // NEW: Direct String parameter
     Order order = Transformers.jsonToOrder(orderJson);
     orderService.save(order);
     order.getItemList().forEach(orderItem -> {
         catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
     });
     ```
   - Remove all JMS imports
   - Add `@Transactional` annotation to method

3. **Message Consumer 2: Inventory Notification** (Konveyor: `jms-to-reactive-quarkus-00010`, `00020`, `00050`)
   
   **File to Modify:**
   - `com.redhat.coolstore.service.InventoryNotificationMDB`
   
   **Changes:**
   - Remove `implements MessageListener`
   - Add `@ApplicationScoped` annotation
   - Remove WebLogic JNDI initialization code (`init()`, `close()`, `getInitialContext()`)
   - Rename `onMessage(Message rcvMessage)` to `checkInventory(String orderJson)`
   - Add `@Incoming` annotation:
     ```java
     @Incoming("orders-inventory")
     public void checkInventory(String orderJson) {
         // Inventory check logic
     }
     ```
   - Simplify message handling (same as OrderServiceMDB)
   - Remove all JNDI, JMS, WebLogic imports
   - Keep catalog service injection

**Konveyor Issues Addressed:**
- `jms-to-reactive-quarkus-00010` (@MessageDriven not supported)
- `jms-to-reactive-quarkus-00020` (@Incoming configuration)
- `jms-to-reactive-quarkus-00040` (Topic replacement with Emitter)
- `jms-to-reactive-quarkus-00050` (JMS not supported)
- `jndi-to-quarkus-00001` (InitialContext in InventoryNotificationMDB)
- `jndi-to-quarkus-00002` (JNDI lookups in InventoryNotificationMDB)

---

### Phase 5: Lifecycle & Cleanup

**Objective**: Handle lifecycle listeners, resource cleanup, and remaining Java EE dependencies

**Changes Required:**

1. **WebLogic Lifecycle Listener** (Konveyor: lifecycle management)
   
   **Files to Modify/Delete:**
   - `com.redhat.coolstore.utils.StartupListener`
   - `weblogic.application.ApplicationLifecycleListener`
   - `weblogic.application.ApplicationLifecycleEvent`
   - `weblogic.i18n.logging.NonCatalogLogger`
   
   **Changes:**
   - Delete `StartupListener` class (WebLogic-specific)
   - Delete entire `weblogic` package (stub classes, not needed in Quarkus)
   - If startup logic is needed, use Quarkus `@Observes StartupEvent` pattern:
     ```java
     void onStart(@Observes StartupEvent event) {
         log.info("Application started");
     }
     ```

2. **Resource Cleanup** (Konveyor: lifecycle management)
   
   **File to Modify:**
   - `com.redhat.coolstore.service.OrderService`
   
   **Changes:**
   - Keep `@PostConstruct` and `@PreDestroy` annotations (supported in Quarkus CDI)
   - Verify audit logger initialization works with Quarkus
   - Ensure file system paths are externalized to `application.properties`:
     ```properties
     audit.log.directory=${AUDIT_LOG_DIR:./device-inventory-audit-logs}
     ```
   - Inject property using `@ConfigProperty`:
     ```java
     @Inject
     @ConfigProperty(name = "audit.log.directory")
     String logDirectory;
     ```

3. **JSON Processing** (Konveyor: `javaee-technology-usage-00030`)
   
   **File to Modify:**
   - `com.redhat.coolstore.utils.Transformers`
   
   **Changes:**
   - Java EE JSON-P (`javax.json`) is supported in Quarkus via `quarkus-jsonp` extension
   - Alternative: Migrate to Jackson (already included with `quarkus-resteasy-reactive-jackson`)
   - Recommendation: Keep JSON-P if code works, or refactor to Jackson for consistency
   - If keeping JSON-P, add dependency: `io.quarkus:quarkus-jsonp`

4. **REST Endpoints** (Konveyor: technology usage)
   
   **Files to Review:**
   - `com.redhat.coolstore.rest.CartEndpoint`
   - `com.redhat.coolstore.rest.OrderEndpoint`
   - `com.redhat.coolstore.rest.ProductEndpoint`
   
   **Changes:**
   - Replace `javax.ws.rs.*` imports with `jakarta.ws.rs.*` (Quarkus 3 uses Jakarta EE 10)
   - Or use RESTEasy Reactive equivalents if available
   - Keep `@SessionScoped` on `CartEndpoint` (supported in Quarkus)
   - Verify `Serializable` implementation still needed (for `@SessionScoped`)

5. **JPA Entities** (Konveyor: `hibernate-00005`)
   
   **Files to Modify:**
   - `com.redhat.coolstore.model.Order`
   - `com.redhat.coolstore.model.OrderItem`
   
   **Changes:**
   - Review `@GeneratedValue(strategy = GenerationType.AUTO)` usage
   - Ensure Flyway creates appropriate sequences: `order_seq`, `order_item_seq`
   - Or explicitly configure sequence names:
     ```java
     @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
     @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 1)
     ```

6. **Static Resources** (Konveyor: webapp disposition)
   
   **Changes:**
   - Move `src/main/webapp/app/` → `src/main/resources/META-INF/resources/app/`
   - Move `src/main/webapp/bower_components/` → `src/main/resources/META-INF/resources/bower_components/`
   - Move `src/main/webapp/partials/` → `src/main/resources/META-INF/resources/partials/`
   - Move `src/main/webapp/*.json` → `src/main/resources/META-INF/resources/`
   - Convert `src/main/webapp/health.jsp` to Quarkus health endpoint:
     - Add dependency: `io.quarkus:quarkus-smallrye-health`
     - Health endpoint available at `/q/health` automatically
   - Convert or move `src/main/webapp/index.jsp`:
     - Option A: Convert to static `index.html` in `META-INF/resources/`
     - Option B: Keep JSP functionality - add `io.quarkus:quarkus-undertow` extension (supports JSP)
   - Delete `src/main/webapp/` directory after migration

7. **Build and Packaging**
   - Remove `maven-war-plugin` from `pom.xml`
   - Verify `quarkus-maven-plugin` produces `quarkus-run.jar` in `target/quarkus-app/`
   - Update any deployment scripts to use `java -jar target/quarkus-app/quarkus-run.jar`

**Konveyor Issues Addressed:**
- `javaee-technology-usage-00030` (JSON-P)
- `hibernate-00005` (Sequence naming)
- WebLogic-specific lifecycle listeners
- Static resource serving

---

### Phase 6: Post-Migration Cleanup

**Objective**: Remove obsolete files, verify configuration completeness

**Files to Delete:**
- `src/main/webapp/` (entire directory after resources migrated)
- `src/main/resources/META-INF/persistence.xml` (optional, if fully replaced by application.properties)
- `com.redhat.coolstore.rest.RestApplication` (optional, if path set in properties)
- `com.redhat.coolstore.utils.DataBaseMigrationStartup` (replaced by Quarkus Flyway)
- `weblogic.*` package (stub classes)

**Files to Review:**
- All files with `@PostConstruct`/`@PreDestroy` - ensure compatibility
- All files with `@Inject` - verify CDI beans are scoped correctly
- All JAX-RS endpoints - verify Jakarta EE 10 compliance

**Configuration Completeness:**
- `application.properties` contains all datasource, messaging, and Flyway configs
- REST base path `/services` is preserved
- Database migration scripts location is configured
- Logging configuration (if needed)

---

## Verification

The migration will be considered successful when:

1. **Build Success**: `mvn package -DskipTests` completes without errors
2. **Startup Success**: `java -jar target/quarkus-app/quarkus-run.jar` starts cleanly without exceptions
3. **REST API Available**: Endpoints at `/services/cart`, `/services/products`, `/services/orders` respond correctly
4. **Messaging Functional**: Order checkout triggers both OrderServiceMDB and InventoryNotificationMDB consumers
5. **Database Operations**: JPA entities persist correctly, Flyway migrations run at startup
6. **Static Resources**: Frontend application loads from `/index.html` or equivalent

**Note**: This assessment phase does NOT include actual execution of these verification steps or source code modifications. The verification criteria will be applied in Stage 3 (validate).

---

## Risk Assessment

**High Risk:**
- Messaging topology preservation (JMS Topic fan-out → Reactive Messaging broadcast)
- Stateful session bean semantics (ShoppingCartService state management)
- System-scoped audit library dependency handling

**Medium Risk:**
- WebLogic-specific code removal (JNDI lookups, lifecycle listeners)
- Hibernate sequence naming changes
- JSP to static HTML conversion (if frontend needs server-side rendering)

**Low Risk:**
- EJB to CDI conversion (well-documented pattern)
- JAX-RS migration (minimal changes required)
- Flyway integration (native Quarkus support)

---

## Dependencies

**Build Phase Prerequisites:**
- Quarkus 3.x BOM selection (recommend 3.15.1 LTS)
- Java 17 runtime (or Java 11 minimum)
- Maven 3.8.1+
- PostgreSQL driver selection (or H2 for testing)

**Runtime Prerequisites:**
- PostgreSQL database instance (or H2)
- Java 17 JRE

**No External Services Required:**
- Messaging uses in-memory connector (no external broker)
- No external JNDI server needed
- Self-contained JAR deployment

---

## Notes

- This plan follows the javaee-to-quarkus skill phase ordering
- All source file modifications are deferred to Stage 2 (remediate)
- Build gates (Maven package testing) are deferred to Stage 2
- The `/services` REST base path is critical for API compatibility and must be preserved
- Static resources can remain served from the monolith; SPA deployment separation is optional
- The in-memory messaging connector is suitable for single-instance deployments; multi-instance deployments would require external broker (e.g., Kafka, AMQP)
