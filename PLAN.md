# PLAN.md

## Goal
Migrate the coolstore monolith application from Java EE 7 (WAR packaging on JBoss/WildFly) to Quarkus 3 (standalone JAR). The migrated application must package cleanly with `mvn package -DskipTests` and start successfully with `java -jar target/quarkus-app/quarkus-run.jar`.

- Reference used: javaee-to-quarkus skill with modules: build-config, app-config, ejb-to-cdi, messaging, lifecycle, cleanup

## Project Summary
- Type: Maven Java EE 7 WAR application
- Source packaging: WAR (with embedded UI in src/main/webapp)
- Target packaging: JAR (Quarkus standalone)
- Files affected: ~205 Java source files + pom.xml + config files
- Estimated complexity: HIGH
- Hardest steps:
  1. COMPLEX — Convert OrderServiceMDB and InventoryNotificationMDB to SmallRye Reactive Messaging
  2. COMPLEX — Convert JMS producer (ShoppingCartOrderProcessor) to use Emitter
  3. COMPLEX — Remove JNDI lookups in ShoppingCartService and InventoryNotificationMDB
  4. Handle webapp assets under JAR packaging (static resource serving)

## Messaging Topology (Original Java EE)

| Producer | Destination | Consumers |
|----------|-------------|-----------|
| ShoppingCartOrderProcessor | topic/orders (java:/topic/orders) | OrderServiceMDB, InventoryNotificationMDB |

**Note**: Topic fan-out pattern — one message sent to topic/orders is consumed by TWO consumers. This must be preserved in the Quarkus implementation using channel multiplexing or separate channel bindings.

## Steps

### Phase 1: Build Config

### Step 1: Replace packaging and add Quarkus BOM
- File: pom.xml
- Action: MODIFY
- What to do:
  - Change `<packaging>war</packaging>` → `<packaging>jar</packaging>`
  - Add `<quarkus.platform.version>3.8.4</quarkus.platform.version>` to properties
  - Add Quarkus BOM in `<dependencyManagement>`:
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
- Why: Quarkus produces self-contained JARs and uses BOM for version management
- Depends on: none
- Verify: `mvn help:effective-pom | grep '<packaging>jar</packaging>'`

### Step 2: Replace Java EE dependencies with Quarkus extensions
- File: pom.xml
- Action: MODIFY
- What to do:
  - Remove:
    - `javax:javaee-web-api`
    - `javax:javaee-api`
    - `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`
    - `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
  - Add Quarkus extensions (no version — managed by BOM):
    ```xml
    <!-- Core CDI -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <!-- REST + JSON -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-rest</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>
    <!-- JPA + Hibernate -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-hibernate-orm</artifactId>
    </dependency>
    <!-- Database driver (H2 for dev, can override in prod) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-jdbc-h2</artifactId>
    </dependency>
    <!-- Flyway for DB migration -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-flyway</artifactId>
    </dependency>
    <!-- Transactions -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-narayana-jta</artifactId>
    </dependency>
    <!-- Reactive Messaging for JMS/MDB replacement -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-reactive-messaging-amqp</artifactId>
    </dependency>
    <!-- Bean Validation -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-hibernate-validator</artifactId>
    </dependency>
    ```
  - Keep `org.flywaydb:flyway-core` dependency but change to Quarkus-managed version (remove explicit version)
  - Keep system-scoped audit-logging-library dependency as-is
- Why: Quarkus uses extension model instead of monolithic Java EE API
- Depends on: Step 1
- Verify: `mvn dependency:tree | grep quarkus` shows Quarkus dependencies

### Step 3: Update Maven plugins
- File: pom.xml
- Action: MODIFY
- What to do:
  - Update `maven-compiler-plugin` source/target from 1.8 to 17 (Quarkus 3 requires Java 17+):
    ```xml
    <source>17</source>
    <target>17</target>
    ```
  - Remove `maven-war-plugin`
  - Add Quarkus Maven plugin:
    ```xml
    <plugin>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
        <version>${quarkus.platform.version}</version>
        <extensions>true</extensions>
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
  - Add Surefire plugin for testing:
    ```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.0.0</version>
        <configuration>
            <systemPropertyVariables>
                <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                <maven.home>${maven.home}</maven.home>
            </systemPropertyVariables>
        </configuration>
    </plugin>
    ```
  - Add Failsafe plugin for integration tests:
    ```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <version>3.0.0</version>
        <executions>
            <execution>
                <goals>
                    <goal>integration-test</goal>
                    <goal>verify</goal>
                </goals>
            </execution>
        </executions>
        <configuration>
            <systemPropertyVariables>
                <native.image.path>${project.build.directory}/${project.build.finalName}-runner</native.image.path>
                <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                <maven.home>${maven.home}</maven.home>
            </systemPropertyVariables>
        </configuration>
    </plugin>
    ```
- Why: Quarkus uses its own build tooling; Java 17 is minimum for Quarkus 3
- Depends on: Step 2
- Verify: `mvn help:effective-pom | grep -A5 quarkus-maven-plugin`

### Step 4: Add native profile
- File: pom.xml
- Action: MODIFY
- What to do:
  - Replace the empty profiles section with native build profile:
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
                <quarkus.native.enabled>true</quarkus.native.enabled>
            </properties>
        </profile>
    </profiles>
    ```
- Why: Enables native image compilation with `mvn package -Pnative`
- Depends on: Step 3
- Verify: `mvn help:all-profiles | grep native`

### Phase 2: App Config

### Step 5: Create application.properties with datasource and persistence config
- File: src/main/resources/application.properties
- Action: CREATE
- What to do:
  - Create new file with content:
    ```properties
    # Datasource configuration (H2 for dev, override for prod)
    quarkus.datasource.db-kind=h2
    quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    quarkus.datasource.username=sa
    quarkus.datasource.password=
    
    # Hibernate ORM configuration
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.log.sql=false
    quarkus.hibernate-orm.log.format-sql=true
    quarkus.hibernate-orm.sql-load-script=no-file
    
    # Flyway configuration
    quarkus.flyway.migrate-at-start=true
    
    # HTTP configuration
    quarkus.http.port=8080
    
    # REST path (preserve original /services base path)
    quarkus.rest.path=/services
    
    # Reactive Messaging configuration (AMQP broker for topic/orders)
    mp.messaging.incoming.orders.connector=smallrye-amqp
    mp.messaging.incoming.orders.address=orders
    mp.messaging.incoming.orders.durable=true
    
    mp.messaging.outgoing.orders-out.connector=smallrye-amqp
    mp.messaging.outgoing.orders-out.address=orders
    mp.messaging.outgoing.orders-out.durable=true
    
    # AMQP broker connection (default to in-memory for dev)
    amqp-host=localhost
    amqp-port=5672
    amqp-username=admin
    amqp-password=admin
    
    # Dev mode specific settings
    %dev.quarkus.http.cors=true
    %dev.quarkus.hibernate-orm.log.sql=true
    ```
- Why: Replaces persistence.xml, web.xml, and server-specific datasource configs
- Depends on: Step 4
- Verify: File exists at correct path

### Step 6: Delete persistence.xml
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Remove this file — configuration now in application.properties
- Why: Quarkus uses application.properties for all configuration
- Depends on: Step 5
- Verify: File no longer exists

### Step 7: Delete beans.xml
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Remove this file — CDI is enabled by default in Quarkus
- Why: Quarkus Arc doesn't use beans.xml; CDI is always on
- Depends on: Step 5
- Verify: File no longer exists

### Step 8: Delete web.xml
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Remove this file — JAX-RS configuration via annotations
- Why: Quarkus doesn't use web.xml; servlet spec not applicable
- Depends on: Step 5
- Verify: File no longer exists

### Step 9: Move static web resources to META-INF/resources
- File: src/main/webapp/ (directory structure)
- Action: MODIFY
- What to do:
  - Create directory: `src/main/resources/META-INF/resources/`
  - Move contents (excluding WEB-INF which is now deleted):
    - `src/main/webapp/app/` → `src/main/resources/META-INF/resources/app/`
    - `src/main/webapp/bower_components/` → `src/main/resources/META-INF/resources/bower_components/`
    - `src/main/webapp/partials/` → `src/main/resources/META-INF/resources/partials/`
    - `src/main/webapp/*.json` → `src/main/resources/META-INF/resources/`
    - `src/main/webapp/*.jsp` → `src/main/resources/META-INF/resources/` (rename .jsp to .html)
  - Delete now-empty `src/main/webapp/` directory
  - Note: JSP files (health.jsp, index.jsp) should be converted to static HTML or REST endpoints
    - health.jsp → consider replacing with Quarkus health check extension
    - index.jsp → rename to index.html if it's purely static content
- Why: Quarkus serves static content from META-INF/resources in JAR packaging
- Depends on: Steps 7, 8 (WEB-INF deleted first)
- Verify: `ls src/main/resources/META-INF/resources/` shows app/, bower_components/, partials/

### Phase 3: EJB to CDI

### Step 10: Convert CatalogService from @Stateless to @ApplicationScoped + @Transactional
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Replace imports:
    - Remove: `javax.ejb.Stateless`
    - Remove: `javax.inject.Inject`
    - Remove: `javax.persistence.EntityManager`
    - Add: `jakarta.enterprise.context.ApplicationScoped`
    - Add: `jakarta.inject.Inject`
    - Add: `jakarta.persistence.EntityManager`
    - Add: `jakarta.transaction.Transactional`
  - Replace `@Stateless` with `@ApplicationScoped`
  - Add `@Transactional` to methods: `updateInventoryItems` (modifies data)
- Why: Quarkus uses CDI ApplicationScoped beans instead of EJBs; explicit transaction boundaries required
- Depends on: Step 9
- Verify: `grep -c '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java` returns 1

### Step 11: Convert ProductService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Replace imports: `javax.*` → `jakarta.*`
  - Replace `@Stateless` with `@ApplicationScoped`
  - No @Transactional needed if methods are read-only
- Why: Same as Step 10
- Depends on: Step 10
- Verify: Class compiles and has @ApplicationScoped

### Step 12: Convert OrderService from @Stateless to @ApplicationScoped + @Transactional
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Replace imports: `javax.*` → `jakarta.*`
  - Replace `@Stateless` with `@ApplicationScoped`
  - Add `@Transactional` to `save` method (write operation)
- Why: Same as Step 10
- Depends on: Step 10
- Verify: Method `save` has @Transactional annotation

### Step 13: Convert PromoService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Replace imports: `javax.*` → `jakarta.*`
  - Replace `@Stateless` with `@ApplicationScoped`
- Why: Same as Step 10
- Depends on: Step 10
- Verify: Class compiles and has @ApplicationScoped

### Step 14: COMPLEX — Convert ShoppingCartService from @Stateful to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - Replace imports: `javax.*` → `jakarta.*`
  - Replace `@Stateful` with `@ApplicationScoped`
  - Remove JNDI lookup for ShippingServiceRemote:
    - Remove method `lookupShippingServiceRemote()` entirely
    - Add field: `@Inject ShippingService shippingService;`
    - Replace all calls to `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
    - Replace all calls to `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
  - Remove imports:
    - `java.util.Hashtable`
    - `javax.naming.Context`
    - `javax.naming.InitialContext`
    - `javax.naming.NamingException`
  - Note: Original @Stateful maintained per-user cart state; ApplicationScoped is singleton.
    - If true per-session state needed, consider request-scoped storage or external session store
    - Current implementation already has cart instance variable; verify if this causes issues
- Why: Quarkus doesn't support stateful EJBs; JNDI is not available; direct injection replaces remote lookups
- Depends on: Step 10
- Verify: No JNDI imports remain; no calls to InitialContext

### Step 15: Convert ShippingService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - Replace imports: `javax.*` → `jakarta.*`
  - Replace `@Stateless` with `@ApplicationScoped`
  - Remove `@Local` or `@LocalBean` annotations if present
- Why: Same as Step 10
- Depends on: Step 14
- Verify: Class compiles and has @ApplicationScoped

### Step 16: Delete ShippingServiceRemote interface
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Remove this file — remote interfaces not needed in Quarkus
- Why: EJB remote/local interfaces are app-server constructs; Quarkus uses direct injection
- Depends on: Step 15
- Verify: File no longer exists

### Step 17: COMPLEX — Convert ShoppingCartOrderProcessor from @Stateless JMS to use Emitter
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE pattern:
    ```java
    @Stateless
    public class ShoppingCartOrderProcessor {
        @Inject private transient JMSContext context;
        @Resource(lookup = "java:/topic/orders") private Topic ordersTopic;
        
        public void process(ShoppingCart cart) {
            context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));
        }
    }
    ```
  - AFTER pattern:
    ```java
    @ApplicationScoped
    public class ShoppingCartOrderProcessor {
        @Inject Logger log;
        
        @Inject
        @Channel("orders-out")
        Emitter<String> ordersEmitter;
        
        public void process(ShoppingCart cart) {
            log.info("Sending order from processor");
            ordersEmitter.send(Transformers.shoppingCartToJson(cart));
        }
    }
    ```
  - Replace imports:
    - Remove: `javax.ejb.Stateless`, `javax.annotation.Resource`, `javax.inject.Inject`, `javax.jms.*`
    - Add: `jakarta.enterprise.context.ApplicationScoped`, `jakarta.inject.Inject`
    - Add: `org.eclipse.microprofile.reactive.messaging.Channel`
    - Add: `org.eclipse.microprofile.reactive.messaging.Emitter`
  - Remove `@Resource` and `JMSContext` fields
  - Add `@Inject @Channel("orders-out") Emitter<String> ordersEmitter;`
  - Replace `context.createProducer().send(ordersTopic, json)` with `ordersEmitter.send(json)`
- Why: Quarkus replaces JMS with SmallRye Reactive Messaging; Emitter replaces JMS producer
- Depends on: Step 5 (application.properties with messaging config)
- Verify: No JMS imports remain; @Channel annotation present

### Phase 4: Messaging

### Step 18: COMPLEX — Convert OrderServiceMDB from @MessageDriven to @Incoming
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE pattern:
    ```java
    @MessageDriven(name = "OrderServiceMDB", activationConfig = {...})
    public class OrderServiceMDB implements MessageListener {
        @Inject OrderService orderService;
        @Inject CatalogService catalogService;
        
        @Override
        public void onMessage(Message rcvMessage) {
            TextMessage msg = (TextMessage) rcvMessage;
            String orderStr = msg.getBody(String.class);
            // process...
        }
    }
    ```
  - AFTER pattern:
    ```java
    @ApplicationScoped
    public class OrderServiceMDB {
        @Inject OrderService orderService;
        @Inject CatalogService catalogService;
        
        @Incoming("orders")
        @Transactional
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
    }
    ```
  - Replace imports:
    - Remove: `javax.ejb.*`, `javax.jms.*`, `javax.inject.Inject`
    - Add: `jakarta.enterprise.context.ApplicationScoped`, `jakarta.inject.Inject`
    - Add: `org.eclipse.microprofile.reactive.messaging.Incoming`
    - Add: `jakarta.transaction.Transactional`
  - Remove `@MessageDriven`, `@ActivationConfigProperty` annotations
  - Remove `implements MessageListener`
  - Add `@ApplicationScoped` to class
  - Replace method signature:
    - From: `public void onMessage(Message rcvMessage)`
    - To: `@Incoming("orders") @Transactional public void onMessage(String orderStr)`
  - Remove JMS unwrapping code (TextMessage cast, msg.getBody()) — payload is directly String
  - Remove try-catch for JMSException — not needed
- Why: MDB replaced by reactive messaging consumer; @Incoming connects to channel defined in application.properties
- Depends on: Step 5, Step 17
- Verify: No JMS imports remain; @Incoming annotation present

### Step 19: COMPLEX — Convert InventoryNotificationMDB from manual JNDI subscriber to @Incoming
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE pattern: Manual JNDI lookup with init()/close(), WebLogic-specific InitialContext
  - AFTER pattern:
    ```java
    @ApplicationScoped
    public class InventoryNotificationMDB {
        private static final int LOW_THRESHOLD = 50;
        
        @Inject
        private CatalogService catalogService;
        
        @Incoming("orders")
        public void onMessage(String orderStr) {
            System.out.println("received message inventory");
            Order order = Transformers.jsonToOrder(orderStr);
            order.getItemList().forEach(orderItem -> {
                int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId()).getInventory().getQuantity();
                int new_quantity = old_quantity - orderItem.getQuantity();
                if (new_quantity < LOW_THRESHOLD) {
                    System.out.println("Inventory for item " + orderItem.getProductId() + " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                } else {
                    orderItem.setQuantity(new_quantity);
                }
            });
        }
    }
    ```
  - Replace imports:
    - Remove: `javax.inject.Inject`, `javax.jms.*`, `javax.naming.*`, `javax.rmi.PortableRemoteObject`, `java.util.Hashtable`
    - Add: `jakarta.enterprise.context.ApplicationScoped`, `jakarta.inject.Inject`
    - Add: `org.eclipse.microprofile.reactive.messaging.Incoming`
  - Remove `implements MessageListener`
  - Add `@ApplicationScoped` to class
  - Remove all JNDI-related code: `JNDI_FACTORY`, `JMS_FACTORY`, `TOPIC`, `tcon`, `tsession`, `tsubscriber`, `init()`, `close()`, `getInitialContext()`
  - Replace method signature:
    - From: `public void onMessage(Message rcvMessage)`
    - To: `@Incoming("orders") public void onMessage(String orderStr)`
  - Remove JMS unwrapping code
  - Remove try-catch for JMSException
  - Note: Both OrderServiceMDB and InventoryNotificationMDB use `@Incoming("orders")` — this is correct for topic fan-out; both will receive the same message
- Why: JNDI not available in Quarkus; reactive messaging replaces manual JMS subscription
- Depends on: Step 5, Step 18
- Verify: No JNDI imports remain; no InitialContext usage; @Incoming annotation present

### Phase 5: Lifecycle

### Step 20: COMPLEX — Convert DataBaseMigrationStartup from @Singleton @Startup to Quarkus lifecycle event
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - BEFORE pattern:
    ```java
    @Singleton
    @Startup
    @TransactionManagement(TransactionManagementType.BEAN)
    public class DataBaseMigrationStartup {
        @Inject Logger logger;
        @Resource(mappedName = "java:jboss/datasources/CoolstoreDS") DataSource dataSource;
        
        @PostConstruct
        private void startup() {
            Flyway flyway = new Flyway();
            flyway.setDataSource(dataSource);
            flyway.baseline();
            flyway.migrate();
        }
    }
    ```
  - AFTER pattern:
    ```java
    @ApplicationScoped
    public class DataBaseMigrationStartup {
        @Inject Logger logger;
        
        void onStart(@Observes StartupEvent ev) {
            logger.info("Flyway migration will run automatically via quarkus.flyway.migrate-at-start=true");
            // Migration is now handled by Quarkus Flyway extension automatically
            // This class can be deleted if no other startup logic is needed
        }
    }
    ```
  - OR simply DELETE this file since Flyway is now configured to run automatically via `quarkus.flyway.migrate-at-start=true` in application.properties
  - If keeping the file for logging or other startup tasks:
    - Replace imports:
      - Remove: `javax.annotation.PostConstruct`, `javax.annotation.Resource`, `javax.ejb.*`, `javax.inject.Inject`, `javax.sql.DataSource`
      - Add: `jakarta.enterprise.context.ApplicationScoped`, `jakarta.inject.Inject`, `jakarta.enterprise.event.Observes`
      - Add: `io.quarkus.runtime.StartupEvent`
    - Remove `@Singleton`, `@Startup`, `@TransactionManagement`
    - Add `@ApplicationScoped`
    - Remove `@Resource DataSource` field
    - Replace `@PostConstruct private void startup()` with `void onStart(@Observes StartupEvent ev)`
    - Remove Flyway initialization code — now handled by Quarkus extension
- Why: Quarkus Flyway extension runs migrations automatically; @Observes StartupEvent replaces @Singleton @Startup @PostConstruct
- Depends on: Step 5 (Flyway config in application.properties)
- Verify: No @PostConstruct, @Startup, or @Resource annotations remain

### Step 21: Check and update StartupListener if it exists
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY (if file exists and has logic)
- What to do:
  - If this is a ServletContextListener or WebLogic ApplicationLifecycleListener:
    - Convert to Quarkus lifecycle event with `@Observes StartupEvent`
    - Replace imports: server-specific → `io.quarkus.runtime.StartupEvent`
  - If file is empty or just a placeholder, DELETE it
- Why: Servlet listeners don't exist in Quarkus; use @Observes StartupEvent/ShutdownEvent
- Depends on: Step 20
- Verify: No servlet or app-server-specific imports remain

### Phase 6: Cleanup

### Step 22: Update imports in all model classes (javax → jakarta)
- File: src/main/java/com/redhat/coolstore/model/*.java (all entity classes)
- Action: MODIFY
- What to do:
  - Replace `javax.persistence.*` → `jakarta.persistence.*`
  - Replace `javax.validation.*` → `jakarta.validation.*` (if present)
  - Replace `javax.xml.bind.*` → `jakarta.xml.bind.*` (if present)
  - Files affected:
    - Order.java
    - OrderItem.java
    - Product.java
    - Promotion.java
    - ShoppingCart.java
    - ShoppingCartItem.java
    - CatalogItemEntity.java
    - InventoryEntity.java
- Why: Jakarta EE namespace (javax → jakarta) is required for Quarkus 3
- Depends on: Step 21
- Verify: `grep -r 'javax.persistence' src/main/java/com/redhat/coolstore/model/` returns nothing

### Step 23: Update imports in all REST endpoint classes
- File: src/main/java/com/redhat/coolstore/rest/*.java
- Action: MODIFY
- What to do:
  - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
  - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
  - Files affected:
    - RestApplication.java
    - CartEndpoint.java
    - OrderEndpoint.java
    - ProductEndpoint.java
- Why: Jakarta EE namespace required
- Depends on: Step 22
- Verify: `grep -r 'javax.ws.rs' src/main/java/com/redhat/coolstore/rest/` returns nothing

### Step 24: Update Resources.java EntityManager producer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - Replace imports:
    - `javax.enterprise.context.Dependent` → `jakarta.enterprise.context.Dependent`
    - `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
    - `javax.persistence.EntityManager` → `jakarta.persistence.EntityManager`
    - `javax.persistence.PersistenceContext` → `jakarta.persistence.PersistenceContext`
  - Note: @Produces @PersistenceContext pattern is discouraged in Quarkus but still works
  - Alternative (optional): Remove this class entirely and use `@Inject EntityManager` directly in services
- Why: Jakarta namespace required
- Depends on: Step 22
- Verify: No javax.* imports remain in file

### Step 25: Update Transformers utility class imports
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - Replace any `javax.*` imports → `jakarta.*`
  - If it uses Jackson or JSON-P, ensure compatible with Quarkus version
- Why: Jakarta namespace required
- Depends on: Step 22
- Verify: Class compiles

### Step 26: Update Producers utility class imports
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Replace `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
  - Replace `javax.enterprise.inject.spi.InjectionPoint` → `jakarta.enterprise.inject.spi.InjectionPoint` (if present)
  - Replace any other `javax.*` → `jakarta.*`
- Why: Jakarta namespace required
- Depends on: Step 22
- Verify: No javax.* imports remain

### Step 27: Verify no javax.* Java EE imports remain in entire codebase
- File: (all Java files)
- Action: VERIFY
- What to do:
  - Run: `grep -r 'import javax\.' src/main/java/com/redhat/coolstore/`
  - Should only find non-EE javax packages that are acceptable:
    - `javax.crypto.*` (OK — Java SE)
    - `javax.net.*` (OK — Java SE)
    - `javax.security.auth.*` (OK — Java SE)
    - `javax.xml.* (except javax.xml.bind)` (OK — Java SE)
  - If any Java EE javax imports found (`javax.ejb`, `javax.jms`, `javax.persistence`, `javax.ws.rs`, `javax.inject`, `javax.enterprise`, `javax.annotation`, `javax.transaction`), update them to jakarta.*
- Why: Final verification that migration is complete
- Depends on: Steps 22-26
- Verify: Only Java SE javax packages remain

### Step 28: Delete WEB-INF directory (if not already deleted)
- File: src/main/webapp/WEB-INF/
- Action: DELETE
- What to do: Ensure this directory is completely removed
- Why: No longer needed in JAR packaging
- Depends on: Steps 7, 8
- Verify: `ls src/main/webapp/WEB-INF 2>&1` shows "No such file or directory"

### Step 29: Create Quarkus health check endpoint (optional, replaces health.jsp)
- File: src/main/java/com/redhat/coolstore/health/HealthCheckEndpoint.java
- Action: CREATE
- What to do:
  - Create new file with content:
    ```java
    package com.redhat.coolstore.health;
    
    import jakarta.ws.rs.GET;
    import jakarta.ws.rs.Path;
    import jakarta.ws.rs.Produces;
    import jakarta.ws.rs.core.MediaType;
    
    @Path("/health")
    public class HealthCheckEndpoint {
        
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String health() {
            return "OK";
        }
    }
    ```
  - Alternatively, add Quarkus SmallRye Health extension for full health checks:
    - Add to pom.xml: `io.quarkus:quarkus-smallrye-health`
    - Access built-in health endpoints at `/q/health`, `/q/health/live`, `/q/health/ready`
- Why: Replaces health.jsp with REST endpoint
- Depends on: Step 9
- Verify: File exists or health extension added to pom.xml

### Step 30: Final verification checklist
- File: (entire project)
- Action: VERIFY
- What to do:
  - [ ] pom.xml has `<packaging>jar</packaging>`
  - [ ] pom.xml has Quarkus BOM and extensions
  - [ ] pom.xml has no `maven-war-plugin`
  - [ ] src/main/resources/application.properties exists with datasource, persistence, and messaging config
  - [ ] src/main/resources/META-INF/persistence.xml deleted
  - [ ] src/main/webapp/WEB-INF/ deleted
  - [ ] src/main/resources/META-INF/resources/ contains static web assets
  - [ ] All @Stateless, @Stateful, @Singleton (ejb) replaced with @ApplicationScoped
  - [ ] All @MessageDriven replaced with @ApplicationScoped + @Incoming
  - [ ] JMS producer uses @Channel Emitter
  - [ ] No JNDI lookups (InitialContext, Context.lookup) remain
  - [ ] No `javax.*` Java EE imports remain (only javax Java SE packages like crypto, net are OK)
  - [ ] All imports use `jakarta.*` for EE APIs
  - [ ] @Transactional added to write methods in services
  - [ ] Flyway config in application.properties
  - [ ] REST base path preserved at /services
  - [ ] Messaging topology preserved (fan-out from orders-out channel to two @Incoming("orders") consumers)
- Why: Comprehensive check before build
- Depends on: Steps 1-29
- Verify: All checkboxes pass

## Verification

### Build Command
```bash
mvn package -DskipTests
```

Must exit with code 0 (success). Build artifact: `target/quarkus-app/quarkus-run.jar`

### Start Command
```bash
java -jar target/quarkus-app/quarkus-run.jar
```

Must start cleanly with no errors. Expected output should show:
- Quarkus banner/version
- Flyway migration messages
- "Listening on: http://0.0.0.0:8080" or similar
- AMQP connector connecting to broker (or warning if broker not available — this is acceptable for verification)

Application should respond to HTTP requests at `http://localhost:8080/services/*` (REST base path preserved).

### Messaging Verification (Optional, if AMQP broker available)
```bash
# Start AMQP broker (e.g., Artemis or RabbitMQ) first
# Then test order flow:
curl -X POST http://localhost:8080/services/cart/checkout/{cartId}
# Should trigger: ShoppingCartOrderProcessor → orders-out channel → topic → OrderServiceMDB + InventoryNotificationMDB
```

## Notes

### Messaging Topology Preservation
- **Original**: JMS Topic (topic/orders) with two consumers (OrderServiceMDB, InventoryNotificationMDB) receiving same message (fan-out)
- **Migrated**: AMQP with SmallRye Reactive Messaging
  - Producer: `@Channel("orders-out")` → `mp.messaging.outgoing.orders-out.address=orders`
  - Consumer 1: OrderServiceMDB `@Incoming("orders")` → `mp.messaging.incoming.orders.address=orders`
  - Consumer 2: InventoryNotificationMDB `@Incoming("orders")` → same channel, both receive the message
  - Fan-out behavior: AMQP topic semantics preserve broadcast — both consumers get the message

### Static Content Disposition
- **Original**: src/main/webapp/ served by app server at root path
- **Migrated**: src/main/resources/META-INF/resources/ bundled in JAR and served by Quarkus at root path
- **JSP Handling**: JSP files (health.jsp, index.jsp) are not supported in Quarkus
  - health.jsp → replaced with REST endpoint or SmallRye Health extension
  - index.jsp → if static, rename to .html; if dynamic, convert to REST + client-side rendering
- **Size**: webapp assets are ~50MB (bower_components). This is bundled into the JAR. Consider:
  - Using npm/webpack to minimize frontend assets
  - Serving UI from separate container/CDN in production
  - For this migration, assets are moved as-is to verify build and start

### Session State Warning
- ShoppingCartService was @Stateful (per-user session state)
- Migrated to @ApplicationScoped (singleton)
- Current implementation has instance variable `cart` which will be SHARED across all users
- **Production Fix Required**: Implement proper session storage:
  - Option 1: Request-scoped cart with external session store (Redis, DB)
  - Option 2: Stateless cart lookup by cartId from persistent storage
  - Option 3: Client-side cart management with REST CRUD endpoints
- For verification, this may cause incorrect behavior but won't prevent startup

### JNDI Removal
- All JNDI lookups removed (Context, InitialContext, lookup())
- EJB remote interface lookups replaced with `@Inject` direct injection
- Datasource JNDI replaced with Quarkus config + injection
- JMS destination JNDI replaced with Reactive Messaging channels

### Transaction Management
- Original: Container-managed transactions (implicit in @Stateless/@Stateful)
- Migrated: Explicit `@Transactional` on write methods
- Quarkus JTA extension provides transaction support
- EntityManager operations requiring transactions must be marked @Transactional

### Java Version
- Original: Java 8 (source/target 1.8)
- Migrated: Java 17 minimum (Quarkus 3 requirement)
- Update build environment and runtime to Java 17+

### External Dependencies
- System-scoped dependency (audit-logging-library-1.0.0.jar) preserved as-is
- May need verification that library is compatible with Jakarta EE namespace
- If library uses javax.* APIs, may need Jakarta EE version of the library

### Gotchas
- REST base path `/services` MUST be preserved (requirement)
  - Set via `quarkus.rest.path=/services` in application.properties
  - Verify all REST endpoints remain accessible at existing paths
- Messaging broker required for full functionality
  - Application will start without broker but messaging features won't work
  - Use in-memory connector for dev/test: `mp.messaging.incoming.orders.connector=smallrye-in-memory`
- Large static asset bundle (50MB bower_components)
  - Increases JAR size significantly
  - Consider optimization in production
- Logger injection: Quarkus provides JBoss Logging, @Inject Logger works but consider using org.jboss.logging.Logger explicitly

---

## Verification Results

### Summary
All three validation gates passed successfully. The migrated Quarkus 3 application builds, starts cleanly, and serves REST endpoints at the preserved `/services` base path.

### Gate 1: Build Success ✅
**Command:** `mvn package -DskipTests`
**Status:** PASSED (exit code 0)
**Artifact:** `target/quarkus-app/quarkus-run.jar` (673 bytes bootstrap JAR)
**Build time:** ~50 seconds

**Fixes Applied:**
1. **Ambiguous EntityManager producer** - Deleted `src/main/java/com/redhat/coolstore/persistence/Resources.java`
   - **Reason:** Quarkus provides a built-in EntityManager bean. The custom @Produces @PersistenceContext method conflicted with Quarkus's synthetic EntityManager bean.
   - **Impact:** OrderService, CatalogService, and other services now inject Quarkus's managed EntityManager directly.

2. **ShippingServiceRemote interface implementation** - Removed `implements ShippingServiceRemote` from ShippingService
   - **Reason:** The ShippingServiceRemote interface was deleted (Step 16 in PLAN.md) but ShippingService.java still declared it.
   - **Impact:** ShippingService is now a plain @ApplicationScoped CDI bean without EJB remote interface.

3. **System-scoped dependency configuration** - Restored `<scope>system</scope>` and `<systemPath>` for audit-logging-library
   - **Reason:** The execute stage incorrectly converted this to compile scope, breaking the build when Maven couldn't find the artifact in central.
   - **Impact:** The local JAR at `lib/audit-logging-library-1.0.0.jar` is now correctly referenced.

### Gate 2: Clean Startup ✅
**Command:** `timeout 60 java -jar target/quarkus-app/quarkus-run.jar`
**Status:** PASSED
**Startup time:** ~1.3 seconds
**Output:** `Listening on: http://0.0.0.0:8080`

**Deployment Checks:**
- ✅ No CDI scope errors
- ✅ No SmallRye wiring errors (SRMSG00073 dual-direction channel)
- ✅ No unknown-connector failures
- ✅ No missing-sequence failures
- ✅ Flyway migrations applied successfully (2 migrations: CreateSchema, AddInitialData)
- ✅ All Quarkus features initialized correctly

**Fixes Applied:**
4. **Topic fan-out configuration** - Added `mp.messaging.incoming.orders.broadcast=true` to application.properties
   - **Reason:** SmallRye Reactive Messaging detected two @Incoming("orders") consumers (OrderServiceMDB and InventoryNotificationMDB) but the channel was configured for single-consumer mode.
   - **Error:** `TooManyDownstreamCandidatesException: 'IncomingConnector{channel:'orders'...}' supports a single downstream consumer, but found 2`
   - **Impact:** Both consumers now receive the same message (fan-out/broadcast semantics), preserving the original JMS Topic behavior.

**Expected Warnings (non-blocking):**
- AMQP broker connection failures (Connection refused: localhost:5672)
  - **Expected:** No AMQP broker is running in the test environment.
  - **Behavior:** The application starts successfully and retries connection in the background. The REST API and database layers function independently.
  - **Production Note:** Configure a production AMQP broker (Artemis, RabbitMQ, etc.) via application.properties.

### Gate 3: REST Endpoint Validation ✅
**Status:** PASSED
**Base Path Preserved:** `/services` ✅

**Endpoints Tested:**
1. `GET /services/products` → 200 OK
   - Returns JSON array of 9 products from H2 database
   - Sample: `{"itemId":"329299","name":"Quarkus T-shirt","price":10.0,...}`

2. `GET /services/products/{productId}` → 200 OK
   - Tested with productId=329299
   - Returns single product JSON object

3. `GET /services/cart/{cartId}` → 200 OK
   - Tested with cartId=123
   - Returns empty cart structure: `{"cartItemTotal":0.0,...,"shoppingCartItemList":[]}`

**Verification Method:**
```bash
curl -s http://localhost:8080/services/products
curl -s http://localhost:8080/services/products/329299
curl -s http://localhost:8080/services/cart/123
```

All endpoints returned valid JSON responses with correct data from the in-memory H2 database populated by Flyway migrations.

---

## Honest Caveats

### 1. Messaging End-to-End Not Verified
- **What's Tested:** Topic fan-out wiring (two consumers for one channel) validated at application startup.
- **What's NOT Tested:** Actual message flow from ShoppingCartOrderProcessor → AMQP → OrderServiceMDB + InventoryNotificationMDB.
- **Reason:** No AMQP broker running in test environment.
- **Production Requirement:** Deploy with Artemis, RabbitMQ, or Azure Service Bus and test order checkout flow.

### 2. In-Memory H2 Database
- **Current State:** `jdbc:h2:mem:coolstore` - data lost on restart.
- **Production Requirement:** Configure persistent datasource (PostgreSQL, MySQL, etc.) via:
  ```properties
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.jdbc.url=jdbc:postgresql://host:5432/coolstore
  quarkus.datasource.username=...
  quarkus.datasource.password=...
  ```

### 3. Session State Management
- **Issue:** ShoppingCartService was migrated from @Stateful (per-user session) to @ApplicationScoped (singleton).
- **Impact:** Cart state is shared across all users (not production-safe).
- **Recommendation:** Implement one of:
  - Request-scoped cart with external session store (Redis, database)
  - Stateless cart lookup by cartId from persistent storage
  - Client-side cart management with REST CRUD endpoints

### 4. Static Web Assets (50MB)
- **Current State:** `src/main/resources/META-INF/resources/` contains bower_components (~50MB) bundled in the JAR.
- **Impact:** Large artifact size.
- **Production Consideration:** Serve UI from separate container, CDN, or S3; or minimize with webpack/npm.

### 5. Audit Logging Library
- **Current State:** System-scoped dependency using local JAR (`lib/audit-logging-library-1.0.0.jar`).
- **Risk:** Library may use javax.* APIs incompatible with Jakarta EE namespace.
- **Recommendation:** Verify library compatibility or obtain Jakarta EE-compatible version.

### 6. No Production Configuration Profile
- **Current State:** Uses `prod` profile by default with dev-oriented settings.
- **Recommendation:** Create separate config for dev, test, prod:
  ```properties
  %prod.quarkus.datasource.jdbc.url=...
  %prod.amqp-host=...
  %prod.quarkus.log.level=INFO
  ```

---

## Migration Completion Statement

The Java EE 7 to Quarkus 3 migration is **functionally complete** for the core application:
- ✅ Build succeeds without errors
- ✅ Application starts in <2 seconds (vs. ~30s on Java EE app server)
- ✅ REST endpoints respond correctly at preserved `/services` path
- ✅ Database layer operational (JPA/Hibernate with Flyway migrations)
- ✅ CDI dependency injection functioning
- ✅ Reactive messaging wiring validated (fan-out topology configured)

**Next Steps for Production:**
1. Configure external AMQP broker and verify message flow
2. Replace H2 with production-grade persistent database
3. Fix ShoppingCartService session state management
4. Add comprehensive integration tests
5. Configure production logging, monitoring, and health checks
6. Review and optimize static asset delivery strategy
