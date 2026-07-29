# PLAN.md

## Goal
Migrate the coolstore application from Java EE 7 (WAR on JBoss/WebLogic) to Quarkus 3 (JAR runtime).
- Reference used: javaee-to-quarkus skill
- Migration phases: Build Config → App Config → EJB-to-CDI → Messaging → Lifecycle → Cleanup

## Project Summary
- Type: Maven (WAR packaging → JAR packaging)
- Files affected: 27 Java source files + 1 pom.xml + 3 config files (persistence.xml, web.xml, beans.xml)
- Estimated complexity: High
- Hardest steps:
  1. Convert 2 MDB classes (InventoryNotificationMDB, OrderServiceMDB) from JMS MessageListener to SmallRye Reactive Messaging
  2. Replace JNDI lookups with direct injection (ShoppingCartService, InventoryNotificationMDB)
  3. Convert WebLogic ApplicationLifecycleListener (StartupListener) to Quarkus lifecycle events

## Steps

### PHASE 1: Build Config

### Step 1: Transform pom.xml to Quarkus 3 build
- File: pom.xml
- Action: MODIFY
- What to do:
    - Change `<packaging>war</packaging>` → `<packaging>jar</packaging>`
    - Add Java version properties (17 minimum for Quarkus 3):
      ```xml
      <maven.compiler.source>17</maven.compiler.source>
      <maven.compiler.target>17</maven.compiler.target>
      <maven.compiler.release>17</maven.compiler.release>
      ```
    - Add Quarkus BOM in `<dependencyManagement>`:
      ```xml
      <dependencyManagement>
          <dependencies>
              <dependency>
                  <groupId>io.quarkus.platform</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>3.8.4</version>
                  <type>pom</type>
                  <scope>import</scope>
              </dependency>
          </dependencies>
      </dependencyManagement>
      ```
    - REMOVE dependencies:
      - `javax:javaee-web-api`
      - `javax:javaee-api`
      - `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`
      - `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
    - ADD Quarkus extensions (without version tags):
      ```xml
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-arc</artifactId>
      </dependency>
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-rest</artifactId>
      </dependency>
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-rest-jackson</artifactId>
      </dependency>
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
          <artifactId>quarkus-narayana-jta</artifactId>
      </dependency>
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-smallrye-reactive-messaging</artifactId>
      </dependency>
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-smallrye-reactive-messaging-amqp</artifactId>
      </dependency>
      ```
    - KEEP: `org.flywaydb:flyway-core` (migrate to `io.quarkus:quarkus-flyway` or keep as-is)
    - KEEP: system-scoped `audit-logging-library` dependency as-is
    - Update `maven-compiler-plugin` to version 3.11.0 and configure source/target 17
    - REMOVE `maven-war-plugin`
    - ADD Quarkus Maven plugin:
      ```xml
      <plugin>
          <groupId>io.quarkus.platform</groupId>
          <artifactId>quarkus-maven-plugin</artifactId>
          <version>3.8.4</version>
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
    - ADD Maven Surefire plugin configuration:
      ```xml
      <plugin>
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
    - ADD native build profile:
      ```xml
      <profiles>
          <profile>
              <id>native</id>
              <properties>
                  <quarkus.package.type>native</quarkus.package.type>
              </properties>
          </profile>
      </profiles>
      ```
- Why: Quarkus uses JAR packaging with managed dependencies via BOM
- Depends on: none
- Verify: `mvn help:effective-pom` shows Quarkus BOM applied

### PHASE 2: App Config

### Step 2: Create application.properties for datasource and persistence config
- File: src/main/resources/application.properties
- Action: CREATE
- What to do:
    - Create new file with content:
      ```properties
      # Datasource configuration (replaces persistence.xml JNDI datasource)
      quarkus.datasource.db-kind=h2
      quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
      quarkus.datasource.username=sa
      quarkus.datasource.password=sa
      
      # Hibernate ORM configuration
      quarkus.hibernate-orm.database.generation=none
      quarkus.hibernate-orm.log.sql=false
      quarkus.hibernate-orm.log.format-sql=true
      
      # Flyway migration
      quarkus.flyway.migrate-at-start=true
      
      # Reactive Messaging - AMQP broker configuration for topics/queues
      mp.messaging.outgoing.notifications-out.connector=smallrye-amqp
      mp.messaging.outgoing.notifications-out.address=notifications
      mp.messaging.outgoing.notifications-out.durable=true
      
      mp.messaging.incoming.order-queue.connector=smallrye-amqp
      mp.messaging.incoming.order-queue.address=orders
      mp.messaging.incoming.order-queue.durable=true
      
      mp.messaging.outgoing.orders-out.connector=smallrye-amqp
      mp.messaging.outgoing.orders-out.address=orders
      mp.messaging.outgoing.orders-out.durable=true
      ```
- Why: Quarkus uses application.properties instead of persistence.xml and web.xml for configuration
- Depends on: Step 1
- Verify: File exists and contains datasource properties

### Step 3: Delete persistence.xml
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Remove file entirely
- Why: Replaced by application.properties datasource config
- Depends on: Step 2
- Verify: File no longer exists

### Step 4: Delete web.xml
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Remove file entirely
- Why: Not needed for JAR packaging, REST activation is automatic in Quarkus
- Depends on: Step 2
- Verify: File no longer exists

### Step 5: Delete beans.xml
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Remove file entirely
- Why: Quarkus CDI is enabled by default, beans.xml content is ignored in Quarkus
- Depends on: Step 2
- Verify: File no longer exists

### PHASE 3: EJB to CDI

### Step 6: Convert CatalogService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.persistence.EntityManager;` → `import jakarta.persistence.EntityManager;`
    - Replace `import javax.persistence.criteria.*;` → `import jakarta.persistence.criteria.*;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Add `@Transactional` annotation to methods that modify data (updateInventoryItems)
    - Add import: `import jakarta.transaction.Transactional;`
- Why: EJBs are not supported in Quarkus; use CDI @ApplicationScoped instead
- Depends on: Step 1
- Verify: No `@Stateless` or `javax.ejb` imports remain

### Step 7: Convert OrderService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.persistence.EntityManager;` → `import jakarta.persistence.EntityManager;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Add `@Transactional` to save() method (persists Order)
    - Add import: `import jakarta.transaction.Transactional;`
- Why: EJBs not supported in Quarkus
- Depends on: Step 1
- Verify: No `@Stateless` annotation remains

### Step 8: Convert ProductService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.persistence.EntityManager;` → `import jakarta.persistence.EntityManager;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace `@Stateless` → `@ApplicationScoped`
- Why: EJBs not supported in Quarkus
- Depends on: Step 1
- Verify: No `@Stateless` annotation remains

### Step 9: Convert ShoppingCartService from @Stateful to @ApplicationScoped and remove JNDI lookup
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateful;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace `@Stateful` → `@ApplicationScoped`
    - REMOVE imports: `import javax.naming.*;`, `import java.util.Hashtable;`
    - REMOVE the entire `lookupShippingServiceRemote()` method
    - Replace all calls to `lookupShippingServiceRemote()` with direct usage of injected field
    - ADD field at class level: `@Inject ShippingService shippingService;`
    - Replace `lookupShippingServiceRemote().calculateShipping(sc)` → `shippingService.calculateShipping(sc)`
    - Replace `lookupShippingServiceRemote().calculateShippingInsurance(sc)` → `shippingService.calculateShippingInsurance(sc)`
- Why: @Stateful EJBs and JNDI lookups not supported in Quarkus; use CDI injection
- Depends on: Step 1
- Verify: No JNDI or InitialContext references remain

### Step 10: Convert ShippingService from @Remote EJB to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.ejb.Remote;` → (remove this import)
    - Replace `@Stateless @Remote` → `@ApplicationScoped`
    - KEEP: `implements ShippingServiceRemote` (interface can remain for clarity)
- Why: Remote EJBs not supported in Quarkus, only local CDI beans
- Depends on: Step 1
- Verify: No `@Stateless`, `@Remote`, or `javax.ejb` imports remain

### Step 11: Convert ShoppingCartOrderProcessor from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - (JMS changes will come in Phase 4 - Messaging)
- Why: EJBs not supported in Quarkus
- Depends on: Step 1
- Verify: No `@Stateless` annotation remains

### Step 12: Convert PromoService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Replace all `javax.inject.*` → `jakarta.inject.*`
- Why: EJBs not supported in Quarkus
- Depends on: Step 1
- Verify: No `@Stateless` annotation remains

### PHASE 4: Messaging

### Step 13: COMPLEX — Convert ShoppingCartOrderProcessor from JMS Topic producer to Reactive Messaging Emitter
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `import javax.annotation.Resource;`
      - `import javax.jms.JMSContext;`
      - `import javax.jms.Topic;`
    - ADD imports:
      - `import org.eclipse.microprofile.reactive.messaging.Channel;`
      - `import org.eclipse.microprofile.reactive.messaging.Emitter;`
    - REMOVE fields:
      ```java
      @Inject
      private transient JMSContext context;
      
      @Resource(lookup = "java:/topic/orders")
      private Topic ordersTopic;
      ```
    - ADD field:
      ```java
      @Inject
      @Channel("orders-out")
      Emitter<String> ordersEmitter;
      ```
    - Replace method body in `process()`:
      - BEFORE: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));`
      - AFTER: `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: JMS API not supported in Quarkus; use SmallRye Reactive Messaging with Emitter
- Depends on: Step 11, Step 2 (application.properties with messaging config)
- Verify: No `javax.jms` imports remain; Emitter is injected

### Step 14: COMPLEX — Convert OrderServiceMDB from @MessageDriven to @Incoming reactive consumer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `import javax.ejb.ActivationConfigProperty;`
      - `import javax.ejb.MessageDriven;`
      - `import javax.jms.*;`
    - ADD imports:
      - `import jakarta.enterprise.context.ApplicationScoped;`
      - `import jakarta.inject.Inject;`
      - `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    - REMOVE class annotations:
      - Remove entire `@MessageDriven(...)` annotation block
    - ADD class annotation:
      - `@ApplicationScoped`
    - REMOVE: `implements MessageListener`
    - Change method signature:
      - BEFORE:
        ```java
        @Override
        public void onMessage(Message rcvMessage) {
            System.out.println("\nMessage recd !");
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
        ```
      - AFTER:
        ```java
        @Incoming("order-queue")
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
- Why: MDBs not supported in Quarkus; use Reactive Messaging @Incoming
- Depends on: Step 2 (application.properties messaging config)
- Verify: No `@MessageDriven` or `javax.jms` imports remain; method has `@Incoming` annotation

### Step 15: COMPLEX — Convert InventoryNotificationMDB from MessageListener to @Incoming reactive consumer and remove JNDI
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - ADD imports:
      - `import jakarta.enterprise.context.ApplicationScoped;`
      - `import jakarta.inject.Inject;`
      - `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    - REMOVE imports:
      - `import javax.inject.Inject;` → replace with jakarta
      - `import javax.jms.*;`
      - `import javax.naming.*;`
      - `import javax.rmi.PortableRemoteObject;`
      - `import java.util.Hashtable;`
    - ADD class annotation: `@ApplicationScoped`
    - REMOVE: `implements MessageListener`
    - REMOVE all JNDI-related constants and fields:
      - `JNDI_FACTORY`, `JMS_FACTORY`, `TOPIC`
      - `tcon`, `tsession`, `tsubscriber`
    - REMOVE methods: `init()`, `close()`, `getInitialContext()`
    - Change `onMessage` method signature:
      - BEFORE:
        ```java
        public void onMessage(Message rcvMessage) {
            TextMessage msg;
            {
                try {
                    System.out.println("received message inventory");
                    if (rcvMessage instanceof TextMessage) {
                        msg = (TextMessage) rcvMessage;
                        String orderStr = msg.getBody(String.class);
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
                } catch (JMSException jmse) {
                    System.err.println("An exception occurred: " + jmse.getMessage());
                }
            }
        }
        ```
      - AFTER:
        ```java
        @Incoming("order-queue")
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
        ```
- Why: MDBs and JNDI not supported in Quarkus; both MDBs can listen to the same channel
- Depends on: Step 2 (application.properties messaging config)
- Verify: No `javax.jms`, `javax.naming`, or JNDI code remains

### PHASE 5: Lifecycle

### Step 16: COMPLEX — Convert StartupListener from WebLogic ApplicationLifecycleListener to Quarkus lifecycle events
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `import weblogic.application.ApplicationLifecycleEvent;`
      - `import weblogic.application.ApplicationLifecycleListener;`
    - ADD imports:
      - `import jakarta.enterprise.context.ApplicationScoped;`
      - `import jakarta.enterprise.event.Observes;`
      - `import io.quarkus.runtime.StartupEvent;`
      - `import io.quarkus.runtime.ShutdownEvent;`
      - `import jakarta.inject.Inject;`
    - ADD class annotation: `@ApplicationScoped`
    - REMOVE: `extends ApplicationLifecycleListener`
    - Replace method signatures:
      - BEFORE:
        ```java
        @Override
        public void postStart(ApplicationLifecycleEvent evt) {
            log.info("AppListener(postStart)");
        }
        
        @Override
        public void preStop(ApplicationLifecycleEvent evt) {
            log.info("AppListener(preStop)");
        }
        ```
      - AFTER:
        ```java
        void onStart(@Observes StartupEvent event) {
            log.info("AppListener(postStart)");
        }
        
        void onStop(@Observes ShutdownEvent event) {
            log.info("AppListener(preStop)");
        }
        ```
    - Replace `import java.util.logging.Logger;` → `import org.jboss.logging.Logger;`
    - Change Logger type: `Logger log;` (Quarkus Logger is different API)
- Why: WebLogic lifecycle listeners not supported; use Quarkus lifecycle events
- Depends on: Step 1
- Verify: No `weblogic.*` imports remain

### PHASE 6: Namespace Migration (javax → jakarta)

### Step 17: Update JPA entity imports in CatalogItemEntity
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 18: Update JPA entity imports in InventoryEntity
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 19: Update JPA entity imports in Order
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 20: Update JPA entity imports in OrderItem
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.persistence` imports remain

### Step 21: Update Resources.java to remove @Produces EntityManager
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - OPTION 1 (recommended): Delete entire file - not needed in Quarkus
    - OPTION 2 (if kept): 
      - Replace `import javax.enterprise.context.Dependent;` → `import jakarta.enterprise.context.Dependent;`
      - Replace `import javax.enterprise.inject.Produces;` → `import jakarta.enterprise.inject.Produces;`
      - Replace `import javax.persistence.EntityManager;` → `import jakarta.persistence.EntityManager;`
      - Replace `import javax.persistence.PersistenceContext;` → `import jakarta.inject.Inject;`
      - Replace `@PersistenceContext private EntityManager em;` → `@Inject EntityManager em;`
    - NOTE: In Quarkus, EntityManager can be injected directly without a producer; this file may not be needed
- Why: @PersistenceContext → @Inject in Quarkus; producer may be redundant
- Depends on: Step 1
- Verify: If kept, no `javax.*` imports remain; if deleted, services still inject EntityManager directly

### Step 22: Update JAX-RS imports in RestApplication
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ws.rs.ApplicationPath;` → `import jakarta.ws.rs.ApplicationPath;`
    - Replace `import javax.ws.rs.core.Application;` → `import jakarta.ws.rs.core.Application;`
    - NOTE: In Quarkus, JAX-RS activation is automatic; this class may become optional
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.ws.rs` imports remain

### Step 23: Update JAX-RS imports in CartEndpoint
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `import javax.enterprise.context.SessionScoped;` → `import jakarta.enterprise.context.SessionScoped;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace all `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### Step 24: Update JAX-RS imports in OrderEndpoint
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace all `javax.ws.rs.*` → `jakarta.ws.rs.*`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### Step 25: Update JAX-RS imports in ProductEndpoint
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace all `javax.ws.rs.*` → `jakarta.ws.rs.*`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### Step 26: Update CDI imports in Producers utility
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Replace `import javax.enterprise.inject.Produces;` → `import jakarta.enterprise.inject.Produces;`
    - Replace `import javax.enterprise.inject.spi.InjectionPoint;` → `import jakarta.enterprise.inject.spi.InjectionPoint;`
    - Replace `java.util.logging.Logger` → `org.jboss.logging.Logger`
    - Update method signature to return `org.jboss.logging.Logger`
    - Update method body to use `Logger.getLogger(...)` (JBoss Logging API)
- Why: Jakarta EE namespace change; Quarkus uses JBoss Logging
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### Step 27: Update DataBaseMigrationStartup utility
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - Replace all `javax.annotation.*` → `jakarta.annotation.*`
    - Replace all `javax.inject.*` → `jakarta.inject.*`
    - If using `@Singleton`, replace with `@ApplicationScoped`
- Why: Jakarta EE namespace change
- Depends on: Step 1
- Verify: No `javax.*` imports remain

### PHASE 7: Cleanup

### Step 28: Delete WebLogic ApplicationLifecycleListener stub
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Remove file entirely
- Why: WebLogic-specific stub no longer needed
- Depends on: Step 16 (StartupListener migrated)
- Verify: File no longer exists

### Step 29: Delete WebLogic ApplicationLifecycleEvent stub
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Remove file entirely
- Why: WebLogic-specific stub no longer needed
- Depends on: Step 16
- Verify: File no longer exists

### Step 30: Delete WebLogic NonCatalogLogger stub
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Remove file entirely
- Why: WebLogic-specific stub no longer needed; use Quarkus logging
- Depends on: Step 1
- Verify: File no longer exists

### Step 31: Delete WEB-INF directory
- File: src/main/webapp/WEB-INF
- Action: DELETE
- What to do: Remove entire directory (already deleted web.xml and beans.xml)
- Why: JAR packaging doesn't use WEB-INF
- Depends on: Step 4, Step 5
- Verify: Directory no longer exists

### Step 32: Verify no javax.* Java EE imports remain in codebase
- File: (entire codebase)
- Action: VERIFY
- What to do:
    - Run: `grep -r "import javax\\.ejb\\." src/main/java/com/redhat/coolstore/ || echo "OK: No javax.ejb imports"`
    - Run: `grep -r "import javax\\.jms\\." src/main/java/com/redhat/coolstore/ || echo "OK: No javax.jms imports"`
    - Run: `grep -r "import javax\\.naming\\." src/main/java/com/redhat/coolstore/ || echo "OK: No javax.naming imports"`
    - Run: `grep -r "import javax\\.annotation\\.Resource" src/main/java/com/redhat/coolstore/ || echo "OK: No @Resource for JMS"`
- Why: Ensure migration completeness
- Depends on: All previous steps
- Verify: No javax EE API imports remain (except @PostConstruct/@PreDestroy if still using jakarta equivalents)

## Verification

### Build Command
```bash
mvn clean compile
```

### Expected Outcome
- Build succeeds with no compilation errors
- All source files compile against Quarkus 3 APIs
- No javax.* Java EE API references remain (all migrated to jakarta.*)
- No EJB, JMS, or JNDI API usage remains

### Post-Migration Test (after verification stage)
```bash
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

### Additional Checks
- `grep -r "@Stateless\|@Stateful\|@MessageDriven" src/main/java/com/redhat/coolstore/` returns nothing
- `grep -r "javax\.ejb\|javax\.jms\|javax\.naming" src/main/java/com/redhat/coolstore/` returns nothing
- `ls src/main/resources/META-INF/persistence.xml` returns "No such file"
- `ls src/main/webapp/WEB-INF` returns "No such file or directory"

## Notes

### Complex Transformations
1. **Message-Driven Beans (MDBs)**: Two MDB classes required complete restructuring from JMS MessageListener pattern to SmallRye Reactive Messaging @Incoming pattern. Both MDBs listen to the same "order-queue" channel using different channel names in application.properties.

2. **JNDI Lookups**: Two files contained JNDI lookups:
   - ShoppingCartService: Used InitialContext to lookup remote ShippingService EJB
   - InventoryNotificationMDB: Used WebLogic JNDI to initialize JMS Topic connection
   Both replaced with direct CDI @Inject

3. **Remote EJB**: ShippingService was marked @Remote @Stateless. Remote EJBs are not supported in Quarkus (only local CDI beans). Interface preserved for clarity but no longer a remote interface.

4. **WebLogic Lifecycle**: StartupListener extended WebLogic ApplicationLifecycleListener. Converted to CDI bean with @Observes StartupEvent/ShutdownEvent.

5. **@Produces EntityManager**: Resources.java used @PersistenceContext + @Produces pattern. In Quarkus, EntityManager can be injected directly with @Inject. File can be deleted or simplified.

### Reactive Messaging Configuration
The application.properties contains placeholder AMQP configuration. Actual broker connection details (host, port, credentials) will need to be configured at deployment time:
```properties
# Add these when deploying with real AMQP broker (ActiveMQ Artemis, RabbitMQ, etc.):
# amqp-host=localhost
# amqp-port=5672
# amqp-username=admin
# amqp-password=admin
```

### Transactional Boundaries
Methods that persist/merge entities in OrderService and CatalogService must be annotated @Transactional. Quarkus does not provide automatic transaction management like Java EE containers. Review all EntityManager operations and add @Transactional where needed.

### Logging
Quarkus uses JBoss Logging (org.jboss.logging.Logger) instead of java.util.logging.Logger. The Producers utility and any Logger injections need to be updated accordingly.

### Stateful Session Beans
ShoppingCartService was marked @Stateful, which provided per-session state isolation. Converting to @ApplicationScoped makes it a singleton. Session state management will need to be handled differently (e.g., session-scoped CDI beans, HTTP session attributes, or external session store).

### Build Finalization
After all steps are complete, the final build structure will be:
- Artifact: target/monolith-1.0.0-SNAPSHOT.jar (not ROOT.war)
- Runnable JAR: target/quarkus-app/quarkus-run.jar
- No WAR overlay, no WEB-INF structure
