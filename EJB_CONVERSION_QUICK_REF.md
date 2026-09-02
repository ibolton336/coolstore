# EJB Conversion Quick Reference

## Files to Modify (11 files)

### Session Beans (7 files)
1. ✓ `ShippingService.java` - Remove @Stateless, @Remote; Add @ApplicationScoped
2. ✓ `ShoppingCartOrderProcessor.java` - Remove @Stateless; Replace JMS with Emitter
3. ✓ `CatalogService.java` - Remove @Stateless; Add @ApplicationScoped
4. ✓ `OrderService.java` - Remove @Stateless; Add @ApplicationScoped
5. ✓ `ProductService.java` - Remove @Stateless; Add @ApplicationScoped
6. ✓ `ShoppingCartService.java` - Remove @Stateful; Add Map<cartId, ShoppingCart> + @Inject ShippingService
7. ✓ `DataBaseMigrationStartup.java` - Remove @Singleton/@Startup; Add StartupEvent observer

### Message-Driven Beans (2 files)
8. ✓ `OrderServiceMDB.java` - Remove @MessageDriven; Add @Incoming("orders")
9. ✓ `InventoryNotificationMDB.java` - Remove manual setup; Add @Incoming("orders-inventory")

### Build Configuration (1 file)
10. ✓ `pom.xml` - Add Quarkus BOM, extensions, change packaging to jar

### New Configuration (1 file)
11. ✓ `application.properties` - Datasource, messaging, Quarkus config

## Files to Delete (4 files)
1. `StartupListener.java` (WebLogic-specific)
2. `weblogic/i18n/logging/NonCatalogLogger.java`
3. `weblogic/application/ApplicationLifecycleListener.java`
4. `weblogic/application/ApplicationLifecycleEvent.java`

## Conversion Cheat Sheet

### @Stateless → @ApplicationScoped
```java
// REMOVE:
import javax.ejb.Stateless;
@Stateless

// ADD:
import javax.enterprise.context.ApplicationScoped;
@ApplicationScoped
```

### @Stateful → @ApplicationScoped + Map
```java
// REMOVE:
import javax.ejb.Stateful;
@Stateful
private ShoppingCart cart = new ShoppingCart();

// ADD:
import javax.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
@ApplicationScoped
private final Map<String, ShoppingCart> carts = new ConcurrentHashMap<>();
```

### @MessageDriven → @Incoming
```java
// REMOVE:
import javax.ejb.MessageDriven;
import javax.jms.*;
@MessageDriven(name = "...", activationConfig = {...})
public class XyzMDB implements MessageListener {
    public void onMessage(Message msg) { ... }
}

// ADD:
import javax.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
@ApplicationScoped
public class XyzMDB {
    @Incoming("channel-name")
    public void onMessage(String payload) { ... }
}
```

### JMS Producer → Reactive Messaging Emitter
```java
// REMOVE:
import javax.jms.*;
@Inject
private transient JMSContext context;
@Resource(lookup = "java:/topic/orders")
private Topic ordersTopic;
context.createProducer().send(ordersTopic, message);

// ADD:
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
@Inject
@Channel("orders")
Emitter<String> ordersEmitter;
ordersEmitter.send(message);
```

### @Singleton+@Startup → StartupEvent
```java
// REMOVE:
import javax.ejb.Singleton;
import javax.ejb.Startup;
@Singleton
@Startup
@PostConstruct
private void startup() { ... }

// ADD:
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
@ApplicationScoped
void onStart(@Observes StartupEvent event) { ... }
```

### JNDI Lookup → @Inject
```java
// REMOVE:
private static ShippingServiceRemote lookupShippingServiceRemote() {
    final Hashtable<String, String> jndiProperties = new Hashtable<>();
    jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, "...");
    final Context context = new InitialContext(jndiProperties);
    return (ShippingServiceRemote) context.lookup("ejb:/...");
}
lookupShippingServiceRemote().calculateShipping(sc)

// ADD:
@Inject
ShippingService shippingService;
shippingService.calculateShipping(sc)
```

## Messaging Configuration

### application.properties
```properties
# Producer
mp.messaging.outgoing.orders.connector=smallrye-jms
mp.messaging.outgoing.orders.destination=orders
mp.messaging.outgoing.orders.destination-type=topic

# Consumer 1 (OrderServiceMDB)
mp.messaging.incoming.orders.connector=smallrye-jms
mp.messaging.incoming.orders.destination=orders
mp.messaging.incoming.orders.destination-type=topic
mp.messaging.incoming.orders.broadcast=true

# Consumer 2 (InventoryNotificationMDB)
mp.messaging.incoming.orders-inventory.connector=smallrye-jms
mp.messaging.incoming.orders-inventory.destination=orders
mp.messaging.incoming.orders-inventory.destination-type=topic
mp.messaging.incoming.orders-inventory.broadcast=true
```

## Verification Commands

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/quarkus-app/quarkus-run.jar

# Test REST
curl http://localhost:8080/services/products
curl http://localhost:8080/services/cart/test-123
curl -X POST http://localhost:8080/services/cart/test-123/329299/2
curl -X POST http://localhost:8080/services/cart/checkout/test-123
curl http://localhost:8080/services/orders

# Check for EJB remnants
grep -r "@Stateless\|@Stateful\|@Singleton\|@MessageDriven" src/main/java --include="*.java"
```

## Key Risks
1. **ShoppingCartService state** - Use ConcurrentHashMap with cartId key
2. **Topic fan-out** - Ensure broadcast=true for both consumers
3. **Flyway API change** - Update from 4.x to 9.x API
4. **DataSource lookup** - Replace @Resource with @Inject

## Execution Order
1. Update pom.xml + application.properties
2. Simple @Stateless beans (ProductService, CatalogService, OrderService)
3. DataBaseMigrationStartup (Singleton)
4. ShoppingCartOrderProcessor (JMS producer)
5. ShippingService + ShoppingCartService (remove JNDI)
6. MDBs (OrderServiceMDB, InventoryNotificationMDB)
7. Delete WebLogic files
8. Verify build and startup
