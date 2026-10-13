# Migration Plan

## Goal
Migrate a Java EE 7 monolithic web application (CoolStore) from JBoss EAP 7.4 to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4) → Quarkus 3 with Jakarta EE

## Scope
- Files affected: 46
- Estimated complexity: High
- Hardest areas: 
  1. JMS Message-Driven Beans (2 MDBs) to Reactive Messaging
  2. Remote EJB and JNDI lookups to CDI injection
  3. Persistence configuration from XML to properties

## Key Decisions Applied

1. **JMS to Reactive Messaging**: Chose SmallRye Reactive Messaging with @Incoming/@Outgoing annotations over traditional JMS, as this is the Quarkus-recommended approach for asynchronous messaging.

2. **EntityManager injection**: Will use CDI @Inject instead of @PersistenceContext, removing the need for the Resources producer class entirely.

3. **Packaging change**: Changed from WAR to JAR packaging as Quarkus uses an embedded server model rather than deploying to an application server.

4. **Configuration approach**: Will migrate all persistence.xml settings to application.properties and delete XML configuration files (beans.xml can be deleted, web.xml is not needed).

5. **Remote EJB**: The ShippingService remote EJB will be converted to a local CDI bean with @ApplicationScoped. The JNDI lookup will be replaced with @Inject. (Note: In production, this would typically become a REST client, but the analysis suggests local conversion.)

## Approach

**Phase 1 - Build Configuration**: Transform pom.xml to use Quarkus BOM, plugins, and dependencies. Change packaging from WAR to JAR. Remove Java EE dependencies and add Quarkus equivalents.

**Phase 2 - Configuration Files**: Migrate persistence.xml to application.properties. Delete beans.xml and web.xml as they're not needed in Quarkus.

**Phase 3 - Models/Entities**: Update all JPA entity classes to use jakarta.persistence imports instead of javax.persistence. Address Hibernate sequence naming issues.

**Phase 4 - Persistence Layer**: Remove the Resources producer class (no longer needed). Update EntityManager injection points from @PersistenceContext to @Inject.

**Phase 5 - Service Layer Core**: Convert @Stateless and @Stateful EJBs to CDI beans with appropriate scopes. Add @Transactional where needed.

**Phase 6 - Messaging Layer**: Convert JMS Message-Driven Beans to Reactive Messaging with @Incoming/@Outgoing. Replace JMS Topic injection with Emitter. Remove JNDI lookups.

**Phase 7 - Remote/JNDI**: Replace Remote EJB with local CDI bean. Remove all JNDI lookups and replace with CDI injection.

**Phase 8 - REST Layer**: Update JAX-RS imports to Jakarta, simplify RestApplication (JAX-RS activation is automatic in Quarkus).

**Phase 9 - Utilities**: Update utility classes for imports and remove unnecessary @Produces annotations where scope annotations are used.

**Phase 10 - Cleanup**: Delete obsolete configuration files and deployment descriptors.

## Steps

### Step 1: Update Maven packaging
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>`
- Why: Quarkus uses JAR packaging with embedded server, not WAR for external application servers
- Depends on: none
- Verify: `grep '<packaging>jar</packaging>' pom.xml` returns a match

### Step 2: Add Quarkus BOM to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add Quarkus BOM in dependencyManagement section before dependencies section:
  ```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>3.15.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  ```
- Why: Quarkus BOM manages versions of all Quarkus extensions
- Depends on: Step 1
- Verify: `grep 'quarkus-bom' pom.xml` returns a match

### Step 3: Replace Java EE dependencies with Quarkus extensions
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Remove all existing dependencies (javax javaee-web-api, javaee-api, jboss-jms-api, jboss-rmi-api) and replace with Quarkus extensions:
  ```xml
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm-panache</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-reactive-messaging</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-reactive-messaging-kafka</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-arc</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-narayana-jta</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>com.enterprise</groupId>
    <artifactId>audit-logging-library</artifactId>
    <version>1.0.0</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/audit-logging-library-1.0.0.jar</systemPath>
  </dependency>
  ```
- Why: Quarkus uses its own extensions instead of Java EE APIs; reactive messaging replaces JMS
- Depends on: Step 2
- Verify: `grep 'quarkus-hibernate-orm-panache' pom.xml && grep 'quarkus-smallrye-reactive-messaging' pom.xml` both return matches

### Step 4: Add Quarkus Maven plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add properties and replace maven-compiler-plugin, remove maven-war-plugin, add quarkus-maven-plugin:
  ```xml
  <properties>
    <quarkus.platform.version>3.15.1</quarkus.platform.version>
    <compiler-plugin.version>3.11.0</compiler-plugin.version>
    <maven.compiler.release>17</maven.compiler.release>
    <surefire-plugin.version>3.0.0</surefire-plugin.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
  </properties>
  ```
  And in plugins section:
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
  <plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>${compiler-plugin.version}</version>
    <configuration>
      <parameters>true</parameters>
    </configuration>
  </plugin>
  <plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire-plugin.version}</version>
    <configuration>
      <systemPropertyVariables>
        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
      </systemPropertyVariables>
    </configuration>
  </plugin>
  <plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>${surefire-plugin.version}</version>
  </plugin>
  ```
- Why: Quarkus requires specific Maven plugins for building and running
- Depends on: Step 3
- Verify: `grep 'quarkus-maven-plugin' pom.xml` returns a match

### Step 5: Add Quarkus native profile
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Replace the empty profiles section with:
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
- Why: Enables Quarkus native compilation support
- Depends on: Step 4
- Verify: `grep 'quarkus.package.type' pom.xml` returns a match

### Step 6: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create application.properties with database and messaging configuration:
  ```properties
  # Datasource configuration
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  
  # Hibernate configuration
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.log.format-sql=true
  quarkus.hibernate-orm.jdbc.statement-fetch-size=10
  quarkus.hibernate-orm.jdbc.statement-batch-size=10
  
  # Reactive Messaging - Kafka configuration
  mp.messaging.incoming.orders.connector=smallrye-kafka
  mp.messaging.incoming.orders.topic=orders
  mp.messaging.incoming.orders.value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
  mp.messaging.outgoing.orders-out.connector=smallrye-kafka
  mp.messaging.outgoing.orders-out.topic=orders
  mp.messaging.outgoing.orders-out.value.serializer=org.apache.kafka.common.serialization.StringSerializer
  
  # Application configuration
  quarkus.http.port=8080
  quarkus.resteasy-reactive.path=/services
  ```
- Why: Quarkus uses application.properties for all configuration instead of XML files
- Depends on: Step 5
- Verify: File exists and contains quarkus.datasource properties

### Step 7: Migrate imports in CatalogItemEntity.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: `grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java` returns matches, no javax.persistence imports remain

### Step 8: Migrate imports in InventoryEntity.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: `grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/InventoryEntity.java` returns matches, no javax.persistence imports remain

### Step 9: Migrate imports in Order.java and add sequence name
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: 
  1. Replace all `javax.persistence` imports with `jakarta.persistence`
  2. Add explicit sequence name to @GeneratedValue: `@GeneratedValue(strategy = GenerationType.AUTO, generator = "order_seq")`
  3. Add sequence generator: `@SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", allocationSize = 1)`
- Why: Jakarta EE namespace change; Hibernate 6 changed implicit sequence naming, explicit naming prevents migration issues
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/Order.java && grep 'order_seq' src/main/java/com/redhat/coolstore/model/Order.java` both return matches

### Step 10: Migrate imports in OrderItem.java and add sequence name
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: 
  1. Replace all `javax.persistence` imports with `jakarta.persistence`
  2. Add explicit sequence name to @GeneratedValue: `@GeneratedValue(strategy = GenerationType.AUTO, generator = "orderitem_seq")`
  3. Add sequence generator: `@SequenceGenerator(name = "orderitem_seq", sequenceName = "orderitem_sequence", allocationSize = 1)`
- Why: Jakarta EE namespace change; Hibernate 6 changed implicit sequence naming
- Depends on: Step 6
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/OrderItem.java && grep 'orderitem_seq' src/main/java/com/redhat/coolstore/model/OrderItem.java` both return matches

### Step 11: Migrate imports in Product.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence` if present
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: No javax.persistence imports remain in the file

### Step 12: Migrate imports in Promotion.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace `javax.persistence.Transient` import with `jakarta.persistence.Transient` if present
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: No javax.persistence imports remain in the file

### Step 13: Migrate imports in ShoppingCart.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace `javax.xml.bind.annotation` imports with `jakarta.xml.bind.annotation` if present
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: No javax imports remain in the file (jakarta or no imports)

### Step 14: Migrate imports in ShoppingCartItem.java
- Phase: Models/Entities
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace `javax.xml.bind.annotation` imports with `jakarta.xml.bind.annotation` if present
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 6
- Verify: No javax imports remain in the file

### Step 15: COMPLEX - Convert CatalogService from @Stateless to @ApplicationScoped
- Phase: Service Layer Core
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB with `@Inject EntityManager`
  - AFTER: CDI bean with `@ApplicationScoped` and `@Transactional`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.transaction.Transactional;`, `import jakarta.inject.Inject;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
    5. Replace: `javax.persistence` imports with `jakarta.persistence`
    6. Add: `@Transactional` annotation to the `updateInventoryItems` method
- Why: Quarkus doesn't support EJBs; @ApplicationScoped CDI beans replace stateless EJBs; explicit @Transactional needed for persistence operations
- Depends on: Step 14
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/CatalogService.java` both return matches

### Step 16: COMPLEX - Convert ProductService from @Stateless to @ApplicationScoped
- Phase: Service Layer Core
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: CDI bean with `@ApplicationScoped`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
    5. Replace: `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus doesn't support EJBs; @ApplicationScoped CDI beans replace stateless EJBs
- Depends on: Step 15
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ProductService.java` returns a match, no @Stateless remains

### Step 17: COMPLEX - Convert PromoService from @Stateless to @ApplicationScoped
- Phase: Service Layer Core
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: CDI bean with `@ApplicationScoped`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: All `javax.inject` imports with `jakarta.inject`
    5. Replace: `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus doesn't support EJBs; @ApplicationScoped CDI beans replace stateless EJBs
- Depends on: Step 16
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/PromoService.java` returns a match

### Step 18: COMPLEX - Convert OrderService from @Stateless to @ApplicationScoped
- Phase: Service Layer Core
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB with `@PostConstruct` and `@PreDestroy`
  - AFTER: CDI bean with `@ApplicationScoped` and `@Transactional`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.transaction.Transactional;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Replace: `javax.annotation.PostConstruct` with `jakarta.annotation.PostConstruct`
    5. Replace: `javax.annotation.PreDestroy` with `jakarta.annotation.PreDestroy`
    6. Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
    7. Replace: `javax.persistence` imports with `jakarta.persistence`
    8. Add: `@Transactional` annotation to the `save` method
- Why: Quarkus doesn't support EJBs; explicit @Transactional needed for persist operations
- Depends on: Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/OrderService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/OrderService.java` both return matches

### Step 19: COMPLEX - Convert ShoppingCartOrderProcessor from @Stateless to @ApplicationScoped
- Phase: Service Layer Core
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` with JMS `@Resource Topic` and `JMSContext`
  - AFTER: CDI bean with `@Channel Emitter` for reactive messaging
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`, `import javax.annotation.Resource;`, `import javax.jms.JMSContext;`, `import javax.jms.Topic;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`, `import org.eclipse.microprofile.reactive.messaging.Channel;`, `import org.eclipse.microprofile.reactive.messaging.Emitter;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Remove: `@Inject private transient JMSContext context;` and `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;`
    5. Add: `@Inject @Channel("orders-out") Emitter<String> ordersEmitter;`
    6. Replace in `process` method: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));` with `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: Quarkus uses Reactive Messaging with Emitters instead of JMS Topics
- Depends on: Step 18
- Verify: `grep '@Channel' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java && grep 'Emitter' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java` both return matches

### Step 20: COMPLEX - Convert OrderServiceMDB to Reactive Messaging
- Phase: Messaging Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: `@MessageDriven` EJB with `MessageListener.onMessage(Message)`
  - AFTER: CDI bean with `@Incoming` reactive messaging method
  - Specific changes:
    1. Remove: All `javax.ejb`, `javax.jms` imports
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    3. Remove: `@MessageDriven` annotation and its properties, `implements MessageListener`
    4. Add: `@ApplicationScoped` to the class
    5. Replace: `public void onMessage(Message rcvMessage)` signature with `@Incoming("orders") public void onMessage(String orderStr)`
    6. Remove: All JMS message extraction code (TextMessage casting, msg.getBody())
    7. Simplify: Method body to directly use the `orderStr` parameter
- Why: Quarkus doesn't support MDBs; Reactive Messaging with @Incoming replaces JMS MessageListener
- Depends on: Step 19
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` returns a match, no @MessageDriven remains

### Step 21: COMPLEX - Convert InventoryNotificationMDB to Reactive Messaging
- Phase: Messaging Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Listener with JNDI InitialContext, JMS Topic setup, and MessageListener
  - AFTER: CDI bean with `@Incoming` reactive messaging method
  - Specific changes:
    1. Remove: All `javax.jms`, `javax.naming`, `javax.rmi` imports
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.inject.Inject;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    3. Remove: All JNDI constants (JNDI_FACTORY, JMS_FACTORY, TOPIC), connection fields (tcon, tsession, tsubscriber)
    4. Add: `@ApplicationScoped` to the class
    5. Remove: `implements MessageListener`
    6. Replace: `public void onMessage(Message rcvMessage)` signature with `@Incoming("orders") public void onMessage(String orderStr)`
    7. Remove: JMS message handling code and try-catch blocks
    8. Simplify: Method body to directly parse orderStr
    9. Delete: `init()`, `close()`, and `getInitialContext()` methods entirely
- Why: Quarkus doesn't support JNDI or JMS; Reactive Messaging replaces all of this infrastructure
- Depends on: Step 20
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java && grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` both return matches

### Step 22: COMPLEX - Convert ShippingService from Remote EJB to local CDI bean
- Phase: Remote/JNDI
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB implementing `ShippingServiceRemote` (remote interface)
  - AFTER: `@ApplicationScoped` CDI bean, remove remote interface
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`, `import javax.ejb.Remote;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@Stateless` with `@ApplicationScoped`
    4. Remove: `implements ShippingServiceRemote` from class declaration
- Why: Quarkus doesn't support Remote EJBs; converted to local CDI bean with direct injection
- Depends on: Step 21
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ShippingService.java` returns a match, no @Stateless or @Remote remains

### Step 23: COMPLEX - Convert ShoppingCartService from @Stateful to @SessionScoped and remove JNDI
- Phase: Remote/JNDI
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateful` EJB with JNDI lookup for remote ShippingService
  - AFTER: `@SessionScoped` CDI bean with direct @Inject
  - Specific changes:
    1. Remove: `import javax.ejb.Stateful;`, `import javax.naming.Context;`, `import javax.naming.InitialContext;`, `import javax.naming.NamingException;`, `import java.util.Hashtable;`
    2. Add: `import jakarta.enterprise.context.SessionScoped;`, `import jakarta.inject.Inject;`, `import java.io.Serializable;`
    3. Replace: `@Stateful` with `@SessionScoped`
    4. Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
    5. Add: Class must implement `Serializable`
    6. Add: `@Inject ShippingService shippingService;` field
    7. Delete: `lookupShippingServiceRemote()` method entirely
    8. Replace: All `lookupShippingServiceRemote().calculateShipping(sc)` calls with `shippingService.calculateShipping(sc)`
    9. Replace: All `lookupShippingServiceRemote().calculateShippingInsurance(sc)` calls with `shippingService.calculateShippingInsurance(sc)`
- Why: Quarkus doesn't support @Stateful EJBs or JNDI; @SessionScoped provides stateful behavior; direct injection replaces JNDI lookups
- Depends on: Step 22
- Verify: `grep '@SessionScoped' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java && grep '@Inject ShippingService' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` both return matches

### Step 24: Migrate imports in CartEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs` imports with `jakarta.ws.rs`, replace `javax.inject.Inject` with `jakarta.inject.Inject`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 23
- Verify: `grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java` returns matches, no javax imports remain

### Step 25: Migrate imports in OrderEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs` imports with `jakarta.ws.rs`, replace `javax.inject.Inject` with `jakarta.inject.Inject`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 24
- Verify: `grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java` returns matches

### Step 26: Migrate imports in ProductEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs` imports with `jakarta.ws.rs`, replace `javax.inject.Inject` with `jakarta.inject.Inject`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 25
- Verify: `grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java` returns matches

### Step 27: Simplify RestApplication.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do: 
  1. Replace `javax.ws.rs` imports with `jakarta.ws.rs`
  2. Keep `@ApplicationPath("/services")` but note it's optional in Quarkus (can be configured in application.properties)
- Why: JAX-RS activation is automatic in Quarkus; the class can be kept for explicit path configuration or removed entirely
- Depends on: Step 26
- Verify: `grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/RestApplication.java` returns matches

### Step 28: Update Producers.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  1. Replace `javax.enterprise.inject` imports with `jakarta.enterprise.inject`
  2. Add `@ApplicationScoped` or `@Dependent` to the class
  3. Note: @Produces can remain; it's valid in Quarkus for Logger production
- Why: Jakarta namespace change; class needs scope annotation
- Depends on: Step 27
- Verify: `grep 'import jakarta.enterprise' src/main/java/com/redhat/coolstore/utils/Producers.java` returns matches

### Step 29: Update Transformers.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace `javax.json` imports with `jakarta.json`
- Why: Jakarta EE uses jakarta namespace instead of javax
- Depends on: Step 28
- Verify: `grep 'import jakarta.json' src/main/java/com/redhat/coolstore/utils/Transformers.java` returns matches

### Step 30: Update DataBaseMigrationStartup.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  1. Replace `javax.annotation.PostConstruct` with `jakarta.annotation.PostConstruct`
  2. Replace `javax.ejb.Singleton` with `jakarta.enterprise.context.ApplicationScoped`
  3. Replace `javax.ejb.Startup` with `io.quarkus.runtime.Startup`
  4. Replace `javax.annotation.Resource` with `jakarta.annotation.Resource` (if used for DataSource)
  5. Or better: Replace `@Resource DataSource` with `@Inject AgroalDataSource` (Quarkus-native)
- Why: Jakarta namespace change; Quarkus uses @Startup instead of EJB @Startup
- Depends on: Step 29
- Verify: `grep 'jakarta.annotation' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java` returns matches

### Step 31: Update StartupListener.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
  1. If it uses `javax.servlet.ServletContextListener`, replace with `jakarta.servlet.ServletContextListener`
  2. Or better: Replace servlet listener pattern with Quarkus `@Observes StartupEvent` pattern:
     - Add: `import io.quarkus.runtime.StartupEvent;`, `import jakarta.enterprise.event.Observes;`
     - Replace: Listener pattern with `void onStart(@Observes StartupEvent ev)` method in an ApplicationScoped bean
- Why: Quarkus prefers event-based startup hooks over servlet listeners
- Depends on: Step 30
- Verify: File compiles and uses either jakarta.servlet or @Observes StartupEvent

### Step 32: Delete Resources.java
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete the entire file
- Why: In Quarkus, EntityManager is injected directly with @Inject; the @PersistenceContext producer pattern is not needed
- Depends on: Step 31
- Verify: File no longer exists

### Step 33: Delete persistence.xml
- Phase: Cleanup
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file - all configuration moved to application.properties
- Why: Quarkus uses application.properties for all configuration including persistence
- Depends on: Step 32
- Verify: File no longer exists

### Step 34: Delete beans.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file
- Why: Quarkus has CDI enabled by default; beans.xml is not needed and its content is ignored
- Depends on: Step 33
- Verify: File no longer exists

### Step 35: Delete web.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file
- Why: Quarkus doesn't use deployment descriptors; JAR packaging doesn't need web.xml
- Depends on: Step 34
- Verify: File no longer exists

### Step 36: Delete ShippingServiceRemote interface
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this entire file
- Why: Remote EJB interfaces are not needed in Quarkus; ShippingService is now a local CDI bean
- Depends on: Step 35
- Verify: File no longer exists

### Step 37: Delete weblogic ApplicationLifecycleEvent
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific classes are not needed in Quarkus
- Depends on: Step 36
- Verify: File no longer exists

### Step 38: Delete weblogic ApplicationLifecycleListener
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific classes are not needed in Quarkus
- Depends on: Step 37
- Verify: File no longer exists

### Step 39: Delete weblogic NonCatalogLogger
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file
- Why: WebLogic-specific classes are not needed in Quarkus
- Depends on: Step 38
- Verify: File no longer exists

### Step 40: Delete WEB-INF directory
- Phase: Cleanup
- File: src/main/webapp/WEB-INF
- Action: DELETE
- What to do: Delete the entire WEB-INF directory (now empty after previous deletions)
- Why: Not needed for JAR packaging in Quarkus
- Depends on: Step 39
- Verify: Directory no longer exists

### Step 41: Move static web resources
- Phase: Cleanup
- File: src/main/webapp/*
- Action: MODIFY
- What to do: Move all remaining content from src/main/webapp/ (static files: *.jsp, *.json, app/, bower_components/, partials/, etc.) to src/main/resources/META-INF/resources/
- Why: Quarkus serves static resources from META-INF/resources in JAR packaging
- Depends on: Step 40
- Verify: `ls src/main/resources/META-INF/resources/` shows the web content

### Step 42: Delete src/main/webapp directory
- Phase: Cleanup
- File: src/main/webapp
- Action: DELETE
- What to do: Delete the now-empty src/main/webapp directory
- Why: All content moved to META-INF/resources; webapp directory not used in JAR packaging
- Depends on: Step 41
- Verify: Directory no longer exists

### Step 43: Update db migration resources path
- Phase: Utilities
- File: src/main/resources/db/*
- Action: MODIFY
- What to do: Verify Flyway migration scripts are in src/main/resources/db/migration/ (rename db/ to db/migration/ if needed)
- Why: Quarkus Flyway expects migrations in db/migration directory
- Depends on: Step 42
- Verify: `ls src/main/resources/db/migration/` shows migration files

### Step 44: Add Flyway configuration to application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do: Add Flyway configuration:
  ```properties
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  quarkus.flyway.baseline-on-migrate=true
  ```
- Why: Configure Flyway to run migrations on application startup
- Depends on: Step 43
- Verify: `grep 'quarkus.flyway' src/main/resources/application.properties` returns matches

### Step 45: Add Quarkus build properties
- Phase: Build Configuration
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do: Add build configuration:
  ```properties
  # Quarkus build
  quarkus.package.type=uber-jar
  quarkus.application.name=coolstore-monolith
  ```
- Why: Configure Quarkus to build an uber-jar for easier deployment
- Depends on: Step 44
- Verify: `grep 'quarkus.package.type' src/main/resources/application.properties` returns a match

### Step 46: Update Keycloak integration
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do: Add Quarkus OIDC/Keycloak configuration if keycloak.json exists:
  ```properties
  # OIDC/Keycloak configuration (based on keycloak.json in original webapp)
  quarkus.oidc.auth-server-url=http://127.0.0.1:8081/realms/eap
  quarkus.oidc.client-id=<from keycloak.json>
  quarkus.oidc.credentials.secret=<from keycloak.json if present>
  quarkus.oidc.application-type=web-app
  ```
  And add dependency to pom.xml if security is needed
- Why: Quarkus uses OIDC extension for Keycloak integration instead of keycloak.json
- Depends on: Step 45
- Verify: Configuration present if security is required, or this step can be deferred to post-migration

## Verification

- Build: `mvn clean package`
- Test: `mvn test` (tests exist but are skipped in original pom.xml with maven.test.skip=true, so verify build completes)
- Run: `java -jar target/quarkus-app/quarkus-run.jar` or `mvn quarkus:dev`
- Blackbox: 
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start Kafka (for messaging): `podman run -p 9092:9092 -e KAFKA_ENABLE_KRAFT=yes -e KAFKA_CFG_PROCESS_ROLES=broker,controller -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 -e KAFKA_BROKER_ID=1 -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9093 -e ALLOW_PLAINTEXT_LISTENER=yes --name kafka-quarkus bitnami/kafka:latest`
  3. Start application: `java -jar target/quarkus-app/quarkus-run.jar`
  4. Navigate to http://127.0.0.1:8080
  5. Verify coolstore UI loads
  6. Test product catalog listing at http://127.0.0.1:8080/services/products
  7. Test shopping cart operations (add items, checkout)
  8. Verify order processing (check logs for "Message recd !" and "Received order")
  9. Verify inventory updates after order placement

## Notes

1. **Messaging infrastructure change**: The original application uses JMS Topics on JBoss (ActiveMQ). Quarkus migration uses Kafka with SmallRye Reactive Messaging. The channel name "orders" is configured in application.properties to map to a Kafka topic. For production, ensure Kafka is properly configured and the topic exists.

2. **Stateful session handling**: ShoppingCartService was @Stateful EJB (server-side session state). Migrated to @SessionScoped which requires HTTP session support. Each user maintains their cart in HTTP session. For cloud-native deployments, consider externalizing cart state to a database or cache.

3. **Remote EJB removal**: ShippingService was a Remote EJB accessed via JNDI. Converted to local @ApplicationScoped bean with direct injection. If shipping service needs to be separate in production, implement as a REST client using Quarkus REST Client extension.

4. **Audit logging library**: The system dependency on audit-logging-library-1.0.0.jar is preserved. Verify this library works with Quarkus (it's in lib/ folder). If issues arise, may need to update the library or integrate differently.

5. **Database migration**: Flyway migrations remain unchanged. Ensure all SQL scripts are compatible with PostgreSQL and the Hibernate version in Quarkus (6.x).

6. **Keycloak/Security**: The original application uses Keycloak for authentication. Step 46 provides basic OIDC configuration. May need to add `quarkus-oidc` extension to pom.xml and update the configuration based on keycloak.json values. This can be done as a separate security hardening phase.

7. **Clustering**: The original JBoss setup uses standalone-full-ha.xml for clustering. Quarkus handles clustering differently - messaging is distributed via Kafka, and session replication would need Infinispan or similar. For initial migration, focus on single-instance deployment.

8. **WebLogic compatibility code**: Three weblogic.* package classes were found and deleted. If any code references these, those references must also be removed. Check for imports and usage across the codebase.

9. **JSON-B vs JSON-P**: The Transformers class uses javax.json (JSON-P). After migration to jakarta.json, verify JSON processing works. Quarkus includes JSON-B by default; may want to migrate to JSON-B annotations for cleaner code.

10. **Build performance**: Quarkus has fast startup and live reload in dev mode. Use `mvn quarkus:dev` during development for immediate feedback. The uber-jar packaging creates a single executable JAR for deployment.
