# Migration Plan

## Goal
Migrate the CoolStore monolith from Java EE 7 on JBoss EAP 7.4 to Quarkus 3.x while preserving all business functionality including REST APIs, JMS messaging, database persistence, and security integration.

## Source → Target
Java EE 7 on JBoss EAP 7.4 → Quarkus 3.x

## Scope
- Files affected: 28
- Estimated complexity: Medium
- Hardest areas: Message-driven bean (MDB) conversion to Quarkus messaging, EJB @Stateless to Quarkus CDI, JMS/messaging framework adaptation

## Key Decisions Applied

1. **JMS Migration Strategy**: Use Quarkus messaging with SmallRye Reactive Messaging instead of traditional JMS MDBs, as it provides better cloud-native patterns and Quarkus native compilation support.

2. **EJB to CDI**: Convert @Stateless EJBs to @ApplicationScoped CDI beans, as Quarkus uses CDI for all bean management and does not require explicit EJB annotations.

3. **Database Configuration**: Migrate from JNDI datasource lookup to Quarkus configuration properties in application.properties, simplifying cloud deployment.

4. **Persistence Context**: Change from @PersistenceContext with JNDI lookup to Quarkus JPA/Hibernate via CDI injection with automatic entity scanning.

5. **HTTP Server Port**: Application will run on port 8080 by default in Quarkus (no changes needed to README for local testing); deployment configs will specify port override if needed.

6. **Keycloak Integration**: Retain Keycloak configuration via keycloak.json and adapt to Quarkus OIDC extension for streamlined security.

## Approach

### Phase 1: Build Configuration
Replace Maven pom.xml with Quarkus parent and dependencies. Remove Java EE APIs, add Quarkus extensions for REST, JPA, JMS/Messaging, and OIDC.

### Phase 2: Data Models
Update entity annotations from javax.persistence to jakarta.persistence (Quarkus standard). Keep entity structure unchanged; Quarkus will auto-scan entities.

### Phase 3: Persistence Layer
Remove @PersistenceContext producer class; Quarkus provides EntityManager via CDI automatically. Update persistence.xml to Quarkus configuration format.

### Phase 4: Services and Business Logic
Convert @Stateless EJBs to @ApplicationScoped CDI beans. Convert @MessageDriven beans to SmallRye Reactive Messaging channels. Update injection patterns to Quarkus CDI.

### Phase 5: REST API
Update REST annotations from javax.ws.rs to jakarta.ws.rs. RestApplication class remains minimal; Quarkus scans @Path classes automatically.

### Phase 6: Configuration Files
Create application.properties for datasource and Keycloak configuration. Update persistence.xml or remove if using Quarkus defaults. Remove web.xml and beans.xml (Quarkus manages CDI automatically).

### Phase 7: Cleanup and Verification
Remove legacy deployment descriptors, WebLogic classes, and unused dependencies. Build and verify application functionality.

## Steps

### Step 1: Create Quarkus pom.xml
- Phase: Phase 1: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - Replace entire pom.xml with Quarkus parent (io.quarkus:quarkus-bom:3.x.x)
    - Remove Java EE dependencies (javax.javaee-web-api, javax.javaee-api)
    - Add Quarkus extensions: quarkus-resteasy-reactive, quarkus-hibernate-orm, quarkus-jdbc-postgresql, quarkus-smallrye-reactive-messaging-kafka (or amqp for JMS), quarkus-oidc
    - Update maven-compiler-plugin to target Java 11+
    - Update project properties for Quarkus (e.g., maven.compiler.source=11)
    - Keep flyway-core dependency for database migrations
    - Keep audit-logging-library system dependency or adapt to packaged JAR
- Why: Quarkus requires different parent POM and extensions instead of full Java EE server; pom.xml is the foundation for all build configuration
- Depends on: none
- Verify: mvn clean compile succeeds with no dependency resolution errors

### Step 2: Update model entities - CatalogItemEntity
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical (@Entity, @Table, @Column, @OneToOne, @Id, etc.)
    - No other code changes needed
- Why: Quarkus uses jakarta.persistence namespace (Jakarta EE 8+), migration from javax to jakarta is required
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 3: Update model entities - InventoryEntity
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Quarkus uses jakarta.persistence namespace; consistent with all entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 4: Update model entities - Order
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 5: Update model entities - OrderItem
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 6: Update model entities - Product
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 7: Update model entities - Promotion
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 8: Update model entities - ShoppingCart
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 9: Update model entities - ShoppingCartItem
- Phase: Phase 2: Data Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do:
    - Replace import `javax.persistence.*` with `jakarta.persistence.*`
    - All JPA annotations remain identical
- Why: Consistency with other entities
- Depends on: Step 1
- Verify: File compiles with no import errors

### Step 10: Update persistence.xml for Quarkus
- Phase: Phase 3: Persistence Layer
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do:
    - Update XML namespace from `http://xmlns.jcp.org/xml/ns/persistence` to `https://jakarta.ee/xml/ns/persistence`
    - Change version from "2.1" to "3.0"
    - Replace `<jta-data-source>java:jboss/datasources/CoolstoreDS</jta-data-source>` with reference to Quarkus datasource name: `CoolstoreDS` (Quarkus manages this via application.properties)
    - Keep existing properties for hibernate configuration
- Why: Quarkus uses Jakarta EE 3.0 persistence descriptors and manages datasources through configuration properties
- Depends on: Step 1
- Verify: Persistence unit is properly configured and readable by Quarkus

### Step 11: Remove Resources.java producer
- Phase: Phase 3: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete this file entirely
- Why: Quarkus CDI automatically provides EntityManager injection; explicit producer beans are not needed
- Depends on: Step 10
- Verify: File no longer exists in repository

### Step 12: COMPLEX — Migrate CatalogService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses `@Stateless` EJB annotation
    - AFTER: Use `@ApplicationScoped` CDI scope
    - Specific changes:
        1. Remove import `javax.ejb.Stateless`
        2. Add import `jakarta.enterprise.context.ApplicationScoped` and `jakarta.persistence.EntityManager`
        3. Replace `@Stateless` annotation with `@ApplicationScoped`
        4. Replace injection of EntityManager from producer to direct CDI injection: `@Inject private EntityManager em;` (Quarkus provides this automatically)
        5. Update Logger import from `java.util.logging.Logger` to keep as-is; Quarkus supports both JUL and SLF4J
- Why: Quarkus does not use @Stateless EJB pattern; all beans are managed by CDI; Quarkus provides EntityManager via CDI automatically
- Depends on: Step 1, Step 10
- Verify: Class compiles; EntityManager is injected; methods function identically

### Step 13: COMPLEX — Migrate ProductService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Same changes as Step 12 (CatalogService):
        1. Replace `@Stateless` with `@ApplicationScoped`
        2. Update imports to jakarta.enterprise and jakarta.persistence
        3. Ensure EntityManager is injected via CDI
- Why: Consistency with CatalogService; Quarkus CDI bean management
- Depends on: Step 1, Step 10
- Verify: Class compiles; all queries execute correctly

### Step 14: COMPLEX — Migrate ShoppingCartService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - Replace `@Stateless` with `@ApplicationScoped`
    - Update imports to jakarta
    - Ensure EntityManager injection via CDI
- Why: Consistency with other services
- Depends on: Step 1, Step 10
- Verify: Class compiles; shopping cart operations work

### Step 15: COMPLEX — Migrate PromoService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Replace `@Stateless` with `@ApplicationScoped`
    - Update imports to jakarta
    - Ensure EntityManager injection via CDI
- Why: Consistency with other services
- Depends on: Step 1, Step 10
- Verify: Class compiles

### Step 16: COMPLEX — Migrate OrderService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - Replace `@Stateless` with `@ApplicationScoped`
    - Update imports to jakarta
    - Ensure EntityManager injection via CDI
- Why: Consistency with other services; OrderService is used by messaging and REST layers
- Depends on: Step 1, Step 10
- Verify: Class compiles; order persistence works

### Step 17: COMPLEX — Migrate ShippingService from @Stateless EJB to CDI
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - Replace `@Stateless` with `@ApplicationScoped`
    - Update imports to jakarta
- Why: Consistency with other services
- Depends on: Step 1, Step 10
- Verify: Class compiles

### Step 18: COMPLEX — Migrate DataBaseMigrationStartup from @Singleton EJB to Quarkus Startup
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - BEFORE: Uses EJB annotations `@Singleton`, `@Startup`, `@TransactionManagement`
    - AFTER: Use Quarkus lifecycle annotations
    - Specific changes:
        1. Remove imports: `javax.ejb.Singleton`, `javax.ejb.Startup`, `javax.ejb.TransactionManagement`, `javax.ejb.TransactionManagementType`
        2. Add imports: `io.quarkus.runtime.StartupEvent`, `jakarta.enterprise.event.Observes`, `jakarta.enterprise.context.ApplicationScoped`
        3. Replace class annotations:
           - Remove `@Singleton`, `@Startup`, `@TransactionManagement(TransactionManagementType.BEAN)`
           - Add `@ApplicationScoped`
        4. Replace `@PostConstruct` method with observer method: change `private void startup()` to `void startup(@Observes StartupEvent event)`
        5. Update `@Resource` for datasource injection to `@Inject` with `@io.quarkus.datasource.runtime.DataSourceSupport` or keep as-is if Quarkus auto-provides via application.properties
        6. Keep Flyway configuration logic identical
- Why: Quarkus uses CDI lifecycle events instead of EJB lifecycle annotations; provides cloud-native startup semantics
- Depends on: Step 1
- Verify: Application starts successfully; Flyway migrations execute on startup; logs show initialization messages

### Step 19: Update Producers utility class
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Update imports from `javax.enterprise.*` to `jakarta.enterprise.*`
    - Keep `@Produces` annotation and method signature identical
    - Logger production logic remains unchanged
- Why: Quarkus uses jakarta.enterprise CDI namespace
- Depends on: Step 1
- Verify: Logger injection works in all services

### Step 20: COMPLEX — Migrate OrderServiceMDB to Quarkus Reactive Messaging
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Traditional JMS @MessageDriven bean with MessageListener
    - AFTER: SmallRye Reactive Messaging channel consumer
    - Specific changes:
        1. Remove imports: `javax.ejb.*`, `javax.jms.*`
        2. Add imports: `org.eclipse.microprofile.reactive.messaging.Incoming`, `io.smallrye.reactive.messaging.annotations.Blocking`, `jakarta.inject.Inject`
        3. Remove `@MessageDriven` annotation and activation config
        4. Remove `implements MessageListener` interface
        5. Convert `onMessage(Message rcvMessage)` method to channel consumer:
           ```
           @Incoming("orders")
           @Blocking
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
           ```
        6. Remove JMS-specific exception handling (try-catch for JMSException); Reactive Messaging handles errors
        7. Keep `@Inject` dependencies for orderService and catalogService
- Why: Quarkus Reactive Messaging provides modern, cloud-native alternative to JMS MDBs with better performance and scalability; compatible with Kafka, AMQP, and other brokers
- Depends on: Step 1, Step 16
- Verify: Application logs show message processing; orders are saved to database; inventory is updated

### Step 21: COMPLEX — Migrate InventoryNotificationMDB to Quarkus Reactive Messaging
- Phase: Phase 4: Services and Business Logic
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Traditional JMS @MessageDriven bean
    - AFTER: SmallRye Reactive Messaging channel consumer
    - Specific changes:
        1. Remove EJB and JMS imports
        2. Add Reactive Messaging imports (`@Incoming`, `@Blocking`, etc.)
        3. Remove `@MessageDriven` annotation
        4. Remove `implements MessageListener`
        5. Convert `onMessage(Message rcvMessage)` to `@Incoming("inventory")` channel consumer method
        6. Simplify message handling to extract message body as String and process
        7. Remove JMS exception handling; let Reactive Messaging framework handle errors
- Why: Consistency with OrderServiceMDB; cloud-native messaging pattern
- Depends on: Step 1
- Verify: Inventory notifications are processed on application startup and during operation

### Step 22: Update REST API - ProductEndpoint
- Phase: Phase 5: REST API
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace imports `javax.ws.rs.*` with `jakarta.ws.rs.*`
    - Replace import `javax.enterprise.context.RequestScoped` with `jakarta.enterprise.context.RequestScoped`
    - Replace import `javax.inject.Inject` with `jakarta.inject.Inject`
    - All annotations (@Path, @GET, @Consumes, @Produces, @PathParam, etc.) remain identical
    - No other code changes needed
- Why: Quarkus uses jakarta.ws.rs namespace (Jakarta EE 8+) for REST; all annotation names and usage remain the same
- Depends on: Step 1, Step 13
- Verify: REST endpoint compiles; curl requests to /services/products return product list

### Step 23: Update REST API - CartEndpoint
- Phase: Phase 5: REST API
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace imports from `javax.ws.rs.*`, `javax.enterprise.context.*`, `javax.inject.*` with jakarta equivalents
    - All REST annotations and method signatures remain identical
- Why: Consistency with ProductEndpoint; Quarkus jakarta.ws.rs namespace
- Depends on: Step 1, Step 14
- Verify: REST endpoint compiles; cart operations work via REST

### Step 24: Update REST API - OrderEndpoint
- Phase: Phase 5: REST API
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace imports from `javax.ws.rs.*`, `javax.enterprise.context.*`, `javax.inject.*` with jakarta equivalents
    - All REST annotations and method signatures remain identical
- Why: Consistency with other endpoints
- Depends on: Step 1, Step 16
- Verify: REST endpoint compiles; order operations work

### Step 25: Update REST API - RestApplication
- Phase: Phase 5: REST API
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace import `javax.ws.rs.ApplicationPath` with `jakarta.ws.rs.ApplicationPath`
    - Replace import `javax.ws.rs.core.Application` with `jakarta.ws.rs.core.Application`
    - Keep class definition and @ApplicationPath annotation identical
- Why: Quarkus jakarta.ws.rs namespace; Quarkus automatically scans @Path classes, so RestApplication can be minimal
- Depends on: Step 1
- Verify: REST application initializes correctly

### Step 26: Create Quarkus application.properties
- Phase: Phase 6: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with following content:
    ```
    # Quarkus Application Configuration
    quarkus.application.name=coolstore-monolith
    quarkus.application.version=1.0.0-SNAPSHOT
    
    # HTTP Server
    quarkus.http.port=8080
    
    # PostgreSQL Datasource
    quarkus.datasource.db-kind=postgresql
    quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
    quarkus.datasource.username=postgresUser
    quarkus.datasource.password=postgresPW
    
    # JPA/Hibernate
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.log.sql=false
    quarkus.hibernate-orm.jdbc.statement-fetch-size=50
    quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQL10Dialect
    
    # Flyway Database Migration
    quarkus.flyway.migrate-at-start=true
    
    # Keycloak/OIDC Configuration
    quarkus.oidc.auth-server-url=http://127.0.0.1:8081/realms/eap
    quarkus.oidc.client-id=coolstore-app
    quarkus.oidc.application-type=web
    quarkus.oidc.authentication.cookie-path=/
    
    # Reactive Messaging (for JMS/Orders and Inventory topics)
    mp.messaging.incoming.orders.connector=smallrye-amqp
    mp.messaging.incoming.orders.address=topic/orders
    mp.messaging.incoming.inventory.connector=smallrye-amqp
    mp.messaging.incoming.inventory.address=topic/inventory
    mp.messaging.connector.smallrye-amqp.host=127.0.0.1
    mp.messaging.connector.smallrye-amqp.port=5672
    
    # Logging
    quarkus.log.level=INFO
    quarkus.log.console.enable=true
    ```
- Why: Quarkus uses externalized configuration via application.properties for datasource, JPA, Flyway, security, and messaging; eliminates need for web.xml and persistence.xml jndi lookups
- Depends on: Step 1, Step 10
- Verify: File exists; contains all required properties; application starts without configuration errors

### Step 27: Update web.xml for Quarkus (or delete)
- Phase: Phase 6: Configuration Files
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file
- Why: Quarkus does not require web.xml; REST endpoints are auto-discovered via @Path annotations; distributable flag is not needed
- Depends on: Step 25
- Verify: File no longer exists; application starts without errors

### Step 28: Update beans.xml for Quarkus (or delete)
- Phase: Phase 6: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file
- Why: Quarkus enables CDI by default; beans.xml marker file is not required; CDI bean discovery is automatic
- Depends on: none
- Verify: File no longer exists; CDI injection works correctly

### Step 29: Verify transformers utility
- Phase: Phase 6: Configuration Files
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
    - Check and update any javax imports to jakarta (unlikely, but verify)
    - Ensure JSON transformation logic is compatible with Quarkus (verify Jackson or JSON-B usage)
    - No structural changes typically needed
- Why: Utility used by messaging layer; must be compatible with Quarkus runtime
- Depends on: Step 1
- Verify: Transformers compiles; JSON serialization/deserialization works

### Step 30: Remove WebLogic compatibility classes
- Phase: Phase 7: Cleanup and Verification
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific compatibility classes are not needed for Quarkus; cleanup removes legacy dependencies
- Depends on: none
- Verify: File no longer exists

### Step 31: Remove WebLogic compatibility classes
- Phase: Phase 7: Cleanup and Verification
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific; not needed for Quarkus
- Depends on: none
- Verify: File no longer exists

### Step 32: Remove WebLogic compatibility classes
- Phase: Phase 7: Cleanup and Verification
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific; not needed for Quarkus
- Depends on: none
- Verify: File no longer exists

### Step 33: Verify StartupListener if present
- Phase: Phase 7: Cleanup and Verification
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY (or DELETE if redundant)
- What to do:
    - Check if this class is still used; if it duplicates DataBaseMigrationStartup functionality, delete it
    - If used for other startup logic, convert from ServletContextListener to Quarkus @Observes StartupEvent pattern
- Why: Verify no duplicate startup logic; Quarkus uses CDI events instead of servlet listeners
- Depends on: Step 18
- Verify: No duplicate startup operations; application initializes correctly

## Verification

### Build
```bash
mvn clean compile
mvn clean package -DskipTests
```

### Test
```bash
mvn test
```
(If tests exist; current project has tests skipped via maven.test.skip=true)

### Blackbox
1. **Start PostgreSQL**:
   ```bash
   podman run --name myPostgresDb \
      -p 5432:5432 \
      -e POSTGRES_USER=postgresUser \
      -e POSTGRES_PASSWORD=postgresPW \
      -e POSTGRES_DB=postgresDB \
      -d postgres
   ```

2. **Start Keycloak** (on port 8081):
   ```bash
   cd keycloak-20.0.5
   ./bin/kc.sh start-dev --http-port=8081
   ```

3. **Configure Keycloak realm and user** (following README instructions)

4. **Run Quarkus application**:
   ```bash
   mvn quarkus:dev
   ```
   (Development mode with hot-reload) or
   ```bash
   java -jar target/quarkus-app/quarkus-run.jar
   ```
   (Native or JVM production build)

5. **Test key business flows**:
   - Navigate to http://127.0.0.1:8080 in browser
   - Click "Sign in" in top right
   - Login with Keycloak credentials (user1)
   - Browse products (verify GET /services/products)
   - Add items to shopping cart (verify PUT /services/cart)
   - Complete checkout (verify POST order, triggers OrderServiceMDB messaging, updates inventory)
   - Verify order appears in database
   - Verify logs show message processing for inventory and orders topics

6. **Verify clustering (optional)** - Start second Quarkus instance with port offset and verify message distribution across nodes

## Notes

### Gotchas and Special Cases

1. **Messaging Configuration**: The migration uses SmallRye Reactive Messaging configured for AMQP by default in application.properties. If the existing deployment uses ActiveMQ (as per JBoss config), ensure ActiveMQ AMQP broker is running or adjust connector configuration (e.g., to Kafka if preferred). The `mp.messaging.incoming.*` properties define channel names (`orders`, `inventory`) that match the `@Incoming` annotations in MDB conversions.

2. **Persistence Unit Name**: In application.properties, the datasource is named `quarkus.datasource.*`. If using multiple datasources, adjust the naming and update application code accordingly. Current config assumes single "primary" datasource.

3. **Logging**: Quarkus defaults to using JBoss Logging with SLF4J. The existing `java.util.logging.Logger` injection via Producers will still work, but consider migrating to SLF4J for consistency. Current setup preserves backward compatibility.

4. **Keycloak OIDC**: The keycloak.json file in webapp directory was used for JavaScript client integration. Quarkus handles server-side OIDC via quarkus.oidc.* properties. If JavaScript client-side security is needed, update webapp/keycloak.json to point to correct Keycloak server and realm. The migration focuses on server-side; client-side config may need adjustment based on actual Keycloak setup.

5. **Audit Logging Library**: The audit-logging-library-1.0.0.jar is included via system scope dependency. Ensure this JAR is compatible with Quarkus or repackage as a standard Maven dependency. If not used, remove from pom.xml.

6. **Database Migrations**: Flyway configuration is preserved. Ensure migration scripts in src/main/resources/db/migration/ are compatible with PostgreSQL. Quarkus will run Flyway on application startup via `quarkus.flyway.migrate-at-start=true`.

7. **ShippingServiceRemote**: If ShippingServiceRemote is an RMI remote interface, Quarkus does not support RMI. Convert to REST endpoint or internal service. Current migration assumes internal usage; verify and adapt if external RMI clients exist.

8. **Native Compilation**: This plan targets JVM mode first. For native compilation (GraalVM), additional configuration and reflection registration may be required, especially for JPA entities, REST endpoints, and Jackson serialization. Use `quarkus.native.*` properties and `reflect-config.json` as needed.

9. **Testing**: The current pom.xml has `<maven.test.skip>true</maven.test.skip>`. After migration, re-enable testing by removing this property and adding appropriate Quarkus test dependencies (quarkus-junit5, etc.).

10. **Port Configuration**: Quarkus runs on 8080 by default. If different port needed, override via `-Dquarkus.http.port=XXXX` or `quarkus.http.port=XXXX` in application.properties. Ensure Keycloak (8081) and PostgreSQL (5432) configs match your environment.
