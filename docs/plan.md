# Migration Plan

## Goal
Migrate a Java EE 7 monolithic application from JBoss EAP 7.4 to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4) → Quarkus 3

## Scope
- Files affected: 34
- Estimated complexity: High
- Hardest areas: JMS messaging to SmallRye Reactive Messaging conversion, EJB to CDI transformation, JNDI removal

## Key Decisions Applied

1. **JMS to SmallRye Reactive Messaging**: The application uses JMS Topics for order processing with two MDB consumers (OrderServiceMDB and InventoryNotificationMDB) and one producer (ShoppingCartOrderProcessor). Decision: Convert to SmallRye Reactive Messaging with in-memory channels for the migration. For production, these channels can be backed by Apache Kafka or other messaging systems. Rationale: In-memory channels are the simplest migration path and maintain the same message flow patterns. The analysis indicates JMS is not supported in Quarkus.

2. **EJB Scope Mapping**: Map @Stateless EJBs to @ApplicationScoped CDI beans and @Stateful EJB (ShoppingCartService) to a custom session-scoped bean. Rationale: Application-scoped beans provide singleton behavior similar to stateless EJBs, while ShoppingCartService needs user session state management which will be handled via custom scope.

3. **Persistence configuration**: Move all persistence.xml configuration to application.properties as recommended by Konveyor analysis. Rationale: Quarkus prefers properties-based configuration.

4. **Packaging change**: Change from WAR to JAR packaging. Rationale: Quarkus applications are packaged as JAR files, not WAR files.

5. **Hibernate sequence generation**: Update entity ID generation strategy to use explicit sequence names per entity to match Hibernate 6 behavior. Rationale: Konveyor analysis flags that Hibernate 6 (used by Quarkus) changed default sequence naming from hibernate_sequence to <entity>_seq per entity.

## Approach

**Phase 1: Build Configuration** - Convert pom.xml from Java EE to Quarkus dependencies, add Quarkus BOM and Maven plugin, change packaging from WAR to JAR.

**Phase 2: Configuration Files** - Migrate persistence.xml to application.properties, update beans.xml namespaces, remove web.xml deployment descriptor.

**Phase 3: Entity Models** - Update entity ID generation strategies to use explicit sequence names for Hibernate 6 compatibility.

**Phase 4: Persistence Layer** - Convert @PersistenceContext to @Inject for EntityManager injection, remove @Produces from producer methods.

**Phase 5: Service Layer - Simple Services** - Convert @Stateless EJBs to @ApplicationScoped CDI beans, add @Transactional annotations where needed for simple service classes without JMS dependencies.

**Phase 6: Messaging Layer** - Replace JMS with SmallRye Reactive Messaging by converting the producer (ShoppingCartOrderProcessor) and consumers (OrderServiceMDB, InventoryNotificationMDB), remove JNDI lookups.

**Phase 7: Service Layer - Stateful Service** - Convert ShoppingCartService from @Stateful EJB to session-scoped CDI bean, remove JNDI lookups.

**Phase 8: REST Layer** - Simplify JAX-RS activation by removing RestApplication class body (keep only for path mapping), configure root path in properties.

**Phase 9: Utilities** - Update producer classes and startup listeners for Quarkus lifecycle and CDI patterns.

**Phase 10: Cleanup** - Remove legacy Java EE deployment descriptors and WebLogic-specific files.

## Steps

### Step 1: Update Maven packaging type
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>` on line 9
- Why: Quarkus applications are packaged as JAR files, not WAR files
- Depends on: none
- Verify: `grep '<packaging>jar</packaging>' pom.xml` returns a match

### Step 2: Replace Java EE dependencies with Quarkus BOM
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: 
  - Remove all existing dependencies (lines 17-54)
  - Add Quarkus BOM in dependencyManagement section
  - Add Quarkus dependencies: quarkus-resteasy-reactive-jackson, quarkus-hibernate-orm-panache, quarkus-jdbc-postgresql, quarkus-smallrye-reactive-messaging, quarkus-flyway, quarkus-arc
  - Keep the system-scoped audit-logging-library dependency
- Why: Quarkus requires its own BOM and dependency set; Java EE dependencies (javaee-web-api, javaee-api, jboss-jms-api_2.0_spec, jboss-rmi-api_1.0_spec) are not compatible with Quarkus
- Depends on: Step 1
- Verify: `grep 'quarkus-bom' pom.xml` and `grep 'io.quarkus' pom.xml` return matches

### Step 3: Add Quarkus Maven plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: 
  - Add quarkus.platform.version property (3.15.1 or latest 3.x)
  - Replace maven-compiler-plugin and maven-war-plugin with quarkus-maven-plugin
  - Configure quarkus-maven-plugin with goals: build, generate-code, generate-code-tests
- Why: Quarkus requires its Maven plugin for building and packaging
- Depends on: Step 2
- Verify: `grep 'quarkus-maven-plugin' pom.xml` returns a match

### Step 4: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with Quarkus configuration:
  ```properties
  # Datasource configuration
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
  
  # Hibernate configuration
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.log.format-sql=true
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  
  # REST configuration
  quarkus.resteasy-reactive.path=/services
  
  # SmallRye Reactive Messaging in-memory channels
  mp.messaging.outgoing.orders.connector=smallrye-in-memory
  mp.messaging.incoming.orders-inventory.connector=smallrye-in-memory
  mp.messaging.incoming.orders-inventory.broadcast=true
  mp.messaging.incoming.orders-service.connector=smallrye-in-memory
  mp.messaging.incoming.orders-service.broadcast=true
  ```
- Why: Quarkus uses application.properties for configuration instead of XML files; consolidates datasource, Hibernate, and messaging configuration
- Depends on: Step 3
- Verify: File exists with required properties

### Step 5: Update persistence.xml namespaces to Jakarta
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do:
  - Line 2: Change version from "2.1" to "3.0"
  - Line 3: Replace `http://xmlns.jcp.org/xml/ns/persistence` with `https://jakarta.ee/xml/ns/persistence`
  - Line 5: Replace `http://xmlns.jcp.org/xml/ns/persistence` with `https://jakarta.ee/xml/ns/persistence`
  - Line 6: Replace `persistence_2_1.xsd` with `persistence_3_0.xsd`
  - Line 8: Change datasource JNDI name from `java:jboss/datasources/CoolstoreDS` to `java:app/datasources/CoolstoreDS` or remove jta-data-source element (handled by application.properties)
  - Lines 10-15: Remove all property elements (moved to application.properties)
- Why: Jakarta EE (used by Quarkus) uses updated namespaces; persistence properties moved to application.properties as recommended
- Depends on: Step 4
- Verify: `grep 'jakarta.ee' src/main/resources/META-INF/persistence.xml` returns matches

### Step 6: Update beans.xml namespaces to Jakarta
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do:
  - Line 18: Replace `http://xmlns.jcp.org/xml/ns/javaee` with `https://jakarta.ee/xml/ns/jakartaee`
  - Line 20: Replace `http://xmlns.jcp.org/xml/ns/javaee` with `https://jakarta.ee/xml/ns/jakartaee`
  - Line 21: Replace `http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd` with `https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd`
  - Move file from src/main/webapp/WEB-INF/beans.xml to src/main/resources/META-INF/beans.xml
- Why: Jakarta EE namespace update required; Quarkus JAR packaging expects beans.xml in META-INF, not WEB-INF
- Depends on: Step 5
- Verify: `grep 'jakarta.ee' src/main/resources/META-INF/beans.xml` returns matches and old location does not exist

### Step 7: Update Order entity ID generation
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: On line 24, change `@GeneratedValue(strategy = GenerationType.AUTO)` to `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq") @SequenceGenerator(name = "order_seq", sequenceName = "order_seq")`
- Why: Hibernate 6 (used by Quarkus) changed from single hibernate_sequence to per-entity sequences; explicit configuration prevents migration issues
- Depends on: Step 6
- Verify: `grep 'order_seq' src/main/java/com/redhat/coolstore/model/Order.java` returns matches

### Step 8: Update OrderItem entity ID generation
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: On line 18, change `@GeneratedValue(strategy = GenerationType.AUTO)` to `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orderitem_seq") @SequenceGenerator(name = "orderitem_seq", sequenceName = "orderitem_seq")`
- Why: Hibernate 6 requires explicit sequence names per entity
- Depends on: Step 6
- Verify: `grep 'orderitem_seq' src/main/java/com/redhat/coolstore/model/OrderItem.java` returns matches

### Step 9: Update CatalogItemEntity imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java` returns matches

### Step 10: Update InventoryEntity imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/InventoryEntity.java` returns matches

### Step 11: Update Order imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 7
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/Order.java` returns matches

### Step 12: Update OrderItem imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 8
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/OrderItem.java` returns matches

### Step 13: Update Product imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/Product.java` returns matches

### Step 14: Update Promotion imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/Promotion.java` returns matches

### Step 15: Update ShoppingCart imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` and `javax.xml.bind.annotation.*` imports with `jakarta.persistence.*` and `jakarta.xml.bind.annotation.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/ShoppingCart.java` returns matches

### Step 16: Update ShoppingCartItem imports to Jakarta
- Phase: Entity Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java` returns matches

### Step 17: COMPLEX - Convert Resources persistence producer
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - BEFORE: Uses @PersistenceContext for EntityManager injection with @Produces method
  - AFTER: Direct @Inject for EntityManager (Quarkus handles injection automatically)
  - Specific changes:
    1. Remove: `import javax.enterprise.inject.Produces;` and `import javax.persistence.PersistenceContext;`
    2. Add: `import jakarta.inject.Inject;` and `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@PersistenceContext private EntityManager em;` with `@Inject EntityManager em;`
    4. Remove: `@Produces` annotation from getEntityManager method (line 13)
    5. Update: Change class annotation from `@Dependent` to `@ApplicationScoped`
    6. Update: All javax.* imports to jakarta.*
- Why: Quarkus manages EntityManager injection via CDI directly; @PersistenceContext is not used and @Produces is not needed for EntityManager
- Depends on: Step 16
- Verify: `grep '@Inject' src/main/java/com/redhat/coolstore/persistence/Resources.java` returns match and `grep '@PersistenceContext' src/main/java/com/redhat/coolstore/persistence/Resources.java` returns no match

### Step 18: Convert CatalogService from EJB to CDI
- Phase: Service Layer - Simple Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Replace `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
  - Replace `@Stateless` annotation with `@ApplicationScoped`
  - Add `@Transactional` annotation to the class level
  - Update all javax.* imports to jakarta.*
- Why: Quarkus does not support EJBs; @ApplicationScoped provides singleton behavior; @Transactional replaces automatic EJB transaction management
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java` and `grep '@Transactional' src/main/java/com/redhat/coolstore/service/CatalogService.java` return matches

### Step 19: Convert OrderService from EJB to CDI
- Phase: Service Layer - Simple Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Replace `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
  - Replace `@Stateless` annotation with `@ApplicationScoped`
  - Add `@Transactional` annotation to the class level
  - Update all javax.* imports to jakarta.*
- Why: Quarkus does not support EJBs; transactional annotation required for persist operations
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/OrderService.java` and `grep '@Transactional' src/main/java/com/redhat/coolstore/service/OrderService.java` return matches

### Step 20: Convert ProductService from EJB to CDI
- Phase: Service Layer - Simple Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Replace `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
  - Replace `@Stateless` annotation with `@ApplicationScoped`
  - Add `@Transactional` annotation to the class level
  - Update all javax.* imports to jakarta.*
- Why: Quarkus does not support EJBs
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ProductService.java` returns a match

### Step 21: Convert PromoService from EJB to CDI
- Phase: Service Layer - Simple Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Replace `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;`
  - Replace `@Stateless` annotation with `@ApplicationScoped`
  - Update all javax.* imports to jakarta.*
- Why: Quarkus does not support EJBs
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/PromoService.java` returns a match

### Step 22: Convert ShippingService from EJB to CDI
- Phase: Service Layer - Simple Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - Replace `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
  - Replace `@Stateless` annotation with `@ApplicationScoped`
  - Add `@Transactional` annotation to the class level
  - Update all javax.* imports to jakarta.*
- Why: Quarkus does not support EJBs
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ShippingService.java` returns a match

### Step 23: COMPLEX - Convert ShoppingCartOrderProcessor JMS producer to SmallRye Messaging
- Phase: Messaging Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: Uses JMS Topic with @Resource lookup and JMSContext to send messages
  - AFTER: Uses SmallRye Reactive Messaging Emitter to send messages to channel
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`, `import javax.annotation.Resource;`, `import javax.jms.JMSContext;`, `import javax.jms.Topic;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Channel;`, `import org.eclipse.microprofile.reactive.messaging.Emitter;`, `import jakarta.inject.Inject;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: `@Inject private transient JMSContext context;` and `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;` with `@Inject @Channel("orders") Emitter<String> ordersEmitter;`
    5. Replace: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));` with `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
    6. Update: All javax.* imports to jakarta.*
- Why: JMS is not supported in Quarkus; SmallRye Reactive Messaging provides modern event-driven patterns with Emitter replacing JMS producer
- Depends on: Step 4, Step 22
- Verify: `grep 'Emitter' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java` returns matches and `grep 'JMS' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java` returns no matches

### Step 24: COMPLEX - Convert OrderServiceMDB from MDB to SmallRye Messaging consumer
- Phase: Messaging Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Message-Driven Bean with @MessageDriven annotation consuming from JMS Topic
  - AFTER: CDI bean with @Incoming method consuming from SmallRye channel
  - Specific changes:
    1. Remove: `import javax.ejb.ActivationConfigProperty;`, `import javax.ejb.MessageDriven;`, `import javax.jms.JMSException;`, `import javax.jms.Message;`, `import javax.jms.MessageListener;`, `import javax.jms.TextMessage;`, `implements MessageListener`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.inject.Inject;`, `import jakarta.transaction.Transactional;`
    3. Remove: `@MessageDriven` annotation and all activation config properties
    4. Add: `@ApplicationScoped` annotation to class
    5. Replace: `public void onMessage(Message rcvMessage)` method signature with `@Incoming("orders-service") @Transactional public void onMessage(String orderStr)`
    6. Remove: try-catch JMSException handling and TextMessage casting - method receives String directly
    7. Simplify: Direct use of orderStr parameter instead of `msg.getBody(String.class)`
    8. Update: All javax.* imports to jakarta.*
- Why: Quarkus does not support Message-Driven Beans; SmallRye Reactive Messaging @Incoming provides reactive message consumption
- Depends on: Step 4, Step 19
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` returns match and `grep 'MessageDriven' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` returns no match

### Step 25: COMPLEX - Convert InventoryNotificationMDB from MDB to SmallRye Messaging consumer
- Phase: Messaging Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: MessageListener with manual JNDI lookups and JMS Topic subscription using WebLogic InitialContext
  - AFTER: CDI bean with @Incoming method consuming from SmallRye channel
  - Specific changes:
    1. Remove: All JMS imports (`javax.jms.*`), JNDI imports (`javax.naming.*`), RMI import (`javax.rmi.PortableRemoteObject`), `implements MessageListener`
    2. Remove: All private fields for JNDI_FACTORY, JMS_FACTORY, TOPIC, tcon, tsession, tsubscriber
    3. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.transaction.Transactional;`
    4. Add: `@ApplicationScoped` annotation to class
    5. Replace: `public void onMessage(Message rcvMessage)` with `@Incoming("orders-inventory") @Transactional public void onMessage(String orderStr)`
    6. Remove: All JMSException try-catch blocks and TextMessage casting
    7. Simplify: Direct use of orderStr parameter instead of `msg.getBody(String.class)`
    8. Remove: `init()`, `close()`, and `getInitialContext()` methods entirely (not needed with SmallRye)
    9. Update: All remaining javax.* imports to jakarta.*
- Why: Quarkus does not support JNDI or JMS; SmallRye Reactive Messaging handles subscriptions and message delivery; manual connection management not needed
- Depends on: Step 4, Step 18
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` returns match and `grep 'InitialContext\|JMS\|JNDI' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` returns no matches

### Step 26: COMPLEX - Convert ShoppingCartService from Stateful EJB to CDI bean
- Phase: Service Layer - Stateful Service
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: @Stateful EJB with JNDI lookup for ShoppingCartOrderProcessor
  - AFTER: @RequestScoped CDI bean with direct @Inject
  - Specific changes:
    1. Remove: `import javax.ejb.Stateful;`, `import javax.naming.Context;`, `import javax.naming.InitialContext;`, `import javax.naming.NamingException;`
    2. Add: `import jakarta.enterprise.context.RequestScoped;`, `import jakarta.transaction.Transactional;`
    3. Replace: `@Stateful` with `@RequestScoped`
    4. Add: `@Transactional` annotation to class level
    5. Replace: In checkout() method around line 119, remove JNDI lookup code and replace with `@Inject ShoppingCartOrderProcessor cartOrderProcessor;` as a class field
    6. Replace: `processor.process(cart);` call to use injected field instead of JNDI-looked-up variable
    7. Remove: try-catch NamingException block
    8. Update: All javax.* imports to jakarta.*
- Why: Stateful EJBs not supported in Quarkus; @RequestScoped provides per-request state; JNDI not supported, use CDI injection
- Depends on: Step 23
- Verify: `grep '@RequestScoped' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` and `grep '@Inject.*ShoppingCartOrderProcessor' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` return matches; `grep 'InitialContext' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` returns no match

### Step 27: Simplify RestApplication JAX-RS activation
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Update `import javax.ws.rs.ApplicationPath;` to `import jakarta.ws.rs.ApplicationPath;`
  - Update `import javax.ws.rs.core.Application;` to `import jakarta.ws.rs.core.Application;`
  - Keep @ApplicationPath("/services") and class declaration (already minimal)
- Why: JAX-RS activation still works but namespace must be updated to Jakarta; the application.properties already sets the REST path, but keeping this for backward compatibility
- Depends on: Step 26
- Verify: `grep 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/RestApplication.java` returns matches

### Step 28: Update CartEndpoint imports to Jakarta
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*`, `javax.inject.*`, and other javax.* imports with `jakarta.ws.rs.*`, `jakarta.inject.*`, and corresponding jakarta.* imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java` returns matches

### Step 29: Update OrderEndpoint imports to Jakarta
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*` and `javax.inject.*` imports with `jakarta.ws.rs.*` and `jakarta.inject.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java` returns matches

### Step 30: Update ProductEndpoint imports to Jakarta
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*` and `javax.inject.*` imports with `jakarta.ws.rs.*` and `jakarta.inject.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 27
- Verify: `grep 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java` returns matches

### Step 31: Convert Producers utility class
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Replace `import javax.enterprise.inject.Produces;` with `import jakarta.enterprise.inject.Produces;`
  - Replace `import javax.enterprise.inject.spi.InjectionPoint;` with `import jakarta.enterprise.inject.spi.InjectionPoint;`
  - Remove @Produces annotation from logger producer (line 12) as per Konveyor recommendation
  - Update all javax.* imports to jakarta.*
- Why: In Quarkus, @Produces can be skipped for simple producer methods; namespace update required
- Depends on: Step 30
- Verify: `grep 'jakarta.enterprise' src/main/java/com/redhat/coolstore/utils/Producers.java` returns matches

### Step 32: Update DataBaseMigrationStartup to use Quarkus lifecycle
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - Replace `import javax.annotation.PostConstruct;` with `import io.quarkus.runtime.StartupEvent;` and `import jakarta.enterprise.event.Observes;`
  - Replace `import javax.ejb.Singleton;` and `import javax.ejb.Startup;` with `import jakarta.enterprise.context.ApplicationScoped;`
  - Remove @Singleton and @Startup annotations, add @ApplicationScoped
  - Replace `@PostConstruct public void init()` with `void onStart(@Observes StartupEvent event)`
  - Add `import jakarta.transaction.Transactional;` and `@Transactional` annotation to onStart method
  - Update all javax.* imports to jakarta.*
- Why: Quarkus uses event-driven lifecycle instead of @PostConstruct for startup; @Singleton/@Startup are EJB annotations not supported
- Depends on: Step 31
- Verify: `grep 'StartupEvent' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java` returns a match and `grep '@PostConstruct' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java` returns no match

### Step 33: Update StartupListener imports to Jakarta
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do: Replace all `javax.servlet.*` imports with `jakarta.servlet.*` imports
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 32
- Verify: `grep 'jakarta.servlet' src/main/java/com/redhat/coolstore/utils/StartupListener.java` returns matches

### Step 34: Update Transformers imports to Jakarta
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace `import javax.json.bind.Jsonb;` and `import javax.json.bind.JsonbBuilder;` with `import jakarta.json.bind.Jsonb;` and `import jakarta.json.bind.JsonbBuilder;`
- Why: Quarkus uses Jakarta EE namespace
- Depends on: Step 32
- Verify: `grep 'jakarta.json.bind' src/main/java/com/redhat/coolstore/utils/Transformers.java` returns matches

### Step 35: Delete web.xml deployment descriptor
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file if it exists - no longer needed by Quarkus
- Why: Quarkus does not use deployment descriptors; configuration is handled through annotations and application.properties
- Depends on: Step 34
- Verify: File no longer exists

### Step 36: Delete WebLogic ApplicationLifecycleEvent
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file - WebLogic-specific code not needed in Quarkus
- Why: WebLogic-specific classes are not used in Quarkus
- Depends on: Step 35
- Verify: File no longer exists

### Step 37: Delete WebLogic ApplicationLifecycleListener
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file - WebLogic-specific code not needed in Quarkus
- Why: WebLogic-specific classes are not used in Quarkus
- Depends on: Step 35
- Verify: File no longer exists

### Step 38: Delete ShippingServiceRemote interface
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this file - EJB remote interface not needed in Quarkus
- Why: Remote EJB interfaces are not supported in Quarkus; ShippingService is now a local CDI bean
- Depends on: Step 22
- Verify: File no longer exists

## Verification

- Build: `mvn clean package -DskipTests`
- Test: `mvn test` (if tests are enabled by removing maven.test.skip property)
- Blackbox: 
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start Quarkus app in dev mode: `mvn quarkus:dev`
  3. Navigate to `http://localhost:8080` (Note: Quarkus default port is 8080, not requiring separate app server)
  4. Test REST endpoints: `curl http://localhost:8080/services/products`
  5. Test shopping cart flow: Add items to cart, complete checkout
  6. Verify messaging: Check console logs for "Message recd" and "Sending order" messages
  7. Verify database: Check that orders and inventory are updated in PostgreSQL

## Notes

1. **Keycloak Integration**: The original application uses Keycloak for authentication. Quarkus supports Keycloak through the quarkus-oidc extension. If authentication is required, add `io.quarkus:quarkus-oidc` dependency and configure in application.properties. The migration plan focuses on the core application structure; authentication can be added as a follow-up step.

2. **Messaging Channels**: The migration uses in-memory SmallRye channels for simplicity. For production with clustering (like the original's JBoss EAP HA setup), configure channels to use Apache Kafka or another external messaging system by changing the connector type in application.properties and adding the appropriate Quarkus extension.

3. **Stateful to RequestScoped**: ShoppingCartService was converted from @Stateful to @RequestScoped. This works for REST API request scope, but if session state needs to persist across multiple requests, consider using Quarkus session storage or a custom session management approach.

4. **System Dependency**: The audit-logging-library system dependency may need verification. Quarkus can handle system dependencies, but ensure the library is compatible with Jakarta EE and Quarkus classloading.

5. **Flyway Version**: The original uses Flyway 4.1.2. Quarkus includes a newer version of Flyway. Database migration scripts in `src/main/resources/db/migration` should be reviewed for compatibility.

6. **Port Changes**: JBoss EAP uses port 8080, Quarkus also defaults to 8080 in dev mode. For production, Quarkus typically runs on 8080 for HTTP and 8443 for HTTPS, configured via application.properties.

7. **Hibernate Sequence Generation**: The explicit sequence configuration in Order and OrderItem entities requires corresponding database sequences to exist. Flyway migrations should include CREATE SEQUENCE statements for order_seq and orderitem_seq, or use `quarkus.hibernate-orm.database.generation=update` temporarily to auto-create sequences.

8. **Testing**: The original pom.xml has `<maven.test.skip>true</maven.test.skip>`. Tests may need updates to use Quarkus testing framework (@QuarkusTest) if re-enabled.
