# Migration Plan

## Goal
Migrate a Java EE 7 monolithic application to Quarkus 3 with Jakarta EE APIs

## Source → Target
Java EE 7 (WAR deployment on JBoss EAP 7.4) → Quarkus 3 (JAR deployment)

## Scope
- Files affected: 29
- Estimated complexity: High
- Hardest areas: 
  1. JMS Message-Driven Beans to Reactive Messaging (2 MDBs)
  2. JNDI lookups to CDI injection (2 files with JNDI)
  3. Stateful EJB session management (1 file)

## Key Decisions Applied

1. **JMS to Reactive Messaging**: Replace JMS Message-Driven Beans with Quarkus SmallRye Reactive Messaging using `@Incoming` and `@Channel` annotations. The topic/orders will be configured via application.properties using the in-memory connector for simplicity (can be changed to Kafka/AMQP later).

2. **Remote EJB elimination**: ShippingService is marked @Remote but called locally via JNDI. Will convert to standard CDI bean and inject directly, removing the remote interface and JNDI lookup.

3. **Stateful EJB replacement**: ShoppingCartService uses @Stateful for per-user cart state. Will replace with @SessionScoped CDI bean to maintain HTTP session state management.

4. **EntityManager pattern**: Replace @PersistenceContext with @Inject and remove @Produces pattern from Resources.java, as Quarkus supports direct EntityManager injection.

5. **Persistence configuration**: Move persistence.xml datasource and Hibernate settings to application.properties following Quarkus conventions (quarkus.datasource.* and quarkus.hibernate-orm.*).

6. **Application lifecycle listener**: Remove WebLogic-specific StartupListener and replace with Quarkus @Observes StartupEvent pattern if startup logic is needed.

7. **JAX-RS Application**: Keep RestApplication.java but @ApplicationPath is sufficient - no need to extend Application.

8. **Audit logging library**: Keep the system-scoped dependency for now, assuming it's compatible with Quarkus. This may need review during execution.

## Approach

The migration follows a bottom-up, dependency-ordered approach:

1. **Phase 1 - Build Configuration**: Migrate pom.xml to Quarkus BOM, plugins, and JAR packaging. This establishes the foundation.

2. **Phase 2 - Configuration Files**: Convert XML configurations (persistence.xml, beans.xml) to Quarkus application.properties and update namespaces to Jakarta EE.

3. **Phase 3 - Models and Persistence**: Migrate entity models (javax → jakarta imports) and update ID generation strategies. Update persistence layer (Resources.java).

4. **Phase 4 - Core Services**: Migrate stateless services, removing @Stateless and adding @Transactional where needed. These are foundational business logic used by REST and MDBs.

5. **Phase 5 - Complex Services**: Handle stateful EJB, remote EJB, and JNDI lookups. These have architectural changes.

6. **Phase 6 - Messaging Layer**: Convert JMS Message-Driven Beans to Reactive Messaging with @Incoming. This is the most complex transformation.

7. **Phase 7 - REST Layer**: Migrate REST endpoints (javax → jakarta imports), ensure they work with updated services.

8. **Phase 8 - Utilities**: Migrate utility classes and remove WebLogic-specific code.

9. **Phase 9 - Cleanup**: Remove obsolete configuration files (web.xml, beans.xml content) and WebLogic stub classes.

## Steps

### Step 1: Update Maven packaging type to JAR
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>`
- Why: Quarkus applications use JAR packaging, not WAR
- Depends on: none
- Verify: `grep '<packaging>jar</packaging>' pom.xml`

### Step 2: Replace javax dependencies with Jakarta
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: 
  - Remove `<groupId>javax</groupId>` dependencies (javaee-web-api, javaee-api)
  - Remove `<groupId>org.jboss.spec.javax.jms</groupId>` dependency
  - Remove `<groupId>org.jboss.spec.javax.rmi</groupId>` dependency
- Why: Quarkus provides these via its BOM, javax artifacts are replaced by Jakarta
- Depends on: Step 1
- Verify: `grep -c 'groupId>javax<' pom.xml` returns 0

### Step 3: Add Quarkus BOM
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add Quarkus BOM in dependencyManagement section after `<properties>`:
  ```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>3.2.0.Final</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  ```
- Why: Quarkus BOM manages all Quarkus extension versions
- Depends on: Step 2
- Verify: `grep 'quarkus-bom' pom.xml`

### Step 4: Add Quarkus dependencies
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add Quarkus extensions in `<dependencies>` section:
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
    <artifactId>quarkus-smallrye-reactive-messaging-in-memory</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-arc</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-flyway</artifactId>
  </dependency>
  ```
- Why: These Quarkus extensions replace Java EE dependencies (JPA, JAX-RS, JMS, CDI, Flyway)
- Depends on: Step 3
- Verify: `grep 'quarkus-hibernate-orm-panache' pom.xml && grep 'quarkus-smallrye-reactive-messaging' pom.xml`

### Step 5: Update Flyway dependency
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Remove standalone Flyway dependency (org.flywaydb:flyway-core:4.1.2) - it's included in quarkus-flyway
- Why: Quarkus provides Flyway integration via extension
- Depends on: Step 4
- Verify: `grep -c 'org.flywaydb' pom.xml` returns 0

### Step 6: Add Quarkus Maven plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Replace maven-war-plugin with quarkus-maven-plugin in `<build><plugins>`:
  ```xml
  <plugin>
    <groupId>io.quarkus.platform</groupId>
    <artifactId>quarkus-maven-plugin</artifactId>
    <version>3.2.0.Final</version>
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
- Why: Quarkus Maven plugin handles Quarkus application build and packaging
- Depends on: Step 5
- Verify: `grep 'quarkus-maven-plugin' pom.xml`

### Step 7: Update Maven Compiler plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Update maven-compiler-plugin configuration:
  - Update version to 3.11.0
  - Update source and target to 11 or higher (Quarkus 3 requires Java 11+)
  - Add compiler args: `-parameters` for reflection
- Why: Quarkus requires Java 11+ and benefits from parameter names in reflection
- Depends on: Step 6
- Verify: `grep -A 5 'maven-compiler-plugin' pom.xml | grep '<source>11</source>'`

### Step 8: Add Maven Surefire plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add or update maven-surefire-plugin in `<build><plugins>`:
  ```xml
  <plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.0.0-M7</version>
    <configuration>
      <systemPropertyVariables>
        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
        <maven.home>${maven.home}</maven.home>
      </systemPropertyVariables>
    </configuration>
  </plugin>
  ```
- Why: Required for running Quarkus tests
- Depends on: Step 7
- Verify: `grep 'maven-surefire-plugin' pom.xml`

### Step 9: Add Maven Failsafe plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add maven-failsafe-plugin in `<build><plugins>`:
  ```xml
  <plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.0.0-M7</version>
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
  ```
- Why: Required for running Quarkus integration tests
- Depends on: Step 8
- Verify: `grep 'maven-failsafe-plugin' pom.xml`

### Step 10: Add native profile
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add native profile in `<profiles>` section:
  ```xml
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
  ```
- Why: Enables Quarkus native compilation support
- Depends on: Step 9
- Verify: `grep -A 5 'id>native<' pom.xml`

### Step 11: Create Quarkus application.properties
- Phase: Configuration
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create application.properties with datasource and Hibernate settings:
  ```properties
  # Datasource configuration
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgresDB
  
  # Hibernate configuration
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.log.format-sql=true
  quarkus.hibernate-orm.jdbc.statement-fetch-size=10
  quarkus.hibernate-orm.jdbc.statement-batch-size=10
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  quarkus.flyway.locations=classpath:db/migration
  
  # Reactive Messaging configuration - in-memory connector
  mp.messaging.outgoing.orders.connector=smallrye-in-memory
  mp.messaging.incoming.orders.connector=smallrye-in-memory
  
  # Application configuration
  quarkus.http.port=8080
  ```
- Why: Quarkus uses application.properties for configuration instead of XML files
- Depends on: Step 10
- Verify: File exists and contains quarkus.datasource.db-kind=postgresql

### Step 12: Update persistence.xml namespaces
- Phase: Configuration
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do: Update XML namespaces and schema:
  - Change `xmlns="http://xmlns.jcp.org/xml/ns/persistence"` to `xmlns="https://jakarta.ee/xml/ns/persistence"`
  - Change `http://xmlns.jcp.org/xml/ns/persistence/persistence_2_1.xsd` to `https://jakarta.ee/xml/ns/persistence/persistence_3_0.xsd`
  - Change `version="2.1"` to `version="3.0"`
  - Change property name `javax.persistence.schema-generation.database.action` to `jakarta.persistence.schema-generation.database.action`
  - Comment out or note that most properties are now in application.properties
- Why: Jakarta EE uses new namespaces; Quarkus prefers properties file for most config
- Depends on: Step 11
- Verify: `grep 'jakarta.ee/xml/ns/persistence' src/main/resources/META-INF/persistence.xml`

### Step 13: Update beans.xml namespaces
- Phase: Configuration
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do: Update XML namespaces:
  - Change `xmlns="http://xmlns.jcp.org/xml/ns/javaee"` to `xmlns="https://jakarta.ee/xml/ns/jakartaee"`
  - Change schema location from `http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd` to `https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd`
  - Note: In Quarkus, beans.xml content is largely ignored; CDI is enabled by default
- Why: Jakarta EE namespace migration; beans.xml kept for compatibility but content is ignored in Quarkus
- Depends on: Step 12
- Verify: `grep 'jakarta.ee/xml/ns/jakartaee' src/main/webapp/WEB-INF/beans.xml`

### Step 14: Migrate CatalogItemEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace javax.persistence.* imports with jakarta.persistence.*
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java` returns 0

### Step 15: Migrate InventoryEntity imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace javax.persistence.* imports with jakarta.persistence.*
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/InventoryEntity.java` returns 0

### Step 16: COMPLEX - Migrate Order entity with ID generation
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
  - Replace all javax.persistence.* imports with jakarta.persistence.*
  - Update @GeneratedValue strategy if using AUTO:
    - BEFORE: `@GeneratedValue(strategy = GenerationType.AUTO)` (if present)
    - AFTER: Explicitly specify SEQUENCE or IDENTITY and provide a name:
      ```java
      @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
      @SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", allocationSize = 1)
      ```
  - This ensures consistent ID generation behavior across JPA providers
- Why: Hibernate 6 (used by Quarkus) changed implicit sequence/table naming; explicit naming prevents migration issues
- Depends on: Step 13
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/Order.java && grep -c 'import javax' src/main/java/com/redhat/coolstore/model/Order.java` returns 0

### Step 17: COMPLEX - Migrate OrderItem entity with ID generation
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
  - Replace all javax.persistence.* imports with jakarta.persistence.*
  - Update @GeneratedValue strategy similar to Step 16:
    ```java
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orderitem_seq")
    @SequenceGenerator(name = "orderitem_seq", sequenceName = "orderitem_sequence", allocationSize = 1)
    ```
- Why: Same ID generation consistency as Order entity
- Depends on: Step 13
- Verify: `grep 'jakarta.persistence' src/main/java/com/redhat/coolstore/model/OrderItem.java && grep -c 'import javax' src/main/java/com/redhat/coolstore/model/OrderItem.java` returns 0

### Step 18: Migrate Product imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace javax.xml.bind.annotation.* imports with jakarta.xml.bind.annotation.*
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/Product.java` returns 0

### Step 19: Migrate Promotion imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace javax.xml.bind.annotation.* imports with jakarta.xml.bind.annotation.* (if present)
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/Promotion.java` returns 0

### Step 20: Migrate ShoppingCart imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace javax.xml.bind.annotation.* imports with jakarta.xml.bind.annotation.*
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/ShoppingCart.java` returns 0

### Step 21: Migrate ShoppingCartItem imports
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace javax.xml.bind.annotation.* imports with jakarta.xml.bind.annotation.* (if present)
- Why: Jakarta EE renamed javax.* packages to jakarta.*
- Depends on: Step 13
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java` returns 0

### Step 22: COMPLEX - Migrate Resources.java persistence pattern
- Phase: Persistence
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do:
  - BEFORE: @PersistenceContext + @Produces pattern
    ```java
    @PersistenceContext
    private EntityManager em;
    
    @Produces
    public EntityManager getEntityManager() {
        return em;
    }
    ```
  - AFTER: Simple @Produces without @PersistenceContext
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.enterprise.inject.Produces;
    import jakarta.persistence.EntityManager;
    
    @ApplicationScoped
    public class Resources {
        
        @Produces
        EntityManager entityManager;
    }
    ```
  - Replace all javax imports with jakarta imports
  - Change @Dependent to @ApplicationScoped
  - Remove the getEntityManager() method
- Why: Quarkus supports direct EntityManager injection; @PersistenceContext not needed, @Produces pattern simplified
- Depends on: Step 21
- Verify: `grep -c '@PersistenceContext' src/main/java/com/redhat/coolstore/persistence/Resources.java` returns 0

### Step 23: Migrate CatalogService
- Phase: Core Services
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @Stateless annotation
  - Add @ApplicationScoped annotation
  - Add @Transactional annotation to updateInventoryItems() method and any other method that modifies data
- Why: Quarkus doesn't use EJB @Stateless; use CDI @ApplicationScoped instead. Transactions must be explicitly declared.
- Depends on: Step 22
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/CatalogService.java`

### Step 24: Migrate ProductService
- Phase: Core Services
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @Stateless annotation
  - Add @ApplicationScoped annotation
  - Add @Transactional annotation to any methods that persist/merge/remove entities
- Why: Quarkus doesn't use EJB @Stateless; use CDI @ApplicationScoped instead
- Depends on: Step 22
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ProductService.java`

### Step 25: Migrate PromoService
- Phase: Core Services
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - If @Stateless is present, remove it and add @ApplicationScoped
- Why: Jakarta namespace migration and EJB to CDI conversion
- Depends on: Step 22
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/service/PromoService.java` returns 0

### Step 26: Migrate OrderService
- Phase: Core Services
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @Stateless annotation
  - Add @ApplicationScoped annotation
  - Add @Transactional annotation to save() method and any other data modification methods
- Why: Quarkus requires explicit @Transactional for EntityManager operations
- Depends on: Step 22
- Verify: `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/OrderService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/OrderService.java`

### Step 27: COMPLEX - Migrate ShippingService and remove Remote EJB
- Phase: Complex Services
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: @Stateless @Remote EJB
    ```java
    @Stateless
    @Remote
    public class ShippingService implements ShippingServiceRemote {
    ```
  - AFTER: Regular CDI bean
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    
    @ApplicationScoped
    public class ShippingService implements ShippingServiceRemote {
    ```
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @Stateless and @Remote annotations
  - Add @ApplicationScoped annotation
  - Add @Transactional to methods if they do data operations
  - Keep implementing ShippingServiceRemote interface (interface can remain)
- Why: Remote EJBs not supported in Quarkus; this is called locally so regular CDI bean works
- Depends on: Step 26
- Verify: `grep -c '@Remote' src/main/java/com/redhat/coolstore/service/ShippingService.java` returns 0 and `grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ShippingService.java`

### Step 28: COMPLEX - Migrate ShoppingCartService from Stateful to SessionScoped
- Phase: Complex Services
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: @Stateful EJB with JNDI lookup
    ```java
    @Stateful
    public class ShoppingCartService {
        // ... JNDI lookup code
        private static ShippingServiceRemote lookupShippingServiceRemote() {
            final Context context = new InitialContext(jndiProperties);
            return (ShippingServiceRemote) context.lookup("ejb:/ROOT/ShippingService!...");
        }
    }
    ```
  - AFTER: @SessionScoped CDI bean with injection
    ```java
    import jakarta.enterprise.context.SessionScoped;
    import jakarta.inject.Inject;
    import jakarta.transaction.Transactional;
    import java.io.Serializable;
    
    @SessionScoped
    public class ShoppingCartService implements Serializable {
        private static final long serialVersionUID = 1L;
        
        @Inject
        ShippingService shippingService;
        
        // Remove lookupShippingServiceRemote() method
        // Replace all calls to lookupShippingServiceRemote().calculateShipping(sc)
        // with shippingService.calculateShipping(sc)
    }
    ```
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove all JNDI-related imports (javax.naming.*)
  - Add @Transactional to methods that need transactions
  - Implement Serializable (already does, keep it)
- Why: @Stateful EJBs and JNDI not supported in Quarkus; @SessionScoped provides per-user session state
- Depends on: Step 27
- Verify: `grep '@SessionScoped' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java && grep -c 'InitialContext' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java` returns 0

### Step 29: COMPLEX - Migrate ShoppingCartOrderProcessor from JMS to Reactive
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: JMS Topic with JNDI lookup
    ```java
    @Inject
    @JMSConnectionFactory("java:/JmsXA")
    private JMSContext context;
    
    @Resource(lookup = "java:/topic/orders")
    private Topic ordersTopic;
    
    public void process(ShoppingCart cart) {
        context.createProducer().send(ordersTopic, ...);
    }
    ```
  - AFTER: Reactive Messaging with @Channel
    ```java
    import jakarta.inject.Inject;
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.transaction.Transactional;
    import org.eclipse.microprofile.reactive.messaging.Channel;
    import org.eclipse.microprofile.reactive.messaging.Emitter;
    
    @ApplicationScoped
    public class ShoppingCartOrderProcessor {
        
        @Inject
        @Channel("orders")
        Emitter<String> ordersEmitter;
        
        @Transactional
        public void process(ShoppingCart cart) {
            String orderJson = Transformers.shoppingCartToJson(cart);
            ordersEmitter.send(orderJson);
        }
    }
    ```
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @Stateless annotation, add @ApplicationScoped
  - Remove all JMS imports (javax.jms.*)
  - Add @Transactional annotation
  - Use String payload instead of TextMessage
- Why: JMS not supported in Quarkus; SmallRye Reactive Messaging is the replacement
- Depends on: Step 26
- Verify: `grep '@Channel' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java && grep -c 'javax.jms' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java` returns 0

### Step 30: COMPLEX - Migrate OrderServiceMDB to Reactive Messaging
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: @MessageDriven MDB implementing MessageListener
    ```java
    @MessageDriven(name = "OrderServiceMDB", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "topic/orders"),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge")
    })
    public class OrderServiceMDB implements MessageListener {
        public void onMessage(Message rcvMessage) {
            TextMessage msg = (TextMessage) rcvMessage;
            String orderStr = msg.getBody(String.class);
            // process order
        }
    }
    ```
  - AFTER: Regular CDI bean with @Incoming
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.inject.Inject;
    import jakarta.transaction.Transactional;
    import org.eclipse.microprofile.reactive.messaging.Incoming;
    
    @ApplicationScoped
    public class OrderServiceMDB {
        
        @Inject
        OrderService orderService;
        
        @Inject
        CatalogService catalogService;
        
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
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove @MessageDriven annotation and all activationConfig
  - Remove MessageListener interface
  - Change onMessage parameter from Message to String
  - Add @ApplicationScoped annotation
  - Add @Incoming("orders") annotation to onMessage method
  - Add @Transactional annotation to onMessage method
  - Remove all JMS-related code (TextMessage, JMSException handling - use plain String)
- Why: MDBs not supported in Quarkus; Reactive Messaging with @Incoming replaces MDB functionality
- Depends on: Step 29
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java && grep -c '@MessageDriven' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java` returns 0

### Step 31: COMPLEX - Migrate InventoryNotificationMDB to Reactive Messaging
- Phase: Messaging
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Plain MessageListener with JNDI-based JMS setup
    ```java
    public class InventoryNotificationMDB implements MessageListener {
        private TopicConnection tcon;
        private TopicSession tsession;
        
        public void onMessage(Message rcvMessage) {
            TextMessage msg = (TextMessage) rcvMessage;
            String orderStr = msg.getBody(String.class);
            // process
        }
        
        public void init() throws NamingException, JMSException {
            Context ctx = getInitialContext();
            TopicConnectionFactory tconFactory = ...;
            // manual JMS setup
        }
    }
    ```
  - AFTER: CDI bean with @Incoming
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.inject.Inject;
    import org.eclipse.microprofile.reactive.messaging.Incoming;
    
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
                int old_quantity = catalogService.getCatalogItemById(orderItem.getProductId())
                    .getInventory().getQuantity();
                int new_quantity = old_quantity - orderItem.getQuantity();
                if (new_quantity < LOW_THRESHOLD) {
                    System.out.println("Inventory for item " + orderItem.getProductId() + 
                        " is below threshold (" + LOW_THRESHOLD + "), contact supplier!");
                }
            });
        }
    }
    ```
  - Replace all javax.* imports with jakarta.* equivalents
  - Remove MessageListener interface
  - Remove all JNDI code (InitialContext, Context, getInitialContext method)
  - Remove all JMS connection management code (init(), close(), TopicConnection, etc.)
  - Change onMessage parameter from Message to String
  - Add @ApplicationScoped annotation
  - Add @Incoming("orders") annotation to onMessage method
  - Remove JMSException handling, simplify to just process the String
- Why: JNDI and JMS not supported in Quarkus; Reactive Messaging provides clean async message consumption
- Depends on: Step 29
- Verify: `grep '@Incoming' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java && grep -c 'InitialContext' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java` returns 0

### Step 32: Migrate CartEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents (javax.ws.rs.*, javax.enterprise.*, javax.inject.*)
  - Keep @SessionScoped annotation (it's valid in Jakarta CDI)
  - Keep @Path and all JAX-RS annotations
  - Ensure it implements Serializable (already does)
- Why: Jakarta namespace migration for REST and CDI APIs
- Depends on: Step 28
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java` returns 0

### Step 33: Migrate OrderEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents (javax.ws.rs.*, javax.inject.*)
  - Keep all JAX-RS annotations (@Path, @GET, @POST, @Produces, etc.)
- Why: Jakarta namespace migration for REST APIs
- Depends on: Step 28
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java` returns 0

### Step 34: Migrate ProductEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents (javax.ws.rs.*, javax.inject.*)
  - Keep all JAX-RS annotations
- Why: Jakarta namespace migration for REST APIs
- Depends on: Step 28
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java` returns 0

### Step 35: Migrate RestApplication
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Replace javax.ws.rs.* imports with jakarta.ws.rs.*
  - Keep @ApplicationPath("/services") annotation
  - Keep extending Application class
  - Note: Class is kept for @ApplicationPath but extending Application is optional in Quarkus
- Why: Jakarta namespace migration; @ApplicationPath defines REST base path
- Depends on: Step 28
- Verify: `grep 'jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/RestApplication.java`

### Step 36: Migrate DataBaseMigrationStartup
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - If using @Singleton, keep it or change to @ApplicationScoped
  - Add @Transactional to methods that use EntityManager
  - If using @Startup, keep it (supported in Quarkus)
- Why: Jakarta namespace migration and transaction management
- Depends on: Step 22
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java` returns 0

### Step 37: Migrate Producers
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents
  - Keep @Produces annotations (they're still valid in Quarkus CDI)
  - If producing Logger, this pattern works in Quarkus
- Why: Jakarta namespace migration; @Produces is standard CDI
- Depends on: Step 22
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/utils/Producers.java` returns 0

### Step 38: Migrate Transformers
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - Replace all javax.* imports with jakarta.* equivalents (javax.json.*, javax.enterprise.*)
  - Keep utility methods as-is
- Why: Jakarta namespace migration for JSON-P APIs
- Depends on: Step 22
- Verify: `grep -c 'import javax' src/main/java/com/redhat/coolstore/utils/Transformers.java` returns 0

### Step 39: Remove StartupListener
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
  - BEFORE: WebLogic-specific ApplicationLifecycleListener
    ```java
    import weblogic.application.ApplicationLifecycleEvent;
    import weblogic.application.ApplicationLifecycleListener;
    
    public class StartupListener extends ApplicationLifecycleListener {
        public void postStart(ApplicationLifecycleEvent evt) { ... }
        public void preStop(ApplicationLifecycleEvent evt) { ... }
    }
    ```
  - AFTER: Quarkus startup observer (if startup logic is needed)
    ```java
    import jakarta.enterprise.context.ApplicationScoped;
    import jakarta.enterprise.event.Observes;
    import jakarta.inject.Inject;
    import io.quarkus.runtime.StartupEvent;
    import io.quarkus.runtime.ShutdownEvent;
    import java.util.logging.Logger;
    
    @ApplicationScoped
    public class StartupListener {
        
        @Inject
        Logger log;
        
        void onStart(@Observes StartupEvent ev) {
            log.info("AppListener(postStart)");
        }
        
        void onStop(@Observes ShutdownEvent ev) {
            log.info("AppListener(preStop)");
        }
    }
    ```
  - Replace WebLogic imports with Quarkus lifecycle events
  - Add @ApplicationScoped annotation
  - Use @Observes StartupEvent and ShutdownEvent
- Why: WebLogic-specific classes not available; Quarkus provides CDI-based lifecycle events
- Depends on: Step 37
- Verify: `grep 'StartupEvent' src/main/java/com/redhat/coolstore/utils/StartupListener.java && grep -c 'weblogic' src/main/java/com/redhat/coolstore/utils/StartupListener.java` returns 0

### Step 40: DELETE WebLogic ApplicationLifecycleEvent stub
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file - no longer needed after StartupListener migration
- Why: WebLogic stub class is obsolete after migration to Quarkus lifecycle events
- Depends on: Step 39
- Verify: File does not exist

### Step 41: DELETE WebLogic ApplicationLifecycleListener stub
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file - no longer needed after StartupListener migration
- Why: WebLogic stub class is obsolete after migration to Quarkus lifecycle events
- Depends on: Step 39
- Verify: File does not exist

### Step 42: DELETE WebLogic NonCatalogLogger stub
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file - no longer referenced after migration
- Why: WebLogic-specific logging stub is not needed in Quarkus
- Depends on: Step 39
- Verify: File does not exist

### Step 43: Move beans.xml to META-INF
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do: Move beans.xml from src/main/webapp/WEB-INF/ to src/main/resources/META-INF/beans.xml (if not already there) OR delete it since Quarkus has CDI enabled by default
  - Recommended: Delete the file as Quarkus doesn't require beans.xml
- Why: Quarkus JAR packaging expects beans.xml in META-INF, not WEB-INF; alternatively, it's not needed at all
- Depends on: Step 13
- Verify: beans.xml exists in src/main/resources/META-INF/ OR does not exist (preferred)

### Step 44: DELETE web.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete web.xml - not needed for Quarkus JAR packaging
- Why: Quarkus doesn't use web.xml; servlet configuration is done via annotations and application.properties
- Depends on: Step 43
- Verify: File does not exist

### Step 45: Move static web resources
- Phase: Cleanup
- File: src/main/webapp/*
- Action: MODIFY
- What to do: Move all static web resources from src/main/webapp/ to src/main/resources/META-INF/resources/
  - This includes: index.jsp, health.jsp, coolstore.json, keycloak.json, app/, partials/, bower_components/
  - Create directory if it doesn't exist: mkdir -p src/main/resources/META-INF/resources
  - Use: cp -r src/main/webapp/* src/main/resources/META-INF/resources/
- Why: Quarkus serves static resources from META-INF/resources in JAR packaging
- Depends on: Step 44
- Verify: `ls src/main/resources/META-INF/resources/index.jsp`

## Verification

- Build: `./mvnw clean compile`
  - Should complete without errors
  - Verify Quarkus extensions are resolved
  
- Test: No unit tests exist in the project (maven.test.skip=true in original pom.xml)
  - If tests are added later: `./mvnw test`
  
- Run application: `./mvnw quarkus:dev`
  - Application should start on http://localhost:8080
  - Check for no error messages in startup logs
  - Verify Flyway migrations execute successfully
  
- Blackbox verification:
  1. Start PostgreSQL database as per README: 
     ```
     podman run --name myPostgresDb -p 5432:5432 \
       -e POSTGRES_USER=postgresUser \
       -e POSTGRES_PASSWORD=postgresPW \
       -e POSTGRES_DB=postgresDB \
       -d postgres
     ```
  
  2. Start the Quarkus application:
     ```
     ./mvnw quarkus:dev
     ```
  
  3. Test REST endpoints:
     - GET http://localhost:8080/services/products - should return product catalog
     - GET http://localhost:8080/services/cart/123 - should return empty cart
     - POST http://localhost:8080/services/cart/123/329299/1 - should add item to cart
     - POST http://localhost:8080/services/cart/checkout/123 - should checkout and trigger messaging flow
  
  4. Check console logs for:
     - "Message recd !" - confirms OrderServiceMDB received message
     - "received message inventory" - confirms InventoryNotificationMDB received message
     - No errors or exceptions
  
  5. Verify database:
     - Query orders table - should contain the checked-out order
     - Query inventory - quantities should be updated

## Notes

1. **Reactive Messaging Configuration**: The migration uses SmallRye in-memory connector for Reactive Messaging. For production, replace with Kafka or AMQP by:
   - Adding appropriate extension (quarkus-smallrye-reactive-messaging-kafka)
   - Updating application.properties with broker configuration

2. **Session Management**: The @SessionScoped ShoppingCartService relies on HTTP sessions. In cloud-native deployments, consider:
   - Using external session store (Redis)
   - Converting to stateless design with database-backed carts

3. **Audit Logging Library**: The system-scoped dependency (audit-logging-library-1.0.0.jar) is kept as-is. Verify it works in Quarkus; if it has Java EE dependencies, it may need updating.

4. **Keycloak Integration**: The keycloak.json file is migrated with static resources. Quarkus has native Keycloak/OIDC support. Consider migrating to quarkus-oidc extension for better integration.

5. **Native Compilation**: The native profile is added but native compilation may require additional reflection configuration for JPA entities and JSON serialization. Test with `./mvnw package -Pnative` and add reflection hints as needed.

6. **Database Migration**: Flyway is configured to run migrations at startup. The V1_1 and V1_2 SQL scripts should work as-is, but verify sequence creation matches the new @SequenceGenerator configurations in entities.

7. **Transaction Management**: All data-modifying operations now require explicit @Transactional annotations. Review service methods to ensure all EntityManager persist/merge/remove operations are transactional.

8. **JSP Support**: The index.jsp and health.jsp files are migrated to META-INF/resources. Note that Quarkus has limited JSP support. Consider converting to static HTML or using a modern frontend framework.
