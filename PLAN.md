# EJB to Quarkus 3 Migration Plan

## Executive Summary

This migration plan focuses exclusively on converting Enterprise JavaBeans (EJB) components from Java EE 7 to their Quarkus 3 / CDI equivalents. The application contains:
- **6 Session Beans** (4 @Stateless, 1 @Stateful, 1 @Singleton with @Startup)
- **2 Message-Driven Beans** (MDBs)
- **1 EJB @Remote interface** with JNDI lookup

Out of scope: JPA/persistence.xml modifications (except where strictly required), JAX-RS endpoints, build tooling beyond necessary Quarkus extensions, and view layer changes.

## 1. EJB Inventory

### 1.1 Session Beans

#### @Stateless Session Beans (4 components)

| Class | Package | Type | Purpose | Dependencies | Quarkus Target |
|-------|---------|------|---------|--------------|----------------|
| `ShippingService` | `com.redhat.coolstore.service` | @Stateless + @Remote | Calculates shipping costs | None | @ApplicationScoped CDI bean |
| `ShoppingCartOrderProcessor` | `com.redhat.coolstore.service` | @Stateless | Publishes orders to JMS topic | JMSContext, Topic | @ApplicationScoped + Reactive Messaging Emitter |
| `CatalogService` | `com.redhat.coolstore.service` | @Stateless | Catalog/inventory operations | EntityManager | @ApplicationScoped CDI bean |
| `OrderService` | `com.redhat.coolstore.service` | @Stateless | Order persistence and retrieval | EntityManager, AuditLogger | @ApplicationScoped CDI bean |
| `ProductService` | `com.redhat.coolstore.service` | @Stateless | Product operations (delegates to CatalogService) | CatalogService | @ApplicationScoped CDI bean |

**Key Details:**
- All @Stateless beans are thread-safe and stateless → natural fit for @ApplicationScoped
- Current usage: all are @Inject'd, no EJB-specific lookups
- Transaction management: implicit container-managed transactions should continue with Quarkus Narayana

#### @Stateful Session Bean (1 component)

| Class | Package | Type | Purpose | State Management | Quarkus Target |
|-------|---------|------|---------|------------------|----------------|
| `ShoppingCartService` | `com.redhat.coolstore.service` | @Stateful | Manages shopping cart state per session | Single ShoppingCart instance per bean | @ApplicationScoped with Map<cartId, ShoppingCart> |

**Key Details:**
- Current state: `private ShoppingCart cart = new ShoppingCart();`
- Method signature: `getShoppingCart(String cartId)` - cartId already passed from REST layer
- Used by: `CartEndpoint` (@SessionScoped REST endpoint)
- **Conversion Strategy**: Convert to @ApplicationScoped bean with `ConcurrentHashMap<String, ShoppingCart>` keyed by cartId
- The cartId is already passed in all REST calls (`@PathParam("cartId")`), enabling stateless service pattern
- No need to widen scope - cart isolation maintained via key-based lookup

#### @Singleton with @Startup (1 component)

| Class | Package | Type | Purpose | Dependencies | Quarkus Target |
|-------|---------|------|---------|--------------|----------------|
| `DataBaseMigrationStartup` | `com.redhat.coolstore.utils` | @Singleton + @Startup | Runs Flyway DB migrations on startup | DataSource (via @Resource JNDI lookup) | @ApplicationScoped + @Observes StartupEvent |

**Key Details:**
- Runs database migration via Flyway in @PostConstruct
- Uses `@Resource(mappedName = "java:jboss/datasources/CoolstoreDS")`
- **Conversion Strategy**: 
  - Replace @Singleton/@Startup with @ApplicationScoped
  - Replace @PostConstruct with method observing `StartupEvent`
  - Replace @Resource DataSource lookup with @Inject DataSource
  - Configure datasource name in application.properties

### 1.2 Message-Driven Beans (2 components)

| Class | Package | Destination | Topic/Queue | Consumer Type | Purpose | Quarkus Target |
|-------|---------|-------------|-------------|---------------|---------|----------------|
| `OrderServiceMDB` | `com.redhat.coolstore.service` | topic/orders | Topic | Standard subscriber | Persists orders, updates inventory | @ApplicationScoped + @Incoming("orders") |
| `InventoryNotificationMDB` | `com.redhat.coolstore.service` | topic/orders | Topic | Manual WebLogic setup (not deployed) | Low inventory warnings | @ApplicationScoped + @Incoming("orders-inventory") |

**OrderServiceMDB Details:**
- Activation Config: `destinationLookup=topic/orders`, `destinationType=javax.jms.Topic`, `acknowledgeMode=Auto-acknowledge`
- Receives JSON order messages
- Persists via OrderService.save()
- Updates inventory via CatalogService.updateInventoryItems()

**InventoryNotificationMDB Details:**
- **CRITICAL**: This class implements MessageListener but lacks @MessageDriven annotation in current code
- Has manual WebLogic-specific JNDI setup code (init/close methods)
- Currently **not functional** in Java EE deployment (no activation config)
- Subscribes to same topic/orders for inventory alerts
- Should be converted but with NOTE that it requires separate configuration to enable

### 1.3 EJB Remote Interfaces and Lookups

| Interface | Implementation | Lookup Location | Purpose | Quarkus Target |
|-----------|----------------|-----------------|---------|----------------|
| `ShippingServiceRemote` | `ShippingService` | `ShoppingCartService.lookupShippingServiceRemote()` | Calculate shipping costs | Remove interface, direct @Inject |

**JNDI Lookup Details:**
```java
// Current EJB remote lookup in ShoppingCartService
private static ShippingServiceRemote lookupShippingServiceRemote() {
    final Hashtable<String, String> jndiProperties = new Hashtable<>();
    jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, 
                      "org.wildfly.naming.client.WildFlyInitialContextFactory");
    final Context context = new InitialContext(jndiProperties);
    return (ShippingServiceRemote) context.lookup(
        "ejb:/ROOT/ShippingService!" + ShippingServiceRemote.class.getName());
}
```

**Conversion Strategy:**
- ShippingService and ShippingServiceRemote are in the same deployment (monolith)
- Remove @Remote annotation from ShippingService
- Remove ShippingServiceRemote interface (or keep as local interface for contract)
- Replace JNDI lookup with `@Inject ShippingService` in ShoppingCartService
- Update method calls from interface to direct service invocation

## 2. Messaging Topology

### 2.1 JMS Topic Architecture (Current Java EE 7)

```
Producer                    Broker                      Consumers
┌─────────────────┐        ┌──────────────┐           ┌──────────────────┐
│ShoppingCart     │        │              │           │OrderServiceMDB   │
│OrderProcessor   │───────▶│ topic/orders │──────────▶│ - Persist order  │
│ (Stateless)     │        │   (Topic)    │           │ - Update inv     │
└─────────────────┘        │              │           └──────────────────┘
                           │              │
  JMSContext.              │              │           ┌──────────────────┐
  createProducer()         │              │──────────▶│InventoryNotify  │
  .send(topic, json)       └──────────────┘           │MDB               │
                                                      │ - Inventory warn │
                                                      └──────────────────┘
```

**Topic Fan-out Semantics:**
- JNDI Name: `java:/topic/orders` (Java EE) → `orders` (Quarkus)
- Type: JMS Topic (pub/sub, multiple consumers receive each message)
- Message Format: JSON string (ShoppingCart serialized via Transformers.shoppingCartToJson())
- Consumers: 2 MDBs listening to same topic (fan-out pattern)

### 2.2 Quarkus Reactive Messaging Target Architecture

```
Producer                    Broker                      Consumers
┌─────────────────┐        ┌──────────────┐           ┌──────────────────┐
│ShoppingCart     │        │              │           │OrderService      │
│OrderProcessor   │        │ mp.messaging │           │Handler           │
│ @Inject Emitter │───────▶│  .outgoing.  │──────────▶│@Incoming        │
│ <String>        │        │  orders      │           │("orders")        │
└─────────────────┘        │              │           └──────────────────┘
                           │              │
  emitter.send(json)       │              │           ┌──────────────────┐
                           │              │──────────▶│InventoryNotify  │
                           │ mp.messaging │           │Handler           │
                           │  .incoming.  │           │@Incoming         │
                           │  orders      │           │("orders-        │
                           └──────────────┘           │ inventory")      │
                                                      └──────────────────┘
```

**Configuration Requirements (application.properties):**

```properties
# Outgoing channel (producer)
mp.messaging.outgoing.orders.connector=smallrye-jms
mp.messaging.outgoing.orders.destination=orders
mp.messaging.outgoing.orders.destination-type=topic

# Incoming channel 1 (OrderService consumer)
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true

# Incoming channel 2 (InventoryNotification consumer)
mp.messaging.incoming.orders-inventory.connector=smallrye-jms
mp.messaging.incoming.orders-inventory.destination=orders
mp.messaging.incoming.orders-inventory.destination-type=topic
mp.messaging.incoming.orders-inventory.broadcast=true
```

**Preservation of Topic Fan-out:**
- `broadcast=true` enables multicast: each subscriber receives its own copy of messages
- Both consumers connect to same topic but via different channel names
- Maintains JMS Topic pub/sub semantics in reactive messaging model

### 2.3 Message Flow Details

| Producer | Method | Message Type | Target Channel | Consumers |
|----------|--------|--------------|----------------|-----------|
| ShoppingCartOrderProcessor | process() | JSON (ShoppingCart) | orders | orders, orders-inventory |

**Message Format (JSON):**
```json
{
  "orderValue": 123.45,
  "customerName": "Sven Karlsson",
  "customerEmail": "sven@gmail.com",
  "retailPrice": 100.00,
  "discount": -5.00,
  "shippingFee": 10.00,
  "shippingDiscount": 0.00,
  "items": [
    {"productSku": "329299", "quantity": 2}
  ]
}
```

## 3. Session Bean Conversion Plan

### 3.1 @Stateless → @ApplicationScoped (5 beans)

#### 3.1.1 ShippingService
**Changes Required:**
1. Remove `@Stateless` annotation
2. Remove `@Remote` annotation  
3. Add `@ApplicationScoped` annotation
4. Keep `ShippingServiceRemote` interface as local contract (optional - can inline methods)

**File:** `src/main/java/com/redhat/coolstore/service/ShippingService.java`

**Before:**
```java
@Stateless
@Remote
public class ShippingService implements ShippingServiceRemote {
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ShippingService implements ShippingServiceRemote {
```

**Impact:** None - already used via @Inject in ShoppingCartService (after JNDI removal)

---

#### 3.1.2 ShoppingCartOrderProcessor
**Changes Required:**
1. Remove `@Stateless` annotation
2. Add `@ApplicationScoped` annotation
3. Replace JMS API with Reactive Messaging Emitter
4. Remove `@Inject JMSContext` and `@Resource Topic`
5. Add `@Channel("orders") Emitter<String>` injection

**File:** `src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java`

**Before:**
```java
@Stateless
public class ShoppingCartOrderProcessor {
    @Inject
    private transient JMSContext context;

    @Resource(lookup = "java:/topic/orders")
    private Topic ordersTopic;

    public void process(ShoppingCart cart) {
        log.info("Sending order from processor: ");
        context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
    }
}
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

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

**Impact:** ShoppingCartService continues to call `process()` - no changes needed in caller

---

#### 3.1.3 CatalogService
**Changes Required:**
1. Remove `@Stateless` annotation
2. Add `@ApplicationScoped` annotation
3. EntityManager injection remains unchanged (produced by Resources.java)

**File:** `src/main/java/com/redhat/coolstore/service/CatalogService.java`

**Before:**
```java
@Stateless
public class CatalogService {
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CatalogService {
```

**Impact:** None - already used via @Inject

---

#### 3.1.4 OrderService
**Changes Required:**
1. Remove `@Stateless` annotation
2. Add `@ApplicationScoped` annotation
3. EntityManager injection remains unchanged
4. @PostConstruct/@PreDestroy remain functional in CDI

**File:** `src/main/java/com/redhat/coolstore/service/OrderService.java`

**Before:**
```java
@Stateless
public class OrderService {
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderService {
```

**Impact:** None - already used via @Inject

---

#### 3.1.5 ProductService
**Changes Required:**
1. Remove `@Stateless` annotation
2. Add `@ApplicationScoped` annotation

**File:** `src/main/java/com/redhat/coolstore/service/ProductService.java`

**Before:**
```java
@Stateless
public class ProductService {
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ProductService {
```

**Impact:** None - already used via @Inject

---

### 3.2 @Stateful → @ApplicationScoped with State Map

#### 3.2.1 ShoppingCartService
**Changes Required:**
1. Remove `@Stateful` annotation
2. Add `@ApplicationScoped` annotation
3. Replace single `ShoppingCart cart` with `ConcurrentHashMap<String, ShoppingCart>`
4. Update all methods to use cartId parameter for state lookup
5. Replace JNDI remote lookup with `@Inject ShippingService`

**File:** `src/main/java/com/redhat/coolstore/service/ShoppingCartService.java`

**Before:**
```java
@Stateful
public class ShoppingCartService {
    private ShoppingCart cart = new ShoppingCart();
    
    public ShoppingCart getShoppingCart(String cartId) {
        return cart;
    }
    
    private static ShippingServiceRemote lookupShippingServiceRemote() {
        // JNDI lookup code
    }
}
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@ApplicationScoped
public class ShoppingCartService {
    private final Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();
    
    @Inject
    ShippingService shippingService;
    
    public ShoppingCart getShoppingCart(String cartId) {
        return carts.computeIfAbsent(cartId, id -> new ShoppingCart());
    }
    
    // Update all methods to use shippingService directly instead of lookup
    // Example: shippingService.calculateShipping(sc)
}
```

**Methods to Update:**
- `getShoppingCart(String cartId)` - add computeIfAbsent logic
- `checkOutShoppingCart(String cartId)` - use map lookup, clear cart after order
- `priceShoppingCart(ShoppingCart sc)` - replace `lookupShippingServiceRemote()` with `shippingService`

**Impact:** 
- CartEndpoint continues to pass cartId in all calls - no REST API changes
- Thread-safe concurrent access via ConcurrentHashMap
- Cart isolation maintained per cartId

---

### 3.3 @Singleton + @Startup → @ApplicationScoped + StartupEvent

#### 3.3.1 DataBaseMigrationStartup
**Changes Required:**
1. Remove `@Singleton`, `@Startup`, `@TransactionManagement` annotations
2. Add `@ApplicationScoped` annotation
3. Replace `@PostConstruct` with method observing `StartupEvent`
4. Replace `@Resource` DataSource with `@Inject` DataSource
5. Add datasource configuration to application.properties

**File:** `src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java`

**Before:**
```java
@Singleton
@Startup
@TransactionManagement(TransactionManagementType.BEAN)
public class DataBaseMigrationStartup {
    @Resource(mappedName = "java:jboss/datasources/CoolstoreDS")
    DataSource dataSource;

    @PostConstruct
    private void startup() {
        // Flyway migration
    }
}
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class DataBaseMigrationStartup {
    @Inject
    DataSource dataSource;

    void onStart(@Observes StartupEvent event) {
        try {
            logger.info("Initializing/migrating the database using FlyWay");
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
            flyway.baseline();
            flyway.migrate();
        } catch (FlywayException e) {
            if(logger != null)
                logger.log(Level.SEVERE, "FAILED TO INITIALIZE THE DATABASE: " + e.getMessage(), e);
            else
                System.out.println("FAILED TO INITIALIZE THE DATABASE: " + e.getMessage());
        }
    }
}
```

**Note:** Flyway API changed between 4.x and modern versions - `.configure().load()` pattern required

**application.properties addition:**
```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore
quarkus.datasource.username=sa
quarkus.datasource.password=sa
```

---

## 4. Message-Driven Bean to Reactive Messaging Conversion

### 4.1 OrderServiceMDB Conversion

**Changes Required:**
1. Remove `@MessageDriven` annotation and activation config
2. Remove `implements MessageListener`
3. Add `@ApplicationScoped` annotation
4. Replace `onMessage(Message rcvMessage)` with `@Incoming("orders")` method
5. Change method signature to accept `String` payload directly
6. Remove JMS exception handling (SmallRye handles acknowledgment)

**File:** `src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java`

**Before:**
```java
@MessageDriven(name = "OrderServiceMDB", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "topic/orders"),
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
    @ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge")})
public class OrderServiceMDB implements MessageListener {
    
    @Inject
    OrderService orderService;
    
    @Inject
    CatalogService catalogService;
    
    @Override
    public void onMessage(Message rcvMessage) {
        TextMessage msg = null;
        try {
            if (rcvMessage instanceof TextMessage) {
                msg = (TextMessage) rcvMessage;
                String orderStr = msg.getBody(String.class);
                System.out.println("Received order: " + orderStr);
                Order order = Transformers.jsonToOrder(orderStr);
                System.out.println("Order object is " + order);
                orderService.save(order);
                order.getItemList().forEach(orderItem -> {
                    catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
                });
            }
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
    }
}
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import java.util.logging.Logger;

@ApplicationScoped
public class OrderServiceMDB {
    
    @Inject
    Logger log;
    
    @Inject
    OrderService orderService;
    
    @Inject
    CatalogService catalogService;
    
    @Incoming("orders")
    public void onOrder(String orderStr) {
        log.info("Received order: " + orderStr);
        Order order = Transformers.jsonToOrder(orderStr);
        log.info("Order object is " + order);
        orderService.save(order);
        order.getItemList().forEach(orderItem -> {
            catalogService.updateInventoryItems(orderItem.getProductId(), orderItem.getQuantity());
        });
    }
}
```

**Configuration (application.properties):**
```properties
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true
```

---

### 4.2 InventoryNotificationMDB Conversion

**Changes Required:**
1. Remove manual WebLogic JNDI setup code (init/close methods)
2. Remove `implements MessageListener` and manual subscription
3. Add `@ApplicationScoped` annotation
4. Replace `onMessage(Message rcvMessage)` with `@Incoming("orders-inventory")` method
5. Change to accept `String` payload directly

**File:** `src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java`

**Before:**
```java
public class InventoryNotificationMDB implements MessageListener {
    private static final int LOW_THRESHOLD = 50;
    
    @Inject
    private CatalogService catalogService;
    
    private final static String JNDI_FACTORY = "weblogic.jndi.WLInitialContextFactory";
    // ... manual setup code
    
    public void onMessage(Message rcvMessage) {
        TextMessage msg;
        try {
            if (rcvMessage instanceof TextMessage) {
                msg = (TextMessage) rcvMessage;
                String orderStr = msg.getBody(String.class);
                Order order = Transformers.jsonToOrder(orderStr);
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
        } catch (JMSException jmse) {
            System.err.println("An exception occurred: " + jmse.getMessage());
        }
    }
    
    public void init() throws NamingException, JMSException { /* ... */ }
    public void close() throws JMSException { /* ... */ }
    private static InitialContext getInitialContext() throws NamingException { /* ... */ }
}
```

**After:**
```java
import javax.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import java.util.logging.Logger;

@ApplicationScoped
public class InventoryNotificationMDB {
    private static final int LOW_THRESHOLD = 50;
    
    @Inject
    Logger log;
    
    @Inject
    private CatalogService catalogService;
    
    @Incoming("orders-inventory")
    public void onInventoryCheck(String orderStr) {
        log.info("Received order for inventory check");
        Order order = Transformers.jsonToOrder(orderStr);
        order.getItemList().forEach(orderItem -> {
            int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId())
                .getInventory().getQuantity();
            int new_quantity = old_quantity - orderItem.getQuantity();
            if (new_quantity < LOW_THRESHOLD) {
                log.warning("Inventory for item " + orderItem.getProductId() + 
                    " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
            }
        });
    }
}
```

**Configuration (application.properties):**
```properties
mp.messaging.incoming.orders-inventory.connector=smallrye-jms
mp.messaging.incoming.orders-inventory.destination=orders
mp.messaging.incoming.orders-inventory.destination-type=topic
mp.messaging.incoming.orders-inventory.broadcast=true
```

**Note:** This MDB was not functional in the original Java EE deployment (missing @MessageDriven annotation). The conversion enables it for the first time.

---

## 5. EJB Remote Interface and JNDI Lookup Removal

### 5.1 Remove JNDI Lookup in ShoppingCartService

**File:** `src/main/java/com/redhat/coolstore/service/ShoppingCartService.java`

**Changes Required:**
1. Add `@Inject ShippingService` field
2. Remove `lookupShippingServiceRemote()` static method
3. Replace all calls to `lookupShippingServiceRemote()` with `shippingService`

**Before:**
```java
@Stateful
public class ShoppingCartService {
    // ... other fields
    
    public void priceShoppingCart(ShoppingCart sc) {
        // ... calculations
        sc.setShippingTotal(lookupShippingServiceRemote().calculateShipping(sc));
        
        if (sc.getCartItemTotal() >= 25) {
            sc.setShippingTotal(sc.getShippingTotal()
                + lookupShippingServiceRemote().calculateShippingInsurance(sc));
        }
        // ... more code
    }
    
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
}
```

**After:**
```java
@ApplicationScoped
public class ShoppingCartService {
    @Inject
    Logger log;

    @Inject
    ProductService productServices;

    @Inject
    PromoService ps;

    @Inject
    ShoppingCartOrderProcessor shoppingCartOrderProcessor;
    
    @Inject
    ShippingService shippingService;  // NEW: Direct injection replaces JNDI lookup
    
    private final Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();
    
    public void priceShoppingCart(ShoppingCart sc) {
        // ... calculations
        sc.setShippingTotal(shippingService.calculateShipping(sc));
        
        if (sc.getCartItemTotal() >= 25) {
            sc.setShippingTotal(sc.getShippingTotal()
                + shippingService.calculateShippingInsurance(sc));
        }
        // ... more code
    }
    
    // lookupShippingServiceRemote() method REMOVED
}
```

**Imports to Remove:**
```java
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Hashtable;
```

**Imports to Add:**
```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
```

---

### 5.2 ShippingService and ShippingServiceRemote Interface

**Option A: Keep interface as local contract (RECOMMENDED)**
- ShippingServiceRemote interface remains unchanged (becomes simple Java interface)
- ShippingService continues to implement it
- Provides clear API contract for shipping calculations

**Option B: Remove interface entirely**
- Delete `ShippingServiceRemote.java`
- Remove `implements ShippingServiceRemote` from `ShippingService`
- All method signatures remain in ShippingService class

**Recommendation:** Keep Option A for maintainability and clear separation of contract from implementation.

---

## 6. Quarkus Extensions and Dependencies

### 6.1 Required Quarkus Extensions

| Extension | Artifact ID | Purpose |
|-----------|-------------|---------|
| REST (JAX-RS) | quarkus-resteasy-jackson | Existing JAX-RS endpoints (already present in target state) |
| CDI | quarkus-arc | Bean management (@ApplicationScoped, @Inject) |
| Hibernate ORM + Panache | quarkus-hibernate-orm | JPA EntityManager support |
| JDBC Driver (H2) | quarkus-jdbc-h2 | Database connectivity |
| SmallRye Reactive Messaging | quarkus-smallrye-reactive-messaging | Reactive messaging channels |
| SmallRye Reactive Messaging JMS | quarkus-smallrye-reactive-messaging-jms | JMS connector for topics |
| Narayana JTA | quarkus-narayana-jta | Transaction management (replaces CMT) |
| Flyway | quarkus-flyway | Database migration (upgrade from 4.x) |

### 6.2 Maven POM Changes

**Remove Dependencies:**
```xml
<dependency>
    <groupId>javax</groupId>
    <artifactId>javaee-web-api</artifactId>
    <version>7.0</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>javax</groupId>
    <artifactId>javaee-api</artifactId>
    <version>7.0</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.jboss.spec.javax.jms</groupId>
    <artifactId>jboss-jms-api_2.0_spec</artifactId>
    <version>2.0.0.Final</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>4.1.2</version>
</dependency>
<dependency>
    <groupId>org.jboss.spec.javax.rmi</groupId>
    <artifactId>jboss-rmi-api_1.0_spec</artifactId>
    <version>1.0.2.Final</version>
</dependency>
```

**Add Quarkus BOM and Extensions:**
```xml
<properties>
    <quarkus.platform.version>3.8.1</quarkus.platform.version>
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>${quarkus.platform.group-id}</groupId>
            <artifactId>${quarkus.platform.artifact-id}</artifactId>
            <version>${quarkus.platform.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Core Quarkus -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-resteasy-jackson</artifactId>
    </dependency>
    
    <!-- Persistence -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-hibernate-orm</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-jdbc-h2</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-flyway</artifactId>
    </dependency>
    
    <!-- Transactions -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-narayana-jta</artifactId>
    </dependency>
    
    <!-- Reactive Messaging for EJB MDB replacement -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-reactive-messaging</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-reactive-messaging-jms</artifactId>
    </dependency>
    
    <!-- Keep existing audit logging library -->
    <dependency>
        <groupId>com.enterprise</groupId>
        <artifactId>audit-logging-library</artifactId>
        <version>1.0.0</version>
        <scope>system</scope>
        <systemPath>${project.basedir}/lib/audit-logging-library-1.0.0.jar</systemPath>
    </dependency>
</dependencies>
```

**Update Build Plugins:**
```xml
<build>
    <finalName>ROOT</finalName>
    <plugins>
        <plugin>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-maven-plugin</artifactId>
            <version>${quarkus.platform.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>build</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <encoding>${project.encoding}</encoding>
                <source>11</source>
                <target>11</target>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Change Packaging:**
```xml
<packaging>jar</packaging>  <!-- Changed from war -->
```

### 6.3 application.properties Configuration

Create `src/main/resources/application.properties`:

```properties
# Application
quarkus.application.name=coolstore-monolith

# REST endpoint base path (preserve /services)
quarkus.resteasy.path=/services

# Datasource
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_DELAY=-1
quarkus.datasource.username=sa
quarkus.datasource.password=sa

# Hibernate ORM
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=false
quarkus.hibernate-orm.dialect=org.hibernate.dialect.H2Dialect

# Flyway
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.flyway.locations=classpath:db/migration

# JMS / Artemis (embedded for dev/test)
quarkus.artemis.url=tcp://localhost:61616
quarkus.artemis.username=admin
quarkus.artemis.password=admin

# Reactive Messaging - Orders Topic (Producer)
mp.messaging.outgoing.orders.connector=smallrye-jms
mp.messaging.outgoing.orders.destination=orders
mp.messaging.outgoing.orders.destination-type=topic

# Reactive Messaging - Orders Topic (Consumer 1: OrderService)
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true

# Reactive Messaging - Orders Topic (Consumer 2: Inventory Notification)
mp.messaging.incoming.orders-inventory.connector=smallrye-jms
mp.messaging.incoming.orders-inventory.destination=orders
mp.messaging.incoming.orders-inventory.destination-type=topic
mp.messaging.incoming.orders-inventory.broadcast=true

# Logging
quarkus.log.console.enable=true
quarkus.log.console.level=INFO
quarkus.log.category."com.redhat.coolstore".level=INFO

# Dev mode
%dev.quarkus.http.port=8080
```

**Note:** For production deployment, configure external ActiveMQ Artemis broker via `quarkus.artemis.url`.

---

## 7. Out-of-Scope Items (DO NOT MODIFY)

The following components are **explicitly out of scope** for this EJB-focused migration:

### 7.1 JPA / Persistence Layer
- **File:** `src/main/resources/META-INF/persistence.xml` - Keep as-is
- **Entities:** All `@Entity` classes in `com.redhat.coolstore.model` - No changes
- **Producer:** `Resources.java` EntityManager producer - No changes needed
- **Reason:** JPA works in Quarkus with minimal changes; not strictly required for EJB conversion

### 7.2 JAX-RS Endpoints
- **Files:** `CartEndpoint.java`, `OrderEndpoint.java`, `ProductEndpoint.java`
- **Application:** `RestApplication.java` - Keep @ApplicationPath("/services")
- **Reason:** JAX-RS endpoints are not EJB components; continue to work with Quarkus RESTEasy

### 7.3 CDI Beans Already Correct
- **PromoService** - Already @ApplicationScoped, no changes needed
- **Producers.java** - Logger producer already CDI-compliant
- **Resources.java** - EntityManager producer already CDI-compliant

### 7.4 Model/Domain Classes
- All classes in `com.redhat.coolstore.model` package - No changes
- `Transformers.java` utility class - No changes

### 7.5 WebLogic-Specific Components (Remove but Don't Replace)
- **StartupListener.java** - ApplicationLifecycleListener (WebLogic-specific)
  - **Action:** Delete file - not needed in Quarkus
  - **Reason:** WebLogic proprietary API; DataBaseMigrationStartup handles startup needs
- **weblogic.i18n.logging.NonCatalogLogger** - Custom WebLogic logger stub
  - **Action:** Delete file - not needed in Quarkus
  - **Reason:** WebLogic proprietary stub; standard java.util.logging.Logger sufficient
- **weblogic.application.*** classes - WebLogic lifecycle stubs
  - **Action:** Delete files - not needed in Quarkus

### 7.6 View Layer
- **Directory:** `src/main/webapp/` - No changes
- **JSP files:** `index.jsp`, `health.jsp` - Keep as-is
- **Static assets:** All bower_components, partials, etc. - No changes
- **Reason:** Frontend migration not in scope

### 7.7 Build Configuration (Minimal Changes Only)
- **Maven profiles** - Only add Quarkus profile, don't modify existing
- **WAR overlays** - Not applicable (changing to JAR packaging)
- **Reason:** Only touch build config for Quarkus essentials

---

## 8. Migration Execution Order

Execute conversions in this sequence to maintain buildability:

### Phase 1: Dependency Setup (Foundation)
1. Update `pom.xml` with Quarkus BOM and extensions
2. Change packaging from `war` to `jar`
3. Create `application.properties` with datasource and messaging config
4. Verify `mvn clean compile` succeeds

### Phase 2: Simple Session Beans (No Dependencies)
5. Convert `ProductService` (@Stateless → @ApplicationScoped)
6. Convert `CatalogService` (@Stateless → @ApplicationScoped)
7. Convert `OrderService` (@Stateless → @ApplicationScoped)
8. Verify `mvn clean compile` succeeds

### Phase 3: Startup Singleton
9. Convert `DataBaseMigrationStartup` (@Singleton+@Startup → @ApplicationScoped+StartupEvent)
10. Update Flyway API usage (4.x → 9.x)
11. Verify `mvn clean compile` succeeds

### Phase 4: Messaging Producer
12. Convert `ShoppingCartOrderProcessor` (@Stateless → @ApplicationScoped with Emitter)
13. Remove JMS API imports, add Reactive Messaging imports
14. Verify `mvn clean compile` succeeds

### Phase 5: Remote EJB Removal
15. Remove `@Remote` from `ShippingService` (@Stateless → @ApplicationScoped)
16. Convert `ShoppingCartService` (@Stateful → @ApplicationScoped with Map)
17. Replace JNDI lookup with `@Inject ShippingService`
18. Verify `mvn clean compile` succeeds

### Phase 6: Message-Driven Beans
19. Convert `OrderServiceMDB` (@MessageDriven → @ApplicationScoped with @Incoming)
20. Convert `InventoryNotificationMDB` (manual setup → @ApplicationScoped with @Incoming)
21. Verify `mvn clean compile` succeeds

### Phase 7: Cleanup
22. Delete `StartupListener.java` (WebLogic-specific)
23. Delete `weblogic/**` package (proprietary stubs)
24. Remove unused imports across all modified files
25. Verify `mvn clean package -DskipTests` succeeds

### Phase 8: Integration Verification
26. Start application: `java -jar target/quarkus-app/quarkus-run.jar`
27. Verify REST endpoints: `curl http://localhost:8080/services/products`
28. Verify messaging: trigger order flow via `POST /services/cart/checkout/{cartId}`
29. Check logs for order processing and inventory warnings

---

## 9. Verification Criteria

### 9.1 Build Success
```bash
mvn clean package -DskipTests
```
**Expected:** `BUILD SUCCESS`, artifact `target/quarkus-app/quarkus-run.jar` created

### 9.2 Startup Success
```bash
java -jar target/quarkus-app/quarkus-run.jar
```
**Expected:**
- Application starts without errors
- Flyway migrations execute successfully
- REST endpoints available at `/services/*`
- JMS topic `orders` created and consumers connected
- Log output shows:
  ```
  Initializing/migrating the database using FlyWay
  Listening to http://0.0.0.0:8080
  ```

### 9.3 REST API Functional
```bash
# List products
curl http://localhost:8080/services/products

# Get cart (creates empty cart for new cartId)
curl http://localhost:8080/services/cart/test-cart-123

# Add item to cart
curl -X POST http://localhost:8080/services/cart/test-cart-123/329299/2

# Checkout (triggers order message)
curl -X POST http://localhost:8080/services/cart/checkout/test-cart-123

# List orders
curl http://localhost:8080/services/orders
```

**Expected:** All endpoints return valid JSON responses

### 9.4 Messaging Flow Functional
1. POST to `/services/cart/checkout/{cartId}` triggers order processing
2. `ShoppingCartOrderProcessor` sends message to `orders` topic
3. `OrderServiceMDB` receives message, persists order, updates inventory
4. `InventoryNotificationMDB` receives message, checks inventory thresholds
5. Logs show:
   ```
   Sending order from processor
   Received order: {"orderValue":123.45,...}
   Order object is Order@...
   Inventory for item 329299 is below threshold (50), contact supplier!
   ```

### 9.5 No EJB Remnants
```bash
# Verify no EJB annotations remain
grep -r "@Stateless\|@Stateful\|@Singleton\|@MessageDriven\|@Remote\|@EJB" \
  src/main/java/com/redhat/coolstore/service --include="*.java"
```
**Expected:** No matches (or only in commented code)

### 9.6 Session Scope Preserved
- CartEndpoint remains `@SessionScoped`
- Multiple carts can exist concurrently (different cartIds)
- Cart state persists across multiple REST calls with same cartId

---

## 10. Risk Assessment and Mitigation

### 10.1 High-Risk Changes

| Risk | Component | Impact | Mitigation |
|------|-----------|--------|------------|
| State loss | ShoppingCartService | Cart data could be lost between requests | Use ConcurrentHashMap with cartId key; verify with multi-user test |
| Message loss | MDB conversion | Orders might not process during rollout | Use durable topic subscriptions; verify broadcast=true |
| Startup failure | DataBaseMigrationStartup | Database not initialized | Keep Flyway baseline(); test with empty and migrated DBs |
| JNDI lookup removal | ShoppingCartService → ShippingService | Shipping calculation fails | Add @Inject early; test cart pricing thoroughly |

### 10.2 Testing Strategy

**Unit Testing (Out of Scope but Recommended):**
- Mock CatalogService in OrderServiceMDB test
- Test ShoppingCartService with multiple concurrent cartIds
- Verify Flyway migration succeeds on clean database

**Integration Testing:**
1. Deploy to Quarkus dev mode (`mvn quarkus:dev`)
2. Execute full order flow: browse → add to cart → checkout → verify order persisted
3. Verify inventory updates after order
4. Verify inventory warnings trigger at threshold
5. Test multiple simultaneous carts (different browser sessions/cartIds)

**Performance Testing:**
- Compare response times: Java EE vs. Quarkus
- Verify no thread contention in ShoppingCartService ConcurrentHashMap
- Check JMS throughput with multiple rapid checkouts

---

## 11. Rollback Plan

If critical issues arise post-deployment:

1. **Revert Git Commit:** All changes in single commit, easy to revert
2. **Redeploy Java EE WAR:** Original `monolith-1.0.0-SNAPSHOT.war` to WildFly
3. **Database Compatibility:** Flyway migrations are forward-compatible; no schema rollback needed
4. **JMS Messages:** In-flight messages process when Java EE app redeploys

**No Data Loss:** ConcurrentHashMap state is in-memory only (like @Stateful beans); no persistence to migrate

---

## 12. Post-Migration Enhancements (Out of Scope)

Future improvements after EJB migration completes:

1. **Replace ConcurrentHashMap with Redis:** For true stateless, horizontally-scalable cart storage
2. **Add Kafka:** Replace JMS topics with Kafka for better message durability and observability
3. **Add Hibernate Panache:** Simplify repository pattern (out of scope: not required for EJB conversion)
4. **Add Quarkus OpenAPI:** Generate API documentation from JAX-RS endpoints
5. **Add Health Checks:** Replace `health.jsp` with Quarkus SmallRye Health endpoints
6. **Migrate Flyway fully:** Update to Flyway 9.x API throughout (partial migration in this plan)

---

## 13. Summary: Files Modified

### Modified Files (11 Java files)
1. `src/main/java/com/redhat/coolstore/service/ShippingService.java`
2. `src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java`
3. `src/main/java/com/redhat/coolstore/service/CatalogService.java`
4. `src/main/java/com/redhat/coolstore/service/OrderService.java`
5. `src/main/java/com/redhat/coolstore/service/ProductService.java`
6. `src/main/java/com/redhat/coolstore/service/ShoppingCartService.java`
7. `src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java`
8. `src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java`
9. `src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java`
10. `pom.xml`

### Deleted Files (3 files)
11. `src/main/java/com/redhat/coolstore/utils/StartupListener.java`
12. `src/main/java/weblogic/i18n/logging/NonCatalogLogger.java`
13. `src/main/java/weblogic/application/ApplicationLifecycleListener.java`
14. `src/main/java/weblogic/application/ApplicationLifecycleEvent.java`

### New Files (1 file)
15. `src/main/resources/application.properties`

### Unchanged Files (Critical to Preserve)
- All files in `src/main/java/com/redhat/coolstore/model/` (9 files)
- All files in `src/main/java/com/redhat/coolstore/rest/` (4 files)
- `src/main/java/com/redhat/coolstore/persistence/Resources.java`
- `src/main/java/com/redhat/coolstore/utils/Producers.java`
- `src/main/java/com/redhat/coolstore/utils/Transformers.java`
- `src/main/resources/META-INF/persistence.xml`
- `src/main/resources/db/migration/*.sql`
- `src/main/webapp/**` (entire view layer)

---

## Appendix A: Quick Reference - Annotation Mappings

| Java EE 7 EJB | Quarkus 3 CDI | Notes |
|---------------|---------------|-------|
| @Stateless | @ApplicationScoped | Thread-safe stateless beans |
| @Stateful | @ApplicationScoped + state management | Use Map<key, state> for multi-user state |
| @Singleton + @Startup | @ApplicationScoped + @Observes StartupEvent | Startup logic in event observer method |
| @MessageDriven | @ApplicationScoped + @Incoming | Topic/queue via channel config |
| @Remote | (remove) | Use @Inject for in-process beans |
| @EJB | @Inject | Standard CDI injection |
| @Resource(lookup=...) | @Inject | For DataSource, etc. |
| @PostConstruct | @PostConstruct (still works) | Or @Observes StartupEvent for app init |
| @TransactionAttribute | @Transactional | Declarative transactions via Narayana |

---

## Appendix B: Messaging Configuration Matrix

| Aspect | Java EE 7 (JMS) | Quarkus 3 (Reactive Messaging) |
|--------|-----------------|--------------------------------|
| Producer API | JMSContext.createProducer().send() | @Inject @Channel Emitter<T>.send() |
| Consumer API | @MessageDriven + MessageListener | @Incoming("channel") method |
| Topic Lookup | @Resource(lookup="java:/topic/orders") | mp.messaging.*.destination=orders |
| Fan-out (Topic) | Implicit (JMS Topic semantics) | Explicit (broadcast=true config) |
| Acknowledgment | acknowledgeMode in @ActivationConfig | Automatic by SmallRye Reactive Messaging |
| Connection Factory | Container-provided (java:/JmsXA) | Configured via quarkus.artemis.* |

---

**END OF PLAN**
