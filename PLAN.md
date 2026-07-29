# PLAN.md

## Goal
Migrate coolstore-monolith from Java EE 7 (WAR on WebLogic/WildFly) to Quarkus 3 (standalone JAR), preserving all functionality including the JMS messaging topology and AngularJS UI.
- Reference used: javaee-to-quarkus skill (dependency-map.md, pattern-map.md, config-map.md, annotation-map.md)

## Project Summary
- Type: Maven (Java 8 → Java 17+)
- Files affected: 35 Java files + pom.xml + config files + webapp handling
- Estimated complexity: High
- Hardest steps:
  1. Converting InventoryNotificationMDB (manual WebLogic JNDI lookup + Topic subscription)
  2. Replacing JNDI lookup for ShippingServiceRemote with CDI injection
  3. Preserving JMS topic fan-out (1 producer → 2 subscribers on "topic/orders")
  4. Handling src/main/webapp in JAR packaging

## Messaging Topology (Original App - Must Preserve)

| Producer | Broker Address | Consumers | Type |
|---|---|---|---|
| ShoppingCartOrderProcessor.process() | java:/topic/orders | OrderServiceMDB, InventoryNotificationMDB | Topic (fan-out) |

**Notes:**
- Topic fan-out means both MDBs receive the same message independently
- InventoryNotificationMDB uses manual WebLogic JNDI subscription (not standard @MessageDriven)
- OrderServiceMDB uses standard @MessageDriven with activationConfig
- After migration: Use SmallRye Reactive Messaging with 1 outgoing channel + 2 independent incoming channels

## src/main/webapp Disposition (JAR Packaging)

**Original:** AngularJS application in src/main/webapp (WAR packaging)

**Disposition:** Move static web assets to src/main/resources/META-INF/resources for Quarkus JAR packaging.
- src/main/webapp/app → src/main/resources/META-INF/resources/app
- src/main/webapp/bower_components → src/main/resources/META-INF/resources/bower_components
- src/main/webapp/partials → src/main/resources/META-INF/resources/partials
- src/main/webapp/index.jsp → convert to src/main/resources/META-INF/resources/index.html (remove JSP)
- src/main/webapp/health.jsp → remove (use Quarkus health endpoint)
- src/main/webapp/coolstore.json → src/main/resources/META-INF/resources/coolstore.json
- src/main/webapp/keycloak.json → src/main/resources/META-INF/resources/keycloak.json
- DELETE: src/main/webapp/WEB-INF (no longer needed)

---

## Steps

### Phase 1: Build Config

### Step 1: Update pom.xml packaging and properties
- File: pom.xml
- Action: MODIFY
- What to do:
  - Change `<packaging>war</packaging>` to `<packaging>jar</packaging>`
  - Update `<maven.compiler.source>1.8</maven.compiler.source>` to `<maven.compiler.source>17</maven.compiler.source>`
  - Update `<maven.compiler.target>1.8</maven.compiler.target>` to `<maven.compiler.target>17</maven.compiler.target>`
  - Add `<maven.compiler.release>17</maven.compiler.release>`
  - Remove `<maven.test.skip>true</maven.test.skip>` (enable tests)
  - Add Quarkus platform version property: `<quarkus.platform.version>3.8.4</quarkus.platform.version>`
- Why: Quarkus 3 requires Java 17+ and produces JAR artifacts
- Depends on: none
- Verify: `<packaging>jar</packaging>` and `<maven.compiler.release>17</maven.compiler.release>` present in pom.xml

### Step 2: Add Quarkus BOM to pom.xml
- File: pom.xml
- Action: MODIFY
- What to do:
  - Add `<dependencyManagement>` section with quarkus-bom:
    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```
- Why: Quarkus BOM manages all extension versions
- Depends on: Step 1
- Verify: `<dependencyManagement>` section with quarkus-bom exists

### Step 3: Replace Java EE dependencies with Quarkus extensions
- File: pom.xml
- Action: MODIFY
- What to do:
  - REMOVE: `javax:javaee-web-api`, `javax:javaee-api`, `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`, `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
  - ADD (without version tags):
    - `io.quarkus:quarkus-arc` (CDI)
    - `io.quarkus:quarkus-rest` (JAX-RS)
    - `io.quarkus:quarkus-rest-jackson` (JSON)
    - `io.quarkus:quarkus-hibernate-orm` (JPA)
    - `io.quarkus:quarkus-jdbc-postgresql` (PostgreSQL)
    - `io.quarkus:quarkus-narayana-jta` (Transactions)
    - `io.quarkus:quarkus-flyway` (keep existing flyway-core logic)
    - `io.quarkus:quarkus-smallrye-reactive-messaging-amqp` (JMS replacement)
    - `io.quarkus:quarkus-qpid-jms` (AMQP 1.0 JMS client)
  - KEEP: `org.flywaydb:flyway-core` (Quarkus manages version via quarkus-flyway)
  - KEEP: `com.enterprise:audit-logging-library` (system-scoped dependency - handle separately)
- Why: Quarkus uses extensions instead of Java EE umbrella APIs
- Depends on: Step 2
- Verify: No `javax:javaee-*` dependencies remain, all Quarkus extensions lack `<version>` tags

### Step 4: Add Quarkus Maven plugin to pom.xml
- File: pom.xml
- Action: MODIFY
- What to do:
  - ADD to `<build><plugins>`:
    ```xml
    <plugin>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>${quarkus.platform.version}</version>
        <executions>
            <execution>
                <goals>
                    <goal>build</goal>
                    <goal>generate-code</goal>
                    <goal>generate-code-tests</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
    ```
  - REMOVE: `maven-war-plugin`
  - UPDATE maven-compiler-plugin: bump version to `3.11.0`, set `<release>17</release>`
- Why: quarkus-maven-plugin builds Quarkus applications
- Depends on: Step 3
- Verify: quarkus-maven-plugin present, maven-war-plugin absent

### Step 5: Add Maven profiles for Quarkus native build
- File: pom.xml
- Action: MODIFY
- What to do:
  - Replace `<profiles><!-- TODO: Add OpenShift profile here --></profiles>` with:
    ```xml
    <profiles>
        <profile>
            <id>native</id>
            <activation>
                <property>
                    <name>native</name>
                </property>
            </activation>
            <properties>
                <skipITs>false</skipITs>
                <quarkus.package.type>native</quarkus.package.type>
            </properties>
        </profile>
    </profiles>
    ```
- Why: Konveyor analysis recommends native build profile
- Depends on: Step 4
- Verify: `<profile><id>native</id>` present with quarkus.package.type property

---

### Phase 2: App Config

### Step 6: Create application.properties for datasource config
- File: src/main/resources/application.properties
- Action: CREATE
- What to do:
  - Create file with datasource configuration:
    ```properties
    # Datasource
    quarkus.datasource.db-kind=postgresql
    quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/coolstore
    quarkus.datasource.username=coolstore
    quarkus.datasource.password=coolstore
    
    # Hibernate
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.log.sql=false
    quarkus.hibernate-orm.log.format-sql=true
    
    # Flyway
    quarkus.flyway.migrate-at-start=true
    quarkus.flyway.locations=classpath:db/migration
    
    # REST base path (preserve /services)
    quarkus.rest.path=/services
    
    # Dev mode datasource (H2 in-memory)
    %dev.quarkus.datasource.db-kind=h2
    %dev.quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_DELAY=-1
    %dev.quarkus.hibernate-orm.database.generation=drop-and-create
    %dev.quarkus.flyway.migrate-at-start=false
    
    # Test datasource
    %test.quarkus.datasource.db-kind=h2
    %test.quarkus.datasource.jdbc.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1
    %test.quarkus.hibernate-orm.database.generation=drop-and-create
    %test.quarkus.flyway.migrate-at-start=false
    ```
- Why: Quarkus uses application.properties instead of persistence.xml
- Depends on: Step 5
- Verify: File exists at src/main/resources/application.properties

### Step 7: Add messaging configuration to application.properties
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do:
  - Append messaging configuration for AMQP broker:
    ```properties
    
    # AMQP broker connection
    amqp-host=localhost
    amqp-port=5672
    amqp-username=admin
    amqp-password=admin
    
    # Outgoing channel (producer)
    mp.messaging.outgoing.orders-out.connector=smallrye-amqp
    mp.messaging.outgoing.orders-out.address=orders
    
    # Incoming channel 1 (OrderServiceMDB)
    mp.messaging.incoming.orders-in-order-service.connector=smallrye-amqp
    mp.messaging.incoming.orders-in-order-service.address=orders
    mp.messaging.incoming.orders-in-order-service.durable=true
    
    # Incoming channel 2 (InventoryNotificationMDB)
    mp.messaging.incoming.orders-in-inventory.connector=smallrye-amqp
    mp.messaging.incoming.orders-in-inventory.address=orders
    mp.messaging.incoming.orders-in-inventory.durable=true
    ```
- Why: SmallRye Reactive Messaging needs channel configuration (preserves topic fan-out: 1 producer → 2 subscribers)
- Depends on: Step 6
- Verify: Three mp.messaging.* configurations (1 outgoing, 2 incoming) present

### Step 8: Delete persistence.xml
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Remove file (replaced by application.properties)
- Why: Quarkus configures JPA via application.properties
- Depends on: Step 6
- Verify: File does not exist

### Step 9: Delete beans.xml
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Remove file (CDI auto-discovery in Quarkus)
- Why: Quarkus Arc enables CDI by default
- Depends on: Step 6
- Verify: File does not exist

### Step 10: Delete web.xml
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Remove file (no web.xml in Quarkus)
- Why: Quarkus does not use web.xml
- Depends on: Step 6
- Verify: File does not exist

---

### Phase 3: EJB to CDI

### Step 11: Convert ProductService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Remove: `import javax.ejb.Stateless;`, `@Stateless`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*` (javax.inject, javax.persistence)
- Why: Quarkus uses CDI instead of EJBs
- Depends on: Step 10
- Verify: No `@Stateless`, no `javax.*` imports

### Step 12: Convert CatalogService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Remove: `import javax.ejb.Stateless;`, `@Stateless`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*`
- Why: Quarkus uses CDI instead of EJBs
- Depends on: Step 10
- Verify: No `@Stateless`, no `javax.*` imports

### Step 13: Convert OrderService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Remove: `import javax.ejb.Stateless;`, `@Stateless`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*`
  - Keep `@PostConstruct` and `@PreDestroy` (works in Quarkus CDI)
  - Audit logging library usage stays unchanged (system-scoped dependency)
- Why: Quarkus uses CDI instead of EJBs
- Depends on: Step 10
- Verify: `@ApplicationScoped` present, no `@Stateless`, no `javax.*` imports

### Step 14: Convert PromoService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Remove: `import javax.ejb.Stateless;`, `@Stateless`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*`
- Why: Quarkus uses CDI instead of EJBs
- Depends on: Step 10
- Verify: No `@Stateless`, no `javax.*` imports

### Step 15: COMPLEX — Convert ShippingService from @Stateless @Remote to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless @Remote` EJB with Remote interface
  - AFTER: `@ApplicationScoped` CDI bean (no Remote interface)
  - Specific changes:
    1. Remove: `import javax.ejb.Remote;`, `import javax.ejb.Stateless;`, `@Stateless`, `@Remote`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
    3. Keep: `implements ShippingServiceRemote` (interface preserved for contract)
- Why: Quarkus does not support EJB Remote — use direct CDI injection instead
- Depends on: Step 10
- Verify: `@ApplicationScoped` present, no `@Stateless`, no `@Remote`

### Step 16: COMPLEX — Convert ShoppingCartService from @Stateful to @ApplicationScoped + remove JNDI lookup
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateful` EJB with JNDI lookup for ShippingServiceRemote
  - AFTER: `@ApplicationScoped` CDI bean with `@Inject ShippingServiceRemote`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateful;`, `@Stateful`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
    3. Update all `javax.*` imports to `jakarta.*`
    4. DELETE method: `lookupShippingServiceRemote()` (lines ~114-123)
    5. ADD field: `@Inject ShippingServiceRemote shippingService;`
    6. REPLACE: All calls to `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
    7. REPLACE: All calls to `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
  - **Note:** Loss of @Stateful means cart state is no longer session-scoped — acceptable for this migration as session state can be managed client-side or via database
- Why: Quarkus does not support EJB stateful beans or JNDI lookups — use CDI injection
- Depends on: Step 15
- Verify: `@ApplicationScoped` present, no JNDI lookup code, `@Inject ShippingServiceRemote` present

### Step 17: Convert ShoppingCartOrderProcessor from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - Remove: `import javax.ejb.Stateless;`, `@Stateless`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*` (DO NOT touch JMS code yet — messaging phase handles that)
- Why: Quarkus uses CDI instead of EJBs
- Depends on: Step 10
- Verify: `@ApplicationScoped` present, no `@Stateless`, no `javax.*` non-JMS imports

### Step 18: Update REST endpoint CartEndpoint
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - Remove: `@SessionScoped` (line ~24)
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`, `@ApplicationScoped`
  - Update all `javax.*` imports to `jakarta.*`
  - **Note:** Loss of @SessionScoped means session-based cart tracking is lost — client must manage cartId consistently
- Why: Quarkus REST endpoints use CDI scopes
- Depends on: Step 10
- Verify: `@ApplicationScoped` present, no `@SessionScoped`, no `javax.*` imports

### Step 19: Update REST endpoint OrderEndpoint
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Update all `javax.*` imports to `jakarta.*` (javax.ws.rs, javax.inject)
  - No scope annotation change needed (default is @RequestScoped)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: No `javax.*` imports

### Step 20: Update REST endpoint ProductEndpoint
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
  - Update all `javax.*` imports to `jakarta.*` (javax.ws.rs, javax.inject)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: No `javax.*` imports

### Step 21: Update RestApplication
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Update: `import javax.ws.rs.ApplicationPath;` → `import jakarta.ws.rs.ApplicationPath;`
  - Update: `import javax.ws.rs.core.Application;` → `import jakarta.ws.rs.core.Application;`
  - Keep: `@ApplicationPath("/services")` (preserves REST base path)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: `jakarta.ws.rs.*` imports, `/services` path preserved

### Step 22: Update Resources producer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - Update all `javax.*` imports to `jakarta.*` (javax.enterprise, javax.persistence)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: No `javax.*` imports

### Step 23: Update model classes (Order, OrderItem, CatalogItemEntity, InventoryEntity, ShoppingCart, ShoppingCartItem, Product, Promotion)
- File: src/main/java/com/redhat/coolstore/model/*.java (8 files)
- Action: MODIFY
- What to do:
  - For each file, update all `javax.persistence.*` imports to `jakarta.persistence.*`
  - Update `javax.xml.bind.*` imports to `jakarta.xml.bind.*` (if present)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: No `javax.persistence.*` or `javax.xml.bind.*` imports in any model file

---

### Phase 4: Messaging

### Step 24: COMPLEX — Convert OrderServiceMDB from @MessageDriven to SmallRye Reactive Messaging
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: `@MessageDriven` with `activationConfig` for topic/orders
  - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` method
  - Specific changes:
    1. REMOVE: `import javax.ejb.*;`, `import javax.jms.*;`, `implements MessageListener`, all `@MessageDriven` and `@ActivationConfigProperty`
    2. ADD: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import java.util.concurrent.CompletionStage;`, `import java.util.concurrent.CompletableFuture;`
    3. ADD: `@ApplicationScoped` at class level
    4. REPLACE method signature:
       ```java
       // Before:
       public void onMessage(Message rcvMessage) {
           TextMessage msg = null;
           try {
               if (rcvMessage instanceof TextMessage) {
                   msg = (TextMessage) rcvMessage;
                   String orderStr = msg.getBody(String.class);
                   ...
       
       // After:
       @Incoming("orders-in-order-service")
       public CompletionStage<Void> onMessage(String orderStr) {
           try {
               System.out.println("Received order: " + orderStr);
               Order order = Transformers.jsonToOrder(orderStr);
               ...
               return CompletableFuture.completedFuture(null);
           } catch (Exception e) {
               return CompletableFuture.failedFuture(e);
           }
       }
       ```
    5. REMOVE: all JMS exception handling (`JMSException`)
    6. Update: all `javax.inject.*` to `jakarta.inject.*`
- Why: Quarkus uses SmallRye Reactive Messaging instead of MDB
- Depends on: Step 7
- Verify: `@Incoming("orders-in-order-service")` present, no JMS imports, returns CompletionStage

### Step 25: COMPLEX — Convert InventoryNotificationMDB from manual JMS to SmallRye Reactive Messaging
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Manual WebLogic JNDI lookup + Topic subscription with `init()`, `close()`, `getInitialContext()`
  - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` method
  - Specific changes:
    1. REMOVE: All imports (`javax.inject.*`, `javax.jms.*`, `javax.naming.*`, `javax.rmi.*`, `java.util.Hashtable`), `implements MessageListener`
    2. ADD: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import java.util.concurrent.CompletionStage;`, `import java.util.concurrent.CompletableFuture;`
    3. DELETE: All fields (LOW_THRESHOLD can stay as constant), all JNDI-related constants (JNDI_FACTORY, JMS_FACTORY, TOPIC), all JMS fields (tcon, tsession, tsubscriber)
    4. ADD: `@ApplicationScoped` at class level
    5. REPLACE method signature:
       ```java
       // Before:
       public void onMessage(Message rcvMessage) {
           TextMessage msg;
           try {
               System.out.println("received message inventory");
               if (rcvMessage instanceof TextMessage) {
                   msg = (TextMessage) rcvMessage;
                   String orderStr = msg.getBody(String.class);
                   ...
       
       // After:
       @Incoming("orders-in-inventory")
       public CompletionStage<Void> onMessage(String orderStr) {
           try {
               System.out.println("received message inventory");
               Order order = Transformers.jsonToOrder(orderStr);
               ...
               return CompletableFuture.completedFuture(null);
           } catch (Exception e) {
               return CompletableFuture.failedFuture(e);
           }
       }
       ```
    6. DELETE methods: `init()`, `close()`, `getInitialContext()`
    7. Keep: `@Inject CatalogService catalogService;` (update to jakarta.inject.Inject)
- Why: Quarkus does not support WebLogic JNDI — use SmallRye Reactive Messaging
- Depends on: Step 7
- Verify: `@Incoming("orders-in-inventory")` present, no JNDI/JMS code, no init/close methods

### Step 26: COMPLEX — Convert ShoppingCartOrderProcessor JMS producer to Emitter
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Resource Topic` + `@Inject JMSContext` producer
  - AFTER: `@Inject @Channel Emitter<String>` producer
  - Specific changes:
    1. REMOVE: `import javax.annotation.Resource;`, `import javax.jms.*;`, fields `JMSContext context` and `Topic ordersTopic`, `@Resource(lookup = "java:/topic/orders")`
    2. ADD: `import org.eclipse.microprofile.reactive.messaging.Channel;`, `import org.eclipse.microprofile.reactive.messaging.Emitter;`
    3. REPLACE producer field:
       ```java
       // Before:
       @Inject
       private transient JMSContext context;
       
       @Resource(lookup = "java:/topic/orders")
       private Topic ordersTopic;
       
       // After:
       @Inject
       @Channel("orders-out")
       Emitter<String> ordersEmitter;
       ```
    4. REPLACE send logic in `process()` method:
       ```java
       // Before:
       context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
       
       // After:
       ordersEmitter.send(Transformers.shoppingCartToJson(cart));
       ```
    5. Update: `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    6. Keep: `@Inject Logger log;` (update to jakarta.inject)
- Why: Quarkus uses Emitter instead of JMS producer
- Depends on: Step 7
- Verify: `@Channel("orders-out") Emitter<String>` present, no JMS code

---

### Phase 5: Lifecycle

### Step 27: COMPLEX — Replace WebLogic ApplicationLifecycleListener with Quarkus lifecycle events
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
  - BEFORE: `extends ApplicationLifecycleListener` with `postStart()` and `preStop()`
  - AFTER: `@ApplicationScoped` CDI bean with `@Observes StartupEvent` and `@Observes ShutdownEvent`
  - Specific changes:
    1. REMOVE: `import weblogic.application.*;`, `extends ApplicationLifecycleListener`
    2. ADD: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.enterprise.event.Observes;`, `import io.quarkus.runtime.StartupEvent;`, `import io.quarkus.runtime.ShutdownEvent;`
    3. ADD: `@ApplicationScoped` at class level
    4. REPLACE methods:
       ```java
       // Before:
       @Override
       public void postStart(ApplicationLifecycleEvent evt) {
           log.info("AppListener(postStart)");
       }
       
       @Override
       public void preStop(ApplicationLifecycleEvent evt) {
           log.info("AppListener(preStop)");
       }
       
       // After:
       void onStart(@Observes StartupEvent event) {
           log.info("AppListener(postStart)");
       }
       
       void onStop(@Observes ShutdownEvent event) {
           log.info("AppListener(preStop)");
       }
       ```
    5. Update: `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
- Why: Quarkus uses CDI events instead of WebLogic lifecycle listeners
- Depends on: Step 10
- Verify: `@Observes StartupEvent` and `@Observes ShutdownEvent` present, no weblogic imports

### Step 28: Update Transformers utility class
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - Update all `javax.*` imports to `jakarta.*` (if any)
- Why: Jakarta EE namespace migration
- Depends on: Step 10
- Verify: No `javax.*` imports

---

### Phase 6: Cleanup

### Step 29: Move webapp static assets to META-INF/resources
- File: src/main/webapp/* → src/main/resources/META-INF/resources/*
- Action: MODIFY (move directories)
- What to do:
  - Create directory: `src/main/resources/META-INF/resources/`
  - Move: `src/main/webapp/app/` → `src/main/resources/META-INF/resources/app/`
  - Move: `src/main/webapp/bower_components/` → `src/main/resources/META-INF/resources/bower_components/`
  - Move: `src/main/webapp/partials/` → `src/main/resources/META-INF/resources/partials/`
  - Move: `src/main/webapp/coolstore.json` → `src/main/resources/META-INF/resources/coolstore.json`
  - Move: `src/main/webapp/keycloak.json` → `src/main/resources/META-INF/resources/keycloak.json`
  - Convert: `src/main/webapp/index.jsp` → `src/main/resources/META-INF/resources/index.html`
    - Remove JSP directives
    - Keep HTML structure
    - Verify AngularJS app still loads
  - DELETE: `src/main/webapp/health.jsp` (use Quarkus health endpoint `/q/health`)
- Why: Quarkus JAR packaging requires static assets in META-INF/resources
- Depends on: Step 1
- Verify: All webapp assets moved to META-INF/resources, index.html exists (no .jsp)

### Step 30: Delete WEB-INF directory
- File: src/main/webapp/WEB-INF/
- Action: DELETE
- What to do: Remove entire directory (already deleted beans.xml and web.xml in Steps 9-10)
- Why: No WEB-INF in Quarkus JAR packaging
- Depends on: Step 29
- Verify: src/main/webapp/WEB-INF/ does not exist

### Step 31: Delete WebLogic stub classes
- File: src/main/java/weblogic/
- Action: DELETE
- What to do: Remove entire weblogic package tree (ApplicationLifecycleListener, ApplicationLifecycleEvent, NonCatalogLogger)
- Why: No longer needed after StartupListener conversion
- Depends on: Step 27
- Verify: src/main/java/weblogic/ does not exist

### Step 32: Verify no javax.* imports remain in Java sources
- File: All src/main/java/**/*.java
- Action: VERIFY
- What to do:
  - Run: `grep -r "import javax\." src/main/java/ || echo "All javax imports removed"`
  - Expected: No output (all javax.* imports replaced with jakarta.*)
- Why: Ensure complete migration to Jakarta EE namespace
- Depends on: Steps 11-28
- Verify: No `import javax.*` lines in any Java file

---

## Verification

**Build command:**
```bash
mvn package -DskipTests
```

**Expected result:** Exit code 0, JAR artifact at `target/quarkus-app/quarkus-run.jar`

**Startup command:**
```bash
java -jar target/quarkus-app/quarkus-run.jar
```

**Expected result:**
- Application starts without errors
- REST API available at `http://localhost:8080/services/`
- AngularJS UI loads at `http://localhost:8080/`
- Health check at `http://localhost:8080/q/health` returns UP
- Messaging topology preserved (1 producer → 2 subscribers on "orders" address)

---

## Notes

### System-scoped dependency handling
The `audit-logging-library` JAR (system-scoped) remains in pom.xml as-is. Quarkus tolerates system-scoped dependencies but may issue warnings. If the library is compatible with Jakarta EE, it should work unchanged. If it uses javax.* APIs internally, runtime errors may occur — address in verify stage if needed.

### Session state migration
Original app used `@Stateful` EJB (ShoppingCartService) and `@SessionScoped` REST endpoint (CartEndpoint) for session-based cart tracking. Quarkus does not preserve session state automatically in the same way. The client (AngularJS app) must manage `cartId` consistently across requests. This is already the pattern in the original app (cartId passed in URL paths), so the migration should work.

### Messaging broker requirement
The application now requires an AMQP 1.0 broker (e.g., Apache Qpid Dispatch Router, Apache Artemis, or Red Hat AMQ) running at `localhost:5672`. Original app used WebLogic/WildFly embedded JMS — this is an infrastructure change.

### REST base path preservation
The original app uses `@ApplicationPath("/services")` and Quarkus preserves this via `quarkus.rest.path=/services` in application.properties. The AngularJS UI should continue to call `/services/*` endpoints.

### Flyway migration
Quarkus Flyway extension auto-runs migrations at startup. SQL scripts in `src/main/resources/db/migration/` remain unchanged.

### Test coverage
Tests are currently skipped (`maven.test.skip=true` removed in Step 1). No tests exist in the original project. Verification focuses on compile + startup success.
