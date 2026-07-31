# PLAN.md

## Goal
Migrate the coolstore monolith application from Java EE 7 (WAR on app server) to Quarkus 3 (standalone JAR).
- Reference used: javaee-to-quarkus skill (annotation-map.md, pattern-map.md, config-map.md, dependency-map.md)

## Project Summary
- Type: Maven / Java EE 7 WAR → Quarkus 3 JAR
- Files affected: 51 files (27 Java services/rest/model, 1 pom.xml, 7 config files, 2 weblogic stubs, 14 webapp static resources to move)
- Estimated complexity: High
- Hardest steps:
  1. **OrderServiceMDB** — MDB with Topic subscription needs full conversion to SmallRye Reactive Messaging
  2. **COMPLEX: InventoryNotificationMDB** — JNDI-based manual Topic subscription with WebLogic InitialContext
  3. **ShoppingCartService** — Remote EJB lookup via JNDI needs conversion to local injection
  4. **StartupListener** — WebLogic ApplicationLifecycleListener needs conversion to Quarkus lifecycle events
  5. **webapp disposition** — JAR packaging requires static resources migration strategy

## Messaging Topology (Original App)

| Producer | Message Format | Broker Address | Consumers | Notes |
|----------|----------------|----------------|-----------|-------|
| ShoppingCartOrderProcessor (JMS) | JSON (ShoppingCart) | java:/topic/orders | OrderServiceMDB, InventoryNotificationMDB | Topic fan-out: 2 subscribers |

**Quarkus Target:** Replace JMS Topic with AMQP/Kafka channel using SmallRye Reactive Messaging. Producer → `@Channel` Emitter, Consumers → `@Incoming` methods. Topic fan-out preserved via broker-side routing.

## src/main/webapp Disposition (JAR Packaging)

Quarkus supports static resources in JAR packaging via `src/main/resources/META-INF/resources/`. Strategy:
- Move `src/main/webapp/app/` → `src/main/resources/META-INF/resources/app/`
- Move `src/main/webapp/bower_components/` → `src/main/resources/META-INF/resources/bower_components/`
- Move `src/main/webapp/partials/` → `src/main/resources/META-INF/resources/partials/`
- Move `src/main/webapp/*.jsp` → `src/main/resources/META-INF/resources/` (converted to static HTML or served via servlet)
- Move `src/main/webapp/*.json` → `src/main/resources/META-INF/resources/`
- **DELETE** `src/main/webapp/WEB-INF/` — no longer needed (web.xml, beans.xml removed in App Config phase)

REST base path `/services` is preserved via `@ApplicationPath("/services")` in RestApplication.java (no changes needed).

---

## Steps

### **Phase 1: Build Config**

### Step 1: Update pom.xml — change packaging and add Quarkus BOM
- File: pom.xml
- Action: MODIFY
- What to do:
    - Change `<packaging>war</packaging>` → `<packaging>jar</packaging>`
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
    - Update Java version: `<source>1.8</source>` → `<source>17</source>`, `<target>1.8</target>` → `<target>17</target>`
    - Update maven-compiler-plugin version to `3.11.0`
- Why: Quarkus requires JAR packaging, BOM manages extension versions, Java 17 is minimum for Quarkus 3
- Depends on: none
- Verify: `<packaging>jar</packaging>` present, BOM added

### Step 2: Update pom.xml — replace Java EE dependencies with Quarkus extensions
- File: pom.xml
- Action: MODIFY
- What to do:
    - REMOVE dependencies:
      - `javax:javaee-web-api`
      - `javax:javaee-api`
      - `org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec`
      - `org.jboss.spec.javax.rmi:jboss-rmi-api_1.0_spec`
    - ADD Quarkus extensions (no version — managed by BOM):
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
          <artifactId>quarkus-smallrye-reactive-messaging-amqp</artifactId>
      </dependency>
      <dependency>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-flyway</artifactId>
      </dependency>
      ```
    - KEEP `org.flywaydb:flyway-core` dependency (Quarkus manages version)
    - KEEP `com.enterprise:audit-logging-library` (local JAR dependency)
- Why: Replace Java EE API JARs with Quarkus runtime extensions
- Depends on: Step 1
- Verify: No `javax:javaee-*` dependencies, all Quarkus extensions added

### Step 3: Update pom.xml — add Quarkus Maven plugin and remove WAR plugin
- File: pom.xml
- Action: MODIFY
- What to do:
    - REMOVE `maven-war-plugin`
    - UPDATE maven-compiler-plugin to use `<release>17</release>` instead of source/target
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
    - ADD Maven Surefire plugin:
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
- Why: Quarkus plugin manages build lifecycle; WAR plugin no longer needed
- Depends on: Step 2
- Verify: `quarkus-maven-plugin` present, `maven-war-plugin` removed

---

### **Phase 2: App Config**

### Step 4: Create application.properties with datasource config
- File: src/main/resources/application.properties
- Action: CREATE
- What to do:
    - Create application.properties with content:
      ```properties
      # Datasource
      quarkus.datasource.db-kind=h2
      quarkus.datasource.jdbc.url=jdbc:h2:mem:coolstore;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1
      quarkus.datasource.username=sa
      quarkus.datasource.password=

      # Hibernate ORM
      quarkus.hibernate-orm.database.generation=none
      quarkus.hibernate-orm.log.sql=false
      quarkus.hibernate-orm.log.format-sql=true

      # Flyway
      quarkus.flyway.migrate-at-start=true

      # Messaging - AMQP (replace with Kafka if needed)
      mp.messaging.outgoing.orders-out.connector=smallrye-amqp
      mp.messaging.outgoing.orders-out.address=orders
      mp.messaging.outgoing.orders-out.durable=true

      mp.messaging.incoming.order-queue.connector=smallrye-amqp
      mp.messaging.incoming.order-queue.address=orders
      mp.messaging.incoming.order-queue.durable=true

      mp.messaging.incoming.inventory-queue.connector=smallrye-amqp
      mp.messaging.incoming.inventory-queue.address=orders
      mp.messaging.incoming.inventory-queue.durable=true

      # HTTP
      quarkus.http.port=8080

      # Logging
      quarkus.log.console.enable=true
      quarkus.log.console.level=INFO
      ```
- Why: Replaces persistence.xml datasource and adds messaging config
- Depends on: Step 3
- Verify: File exists with datasource, Hibernate, messaging config

### Step 5: Delete persistence.xml
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Remove file (config migrated to application.properties)
- Why: Quarkus uses application.properties for persistence config
- Depends on: Step 4
- Verify: File deleted, no persistence.xml remains

### Step 6: Delete web.xml
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Remove file (distributable flag no longer needed in Quarkus)
- Why: Quarkus doesn't use web.xml; REST config is annotation-based
- Depends on: Step 4
- Verify: File deleted

### Step 7: Delete beans.xml
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Remove file (Quarkus ignores beans.xml content)
- Why: Quarkus CDI is always enabled, beans.xml is optional/ignored
- Depends on: Step 6
- Verify: File deleted

---

### **Phase 3: EJB to CDI**

### Step 8: Convert CatalogService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - Replace `import javax.ejb.Stateless;` → `import jakarta.enterprise.context.ApplicationScoped;`
    - Replace `import javax.inject.Inject;` → `import jakarta.inject.Inject;`
    - Replace `import javax.persistence.*` → `import jakarta.persistence.*`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Add `import jakarta.transaction.Transactional;`
    - Add `@Transactional` annotation to `updateInventoryItems()` method (performs `em.merge()`)
- Why: EJB @Stateless becomes CDI @ApplicationScoped; merge operations need @Transactional in Quarkus
- Depends on: Step 7
- Verify: No `javax.ejb` imports, `@ApplicationScoped` present, `@Transactional` on merge method

### Step 9: Convert OrderService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - Replace `javax.ejb.Stateless` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.persistence.*` → `jakarta.persistence.*`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Add `import jakarta.transaction.Transactional;`
    - Add `@Transactional` annotation to class (all methods perform persist/merge)
- Why: EJB @Stateless → CDI @ApplicationScoped; persistence operations require @Transactional
- Depends on: Step 8
- Verify: `@ApplicationScoped` and `@Transactional` present

### Step 10: Convert ProductService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Replace `javax.ejb.Stateless` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `@Stateless` → `@ApplicationScoped`
- Why: EJB @Stateless → CDI @ApplicationScoped
- Depends on: Step 9
- Verify: `@ApplicationScoped` present, no `javax.ejb`

### Step 11: Convert ShippingService from @Stateless to @ApplicationScoped
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - Replace `javax.ejb.Stateless` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `@Stateless` → `@ApplicationScoped`
- Why: EJB @Stateless → CDI @ApplicationScoped
- Depends on: Step 10
- Verify: `@ApplicationScoped` present

### Step 12: COMPLEX — Convert ShoppingCartService from @Stateful to @ApplicationScoped and remove Remote EJB lookup
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - Replace `javax.ejb.Stateful` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.naming.*` imports with `jakarta.inject.Inject`
    - Replace `@Stateful` → `@ApplicationScoped`
    - REMOVE `lookupShippingServiceRemote()` method entirely
    - REMOVE `import java.util.Hashtable;`
    - REMOVE `import javax.naming.Context;`
    - REMOVE `import javax.naming.InitialContext;`
    - REMOVE `import javax.naming.NamingException;`
    - ADD `@Inject ShippingService shippingService;` field
    - Replace all calls to `lookupShippingServiceRemote().calculateShipping(sc)` → `shippingService.calculateShipping(sc)`
    - Replace all calls to `lookupShippingServiceRemote().calculateShippingInsurance(sc)` → `shippingService.calculateShippingInsurance(sc)`
    - NOTE: ShoppingCartService will now share state across users — this is acceptable for demo/migration, but production would need @SessionScoped + session management
- Why: @Stateful EJB → CDI bean; Remote EJB lookup replaced with local injection; JNDI not supported in Quarkus
- Depends on: Step 11
- Verify: No JNDI imports, `@Inject ShippingService` field present, `lookupShippingServiceRemote()` removed

### Step 13: Convert ShoppingCartOrderProcessor from @Stateless to @ApplicationScoped (messaging conversion in Phase 4)
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - Replace `javax.ejb.Stateless` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `@Stateless` → `@ApplicationScoped`
    - Keep `javax.jms.*` imports and `@Resource` for now (will be replaced in Phase 4 — Messaging)
- Why: EJB @Stateless → CDI @ApplicationScoped; messaging conversion comes later
- Depends on: Step 12
- Verify: `@ApplicationScoped` present; JMS code unchanged

### Step 14: Update PromoService imports
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.enterprise.context.ApplicationScoped` → `jakarta.enterprise.context.ApplicationScoped` (if present)
- Why: javax → jakarta namespace migration
- Depends on: Step 13
- Verify: All `jakarta.*` imports

### Step 15: Update model entity imports
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: javax → jakarta namespace for JPA
- Depends on: Step 14
- Verify: All `jakarta.persistence` imports

### Step 16: Update model entity imports
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: javax → jakarta namespace for JPA
- Depends on: Step 15
- Verify: All `jakarta.persistence` imports

### Step 17: Update model entity imports
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: javax → jakarta namespace for JPA
- Depends on: Step 16
- Verify: All `jakarta.persistence` imports

### Step 18: Update model entity imports
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
    - Replace all `javax.persistence.*` → `jakarta.persistence.*`
- Why: javax → jakarta namespace for JPA
- Depends on: Step 17
- Verify: All `jakarta.persistence` imports

### Step 19: Update Resources producer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
    - Replace `javax.persistence.*` → `jakarta.persistence.*`
    - REMOVE `@Produces` annotation from `EntityManager` producer method (replace with `@Inject`)
    - CHANGE method from producer to injection:
      - BEFORE: `@Produces @PersistenceContext EntityManager em;`
      - AFTER: Just use `@Inject EntityManager` in consuming classes (already done in services)
    - This file may become empty — if so, delete it in cleanup phase
- Why: Quarkus provides EntityManager via CDI injection, not @Produces; @PersistenceContext → @Inject
- Depends on: Step 18
- Verify: `@PersistenceContext` removed, consider file for deletion

### Step 20: Update REST endpoint imports
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.context.SessionScoped` → `jakarta.enterprise.context.SessionScoped`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: javax → jakarta namespace for CDI and JAX-RS
- Depends on: Step 19
- Verify: All `jakarta.*` imports

### Step 21: Update REST endpoint imports
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: javax → jakarta namespace
- Depends on: Step 20
- Verify: All `jakarta.*` imports

### Step 22: Update REST endpoint imports
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ws.rs.*` → `jakarta.ws.rs.*`
- Why: javax → jakarta namespace
- Depends on: Step 21
- Verify: All `jakarta.*` imports

### Step 23: Update REST application imports
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Replace `javax.ws.rs.ApplicationPath` → `jakarta.ws.rs.ApplicationPath`
    - Replace `javax.ws.rs.core.Application` → `jakarta.ws.rs.core.Application`
    - Keep `@ApplicationPath("/services")` unchanged
- Why: javax → jakarta namespace; preserve REST base path
- Depends on: Step 22
- Verify: `/services` path preserved, `jakarta.ws.rs` imports

### Step 24: Update utility class imports
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Replace `javax.enterprise.inject.Produces` → `jakarta.enterprise.inject.Produces`
    - Replace `javax.enterprise.inject.spi.InjectionPoint` → `jakarta.enterprise.inject.spi.InjectionPoint`
- Why: javax → jakarta namespace
- Depends on: Step 23
- Verify: All `jakarta.*` imports

---

### **Phase 4: Messaging**

### Step 25: COMPLEX — Convert OrderServiceMDB from MDB to Reactive Messaging
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `javax.ejb.*`
      - `javax.jms.*`
    - ADD imports:
      - `jakarta.enterprise.context.ApplicationScoped`
      - `jakarta.inject.Inject`
      - `org.eclipse.microprofile.reactive.messaging.Incoming`
    - REMOVE `implements MessageListener`
    - REMOVE `@MessageDriven` annotation and all `@ActivationConfigProperty` annotations
    - ADD `@ApplicationScoped` annotation
    - CHANGE method signature:
      - BEFORE: `public void onMessage(Message rcvMessage)`
      - AFTER: `@Incoming("order-queue") public void onMessage(String orderStr)`
    - REMOVE try-catch and JMS message parsing:
      - REMOVE `TextMessage msg = (TextMessage) rcvMessage;`
      - REMOVE `String orderStr = msg.getBody(String.class);`
      - Use parameter `orderStr` directly
    - Keep business logic (JSON parsing and service calls)
- Why: MDB → CDI bean with @Incoming; JMS API → SmallRye Reactive Messaging; Topic becomes AMQP channel
- Depends on: Step 24
- Verify: No `javax.ejb`, no `MessageListener`, `@Incoming("order-queue")` present

### Step 26: COMPLEX — Convert InventoryNotificationMDB from JNDI-based MDB to Reactive Messaging
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `javax.jms.*`
      - `javax.naming.*`
      - `javax.rmi.PortableRemoteObject`
      - `java.util.Hashtable`
    - ADD imports:
      - `jakarta.enterprise.context.ApplicationScoped`
      - `jakarta.inject.Inject`
      - `org.eclipse.microprofile.reactive.messaging.Incoming`
    - ADD `@ApplicationScoped` annotation
    - REMOVE `implements MessageListener`
    - REMOVE all JNDI-related constants and fields:
      - `JNDI_FACTORY`, `JMS_FACTORY`, `TOPIC`
      - `TopicConnection tcon`, `TopicSession tsession`, `TopicSubscriber tsubscriber`
    - REMOVE `init()` method (manual subscription no longer needed)
    - REMOVE `close()` method
    - REMOVE `getInitialContext()` method
    - CHANGE `onMessage` method signature:
      - BEFORE: `public void onMessage(Message rcvMessage)`
      - AFTER: `@Incoming("inventory-queue") public void onMessage(String orderStr)`
    - REMOVE JMS message parsing:
      - REMOVE `TextMessage msg = (TextMessage) rcvMessage;`
      - REMOVE `String orderStr = msg.getBody(String.class);`
      - Use parameter `orderStr` directly
    - Keep business logic (inventory checking)
- Why: Manual JNDI-based subscription → declarative @Incoming; WebLogic-specific code removed
- Depends on: Step 25
- Verify: No JNDI/JMS code, `@Incoming("inventory-queue")` present, no `init/close` methods

### Step 27: COMPLEX — Convert ShoppingCartOrderProcessor from JMS producer to Reactive Messaging Emitter
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `javax.annotation.Resource`
      - `javax.jms.JMSContext`
      - `javax.jms.Topic`
    - ADD imports:
      - `org.eclipse.microprofile.reactive.messaging.Channel`
      - `org.eclipse.microprofile.reactive.messaging.Emitter`
    - REMOVE fields:
      - `@Inject private transient JMSContext context;`
      - `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;`
    - ADD field:
      - `@Inject @Channel("orders-out") Emitter<String> ordersEmitter;`
    - CHANGE `process()` method body:
      - BEFORE: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));`
      - AFTER: `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: JMS producer → Reactive Messaging Emitter; Topic → AMQP channel
- Depends on: Step 26
- Verify: No JMS code, `@Channel("orders-out") Emitter<String>` present, `emitter.send()` used

---

### **Phase 5: Lifecycle**

### Step 28: COMPLEX — Convert StartupListener from WebLogic lifecycle listener to Quarkus lifecycle events
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - REMOVE imports:
      - `weblogic.application.ApplicationLifecycleEvent`
      - `weblogic.application.ApplicationLifecycleListener`
    - REMOVE `extends ApplicationLifecycleListener`
    - ADD imports:
      - `jakarta.enterprise.context.ApplicationScoped`
      - `jakarta.enterprise.event.Observes`
      - `io.quarkus.runtime.StartupEvent`
      - `io.quarkus.runtime.ShutdownEvent`
      - `jakarta.inject.Inject`
    - ADD `@ApplicationScoped` annotation to class
    - CHANGE method signatures:
      - BEFORE: `public void postStart(ApplicationLifecycleEvent evt)`
      - AFTER: `void onStart(@Observes StartupEvent evt)`
      - BEFORE: `public void preStop(ApplicationLifecycleEvent evt)`
      - AFTER: `void onStop(@Observes ShutdownEvent evt)`
    - Update log messages to use `log.info("Quarkus application started")` and `log.info("Quarkus application stopping")`
- Why: WebLogic-specific lifecycle → Quarkus CDI observer pattern
- Depends on: Step 27
- Verify: No `weblogic.*` imports, `@Observes StartupEvent` present

### Step 29: Update DataBaseMigrationStartup lifecycle
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - Replace `javax.annotation.PostConstruct` → `jakarta.annotation.PostConstruct`
    - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
    - Replace `javax.ejb.Singleton` → `jakarta.enterprise.context.ApplicationScoped`
    - Replace `javax.ejb.Startup` → Remove (use `@PostConstruct` for startup logic)
    - Replace `@Singleton` → `@ApplicationScoped`
    - REMOVE `@Startup` annotation
- Why: EJB Singleton → CDI ApplicationScoped; @Startup replaced by @PostConstruct for startup logic
- Depends on: Step 28
- Verify: `@ApplicationScoped` present, `@PostConstruct` present, no `@Startup`

---

### **Phase 6: Cleanup**

### Step 30: Delete WebLogic stub class
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Remove file (stub no longer needed after StartupListener conversion)
- Why: WebLogic-specific code removed
- Depends on: Step 29
- Verify: File deleted

### Step 31: Delete WebLogic stub class
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Remove file (stub no longer needed)
- Why: WebLogic-specific code removed
- Depends on: Step 30
- Verify: File deleted

### Step 32: Delete weblogic package directory
- File: src/main/java/weblogic/
- Action: DELETE
- What to do: Remove entire directory (now empty after deleting stubs)
- Why: No WebLogic code remains
- Depends on: Step 31
- Verify: Directory deleted

### Step 33: Delete ShippingServiceRemote interface
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Remove file (Remote EJB interface no longer needed after ShoppingCartService refactor)
- Why: Remote EJB removed in Step 12
- Depends on: Step 32
- Verify: File deleted

### Step 34: Clean up Resources.java or delete if empty
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY or DELETE
- What to do:
    - If file only contained EntityManager producer and is now empty → DELETE
    - If file has other producers → keep and verify no `@PersistenceContext` remains
- Why: EntityManager producer removed in Step 19
- Depends on: Step 33
- Verify: File deleted or cleaned

### Step 35: Move webapp static resources to META-INF/resources
- File: src/main/webapp/app/
- Action: MODIFY
- What to do:
    - Create directory: `src/main/resources/META-INF/resources/`
    - Move entire `src/main/webapp/app/` → `src/main/resources/META-INF/resources/app/`
- Why: Quarkus JAR packaging serves static resources from META-INF/resources
- Depends on: Step 34
- Verify: `src/main/resources/META-INF/resources/app/` exists, original deleted

### Step 36: Move webapp bower_components
- File: src/main/webapp/bower_components/
- Action: MODIFY
- What to do:
    - Move `src/main/webapp/bower_components/` → `src/main/resources/META-INF/resources/bower_components/`
- Why: Static library resources must be in META-INF/resources for JAR packaging
- Depends on: Step 35
- Verify: `src/main/resources/META-INF/resources/bower_components/` exists

### Step 37: Move webapp partials
- File: src/main/webapp/partials/
- Action: MODIFY
- What to do:
    - Move `src/main/webapp/partials/` → `src/main/resources/META-INF/resources/partials/`
- Why: HTML partials must be in META-INF/resources
- Depends on: Step 36
- Verify: `src/main/resources/META-INF/resources/partials/` exists

### Step 38: Move webapp root files
- File: src/main/webapp/*.jsp, src/main/webapp/*.json
- Action: MODIFY
- What to do:
    - Move `src/main/webapp/index.jsp` → `src/main/resources/META-INF/resources/index.html` (rename to .html)
    - Move `src/main/webapp/health.jsp` → `src/main/resources/META-INF/resources/health.html` (rename to .html)
    - Move `src/main/webapp/coolstore.json` → `src/main/resources/META-INF/resources/coolstore.json`
    - Move `src/main/webapp/keycloak.json` → `src/main/resources/META-INF/resources/keycloak.json`
    - NOTE: If JSP files contain dynamic Java code, convert to static HTML or implement via REST endpoint
- Why: JSP not supported in Quarkus; static HTML served from META-INF/resources
- Depends on: Step 37
- Verify: Files in `src/main/resources/META-INF/resources/`, JSP converted to HTML

### Step 39: Delete WEB-INF directory
- File: src/main/webapp/WEB-INF/
- Action: DELETE
- What to do: Remove entire directory (web.xml and beans.xml already deleted in Phase 2)
- Why: No longer needed in JAR packaging
- Depends on: Step 38
- Verify: Directory deleted

### Step 40: Delete src/main/webapp directory
- File: src/main/webapp/
- Action: DELETE
- What to do: Remove entire directory (all content migrated to META-INF/resources)
- Why: No longer used in Quarkus JAR packaging
- Depends on: Step 39
- Verify: Directory deleted, all static resources in META-INF/resources

### Step 41: Verify no javax.* Java EE imports remain
- File: all Java files in src/main/java/com/redhat/coolstore/
- Action: VERIFY
- What to do:
    - Run: `grep -r "javax\.ejb\|javax\.jms\|javax\.naming\|javax\.persistence\|javax\.inject\|javax\.ws\.rs\|javax\.enterprise" src/main/java/com/redhat/coolstore/ --include="*.java"`
    - If any matches found, manually replace with `jakarta.*` equivalent
- Why: All Java EE APIs must use jakarta namespace
- Depends on: Step 40
- Verify: No `javax.*` Java EE imports (only `java.util.*`, `java.io.*`, etc. allowed)

---

## Verification

After all steps are complete, the application must:

1. **Build cleanly:**
   ```bash
   mvn clean package -DskipTests
   ```
   - Exit code must be 0
   - Output JAR: `target/quarkus-app/quarkus-run.jar`

2. **Start cleanly:**
   ```bash
   java -jar target/quarkus-app/quarkus-run.jar
   ```
   - Process must start without exceptions
   - Console output shows:
     - Flyway migration runs successfully
     - Datasource initialized (H2 in-memory)
     - REST endpoints registered at `/services/*`
     - Application started in < 5 seconds
     - Listening on port 8080

3. **REST endpoints functional:**
   ```bash
   curl http://localhost:8080/services/products
   curl http://localhost:8080/services/cart/123
   ```
   - Must return JSON responses without errors

4. **Messaging operational:**
   - Requires AMQP broker (Artemis, RabbitMQ, or embedded)
   - If broker not available, application starts but messaging channels remain inactive (acceptable for migration verification)

---

## Notes

### Session Management Warning
- **Original:** `ShoppingCartService` is `@Stateful` (per-user state)
- **Migrated:** `@ApplicationScoped` (shared state across users)
- **Impact:** Shopping cart state is NOT isolated per session in the migrated version
- **Fix for production:** Use `@SessionScoped` + HTTP session management, or store cart in database/cache with user ID key

### JNDI Removal
- All JNDI lookups removed:
  - `InitialContext` usage eliminated
  - `@Resource(lookup="...")` replaced with `@Inject @Channel`
  - Remote EJB lookup replaced with local `@Inject`

### Messaging Broker
- Configuration assumes AMQP broker (default for SmallRye Reactive Messaging)
- For Kafka: Change `connector=smallrye-amqp` → `connector=smallrye-kafka` in application.properties
- Topic fan-out (1 producer → 2 consumers) works with both AMQP and Kafka

### Static Resources
- AngularJS SPA frontend moved to `META-INF/resources/`
- Quarkus serves static resources automatically from this location
- REST base path `/services` preserved (no frontend routing changes needed)

### Flyway
- Database migration runs at startup (`quarkus.flyway.migrate-at-start=true`)
- H2 in-memory database used (replace with PostgreSQL/MySQL for production)

### System JAR Dependency
- `audit-logging-library-1.0.0.jar` (local JAR) preserved
- Quarkus supports `<scope>system</scope>` dependencies
- Verify JAR is accessible at build time

### Testing
- Tests are skipped in verification (`-DskipTests`)
- Test migration is out of scope for this assessment stage
- Tests would need Quarkus test framework (`@QuarkusTest`) in a full migration

---

## Verification Results

### Summary
**All verification gates passed successfully.** The migrated application compiles, starts cleanly, and responds to REST requests.

### Gate 1: Build ✅
- **Command:** `mvn package -DskipTests`
- **Status:** SUCCESS
- **Fixes applied:**
  1. Replaced `javax.json.*` with `jakarta.json.*` in `Transformers.java` (compile missed this namespace change)
  2. Updated Flyway API from deprecated constructor pattern to builder pattern (`Flyway.configure().dataSource().load()`)
  3. Removed `Resources.java` EntityManager producer (conflicted with Quarkus's built-in EntityManager bean, causing `AmbiguousResolutionException`)

### Gate 2: Application Startup ✅
- **Command:** `timeout 60 java -jar target/quarkus-app/quarkus-run.jar`
- **Status:** Started successfully - "Listening on: http://0.0.0.0:8080"
- **Verification checks:**
  - ✅ No CDI scope errors
  - ✅ No SmallRye wiring errors (SRMSG00073 dual-direction channel)
  - ✅ No unknown-connector failures
  - ✅ No missing-sequence failures
- **Expected warnings:**
  - AMQP broker connection failures (SRMSG16215) are expected and acceptable - no broker is configured in this test environment. The application starts correctly and messaging channels are configured; they will activate when a broker becomes available.

### Gate 3: REST Endpoints ✅
- **Base path preserved:** `/services` (via `@ApplicationPath` in `RestApplication.java`)
- **Endpoints tested:**
  - `GET /services/products` → 200 OK (returns JSON product catalog)
  - `GET /services/cart/{cartId}` → 200 OK (returns cart by ID)
  - `GET /services/orders` → 200 OK (returns order list)
- **Database:** Flyway migrations executed successfully on startup, H2 in-memory database seeded with test data

### Honest Caveats (Compile Cannot See)

1. **Messaging end-to-end not tested:**
   - No AMQP/Kafka broker is running in this verification environment
   - Producer (`ShoppingCartOrderProcessor`) and consumers (`OrderServiceMDB`, `InventoryNotificationMDB`) are wired correctly (no SRMSG errors at startup)
   - Message serialization/deserialization and broker-side routing are untested
   - **Recommendation:** Integration test with Artemis or RabbitMQ in staging environment

2. **In-memory H2 database:**
   - Production should use PostgreSQL or MySQL
   - Current datasource configuration uses `jdbc:h2:mem:coolstore` (ephemeral, resets on restart)
   - Flyway migrations are tested, but production database connectivity is not

3. **Session management limitation:**
   - `ShoppingCartService` migrated from `@Stateful` (per-user state) to `@ApplicationScoped` (shared singleton)
   - Current implementation does NOT isolate shopping carts by user session
   - **Production fix required:** Use `@SessionScoped` with HTTP session management, or implement cart storage with user ID keys in database/cache

4. **No production AMQP broker configuration:**
   - `application.properties` uses default AMQP broker (`localhost:5672`)
   - Credentials, TLS, and high-availability settings are not configured
   - **Recommendation:** Externalize broker config via environment variables for staging/production

5. **Static resources served in-process:**
   - AngularJS frontend is served from `META-INF/resources/` within the Quarkus app JAR
   - Production deployments often use separate CDN or reverse proxy (nginx) for static assets
   - Current approach works but is not optimized for scale

### Migration Completeness
- ✅ Java EE 7 → Quarkus 3 namespace changes (javax → jakarta)
- ✅ EJB → CDI conversion (@Stateless/@Stateful → @ApplicationScoped)
- ✅ JMS MDB → SmallRye Reactive Messaging (@MessageDriven → @Incoming)
- ✅ JMS producers → Reactive Messaging Emitter (@Channel + Emitter<T>)
- ✅ JNDI lookups removed (InitialContext → @Inject)
- ✅ WAR → JAR packaging with embedded HTTP server
- ✅ Application server lifecycle → Quarkus lifecycle events (@Observes StartupEvent/ShutdownEvent)
- ✅ persistence.xml → application.properties
- ✅ Flyway database migrations operational
- ✅ REST endpoints functional with preserved `/services` base path
- ⚠️  Session state management requires production hardening (see caveat #3)

### Next Steps for Production Readiness
1. Add integration tests with TestContainers (AMQP broker + PostgreSQL)
2. Configure production datasource (PostgreSQL with connection pooling)
3. Fix shopping cart session isolation (use `@SessionScoped` or database-backed cart store)
4. Externalize AMQP broker configuration (credentials, TLS, host/port)
5. Add observability (metrics, health checks, distributed tracing)
6. Load test messaging subsystem (verify topic fan-out under load)
