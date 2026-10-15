# Migration Plan

## Goal
Migrate a Java EE 7 monolithic application (CoolStore) to Quarkus 3

## Source → Target
Java EE 7 / JBoss EAP 7.4 → Quarkus 3.x

## Scope
- Files affected: 38
  - 1 build manifest (pom.xml)
  - 30 Java source files
  - 2 XML configuration files (persistence.xml, beans.xml)
  - 1 web.xml descriptor
  - 3 WebLogic compatibility files (to be deleted)
  - 1 new application.properties file (to be created)
- Estimated complexity: High
- Hardest areas:
  1. JMS Message-Driven Beans (OrderServiceMDB, InventoryNotificationMDB) - require conversion to reactive messaging
  2. JNDI lookups and Remote EJBs (ShippingService, ShoppingCartService) - not supported in Quarkus
  3. @PersistenceContext producer pattern (Resources.java) - conflicts with Quarkus CDI

## Key Decisions Applied
1. **Reactive Messaging Framework**: Using SmallRye Reactive Messaging with in-memory connector for JMS migration, as the analysis indicates JMS Topics need to become Channels with Emitters. This choice allows testing without external message broker configuration.
2. **Sequence Generation Strategy**: Keeping default Hibernate 6 behavior (sequence per entity) as recommended by analysis, will document need for database schema updates.
3. **Remote EJB to REST**: Converting ShippingService remote EJB to REST endpoints as recommended by analysis, using CDI injection in ShoppingCartService instead of JNDI lookup.
4. **Stateful Session Scope**: Converting @Stateful ShoppingCartService to @ApplicationScoped with external state management pattern, as recommended for Quarkus best practices.
5. **CDI Producer Removal**: Removing @PersistenceContext/@Produces pattern for EntityManager, using direct @Inject as Quarkus manages EntityManager as a CDI bean automatically.
6. **Datasource Configuration**: Moving from JNDI datasource (java:jboss/datasources/CoolstoreDS) to Quarkus datasource properties (quarkus.datasource.*).

## Approach

**Phase 1: Build & Configuration** - Modernize the Maven POM to use Quarkus BOM, plugins, and dependencies. Update packaging from WAR to JAR. Configure Quarkus-specific build profiles.

**Phase 2: Configuration Files** - Migrate persistence.xml to application.properties, update namespace declarations for Jakarta EE, and remove obsolete descriptors (beans.xml, web.xml).

**Phase 3: Dependency Injection & Persistence** - Replace @PersistenceContext with @Inject for EntityManager, remove the CDI producer pattern, update javax to jakarta imports.

**Phase 4: Model Layer** - Update all entity classes with jakarta.persistence imports, adjust @GeneratedValue strategy for Hibernate 6 compatibility.

**Phase 5: Service Layer - Core** - Convert @Stateless EJBs to @ApplicationScoped CDI beans, add @Transactional annotations, update javax to jakarta imports.

**Phase 6: Service Layer - JMS Migration** - Convert JMS Message-Driven Beans to reactive messaging with @Incoming/@Channel, replace Topic injection with Emitter, remove JNDI lookups.

**Phase 7: Service Layer - Remote EJB** - Convert remote EJB to REST service, replace JNDI lookup with CDI injection, remove @Remote annotation.

**Phase 8: REST Layer** - Update JAX-RS imports to jakarta.ws.rs, simplify RestApplication (remove unnecessary methods).

**Phase 9: Utilities** - Update utility classes with jakarta imports, adjust WebLogic-specific code if needed.

**Phase 10: Cleanup** - Delete WebLogic compatibility files, remove obsolete XML descriptors.

## Steps

### Step 1: Update pom.xml packaging
- Phase: Build & Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>` (line 9)
- Why: Quarkus applications are packaged as JAR files, not WAR files
- Depends on: none
- Verify: `grep '<packaging>jar</packaging>' pom.xml`

### Step 2: Add Quarkus BOM to pom.xml
- Phase: Build & Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add Quarkus platform properties and BOM to dependencyManagement section:
  ```xml
  <properties>
    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
    <quarkus.platform.version>3.1.0.Final</quarkus.platform.version>
    <compiler-plugin.version>3.10.1</compiler-plugin.version>
    <maven.compiler.release>11</maven.compiler.release>
    <surefire-plugin.version>3.0.0</surefire-plugin.version>
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
  ```
- Why: Quarkus BOM manages all Quarkus dependency versions consistently
- Depends on: Step 1
- Verify: `grep 'quarkus-bom' pom.xml`

### Step 3: Replace Java EE dependencies with Quarkus extensions
- Phase: Build & Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Remove javax dependencies (javaee-web-api, javaee-api, jboss-jms-api) and add Quarkus extensions:
  ```xml
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
  </dependency>
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
    <artifactId>quarkus-smallrye-reactive-messaging</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-reactive-messaging-in-memory</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-arc</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>4.1.2</version>
  </dependency>
  ```
- Why: Quarkus uses its own optimized extensions instead of full Java EE dependencies
- Depends on: Step 2
- Verify: `grep 'quarkus-resteasy-reactive-jackson' pom.xml && grep 'quarkus-hibernate-orm-panache' pom.xml`

### Step 4: Add Quarkus Maven plugins
- Phase: Build & Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Replace existing maven-compiler-plugin and maven-war-plugin with Quarkus plugins:
  ```xml
  <build>
    <finalName>coolstore</finalName>
    <plugins>
      <plugin>
        <groupId>${quarkus.platform.group-id}</groupId>
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
          <compilerArgs>
            <arg>-parameters</arg>
          </compilerArgs>
        </configuration>
      </plugin>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire-plugin.version}</version>
        <configuration>
          <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            <maven.home>${maven.home}</maven.home>
          </systemPropertyVariables>
        </configuration>
      </plugin>
      <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
        <version>${surefire-plugin.version}</version>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
            <configuration>
              <systemPropertyVariables>
                <native.image.path>${project.build.directory}/${project.build.finalName}-runner</native.image.path>
                <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                <maven.home>${maven.home}</maven.home>
              </systemPropertyVariables>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
  ```
- Why: Quarkus requires specific Maven plugins for code generation and native image support
- Depends on: Step 3
- Verify: `grep 'quarkus-maven-plugin' pom.xml`

### Step 5: Add native profile to pom.xml
- Phase: Build & Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add native build profile:
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
- Why: Enables optional native image compilation with GraalVM
- Depends on: Step 4
- Verify: `grep '<id>native</id>' pom.xml`

### Step 6: Create application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create Quarkus configuration file with datasource and persistence settings:
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
  quarkus.hibernate-orm.jdbc.statement-batch-size=false
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  
  # Reactive Messaging - orders topic/channel
  mp.messaging.outgoing.orders.connector=smallrye-in-memory
  mp.messaging.incoming.orders.connector=smallrye-in-memory
  
  # REST configuration
  quarkus.http.port=8080
  quarkus.resteasy-reactive.path=/services
  
  # Logging
  quarkus.log.console.enable=true
  quarkus.log.console.level=INFO
  ```
- Why: Quarkus uses application.properties instead of XML configuration files for centralized configuration
- Depends on: Step 5
- Verify: `test -f src/main/resources/application.properties`

### Step 7: Update persistence.xml namespaces
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do: Update XML namespaces and schema from javax to jakarta:
  - Replace `http://xmlns.jcp.org/xml/ns/persistence` with `https://jakarta.ee/xml/ns/persistence`
  - Replace `persistence_2_1.xsd` with `persistence_3_0.xsd`
  - Change version attribute from `2.1` to `3.0`
  - Replace `javax.persistence.*` property names with `jakarta.persistence.*`
- Why: Jakarta EE 9+ uses new namespace and schema locations
- Depends on: Step 6
- Verify: `grep 'jakarta.ee/xml/ns/persistence' src/main/resources/META-INF/persistence.xml`

### Step 8: Update beans.xml namespaces
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do: Update XML namespaces and schema:
  - Replace `http://xmlns.jcp.org/xml/ns/javaee` with `https://jakarta.ee/xml/ns/jakartaee`
  - Replace `beans_1_1.xsd` with `beans_3_0.xsd`
  - Note: This file will be deleted in Phase 10, but update it for intermediate compatibility
- Why: Jakarta EE 9+ requires updated namespace declarations
- Depends on: Step 7
- Verify: `grep 'jakarta.ee/xml/ns/jakartaee' src/main/webapp/WEB-INF/beans.xml`

### Step 9: Update Resources.java - remove @PersistenceContext
- Phase: Dependency Injection & Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - BEFORE: Class with @PersistenceContext producing EntityManager
  - AFTER: Delete entire file (EntityManager is managed automatically by Quarkus)
  - This class is no longer needed - Quarkus provides EntityManager as a CDI bean automatically
- Why: Quarkus manages EntityManager directly; using @Produces with @PersistenceContext conflicts with Quarkus CDI, and the producer pattern is unnecessary
- Depends on: Step 8
- Verify: `test ! -f src/main/java/com/redhat/coolstore/persistence/Resources.java`

### Step 10: Migrate imports in CatalogItemEntity.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` imports with `jakarta.persistence.*`
- Why: Jakarta EE 9+ moved persistence API from javax to jakarta namespace
- Depends on: Step 9
- Verify: `grep -q 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java && ! grep -q 'javax.persistence' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java`

### Step 11: Migrate imports in InventoryEntity.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence.*` and `javax.xml.*` imports with `jakarta.persistence.*` and `jakarta.xml.*`
- Why: Jakarta EE 9+ moved all javax enterprise APIs to jakarta namespace
- Depends on: Step 9
- Verify: `grep -q 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/InventoryEntity.java && ! grep -q 'javax.persistence' src/main/java/com/redhat/coolstore/model/InventoryEntity.java`

### Step 12: COMPLEX - Migrate Order.java with sequence strategy
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
  - BEFORE: `@GeneratedValue` (using default AUTO strategy with hibernate_sequence)
  - AFTER: `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")` and add `@SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 1)`
  - Replace all `javax.persistence.*` imports with `jakarta.persistence.*`
  - Specific changes:
    1. Replace: `import javax.persistence.*` with `import jakarta.persistence.*`
    2. Add imports: `import jakarta.persistence.SequenceGenerator;`
    3. Modify @GeneratedValue annotation as shown above
    4. Add @SequenceGenerator annotation before the orderId field
- Why: Hibernate 6 changed from single hibernate_sequence to per-entity sequences; explicit configuration prevents runtime errors
- Depends on: Step 9
- Verify: `grep 'order_seq' src/main/java/com/redhat/coolstore/model/Order.java`

### Step 13: COMPLEX - Migrate OrderItem.java with sequence strategy
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
  - BEFORE: `@GeneratedValue` (using default AUTO strategy)
  - AFTER: `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_item_seq")` and add `@SequenceGenerator(name = "order_item_seq", sequenceName = "order_item_seq", allocationSize = 1)`
  - Replace all `javax.persistence.*` imports with `jakarta.persistence.*`
- Why: Hibernate 6 requires explicit sequence configuration per entity
- Depends on: Step 9
- Verify: `grep 'order_item_seq' src/main/java/com/redhat/coolstore/model/OrderItem.java`

### Step 14: Migrate imports in Product.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all `javax.xml.*` imports with `jakarta.xml.*`
- Why: Jakarta EE 9+ moved XML binding APIs to jakarta namespace
- Depends on: Step 9
- Verify: `grep -q 'jakarta.xml' src/main/java/com/redhat/coolstore/model/Product.java && ! grep -q 'javax.xml' src/main/java/com/redhat/coolstore/model/Product.java`

### Step 15: Migrate imports in Promotion.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace all `javax.*` imports with `jakarta.*` equivalents
- Why: Jakarta EE 9+ namespace change
- Depends on: Step 9
- Verify: `! grep -q 'import javax\.' src/main/java/com/redhat/coolstore/model/Promotion.java || grep -q 'import jakarta\.' src/main/java/com/redhat/coolstore/model/Promotion.java`

### Step 16: Migrate imports in ShoppingCart.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all `javax.xml.*` imports with `jakarta.xml.*`
- Why: Jakarta EE 9+ namespace change
- Depends on: Step 9
- Verify: `grep -q 'jakarta.xml' src/main/java/com/redhat/coolstore/model/ShoppingCart.java && ! grep -q 'javax.xml' src/main/java/com/redhat/coolstore/model/ShoppingCart.java`

### Step 17: Migrate imports in ShoppingCartItem.java
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace all `javax.xml.*` imports with `jakarta.xml.*`
- Why: Jakarta EE 9+ namespace change
- Depends on: Step 9
- Verify: `grep -q 'jakarta.xml' src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java && ! grep -q 'javax.xml' src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java`

### Step 18: COMPLEX - Migrate CatalogService.java
- Phase: Service Layer - Core
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB with container-managed transactions
  - AFTER: `@ApplicationScoped` CDI bean with explicit `@Transactional`
  - Specific changes:
    1. Replace: `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
    2. Replace: `@Stateless` with `@ApplicationScoped`
    3. Add: `@Transactional` annotation to class or to methods that use EntityManager (persist, merge, remove)
    4. Replace: `import javax.inject.Inject;` with `import jakarta.inject.Inject;`
    5. Replace: `import javax.persistence.EntityManager;` with `import jakarta.persistence.EntityManager;`
    6. Update EntityManager injection to use `@Inject` instead of any @PersistenceContext
- Why: Quarkus uses CDI beans instead of EJBs; transactions must be explicitly marked with @Transactional
- Depends on: Step 10, Step 11, Step 12, Step 13, Step 14, Step 15, Step 16, Step 17
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/CatalogService.java`

### Step 19: COMPLEX - Migrate OrderService.java
- Phase: Service Layer - Core
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: `@ApplicationScoped` CDI bean with `@Transactional`
  - Specific changes:
    1. Replace: `@Stateless` with `@ApplicationScoped`
    2. Add: `@Transactional` annotation to the class
    3. Replace all `javax.*` imports with `jakarta.*`
    4. Update EntityManager injection to `@Inject EntityManager em;`
- Why: Convert EJB to CDI bean with explicit transaction management
- Depends on: Step 18
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/OrderService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/OrderService.java`

### Step 20: COMPLEX - Migrate ProductService.java
- Phase: Service Layer - Core
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: `@ApplicationScoped` CDI bean with `@Transactional`
  - Replace all `javax.*` imports with `jakarta.*`
- Why: Convert EJB to CDI bean
- Depends on: Step 18
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ProductService.java`

### Step 21: Migrate PromoService.java
- Phase: Service Layer - Core
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do: Replace all `javax.enterprise.*` imports with `jakarta.enterprise.*`
- Why: CDI annotations moved to jakarta namespace
- Depends on: Step 18
- Verify: `grep -q 'jakarta.enterprise' src/main/java/com/redhat/coolstore/service/PromoService.java`

### Step 22: COMPLEX - Migrate ShoppingCartOrderProcessor.java
- Phase: Service Layer - JMS Migration
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` with JMS Topic injection via `@Resource(lookup = "java:/topic/orders")`
  - AFTER: `@ApplicationScoped` with reactive messaging Emitter
  - Specific changes:
    1. Replace: `import javax.ejb.Stateless;` with `import jakarta.enterprise.context.ApplicationScoped;`
    2. Remove: `import javax.annotation.Resource;`, `import javax.jms.JMSContext;`, `import javax.jms.Topic;`
    3. Add: `import org.eclipse.microprofile.reactive.messaging.Channel;`, `import org.eclipse.microprofile.reactive.messaging.Emitter;`, `import jakarta.inject.Inject;`
    4. Replace: `@Stateless` with `@ApplicationScoped`
    5. Remove: `@Inject private transient JMSContext context;` and `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;`
    6. Add: `@Inject @Channel("orders") Emitter<String> ordersEmitter;`
    7. Replace: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));` with `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
    8. Update Logger import to `jakarta.inject.Inject;` for injecting logger
- Why: JMS is not supported in Quarkus; use SmallRye Reactive Messaging with Channel/Emitter pattern
- Depends on: Step 18
- Verify: `grep '@Channel("orders")' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java && grep 'Emitter<String>' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java`

### Step 23: COMPLEX - Migrate OrderServiceMDB.java to reactive messaging
- Phase: Service Layer - JMS Migration
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: `@MessageDriven` MDB with `@ActivationConfigProperty` for topic subscription
  - AFTER: `@ApplicationScoped` CDI bean with `@Incoming("orders")` method
  - Specific changes:
    1. Remove: `import javax.ejb.*;`, `import javax.jms.*;`
    2. Add: `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.transaction.Transactional;`
    3. Replace: `@MessageDriven(name = "OrderServiceMDB", activationConfig = {...})` with `@ApplicationScoped`
    4. Remove: `implements MessageListener`
    5. Replace method signature: `public void onMessage(Message rcvMessage)` with `@Incoming("orders") @Transactional public void onMessage(String orderStr)`
    6. Simplify method body: remove JMS message unwrapping, directly use the String parameter
    7. Replace: `import javax.inject.Inject;` with `import jakarta.inject.Inject;`
- Why: Message-driven beans are replaced by reactive messaging in Quarkus
- Depends on: Step 19, Step 18, Step 22
- Verify: `grep '@Incoming("orders")' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java && ! grep '@MessageDriven' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java`

### Step 24: COMPLEX - Migrate InventoryNotificationMDB.java to reactive messaging
- Phase: Service Layer - JMS Migration
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Manual JMS topic subscription with JNDI lookups and WebLogic-specific configuration
  - AFTER: `@ApplicationScoped` CDI bean with `@Incoming("orders")` reactive method
  - Specific changes:
    1. Remove: All JMS and JNDI imports (`javax.jms.*`, `javax.naming.*`, `javax.rmi.*`)
    2. Add: `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.enterprise.context.ApplicationScoped;`
    3. Add: `@ApplicationScoped` annotation to class
    4. Remove: All WebLogic-specific JNDI constants and fields (JNDI_FACTORY, JMS_FACTORY, TOPIC, connection fields)
    5. Replace method: `public void onMessage(Message rcvMessage)` with `@Incoming("orders") public void onMessage(String orderStr)`
    6. Simplify method body: remove JMS unwrapping, work directly with String parameter
    7. Remove: `init()`, `close()`, and `getInitialContext()` methods (no longer needed)
    8. Replace: `import javax.inject.Inject;` with `import jakarta.inject.Inject;`
- Why: JNDI and manual JMS subscription not supported in Quarkus; use reactive messaging
- Depends on: Step 23
- Verify: `grep '@Incoming("orders")' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java && ! grep 'InitialContext' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java`

### Step 25: COMPLEX - Convert ShippingService from Remote EJB to REST
- Phase: Service Layer - Remote EJB
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless @Remote` EJB implementing ShippingServiceRemote interface
  - AFTER: `@ApplicationScoped` CDI bean with JAX-RS REST endpoints
  - Specific changes:
    1. Remove: `import javax.ejb.Remote;`, `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import jakarta.ws.rs.*;`, `import jakarta.ws.rs.core.MediaType;`
    3. Replace: `@Stateless @Remote` with `@ApplicationScoped @Path("/shipping")`
    4. Keep: `implements ShippingServiceRemote` (will update interface separately)
    5. Add: `@POST @Path("/calculate") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)` to calculateShipping method
    6. Add: `@POST @Path("/insurance") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)` to calculateShippingInsurance method
- Why: Remote EJBs are not supported in Quarkus; replace with REST endpoints
- Depends on: Step 20
- Verify: `grep '@Path("/shipping")' src/main/java/com/redhat/coolstore/service/ShippingService.java && ! grep '@Remote' src/main/java/com/redhat/coolstore/service/ShippingService.java`

### Step 26: Update ShippingServiceRemote interface
- Phase: Service Layer - Remote EJB
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do: Keep interface but remove any EJB-specific imports if present; it now serves as a local interface
- Why: Interface can be retained for consistency but no longer needs to be a remote interface
- Depends on: Step 25
- Verify: `test -f src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java`

### Step 27: COMPLEX - Migrate ShoppingCartService.java
- Phase: Service Layer - Remote EJB
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateful` EJB with JNDI lookup for remote ShippingService
  - AFTER: `@ApplicationScoped` CDI bean with injected ShippingService
  - Specific changes:
    1. Remove: `import javax.ejb.Stateful;`, `import javax.naming.*;`, `import java.util.Hashtable;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@Stateful` with `@ApplicationScoped`
    4. Add: `@Inject ShippingService shippingService;` field
    5. Remove: `lookupShippingServiceRemote()` method entirely
    6. Replace all calls: `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)`
    7. Replace all calls: `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
    8. Replace: `import javax.inject.Inject;` with `import jakarta.inject.Inject;`
    9. Note: Stateful session state management should be externalized in production; this ApplicationScoped bean maintains state in-memory per instance
- Why: JNDI lookups and remote EJBs not supported; use CDI injection for local service access
- Depends on: Step 25, Step 26, Step 22
- Verify: `grep '@Inject ShippingService' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java && ! grep 'lookupShippingServiceRemote' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java`

### Step 28: Migrate CartEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*` and `javax.inject.*` imports with `jakarta.ws.rs.*` and `jakarta.inject.*`
- Why: JAX-RS API moved to jakarta namespace
- Depends on: Step 27
- Verify: `grep -q 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java && ! grep -q 'javax.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java`

### Step 29: Migrate OrderEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*` and `javax.inject.*` imports with `jakarta.ws.rs.*` and `jakarta.inject.*`
- Why: JAX-RS API moved to jakarta namespace
- Depends on: Step 27
- Verify: `grep -q 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java && ! grep -q 'javax.ws.rs' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java`

### Step 30: Migrate ProductEndpoint.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace all `javax.ws.rs.*` and `javax.inject.*` imports with `jakarta.ws.rs.*` and `jakarta.inject.*`
- Why: JAX-RS API moved to jakarta namespace
- Depends on: Step 27
- Verify: `grep -q 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java && ! grep -q 'javax.ws.rs' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java`

### Step 31: Simplify RestApplication.java
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Replace: `import javax.ws.rs.*;` with `import jakarta.ws.rs.*;`
  - Keep: `@ApplicationPath("/services")` annotation (this sets the REST base path)
  - Note: The class body can remain empty; Quarkus auto-discovers endpoints
- Why: JAX-RS activation is simplified in Quarkus; @ApplicationPath defines the REST base path but class can be minimal
- Depends on: Step 28, Step 29, Step 30
- Verify: `grep 'jakarta.ws.rs.ApplicationPath' src/main/java/com/redhat/coolstore/rest/RestApplication.java`

### Step 32: Migrate DataBaseMigrationStartup.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do: Replace all `javax.*` imports with `jakarta.*` (for annotations like @PostConstruct, @Inject, etc.)
- Why: Java EE annotations moved to jakarta namespace
- Depends on: Step 31
- Verify: `! grep -q 'import javax\.' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java || grep -q 'import jakarta\.' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java`

### Step 33: COMPLEX - Migrate Producers.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Review @Produces methods and determine if they're still needed
  - Replace: `import javax.enterprise.inject.Produces;` with `import jakarta.enterprise.inject.Produces;`
  - Replace other `javax.*` imports with `jakarta.*`
  - Consider removing @Produces if the analysis suggests it's unnecessary for Logger (Quarkus may provide it automatically)
- Why: CDI annotations moved to jakarta; some producers may be redundant in Quarkus
- Depends on: Step 32
- Verify: `grep -q 'jakarta.enterprise.inject.Produces' src/main/java/com/redhat/coolstore/utils/Producers.java`

### Step 34: Migrate StartupListener.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do: Replace all `javax.*` imports with `jakarta.*`; if it uses WebLogic-specific lifecycle listeners, adapt to Quarkus startup events (@Observes StartupEvent)
- Why: Lifecycle management differs in Quarkus
- Depends on: Step 33
- Verify: `! grep -q 'import javax\.' src/main/java/com/redhat/coolstore/utils/StartupListener.java || grep -q 'import jakarta\.' src/main/java/com/redhat/coolstore/utils/StartupListener.java`

### Step 35: Migrate Transformers.java
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: If it has any `javax.*` imports, replace with `jakarta.*`; review for JSON processing compatibility
- Why: Ensure all enterprise APIs use jakarta namespace
- Depends on: Step 34
- Verify: `! grep -q 'import javax\.' src/main/java/com/redhat/coolstore/utils/Transformers.java || grep -q 'import jakarta\.' src/main/java/com/redhat/coolstore/utils/Transformers.java`

### Step 36: Delete WebLogic ApplicationLifecycleEvent.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this WebLogic compatibility file - not needed in Quarkus
- Why: WebLogic-specific code is not compatible with Quarkus
- Depends on: Step 35
- Verify: `test ! -f src/main/java/weblogic/application/ApplicationLifecycleEvent.java`

### Step 37: Delete WebLogic ApplicationLifecycleListener.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this WebLogic compatibility file - not needed in Quarkus
- Why: WebLogic-specific code is not compatible with Quarkus
- Depends on: Step 35
- Verify: `test ! -f src/main/java/weblogic/application/ApplicationLifecycleListener.java`

### Step 38: Delete WebLogic NonCatalogLogger.java
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this WebLogic compatibility file - not needed in Quarkus
- Why: WebLogic-specific logging is not compatible with Quarkus
- Depends on: Step 35
- Verify: `test ! -f src/main/java/weblogic/i18n/logging/NonCatalogLogger.java`

## Verification

- Build: `mvn clean compile` (should complete without errors)
- Test: Tests are currently skipped (maven.test.skip=true in pom.xml), but once enabled: `mvn test`
- Blackbox: 
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Run application: `mvn quarkus:dev`
  3. Access application at http://localhost:8080
  4. Test REST endpoints:
     - GET http://localhost:8080/services/products
     - POST http://localhost:8080/services/cart/checkout/{cartId}
  5. Verify order processing through reactive messaging by monitoring logs for "Message recd" and "Received order" messages
  6. Test shipping calculation through new REST endpoint: POST http://localhost:8080/services/shipping/calculate

## Notes

### Sequence Generation
Database schema must be updated to include per-entity sequences before running the migrated application:
- `order_seq` for Order entity
- `order_item_seq` for OrderItem entity

You can generate DDL by temporarily setting `quarkus.hibernate-orm.database.generation=drop-and-create` in application.properties (development only).

### Reactive Messaging
The migration uses in-memory connector for the "orders" channel. For production:
1. Switch to a proper message broker (Apache Kafka, AMQP, etc.)
2. Update application.properties with connector configuration
3. Add appropriate Quarkus messaging extension (quarkus-smallrye-reactive-messaging-kafka, etc.)

### Stateful Session Management
ShoppingCartService was converted from @Stateful to @ApplicationScoped. In production:
1. Consider using @SessionScoped with quarkus-undertow extension
2. Or implement external session storage (Redis, database)
3. Current implementation maintains cart state in-memory per application instance

### Web Resources
Static web resources (HTML, JavaScript, CSS) in src/main/webapp should be moved to src/main/resources/META-INF/resources/ for Quarkus to serve them properly. This plan focuses on backend Java code migration; frontend resource migration can be done as a follow-up.

### Embedded Libraries
Two embedded JAR files (audit-logging-library-1.0.0.jar, audit-logging-library-2.0.0.jar) in the lib/ directory contain GIN framework and logging utilities. Review these for Quarkus compatibility and consider:
1. Extracting and migrating their code if source is available
2. Replacing with Quarkus-compatible alternatives
3. Verifying they don't conflict with Quarkus CDI

### Testing Strategy
Current POM has `maven.test.skip=true`. After migration:
1. Enable tests by removing or setting to false
2. Migrate any existing tests to use `@QuarkusTest` annotation
3. Add integration tests for REST endpoints and reactive messaging

### Build Verification
After completing all steps, verify the build produces a runnable JAR:
```bash
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

### Keycloak Integration
The application uses Keycloak for authentication. Quarkus has native OIDC support; after basic migration:
1. Add quarkus-oidc extension
2. Configure OIDC properties in application.properties
3. Migrate keycloak.json configuration to Quarkus OIDC format
