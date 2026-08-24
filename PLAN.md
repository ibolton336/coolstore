# Java EE 7 to Quarkus 3 Migration Plan

## Executive Summary

This document outlines the migration plan for the coolstore monolith application from Java EE 7 (WAR packaging on an application server) to Quarkus 3 (JAR packaging). The migration involves converting EJBs to CDI beans, replacing JMS with Reactive Messaging, removing JNDI lookups, and adapting to Quarkus's packaging and configuration model.

## Current Application Architecture

### Technology Stack (Java EE 7)
- **Packaging**: WAR (deployed to WildFly/JBoss EAP)
- **Dependency Injection**: CDI 1.1 with EJB 3.2
- **Persistence**: JPA 2.1 with Hibernate, using JNDI datasource lookup
- **Messaging**: JMS 2.0 with Topic-based pub-sub pattern
- **REST**: JAX-RS 2.0 with `/services` base path
- **Database Migration**: Flyway 4.1.2
- **External Library**: audit-logging-library-1.0.0.jar (system-scoped dependency)

### Application Components

#### REST Endpoints (JAX-RS)
- `RestApplication` - defines `/services` base path (must be preserved)
- `ProductEndpoint` - `/services/products/*` - product catalog operations
- `CartEndpoint` - `/services/cart/*` - shopping cart management (@SessionScoped)
- `OrderEndpoint` - `/services/orders/*` - order retrieval

#### Service Layer (EJBs)
- **@Stateless EJBs** (5):
  - `CatalogService` - product catalog and inventory management
  - `OrderService` - order persistence
  - `ProductService` - product retrieval facade
  - `ShippingService` - shipping calculation (@Remote EJB)
  - `ShoppingCartOrderProcessor` - publishes orders to JMS topic

- **@Stateful EJB** (1):
  - `ShoppingCartService` - session-scoped shopping cart state

- **@MessageDriven EJB** (1):
  - `OrderServiceMDB` - consumes orders from JMS topic, saves to database

- **@ApplicationScoped CDI Bean** (1):
  - `PromoService` - promotion calculation logic (already CDI)

- **Non-annotated MDB** (1):
  - `InventoryNotificationMDB` - manual JNDI-based topic subscriber (WebLogic-specific)

#### Persistence Layer
- `Resources` - produces EntityManager using @PersistenceContext
- JPA entities: `CatalogItemEntity`, `InventoryEntity`, `Order`, `OrderItem`, `Product`, `Promotion`, `ShoppingCart`, `ShoppingCartItem`
- Uses JNDI datasource: `java:jboss/datasources/CoolstoreDS`

#### Lifecycle and Utilities
- `DataBaseMigrationStartup` - @Singleton @Startup EJB for Flyway database migration
- `StartupListener` - WebLogic ApplicationLifecycleListener for startup/shutdown hooks
- `Producers` - CDI producer for Logger injection
- `Transformers` - JSON/Object conversion utilities

### Messaging Topology

#### Original Application Message Flow

| Producer | Broker Address | Consumers | Message Type | Fan-out |
|----------|---------------|-----------|--------------|---------|
| `ShoppingCartOrderProcessor.process()` | `java:/topic/orders` (JNDI) | `OrderServiceMDB`, `InventoryNotificationMDB` | Topic (pub-sub) | 2 consumers |

**Message Flow Details**:
1. **Producer**: `ShoppingCartOrderProcessor` (Stateless EJB)
   - Method: `process(ShoppingCart cart)`
   - Uses: `@Inject JMSContext` + `@Resource(lookup="java:/topic/orders") Topic`
   - Action: Publishes shopping cart as JSON to topic
   
2. **Consumer 1**: `OrderServiceMDB` (Message-Driven Bean)
   - Destination: `topic/orders` via `@ActivationConfigProperty`
   - Action: Deserializes order, saves to database via `OrderService`, updates inventory via `CatalogService`
   
3. **Consumer 2**: `InventoryNotificationMDB` (Manual subscriber)
   - Destination: `topic/orders` via manual WebLogic JNDI lookup (`t3://localhost:7001`)
   - Action: Monitors inventory levels and logs warnings when below threshold (50 units)
   - Note: Uses programmatic subscription with `TopicConnectionFactory` and `TopicSubscriber`

**Conversion Requirements**:
- Topic must be converted to a Reactive Messaging channel
- Producer must use `@Channel` + `Emitter<String>`
- Consumers must use `@Incoming` annotation
- Preserve 1-to-many (fan-out) semantics with multiple consumers on the same channel

## Migration Phases

### Phase 1: Build Configuration
**Objective**: Convert from WAR to JAR packaging, adopt Quarkus build tooling

#### 1.1 Update pom.xml Structure
- **Change packaging** from `war` to `jar`
- **Add Quarkus BOM**: Import `io.quarkus.platform:quarkus-bom:3.x.x`
- **Add Quarkus Maven Plugin**: Configure with `build`, `generate-code`, `generate-code-tests` goals
- **Update Maven Compiler Plugin**: 
  - Set version to 3.10.1+
  - Configure for Java 11+ (`maven.compiler.release=11`)
  - Add `-parameters` compiler arg for CDI injection
- **Add Maven Surefire Plugin**: Configure with JBoss LogManager
- **Add Maven Failsafe Plugin**: Configure for integration tests
- **Add Native Profile**: Configure `quarkus.package.type=native`

#### 1.2 Replace Java EE Dependencies with Quarkus Extensions
Remove:
- `javax:javaee-web-api:7.0`
- `javax:javaee-api:7.0`
- `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`
- `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`

Add Quarkus extensions:
- `quarkus-resteasy-reactive-jackson` (JAX-RS + JSON)
- `quarkus-hibernate-orm-panache` or `quarkus-hibernate-orm` (JPA)
- `quarkus-jdbc-postgresql` or `quarkus-jdbc-h2` (JDBC driver)
- `quarkus-smallrye-reactive-messaging` (messaging)
- `quarkus-messaging-connector-smallrye-in-memory` (in-memory broker for topic fan-out)
- `quarkus-flyway` (database migration)
- `quarkus-undertow` (for @SessionScoped support in CartEndpoint)
- `quarkus-arc` (CDI - included by default)

#### 1.3 Handle System-Scoped Dependency
**Issue**: `audit-logging-library-1.0.0.jar` uses `<scope>system</scope>`
**Options**:
1. Install JAR to local Maven repository and change to normal dependency
2. Copy JAR to `src/main/resources` and access via classloader
3. Create a thin wrapper Quarkus extension
**Recommendation**: Option 1 (install to local repo) for simplicity

#### 1.4 Update Build Properties
```xml
<properties>
    <maven.compiler.release>11</maven.compiler.release>
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.version>3.8.0</quarkus.platform.version>
    <compiler-plugin.version>3.10.1</compiler-plugin.version>
    <surefire-plugin.version>3.0.0</surefire-plugin.version>
</properties>
```

### Phase 2: Application Configuration
**Objective**: Replace Java EE XML descriptors with Quarkus properties

#### 2.1 Convert persistence.xml to application.properties
Move from `src/main/resources/META-INF/persistence.xml` to `src/main/resources/application.properties`:

```properties
# Datasource configuration (replaces JNDI lookup)
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=coolstore
quarkus.datasource.password=coolstore
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/coolstore

# Hibernate/JPA configuration
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=false
quarkus.hibernate-orm.sql-load-script=no-file

# Flyway configuration
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true

# JAX-RS path preservation
quarkus.resteasy-reactive.path=/services
```

**Note**: `quarkus.resteasy-reactive.path=/services` ensures REST base path `/services` is preserved as required.

#### 2.2 Remove/Archive beans.xml
- File: `src/main/webapp/WEB-INF/beans.xml`
- Action: Delete or move to documentation (content is ignored in Quarkus)
- Note: Quarkus uses `bean-discovery-mode=annotated` by default

#### 2.3 Configure Reactive Messaging Channels
Add to `application.properties`:

```properties
# In-memory connector for topic fan-out (2 consumers)
mp.messaging.incoming.orders.connector=smallrye-in-memory

# Configure channel
mp.messaging.outgoing.orders.connector=smallrye-in-memory
```

### Phase 3: EJB to CDI Conversion
**Objective**: Replace EJB annotations with CDI scopes and transaction annotations

#### 3.1 Convert @Stateless EJBs to @ApplicationScoped (5 classes)
Files:
- `CatalogService.java`
- `OrderService.java`
- `ProductService.java`
- `ShippingService.java`
- `ShoppingCartOrderProcessor.java`

Changes per file:
1. Remove `import javax.ejb.Stateless`
2. Add `import javax.enterprise.context.ApplicationScoped`
3. Replace `@Stateless` with `@ApplicationScoped`
4. Add `import javax.transaction.Transactional`
5. Mark transactional methods with `@Transactional` (see Phase 3.5)

#### 3.2 Convert @Stateful EJB to @SessionScoped (1 class)
File: `ShoppingCartService.java`

Changes:
1. Remove `import javax.ejb.Stateful`
2. Add `import javax.enterprise.context.SessionScoped`
3. Replace `@Stateful` with `@SessionScoped`
4. Note: `@SessionScoped` requires `quarkus-undertow` extension for servlet session support

**Rationale**: Shopping cart maintains per-user session state accessed by `CartEndpoint` which is also `@SessionScoped`.

#### 3.3 Convert @Singleton @Startup EJB (1 class)
File: `DataBaseMigrationStartup.java`

Changes:
1. Remove `import javax.ejb.Singleton`, `javax.ejb.Startup`, `javax.ejb.TransactionManagement`
2. Add `import io.quarkus.runtime.Startup`
3. Add `import javax.enterprise.context.ApplicationScoped`
4. Replace `@Singleton` with `@ApplicationScoped`
5. Keep `@Startup` (Quarkus version)
6. Remove `@TransactionManagement(TransactionManagementType.BEAN)` (use `@Transactional` on methods if needed)
7. Remove `@Resource(mappedName = "java:jboss/datasources/CoolstoreDS") DataSource dataSource`
8. Add `@Inject @ConfigProperty(name = "quarkus.datasource.jdbc.url") String datasourceUrl` 
9. Update Flyway initialization to use Quarkus-style injection or migrate to declarative Flyway config

**Alternative**: Leverage Quarkus Flyway extension's auto-migration feature (configure via properties, remove class entirely)

#### 3.4 Convert @Remote EJB to REST (1 class)
File: `ShippingService.java`

Changes:
1. Remove `import javax.ejb.Remote`, `javax.ejb.Stateless`
2. Remove `@Remote` interface implementation
3. Keep as `@ApplicationScoped` CDI bean
4. **Do NOT convert to REST endpoint** - it's called internally via JNDI lookup in `ShoppingCartService.lookupShippingServiceRemote()`
5. Update `ShoppingCartService` to remove JNDI lookup and use `@Inject ShippingService` instead

File: `ShoppingCartService.java`
Changes:
1. Add `@Inject ShippingService shippingService` field
2. Remove `lookupShippingServiceRemote()` method
3. Replace calls to `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
4. Replace calls to `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
5. Remove JNDI imports: `javax.naming.Context`, `javax.naming.InitialContext`, `javax.naming.NamingException`

#### 3.5 Add @Transactional Annotations
**Rationale**: EJBs have container-managed transactions by default (CMT). In CDI, transactions must be explicit.

Files requiring `@Transactional` on methods or class level:
1. **CatalogService**:
   - `updateInventoryItems()` - performs `em.merge()`
   
2. **OrderService**:
   - `save()` - performs `em.persist()`
   
3. **OrderServiceMDB** (post-conversion):
   - `onMessage()` / new `@Incoming` method - persists order and updates inventory

Add at class level (all methods transactional):
- `CatalogService` - add `@Transactional` to class
- `OrderService` - add `@Transactional` to class
- `OrderServiceMDB` - add `@Transactional` to class after messaging conversion

### Phase 4: Messaging (JMS to Reactive Messaging)
**Objective**: Replace JMS Topic with Reactive Messaging channels

#### 4.1 Convert Producer (ShoppingCartOrderProcessor)
File: `ShoppingCartOrderProcessor.java`

Remove:
```java
import javax.jms.JMSContext;
import javax.jms.Topic;
import javax.annotation.Resource;

@Inject
private transient JMSContext context;

@Resource(lookup = "java:/topic/orders")
private Topic ordersTopic;
```

Add:
```java
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@Inject
@Channel("orders")
Emitter<String> ordersEmitter;
```

Change method:
```java
// Before:
context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));

// After:
ordersEmitter.send(Transformers.shoppingCartToJson(cart));
```

#### 4.2 Convert Consumer 1 (OrderServiceMDB)
File: `OrderServiceMDB.java`

Remove:
```java
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.MessageListener;
import javax.jms.Message;
import javax.jms.TextMessage;
import javax.jms.JMSException;

@MessageDriven(name = "OrderServiceMDB", activationConfig = {...})
public class OrderServiceMDB implements MessageListener {
    @Override
    public void onMessage(Message rcvMessage) { ... }
}
```

Add:
```java
import org.eclipse.microprofile.reactive.messaging.Incoming;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped
@Transactional
public class OrderServiceMDB {
    
    @Incoming("orders")
    public void processOrder(String orderJson) {
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

**Changes**:
- Replace `@MessageDriven` with `@ApplicationScoped`
- Add `@Transactional` for transaction management
- Replace `onMessage(Message)` with `processOrder(String)` annotated with `@Incoming("orders")`
- Remove JMS-specific exception handling (Reactive Messaging handles errors)
- Simplify message parsing (String input instead of TextMessage)

#### 4.3 Convert Consumer 2 (InventoryNotificationMDB)
File: `InventoryNotificationMDB.java`

**Current Implementation**: Programmatic WebLogic JNDI topic subscription (not annotated as MDB)

Remove:
```java
import javax.jms.*;
import javax.naming.*;
import javax.rmi.PortableRemoteObject;
import java.util.Hashtable;

private final static String JNDI_FACTORY = "weblogic.jndi.WLInitialContextFactory";
private TopicConnection tcon;
private TopicSession tsession;
private TopicSubscriber tsubscriber;

public void init() throws NamingException, JMSException { ... }
public void close() throws JMSException { ... }
private static InitialContext getInitialContext() throws NamingException { ... }
```

Add:
```java
import org.eclipse.microprofile.reactive.messaging.Incoming;
import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InventoryNotificationMDB {
    
    @Incoming("orders")
    public void checkInventory(String orderJson) {
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
}
```

**Changes**:
- Add `@ApplicationScoped` scope
- Replace `onMessage(Message)` with `checkInventory(String)` annotated with `@Incoming("orders")`
- Remove all JNDI and WebLogic-specific code
- Remove manual connection management (`init()`, `close()` methods)
- Simplify message parsing

**Note**: Both MDBs now consume from the same `"orders"` channel, preserving the 1-to-2 fan-out pattern.

#### 4.4 Verify Message Connector Configuration
Ensure `application.properties` includes:
```properties
# In-memory broker (suitable for single-instance deployment)
mp.messaging.incoming.orders.connector=smallrye-in-memory
mp.messaging.outgoing.orders.connector=smallrye-in-memory

# For production with external broker, use:
# mp.messaging.incoming.orders.connector=smallrye-kafka
# mp.messaging.outgoing.orders.connector=smallrye-kafka
# kafka.bootstrap.servers=localhost:9092
```

### Phase 5: Lifecycle and Miscellaneous
**Objective**: Replace Java EE lifecycle hooks and update CDI producers

#### 5.1 Remove WebLogic Lifecycle Listener
File: `StartupListener.java`

**Action**: Delete file entirely

**Rationale**: 
- Extends `weblogic.application.ApplicationLifecycleListener` (WebLogic-specific)
- Quarkus uses different lifecycle mechanism (`@Observes StartupEvent`, `@Observes ShutdownEvent`)
- No equivalent functionality needed (logger injection already works in Quarkus)

#### 5.2 Update CDI Producers
File: `Producers.java`

**Current**:
```java
@Produces
public Logger produceLog(InjectionPoint injectionPoint) {
    return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
}
```

**Option 1 - Keep as-is**: `@Produces` Logger is valid in Quarkus
**Option 2 - Simplify**: Use Quarkus built-in JBoss Logging
```java
import io.quarkus.logging.Log;
// Then inject with: Log.info("message") directly (no @Inject needed)
```

**Recommendation**: Keep current implementation (no change needed, but could be simplified in cleanup phase)

#### 5.3 Update EntityManager Producer
File: `Resources.java`

**Current Issue**: `@Produces` on `EntityManager` conflicts with Quarkus auto-injection

**Option 1 - Remove producer entirely** (recommended):
```java
// DELETE this file - EntityManager can be @Injected directly in Quarkus
```

Then update all consumers to inject directly:
```java
@Inject
EntityManager em;
```

**Option 2 - Add qualifier** (if custom producer logic is needed):
```java
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface CustomEM {}

@Dependent
public class Resources {
    @Produces
    @CustomEM
    public EntityManager getEntityManager() {
        // custom logic
    }
}

// Then inject with:
@Inject @CustomEM EntityManager em;
```

**Recommendation**: Option 1 (remove file). Update `CatalogService.java` and `OrderService.java`:
- Change remains: `@Inject EntityManager em;` (no `@PersistenceContext` needed)

#### 5.4 Update Flyway Integration
File: `DataBaseMigrationStartup.java`

**Option 1 - Use Quarkus Flyway Extension** (recommended):
1. Delete `DataBaseMigrationStartup.java`
2. Add dependency: `quarkus-flyway`
3. Configure in `application.properties`:
```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.flyway.locations=classpath:db/migration
```
4. Ensure migration scripts are in `src/main/resources/db/migration/`

**Option 2 - Keep programmatic approach**:
1. Convert to `@ApplicationScoped` with `@Startup`
2. Inject datasource via CDI instead of `@Resource`
3. Update Flyway API to 9.x+ (Quarkus compatible version)

**Recommendation**: Option 1 (declarative via extension)

#### 5.5 Handle Audit Logging Library
File: `OrderService.java`

**Current**: Uses `FileSystemAuditLogger` from `audit-logging-library-1.0.0.jar`

**Changes**:
1. Keep `@PostConstruct` and `@PreDestroy` methods (supported in Quarkus CDI)
2. Ensure JAR is available (installed to local Maven repo as per Phase 1.3)
3. Update import if package changed from `javax.annotation` to `jakarta.annotation`:
```java
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
```

**No functional changes needed** - CDI lifecycle callbacks work in Quarkus

### Phase 6: Cleanup and Optimization
**Objective**: Remove Java EE artifacts and optimize for Quarkus

#### 6.1 Update Java Package Imports
Replace `javax.*` with `jakarta.*` where appropriate:
- `javax.persistence.*` → `jakarta.persistence.*`
- `javax.transaction.*` → `jakarta.transaction.*`
- `javax.ws.rs.*` → `jakarta.ws.rs.*`
- `javax.inject.*` → `jakarta.inject.*`
- `javax.enterprise.*` → `jakarta.enterprise.*`

**Note**: Quarkus 3.x uses Jakarta EE 10 namespace

#### 6.2 Remove Unused Annotations
Files to clean:
- Remove all `@Remote` references (ShippingService.java, ShippingServiceRemote.java interface)
- Remove `@ActivationConfigProperty` from converted MDBs
- Remove `@TransactionManagement` from DataBaseMigrationStartup (if kept)

#### 6.3 Handle src/main/webapp Content (50MB)
**Contents**:
- `WEB-INF/` - Java EE descriptors (beans.xml, web.xml if present)
- `app/` - Frontend application code (AngularJS/JavaScript)
- `bower_components/` - Third-party libraries (~45MB)
- `partials/` - HTML templates
- `index.jsp`, `health.jsp` - JSP pages
- `coolstore.json`, `keycloak.json` - Configuration files

**Quarkus JAR Packaging - Options**:

**Option 1 - Move to src/main/resources/META-INF/resources** (recommended):
```bash
mkdir -p src/main/resources/META-INF/resources
mv src/main/webapp/* src/main/resources/META-INF/resources/
rmdir src/main/webapp
```
- Quarkus serves static content from `META-INF/resources` in the JAR
- Preserves all frontend assets
- JSP files need conversion to static HTML (JSPs not supported in Quarkus)

**Option 2 - External static file server**:
- Deploy frontend separately (Nginx, Apache, CDN)
- Keep Quarkus as pure API backend
- Update frontend to call `/services/*` endpoints

**Option 3 - Separate frontend module**:
- Create multi-module Maven project
- `frontend/` module for UI
- `backend/` module for Quarkus API
- Build frontend with npm/webpack, copy to backend's `META-INF/resources`

**Recommendation**: Option 1 for simplicity, with JSP-to-HTML conversion:
- Convert `index.jsp` to `index.html` (remove JSP directives)
- Convert `health.jsp` to `health.html` or remove (use Quarkus health endpoint: `/q/health`)
- Verify `keycloak.json` is compatible with Quarkus OIDC extension (may need migration)

**JSP Migration**:
```jsp
<!-- index.jsp - Before -->
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
...
</html>

<!-- index.html - After -->
<!DOCTYPE html>
<html>
...
</html>
```

#### 6.4 Remove Old Descriptors
Delete or archive:
- `src/main/webapp/WEB-INF/web.xml` (if exists)
- `src/main/webapp/WEB-INF/beans.xml` (content ignored)
- Any `ejb-jar.xml`, `jboss-web.xml`, etc.

#### 6.5 Add Quarkus Health and Metrics (Optional Enhancement)
```properties
# application.properties
quarkus.smallrye-health.root-path=/q/health
```

Replace `health.jsp` with built-in health endpoint at `/q/health`

#### 6.6 Optimize Dependencies
Review and remove unused dependencies:
- `jboss-jms-api_2.0_spec` - replaced by Reactive Messaging
- `jboss-rmi-api_1.0_spec` - no longer needed after JNDI removal

Add explicit versions for transitive dependencies if needed.

## Source File Change Summary

### Files to Modify (18 files)

| File | Phase | Changes |
|------|-------|---------|
| `pom.xml` | 1 | Packaging, dependencies, plugins, properties |
| `src/main/resources/application.properties` | 2 | Create (datasource, Flyway, messaging, REST path) |
| `CatalogService.java` | 3 | @Stateless → @ApplicationScoped, add @Transactional |
| `OrderService.java` | 3 | @Stateless → @ApplicationScoped, add @Transactional, update imports |
| `ProductService.java` | 3 | @Stateless → @ApplicationScoped |
| `ShippingService.java` | 3 | @Stateless+@Remote → @ApplicationScoped, remove @Remote |
| `ShoppingCartOrderProcessor.java` | 3,4 | @Stateless → @ApplicationScoped, JMS → Emitter |
| `ShoppingCartService.java` | 3 | @Stateful → @SessionScoped, remove JNDI lookup, inject ShippingService |
| `DataBaseMigrationStartup.java` | 3 | @Singleton+@Startup → @ApplicationScoped+@Startup, remove @Resource |
| `OrderServiceMDB.java` | 3,4 | @MessageDriven → @ApplicationScoped, add @Transactional, @Incoming |
| `InventoryNotificationMDB.java` | 4 | Add @ApplicationScoped, add @Incoming, remove JNDI code |
| `Producers.java` | 5 | Optional: simplify or keep as-is |
| `RestApplication.java` | 6 | May be removable (path configured in properties) |
| `CartEndpoint.java` | 6 | Update imports to jakarta.* |
| `ProductEndpoint.java` | 6 | Update imports to jakarta.* |
| `OrderEndpoint.java` | 6 | Update imports to jakarta.* |
| `src/main/webapp/index.jsp` | 6 | Convert to index.html |
| `src/main/webapp/health.jsp` | 6 | Convert to health.html or delete |

### Files to Delete (3 files)

| File | Phase | Reason |
|------|-------|--------|
| `Resources.java` | 5 | EntityManager producer not needed in Quarkus |
| `StartupListener.java` | 5 | WebLogic-specific lifecycle listener |
| `src/main/webapp/WEB-INF/beans.xml` | 2 | Content ignored in Quarkus |

### Files to Move (src/main/webapp → src/main/resources/META-INF/resources)

| Directory/File | Phase | Size |
|----------------|-------|------|
| `app/` | 6 | ~5MB |
| `bower_components/` | 6 | ~45MB |
| `partials/` | 6 | <1MB |
| `coolstore.json` | 6 | <1KB |
| `keycloak.json` | 6 | <1KB |

**Note**: `WEB-INF/` directory should NOT be moved (not needed in JAR packaging)

## Migration Order (Detailed Steps)

### Step 1: Build Configuration (Phase 1)
1. Update `pom.xml` packaging to `jar`
2. Add Quarkus BOM and platform properties
3. Add Quarkus extensions (REST, JPA, Flyway, Messaging, Undertow)
4. Add/update Maven plugins (compiler, Quarkus, Surefire, Failsafe)
5. Add native profile
6. Install `audit-logging-library-1.0.0.jar` to local Maven repo
7. Update dependency scope from `system` to normal
8. Remove Java EE API dependencies

### Step 2: Application Configuration (Phase 2)
1. Create `src/main/resources/application.properties`
2. Add datasource configuration (PostgreSQL/H2)
3. Add Hibernate/JPA configuration
4. Add Flyway configuration
5. Add REST base path configuration: `quarkus.resteasy-reactive.path=/services`
6. Add Reactive Messaging configuration (in-memory connector)
7. Delete `src/main/webapp/WEB-INF/beans.xml`

### Step 3: EJB Conversions (Phase 3)
1. Convert `CatalogService`: @Stateless → @ApplicationScoped + @Transactional
2. Convert `OrderService`: @Stateless → @ApplicationScoped + @Transactional
3. Convert `ProductService`: @Stateless → @ApplicationScoped
4. Convert `ShippingService`: @Stateless+@Remote → @ApplicationScoped
5. Convert `ShoppingCartOrderProcessor`: @Stateless → @ApplicationScoped (messaging in next step)
6. Convert `ShoppingCartService`: @Stateful → @SessionScoped
   - Remove `lookupShippingServiceRemote()` method
   - Add `@Inject ShippingService shippingService`
   - Replace JNDI lookup calls with direct service calls
7. Convert `DataBaseMigrationStartup`: @Singleton+@Startup → delete (use Flyway extension)

### Step 4: Messaging Conversion (Phase 4)
1. Update `ShoppingCartOrderProcessor`:
   - Remove JMS imports (JMSContext, Topic, @Resource)
   - Add Reactive Messaging imports (Emitter, @Channel)
   - Replace JMS send with Emitter.send()
2. Update `OrderServiceMDB`:
   - Remove @MessageDriven and MDB config
   - Add @ApplicationScoped + @Transactional
   - Replace onMessage(Message) with processOrder(String) + @Incoming("orders")
3. Update `InventoryNotificationMDB`:
   - Add @ApplicationScoped
   - Remove all JNDI and WebLogic code
   - Add checkInventory(String) + @Incoming("orders")
   - Remove init(), close(), getInitialContext() methods

### Step 5: Lifecycle and Cleanup (Phase 5)
1. Delete `Resources.java`
2. Update `CatalogService` and `OrderService`: ensure `@Inject EntityManager em` (no change needed)
3. Delete `StartupListener.java`
4. Keep `Producers.java` as-is (or simplify to Quarkus logging)
5. Verify audit logging in `OrderService` works with PostConstruct/PreDestroy

### Step 6: Static Content and Final Cleanup (Phase 6)
1. Create `src/main/resources/META-INF/resources/`
2. Move contents of `src/main/webapp/` (except WEB-INF) to `META-INF/resources/`
3. Convert `index.jsp` to `index.html`
4. Delete `health.jsp` (use Quarkus `/q/health` endpoint)
5. Delete `src/main/webapp/WEB-INF/` directory
6. Update all imports from `javax.*` to `jakarta.*`
7. Remove unused annotations and imports
8. Verify `keycloak.json` compatibility or migrate to `application.properties` OIDC config

## Testing Strategy

### Unit Testing
- Test service layer conversions (EJB → CDI)
- Test messaging conversion (mock Emitter, verify @Incoming methods)
- Test transaction boundaries (@Transactional methods)

### Integration Testing
- Test REST endpoints (`/services/products`, `/services/cart`, `/services/orders`)
- Test messaging flow (producer → 2 consumers)
- Test database operations (Flyway migration, CRUD)
- Test session-scoped shopping cart

### Smoke Testing
- `mvn clean package -DskipTests` - must succeed
- `java -jar target/quarkus-app/quarkus-run.jar` - must start cleanly
- Access frontend at `http://localhost:8080/index.html`
- Access REST API at `http://localhost:8080/services/products`
- Verify health endpoint at `http://localhost:8080/q/health`

## Verification

### Build Verification
```bash
# Must succeed
mvn clean package -DskipTests
```

**Expected Output**:
- No compilation errors
- JAR created at `target/quarkus-app/quarkus-run.jar`
- Quarkus runner artifacts in `target/quarkus-app/`

### Runtime Verification
```bash
# Must start without errors
java -jar target/quarkus-app/quarkus-run.jar
```

**Expected Output**:
- Quarkus banner displayed
- Datasource initialized
- Flyway migrations executed
- RESTEasy Reactive started on `/services`
- SmallRye Reactive Messaging channels initialized
- Application started in <3 seconds (dev mode)
- No ERROR or WARN logs related to:
  - Missing dependencies
  - Failed CDI bean discovery
  - Transaction manager issues
  - Messaging channel binding failures

**Expected Log Messages**:
```
INFO  [io.quarkus] (main) coolstore-monolith 1.0.0-SNAPSHOT on JVM started in 2.5s
INFO  [io.quarkus] (main) Profile prod activated
INFO  [io.quarkus] (main) Installed features: [agroal, cdi, flyway, hibernate-orm, jdbc-postgresql, 
                                                 narayana-jta, resteasy-reactive, resteasy-reactive-jackson, 
                                                 smallrye-context-propagation, smallrye-reactive-messaging, 
                                                 undertow, vertx]
```

### Functional Verification
1. **REST API**:
   - GET `http://localhost:8080/services/products` → Returns product list (JSON)
   - GET `http://localhost:8080/services/cart/test123` → Returns empty cart
   - POST `http://localhost:8080/services/cart/test123/329299/2` → Adds item to cart

2. **Messaging**:
   - POST checkout triggers `ShoppingCartOrderProcessor.process()`
   - Verify logs: "Message recd !" from both `OrderServiceMDB` and `InventoryNotificationMDB`
   - Verify order saved to database
   - Verify inventory decremented

3. **Database**:
   - Tables created by Flyway migrations
   - Data populated (products, inventory)
   - Orders can be persisted and retrieved

4. **Static Content**:
   - GET `http://localhost:8080/index.html` → Frontend loads
   - GET `http://localhost:8080/app/services/catalog.js` → AngularJS services load

5. **Health**:
   - GET `http://localhost:8080/q/health` → Returns UP status

## Risk Assessment

### High Risk Items
1. **Messaging topology preservation** (1-to-2 fan-out)
   - Risk: In-memory connector may not support multiple consumers properly
   - Mitigation: Test thoroughly; consider Kafka connector for production

2. **@SessionScoped with Quarkus Undertow**
   - Risk: Session management differs from Java EE servlet containers
   - Mitigation: Test cart state persistence across requests; consider alternative state management (Redis, database)

3. **Audit logging library compatibility**
   - Risk: JAR may depend on Java EE APIs not available in Quarkus
   - Mitigation: Test thoroughly; may need to create Quarkus extension or replace library

4. **50MB webapp directory**
   - Risk: JAR size bloat, potential path mapping issues
   - Mitigation: Consider CDN for bower_components; verify all paths work in JAR

### Medium Risk Items
1. **Flyway version compatibility** (4.1.2 → 9.x)
   - Risk: Migration scripts may need updates
   - Mitigation: Test migrations on clean database

2. **Hibernate ID generation strategy changes** (Hibernate 5 → 6)
   - Risk: Existing data may conflict with new sequence naming
   - Mitigation: Explicitly define sequence names in entities

3. **Transaction boundaries**
   - Risk: Missing @Transactional annotations could cause LazyInitializationException
   - Mitigation: Comprehensive testing of all database operations

### Low Risk Items
1. **Logger producer** - already CDI-based, should work as-is
2. **REST endpoints** - minimal changes (import updates)
3. **PromoService** - already @ApplicationScoped, no changes needed

## Rollback Plan

If migration fails:
1. Revert to WAR packaging in pom.xml
2. Revert Java EE dependencies
3. Restore original EJB annotations
4. Restore JMS code
5. Restore src/main/webapp structure
6. Re-deploy to WildFly/JBoss EAP

Commit strategy:
- Phase 1: Build config changes (separate commit)
- Phase 2: Application config (separate commit)
- Phase 3: EJB conversions (separate commit per service class)
- Phase 4: Messaging (separate commit)
- Phase 5-6: Cleanup (separate commit)

Each commit should be buildable (may not run, but should compile).

## Dependencies and Prerequisites

### Build-time
- Maven 3.8.1+
- JDK 11 or 17
- Access to Maven Central or internal Maven repository

### Runtime
- PostgreSQL 13+ or H2 (embedded for dev)
- 512MB heap minimum
- No application server required

### External Systems
- Database (PostgreSQL or H2)
- Keycloak (if OIDC/OAuth2 is enabled via keycloak.json)

## Post-Migration Enhancements (Out of Scope)

Future improvements after migration is complete:
1. Replace in-memory messaging with Kafka for production scalability
2. Add Hibernate Panache for repository pattern
3. Add Quarkus Dev Services for automatic database provisioning
4. Add OpenTelemetry for distributed tracing
5. Optimize frontend build (webpack, minification)
6. Add native image compilation profile testing
7. Replace @SessionScoped with stateless architecture (JWT + database)
8. Add Quarkus Mailer for email notifications (inventory threshold alerts)
9. Add Quarkus Scheduler for background jobs
10. Add Quarkus Security for endpoint authentication/authorization

## Appendix: Quick Reference

### Key Annotation Mappings
| Java EE 7 | Quarkus 3 |
|-----------|-----------|
| `@Stateless` | `@ApplicationScoped` |
| `@Stateful` | `@SessionScoped` (needs quarkus-undertow) |
| `@Singleton` + `@Startup` | `@ApplicationScoped` + `@Startup` |
| `@MessageDriven` | `@ApplicationScoped` + `@Incoming` |
| `@PersistenceContext` | `@Inject` (EntityManager) |
| `@Resource(lookup="...")` | `@Inject` (with config) |
| `@Remote` EJB | REST endpoint or CDI bean |
| `@TransactionAttribute` | `@Transactional` |
| `@ActivationConfigProperty` | `@Incoming(channel)` |
| JMS Topic | `@Channel` + `Emitter<T>` |

### Key Configuration Mappings
| Java EE 7 | Quarkus 3 |
|-----------|-----------|
| persistence.xml | application.properties (quarkus.datasource.*) |
| JNDI datasource lookup | Injected datasource |
| beans.xml | Not needed (implicit CDI) |
| web.xml | application.properties (quarkus.http.*) |
| ejb-jar.xml | Annotations + application.properties |

### Import Namespace Changes
| Old (javax) | New (jakarta) |
|-------------|---------------|
| `javax.persistence.*` | `jakarta.persistence.*` |
| `javax.transaction.*` | `jakarta.transaction.*` |
| `javax.ws.rs.*` | `jakarta.ws.rs.*` |
| `javax.inject.*` | `jakarta.inject.*` |
| `javax.enterprise.*` | `jakarta.enterprise.*` |
| `javax.annotation.*` | `jakarta.annotation.*` |

---

**Document Version**: 1.0  
**Author**: Migration Assessment Tool  
**Date**: 2026-08-24  
**Target Quarkus Version**: 3.8.x  
**Source Application Version**: 1.0.0-SNAPSHOT
