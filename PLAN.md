# EJB to Quarkus 3 Migration Plan

## Executive Summary

This plan focuses exclusively on converting Enterprise JavaBeans (EJB) components in the coolstore Java EE 7 monolith application to their Quarkus 3 / CDI equivalents. The migration will convert session beans, message-driven beans, and EJB remote lookups while preserving messaging topology and REST base path (`/services`).

**Scope**: EJB components only
**Out of Scope**: Persistence.xml/JPA entities, JAX-RS endpoints (except where EJB conversions require changes), view layer, and build tooling beyond required Quarkus extensions

---

## 1. EJB Inventory

### 1.1 @Stateless Session Beans

| Class | Package | Current Annotations | Target Conversion | Dependencies |
|-------|---------|-------------------|------------------|--------------|
| **CatalogService** | com.redhat.coolstore.service | `@Stateless` | `@ApplicationScoped` | EntityManager, Logger |
| **OrderService** | com.redhat.coolstore.service | `@Stateless` | `@ApplicationScoped` | EntityManager, audit logging library |
| **ProductService** | com.redhat.coolstore.service | `@Stateless` | `@ApplicationScoped` | CatalogService |
| **ShoppingCartOrderProcessor** | com.redhat.coolstore.service | `@Stateless` | `@ApplicationScoped` | JMSContext, Topic (orders), Logger |
| **ShippingService** | com.redhat.coolstore.service | `@Stateless`, `@Remote` | `@ApplicationScoped` (remove @Remote) | ShoppingCart model |

**Conversion Strategy**: Replace `@Stateless` with `@ApplicationScoped`. These are stateless service beans with no conversational state, making them ideal candidates for standard CDI scoped beans.

### 1.2 @Stateful Session Bean

| Class | Package | Current Annotations | State Management | Target Conversion |
|-------|---------|-------------------|------------------|------------------|
| **ShoppingCartService** | com.redhat.coolstore.service | `@Stateful` | Single `ShoppingCart cart` field | `@ApplicationScoped` with `Map<String, ShoppingCart>` keyed by cartId |

**Conversion Strategy**: 
- The current `@Stateful` bean holds one `ShoppingCart` instance per EJB instance
- CartEndpoint is `@SessionScoped` and already passes `cartId` to all ShoppingCartService methods
- Convert to `@ApplicationScoped` singleton with internal `Map<String, ShoppingCart>` to store carts by cartId
- The cartId parameter already exists in the API (e.g., `getShoppingCart(String cartId)`)
- This preserves the per-user cart isolation using the existing identifier

### 1.3 @Singleton + @Startup Bean

| Class | Package | Current Annotations | Purpose | Target Conversion |
|-------|---------|-------------------|---------|------------------|
| **DataBaseMigrationStartup** | com.redhat.coolstore.utils | `@Singleton`, `@Startup`, `@TransactionManagement(BEAN)` | Flyway database migration on startup | `@ApplicationScoped` + `void onStart(@Observes StartupEvent ev)` |

**Conversion Strategy**: 
- Replace `@Singleton` with `@ApplicationScoped`
- Move logic from `@PostConstruct startup()` to `void onStart(@Observes StartupEvent ev)` method
- Import `io.quarkus.runtime.StartupEvent`
- Keep transaction management as-is (bean-managed)

### 1.4 @MessageDriven Beans (MDBs)

| Class | Package | Current Config | Destination | Ack Mode | Target Conversion |
|-------|---------|----------------|-------------|----------|------------------|
| **OrderServiceMDB** | com.redhat.coolstore.service | `@MessageDriven`, activationConfig: `destinationLookup="topic/orders"`, `destinationType=javax.jms.Topic`, `acknowledgeMode=Auto-acknowledge` | topic/orders | Auto | SmallRye `@Incoming("orders")` with `@Broadcast` |
| **InventoryNotificationMDB** | com.redhat.coolstore.service | No annotation (incomplete MDB) - implements MessageListener, has JNDI lookup code for topic/orders | topic/orders | Auto | SmallRye `@Incoming("orders")` with `@Broadcast` |

**Conversion Strategy**:
- Convert to SmallRye Reactive Messaging with `@Incoming` annotation
- Use `@Broadcast` to preserve topic fan-out (multiple consumers on same topic)
- Configure channels in `application.properties` mapping to JMS topics
- Replace `MessageListener.onMessage(Message)` with methods accepting `String` payload
- Remove JNDI lookups and activation config

### 1.5 EJB Remote Interfaces and JNDI Lookups

| Interface | Implementation | Lookup Location | Used By | Target Conversion |
|-----------|----------------|-----------------|---------|------------------|
| **ShippingServiceRemote** | ShippingService | `ejb:/ROOT/ShippingService!com.redhat.coolstore.service.ShippingServiceRemote` | ShoppingCartService.lookupShippingServiceRemote() | Remove interface marker, direct `@Inject ShippingService` |

**Conversion Strategy**:
- Remove `@Remote` annotation from ShippingService
- Keep ShippingServiceRemote interface (for method signatures) or inline methods
- In ShoppingCartService, replace `lookupShippingServiceRemote()` JNDI call with `@Inject ShippingService shippingService`
- Remove the static JNDI lookup method entirely
- Both beans will be in the same deployment, so direct CDI injection works

### 1.6 Additional JNDI Lookups

| Location | Lookup Target | Type | Conversion |
|----------|--------------|------|------------|
| InventoryNotificationMDB.init() | `JMS_FACTORY` = "TCF", `TOPIC` = "topic/orders" | TopicConnectionFactory, Topic | Remove entire manual connection setup; SmallRye handles it |
| ShoppingCartOrderProcessor | `@Resource(lookup = "java:/topic/orders")` Topic ordersTopic | JMS Topic | Configure via SmallRye channel, inject `Emitter<String>` |

---

## 2. Messaging Topology

### 2.1 Current JMS Architecture

**Topic**: `topic/orders` (JMS Topic for fan-out)

```
Producer:
  ShoppingCartOrderProcessor.process(ShoppingCart cart)
    ↓ (publishes JSON via JMSContext.createProducer().send())
    ↓
topic/orders (JMS Topic)
    ↓
    ├─→ Consumer 1: OrderServiceMDB.onMessage()
    │   - Saves Order to database
    │   - Updates inventory quantities
    │
    └─→ Consumer 2: InventoryNotificationMDB.onMessage()
        - Checks inventory thresholds
        - Logs low-stock warnings
```

### 2.2 Target Quarkus Reactive Messaging Architecture

**Channel**: `orders` (mapped to JMS topic)

```
Producer:
  ShoppingCartOrderProcessor
    - @Inject Emitter<String> ordersEmitter
    - ordersEmitter.send(cartJson)
    ↓
Channel: "orders" 
  (configured as connector=smallrye-jms, destination=orders)
    ↓
    ├─→ @Incoming("orders") @Broadcast
    │   OrderServiceMDB.processOrder(String orderJson)
    │
    └─→ @Incoming("orders") @Broadcast  
        InventoryNotificationMDB.checkInventory(String orderJson)
```

**Topology Preservation Requirements**:
- **Topic fan-out**: Must be preserved using `@Broadcast` annotation on each `@Incoming` method
- **Message format**: JSON String (unchanged, using Transformers.shoppingCartToJson() and Transformers.jsonToOrder())
- **Delivery semantics**: At-most-once (auto-acknowledge) → defaults in SmallRye
- **Multiple consumers**: Both MDBs must receive each message independently

### 2.3 SmallRye Configuration (application.properties)

```properties
# Outgoing channel (producer)
mp.messaging.outgoing.orders.connector=smallrye-jms
mp.messaging.outgoing.orders.destination=orders
mp.messaging.outgoing.orders.destination-type=topic

# Incoming channel (consumers - shared config)
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true
```

---

## 3. Session Bean Conversion Details

### 3.1 @Stateless → @ApplicationScoped (5 beans)

**Pattern**:
```java
// BEFORE (Java EE)
@Stateless
public class ExampleService {
    @Inject
    private EntityManager em;
}

// AFTER (Quarkus)
@ApplicationScoped
public class ExampleService {
    @Inject
    EntityManager em;
}
```

**Affected Classes**:
1. **CatalogService**: Remove `@Stateless`, add `@ApplicationScoped`
2. **OrderService**: Remove `@Stateless`, add `@ApplicationScoped` (keep @PostConstruct/@PreDestroy for audit logger)
3. **ProductService**: Remove `@Stateless`, add `@ApplicationScoped`
4. **ShoppingCartOrderProcessor**: Remove `@Stateless`, add `@ApplicationScoped` (messaging conversion below)
5. **ShippingService**: Remove `@Stateless` and `@Remote`, add `@ApplicationScoped`

**Import Changes**:
- Remove: `import javax.ejb.Stateless;`
- Add: `import jakarta.enterprise.context.ApplicationScoped;`

### 3.2 @Stateful → @ApplicationScoped + Map (1 bean)

**ShoppingCartService Conversion**:

```java
// BEFORE (Java EE)
@Stateful
public class ShoppingCartService {
    private ShoppingCart cart = new ShoppingCart();
    
    public ShoppingCart getShoppingCart(String cartId) {
        return cart;
    }
}

// AFTER (Quarkus)
@ApplicationScoped
public class ShoppingCartService {
    private final Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();
    
    public ShoppingCart getShoppingCart(String cartId) {
        return carts.computeIfAbsent(cartId, id -> new ShoppingCart());
    }
    
    // Update all methods to use carts.get(cartId) or carts.computeIfAbsent(cartId, ...)
}
```

**Key Changes**:
- Replace single `cart` field with `Map<String, ShoppingCart> carts`
- Use `ConcurrentHashMap` for thread safety
- All methods already receive `cartId` parameter
- Use `computeIfAbsent()` to auto-create carts on first access
- `checkOutShoppingCart()` should clear items but keep cart in map (or remove and recreate)

**Import Changes**:
- Remove: `import javax.ejb.Stateful;`
- Add: `import jakarta.enterprise.context.ApplicationScoped;`
- Add: `import java.util.Map;`, `import java.util.concurrent.ConcurrentHashMap;`

### 3.3 @Singleton + @Startup → @ApplicationScoped + StartupEvent (1 bean)

**DataBaseMigrationStartup Conversion**:

```java
// BEFORE (Java EE)
@Singleton
@Startup
@TransactionManagement(TransactionManagementType.BEAN)
public class DataBaseMigrationStartup {
    @PostConstruct
    private void startup() {
        // Flyway migration logic
    }
}

// AFTER (Quarkus)
@ApplicationScoped
public class DataBaseMigrationStartup {
    void onStart(@Observes StartupEvent ev) {
        // Flyway migration logic (moved from startup())
    }
}
```

**Import Changes**:
- Remove: `import javax.ejb.Singleton;`, `import javax.ejb.Startup;`, `import javax.ejb.TransactionManagement;`, `import javax.ejb.TransactionManagementType;`, `import javax.annotation.PostConstruct;`
- Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.enterprise.event.Observes;`, `import io.quarkus.runtime.StartupEvent;`
- Remove `@TransactionManagement` annotation (not needed in Quarkus)

---

## 4. Message-Driven Bean to Reactive Messaging Conversion

### 4.1 OrderServiceMDB Conversion

**BEFORE (Java EE MDB)**:
```java
@MessageDriven(name = "OrderServiceMDB", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "topic/orders"),
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
    @ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge")
})
public class OrderServiceMDB implements MessageListener {
    @Override
    public void onMessage(Message rcvMessage) {
        TextMessage msg = (TextMessage) rcvMessage;
        String orderStr = msg.getBody(String.class);
        // Process order
    }
}
```

**AFTER (Quarkus SmallRye)**:
```java
@ApplicationScoped
public class OrderServiceMDB {
    
    @Inject
    OrderService orderService;
    
    @Inject
    CatalogService catalogService;
    
    @Incoming("orders")
    @Broadcast
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

**Import Changes**:
- Remove: `javax.ejb.*`, `javax.jms.*`
- Add: `jakarta.enterprise.context.ApplicationScoped`, `org.eclipse.microprofile.reactive.messaging.Incoming`, `io.smallrye.reactive.messaging.annotations.Broadcast`
- Remove `implements MessageListener`

### 4.2 InventoryNotificationMDB Conversion

**BEFORE (Java EE partial MDB with manual JNDI)**:
```java
public class InventoryNotificationMDB implements MessageListener {
    public void onMessage(Message rcvMessage) {
        // Process message
    }
    
    public void init() throws NamingException, JMSException {
        Context ctx = getInitialContext();
        TopicConnectionFactory tconFactory = ...;
        // Manual JMS setup
    }
}
```

**AFTER (Quarkus SmallRye)**:
```java
@ApplicationScoped
public class InventoryNotificationMDB {
    
    private static final int LOW_THRESHOLD = 50;
    
    @Inject
    private CatalogService catalogService;
    
    @Incoming("orders")
    @Broadcast
    public void checkInventory(String orderJson) {
        System.out.println("received message inventory");
        Order order = Transformers.jsonToOrder(orderJson);
        order.getItemList().forEach(orderItem -> {
            int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId())
                .getInventory().getQuantity();
            int new_quantity = old_quantity - orderItem.getQuantity();
            if (new_quantity < LOW_THRESHOLD) {
                System.out.println("Inventory for item " + orderItem.getProductId() + 
                    " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
            }
        });
    }
}
```

**Import Changes**:
- Remove: `javax.jms.*`, `javax.naming.*`, `javax.rmi.PortableRemoteObject`, `java.util.Hashtable`
- Add: `jakarta.enterprise.context.ApplicationScoped`, `org.eclipse.microprofile.reactive.messaging.Incoming`, `io.smallrye.reactive.messaging.annotations.Broadcast`
- Remove `implements MessageListener`, `init()`, `close()`, `getInitialContext()` methods

### 4.3 ShoppingCartOrderProcessor (JMS Producer) Conversion

**BEFORE (Java EE)**:
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

**AFTER (Quarkus SmallRye)**:
```java
@ApplicationScoped
public class ShoppingCartOrderProcessor {
    
    @Inject
    Logger log;
    
    @Inject
    @Channel("orders")
    Emitter<String> ordersEmitter;
    
    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        ordersEmitter.send(Transformers.shoppingCartToJson(cart));
    }
}
```

**Import Changes**:
- Remove: `javax.ejb.Stateless`, `javax.annotation.Resource`, `javax.jms.JMSContext`, `javax.jms.Topic`
- Add: `jakarta.enterprise.context.ApplicationScoped`, `org.eclipse.microprofile.reactive.messaging.Channel`, `org.eclipse.microprofile.reactive.messaging.Emitter`

---

## 5. EJB Remote to Local CDI Injection

### 5.1 Remove @Remote Interface Marker

**ShippingService.java**:
```java
// BEFORE
@Stateless
@Remote
public class ShippingService implements ShippingServiceRemote {
    // methods
}

// AFTER
@ApplicationScoped
public class ShippingService implements ShippingServiceRemote {
    // methods (unchanged)
}
```

### 5.2 Replace JNDI Lookup with @Inject

**ShoppingCartService.java**:

**BEFORE**:
```java
private static ShippingServiceRemote lookupShippingServiceRemote() {
    try {
        final Hashtable<String, String> jndiProperties = new Hashtable<>();
        jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, 
            "org.wildfly.naming.client.WildFlyInitialContextFactory");
        final Context context = new InitialContext(jndiProperties);
        return (ShippingServiceRemote) context.lookup(
            "ejb:/ROOT/ShippingService!" + ShippingServiceRemote.class.getName());
    } catch (NamingException e) {
        throw new RuntimeException(e);
    }
}

public void priceShoppingCart(ShoppingCart sc) {
    // ...
    sc.setShippingTotal(lookupShippingServiceRemote().calculateShipping(sc));
    // ...
    sc.setShippingTotal(sc.getShippingTotal()
        + lookupShippingServiceRemote().calculateShippingInsurance(sc));
    // ...
}
```

**AFTER**:
```java
@Inject
ShippingService shippingService;

public void priceShoppingCart(ShoppingCart sc) {
    // ...
    sc.setShippingTotal(shippingService.calculateShipping(sc));
    // ...
    sc.setShippingTotal(sc.getShippingTotal()
        + shippingService.calculateShippingInsurance(sc));
    // ...
}
```

**Import Changes**:
- Remove: `import java.util.Hashtable;`, `import javax.naming.Context;`, `import javax.naming.InitialContext;`, `import javax.naming.NamingException;`
- Remove: `import com.redhat.coolstore.service.ShippingServiceRemote;` (if only used for lookup)
- Add: `import com.redhat.coolstore.service.ShippingService;`
- Remove the entire `lookupShippingServiceRemote()` static method

---

## 6. Required Quarkus Extensions

Add only the extensions needed for EJB conversions:

### 6.1 Core CDI and Messaging
```bash
mvn quarkus:add-extension -Dextensions="cdi"                    # CDI beans (implicit in most configs)
mvn quarkus:add-extension -Dextensions="smallrye-reactive-messaging-jms"  # Reactive messaging for MDB replacement
mvn quarkus:add-extension -Dextensions="artemis-jms"             # JMS provider (ActiveMQ Artemis)
```

### 6.2 Supporting Extensions
```bash
mvn quarkus:add-extension -Dextensions="hibernate-orm"          # Already used, ensure present
mvn quarkus:add-extension -Dextensions="jdbc-postgresql"         # DB driver (assuming PostgreSQL)
mvn quarkus:add-extension -Dextensions="flyway"                  # Database migration (already used)
mvn quarkus:add-extension -Dextensions="resteasy-reactive-jackson"  # JAX-RS + JSON
```

### 6.3 POM Dependency Summary

**Add**:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-reactive-messaging-jms</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-artemis-jms</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-flyway</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
</dependency>
```

**Remove**:
```xml
<!-- Remove Java EE dependencies -->
<dependency>
    <groupId>javax</groupId>
    <artifactId>javaee-web-api</artifactId>
</dependency>
<dependency>
    <groupId>javax</groupId>
    <artifactId>javaee-api</artifactId>
</dependency>
<dependency>
    <groupId>org.jboss.spec.javax.jms</groupId>
    <artifactId>jboss-jms-api_2.0_spec</artifactId>
</dependency>
<dependency>
    <groupId>org.jboss.spec.javax.rmi</groupId>
    <artifactId>jboss-rmi-api_1.0_spec</artifactId>
</dependency>
```

---

## 7. Configuration Files

### 7.1 application.properties (New File)

Create `src/main/resources/application.properties`:

```properties
# Application name
quarkus.application.name=coolstore-monolith

# REST configuration - preserve /services base path
quarkus.resteasy-reactive.path=/services

# Datasource configuration
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=coolstore
quarkus.datasource.password=coolstore
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/coolstore

# Hibernate configuration
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.sql-load-script=no-file
quarkus.hibernate-orm.log.sql=false

# Flyway migration
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration

# JMS / Artemis configuration (embedded for dev/test)
quarkus.artemis.url=tcp://localhost:61616
quarkus.artemis.username=admin
quarkus.artemis.password=admin

# Reactive Messaging - Orders topic (producer)
mp.messaging.outgoing.orders.connector=smallrye-jms
mp.messaging.outgoing.orders.destination=orders
mp.messaging.outgoing.orders.destination-type=topic

# Reactive Messaging - Orders topic (consumers)
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true

# Logging
quarkus.log.level=INFO
quarkus.log.category."com.redhat.coolstore".level=INFO
```

### 7.2 Update persistence.xml (Minimal Changes)

Update `src/main/resources/META-INF/persistence.xml` to use Quarkus datasource reference:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence version="2.1"
             xmlns="http://xmlns.jcp.org/xml/ns/persistence" 
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/persistence
             http://xmlns.jcp.org/xml/ns/persistence/persistence_2_1.xsd">
    <persistence-unit name="primary" transaction-type="JTA">
        <!-- Remove jta-data-source, Quarkus handles this -->
        <properties>
            <property name="javax.persistence.schema-generation.database.action" value="none"/>
            <property name="hibernate.show_sql" value="false" />
            <property name="hibernate.format_sql" value="true" />
        </properties>
    </persistence-unit>
</persistence>
```

### 7.3 Update beans.xml (CDI 2.0 for Quarkus)

Update `src/main/webapp/WEB-INF/beans.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://xmlns.jcp.org/xml/ns/javaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
       http://xmlns.jcp.org/xml/ns/javaee/beans_2_0.xsd"
       version="2.0"
       bean-discovery-mode="all">
</beans>
```

---

## 8. Package Import Updates

All affected Java files will need import statement updates:

### 8.1 Javax → Jakarta Migration

| Old Import (javax.*) | New Import (jakarta.*) |
|---------------------|----------------------|
| `javax.ejb.*` | **Remove** (no jakarta.ejb in Quarkus) |
| `javax.inject.Inject` | `jakarta.inject.Inject` |
| `javax.enterprise.context.*` | `jakarta.enterprise.context.*` |
| `javax.ws.rs.*` | `jakarta.ws.rs.*` |
| `javax.persistence.*` | `jakarta.persistence.*` |
| `javax.annotation.*` | `jakarta.annotation.*` |
| `javax.jms.*` | **Remove** (use SmallRye reactive messaging) |
| `javax.naming.*` | **Remove** (no JNDI in Quarkus) |

### 8.2 New Quarkus Imports

Add as needed per conversion above:
- `io.quarkus.runtime.StartupEvent`
- `jakarta.enterprise.event.Observes`
- `org.eclipse.microprofile.reactive.messaging.Incoming`
- `org.eclipse.microprofile.reactive.messaging.Channel`
- `org.eclipse.microprofile.reactive.messaging.Emitter`
- `io.smallrye.reactive.messaging.annotations.Broadcast`

---

## 9. Execution Order

### Phase 1: Stateless Session Beans (Lowest Risk)
1. Convert CatalogService (@Stateless → @ApplicationScoped)
2. Convert ProductService (@Stateless → @ApplicationScoped)
3. Convert OrderService (@Stateless → @ApplicationScoped)
4. Convert ShippingService (@Stateless @Remote → @ApplicationScoped, remove @Remote)

### Phase 2: EJB Remote to CDI Injection
5. In ShoppingCartService, replace JNDI lookup with @Inject ShippingService
6. Remove lookupShippingServiceRemote() method
7. Update method calls from lookupShippingServiceRemote().method() to shippingService.method()

### Phase 3: Stateful to ApplicationScoped + Map
8. Convert ShoppingCartService (@Stateful → @ApplicationScoped with Map<String, ShoppingCart>)
9. Update all methods to use map-based cart storage

### Phase 4: Singleton + Startup
10. Convert DataBaseMigrationStartup (@Singleton @Startup → @ApplicationScoped + StartupEvent)

### Phase 5: Messaging Producer
11. Convert ShoppingCartOrderProcessor (JMS Producer → SmallRye Emitter)
12. Add application.properties with outgoing channel config

### Phase 6: Message-Driven Beans (Consumers)
13. Convert OrderServiceMDB (@MessageDriven → @ApplicationScoped + @Incoming @Broadcast)
14. Convert InventoryNotificationMDB (manual JNDI → @ApplicationScoped + @Incoming @Broadcast)
15. Update application.properties with incoming channel config

### Phase 7: Dependencies and Configuration
16. Update pom.xml: remove Java EE deps, add Quarkus BOM and extensions
17. Create/update application.properties with complete config
18. Update persistence.xml (remove jta-data-source reference)
19. Update beans.xml to CDI 2.0

### Phase 8: Import Cleanup
20. Global search-replace: javax.inject → jakarta.inject
21. Global search-replace: javax.enterprise → jakarta.enterprise
22. Global search-replace: javax.ws.rs → jakarta.ws.rs
23. Global search-replace: javax.persistence → jakarta.persistence
24. Remove all javax.ejb.* imports
25. Remove all javax.jms.* imports
26. Remove all javax.naming.* imports

---

## 10. Verification Criteria

### 10.1 Build Verification
```bash
mvn clean package -DskipTests
```
**Success Criteria**: 
- Build completes without errors
- `target/quarkus-app/quarkus-run.jar` exists

### 10.2 Startup Verification
```bash
java -jar target/quarkus-app/quarkus-run.jar
```
**Success Criteria**:
- Application starts without exceptions
- Flyway migration executes successfully
- JMS/Artemis connection established
- CDI beans instantiated
- JAX-RS endpoints available at `/services`

### 10.3 Functional Verification (Manual Testing)

**REST Endpoints**:
- `GET /services/products` - List products (ProductService via CatalogService)
- `GET /services/cart/{cartId}` - Get cart (ShoppingCartService with Map)
- `POST /services/cart/{cartId}/{itemId}/{quantity}` - Add to cart
- `POST /services/cart/checkout/{cartId}` - Checkout (triggers messaging)

**Messaging Flow**:
1. Checkout cart triggers ShoppingCartOrderProcessor
2. Message sent to "orders" topic
3. OrderServiceMDB receives and saves order
4. InventoryNotificationMDB receives and checks thresholds
5. Both consumers log their processing

**Database**:
- Orders saved to database
- Inventory quantities updated
- Flyway migrations applied at startup

### 10.4 Logs to Verify

Look for:
```
INFO  [io.quarkus] (main) coolstore-monolith 1.0.0-SNAPSHOT on JVM started
INFO  [com.red...DataBaseMigrationStartup] Initializing/migrating the database using FlyWay
INFO  [io.quarkus] (main) Installed features: [cdi, hibernate-orm, jdbc-postgresql, ...]
INFO  Sending order from processor:
INFO  Message recd !
INFO  received message inventory
```

---

## 11. Rollback Strategy

If verification fails at any phase:
1. Use git to revert to previous working state
2. Identify the failing component (build logs, startup logs)
3. Re-verify the conversion mapping for that component
4. Check import statements and annotation usage
5. Validate application.properties channel configuration matches code

---

## 12. Known Constraints and Assumptions

### 12.1 Assumptions
- PostgreSQL is the target database (update application.properties if different)
- Artemis JMS broker is available (or embedded for dev)
- CartEndpoint's @SessionScoped manages HTTP session; ShoppingCartService cartId parameter comes from session
- Audit logging library (com.enterprise.audit.logging) is compatible with Quarkus or will be addressed separately

### 12.2 Out of Scope (No Changes Required)
- JPA entities and repository patterns (already using standard JPA)
- JAX-RS endpoints (already using standard JAX-RS annotations)
- View layer (JSP pages remain unchanged)
- Build plugins beyond Quarkus Maven plugin
- Test infrastructure

### 12.3 Potential Issues
- **Audit logging library**: System-scoped dependency may need conversion to Maven repository or Quarkus-compatible version
- **Flyway version**: May need to use Quarkus-managed Flyway version
- **Transaction boundaries**: Verify @Transactional usage if container-managed transactions were implicit in EJBs

---

## 13. Success Metrics

- **EJB Removal**: 0 remaining @Stateless, @Stateful, @Singleton, @MessageDriven annotations
- **JNDI Removal**: 0 remaining InitialContext or lookup() calls
- **CDI Adoption**: All services use @ApplicationScoped with @Inject
- **Messaging Functional**: Topic fan-out preserved, both consumers receive messages
- **Build Success**: `mvn package -DskipTests` exits with code 0
- **Runtime Success**: Application starts and responds to REST requests
- **REST Path Preserved**: All endpoints accessible at `/services/*`

---

## End of Plan
