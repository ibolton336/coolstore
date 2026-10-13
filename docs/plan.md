# Migration Plan

## Goal
Migrate a Java EE 7 monolith application from JBoss EAP 7.4 to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4) → Quarkus 3 (Jakarta EE with Quarkus extensions)

## Scope
- Files affected: 35 (30 Java files, 1 POM, 2 XML configs, 2 files to delete)
- Estimated complexity: High
- Hardest areas: 
  1. JMS message-driven beans → Reactive Messaging with Smallrye
  2. EJB stateless beans → CDI with @Transactional
  3. JNDI lookups → CDI injection

## Key Decisions Applied
1. **Reactive Messaging**: Replace JMS @MessageDriven with Smallrye Reactive Messaging @Incoming/@Outgoing channels instead of attempting to use legacy JMS APIs
2. **EntityManager injection**: Replace @PersistenceContext with @Inject and remove the Resources producer class per Quarkus best practices
3. **Datasource configuration**: Move persistence.xml configuration to application.properties (Quarkus standard)
4. **Logger production**: Remove @Produces Logger pattern and use direct injection per Quarkus CDI
5. **WebLogic stubs**: Delete WebLogic-specific stub classes as they're not needed in Quarkus
6. **Packaging**: Change from WAR to JAR (Quarkus default)
7. **Hibernate sequence names**: Add explicit @SequenceGenerator names for @GeneratedValue annotations to avoid Hibernate 6 implicit naming issues

## Approach
1. **Phase 1 - Build Configuration**: Transform pom.xml to use Quarkus BOM, plugins, and dependencies; change packaging from WAR to JAR
2. **Phase 2 - Configuration Files**: Convert persistence.xml to application.properties; update beans.xml to Jakarta namespaces; delete web.xml
3. **Phase 3 - Model Layer**: Update entity imports from javax to jakarta; add explicit sequence generators for Hibernate 6 compatibility
4. **Phase 4 - Persistence Layer**: Convert @PersistenceContext to @Inject; remove Resources producer class; delete @Produces for EntityManager
5. **Phase 5 - Service Layer**: Replace @Stateless with CDI; add @Transactional where needed; convert MDBs to Reactive Messaging; remove JNDI lookups
6. **Phase 6 - REST Layer**: Update REST imports from javax to jakarta; remove @ApplicationPath activation (automatic in Quarkus)
7. **Phase 7 - Utilities**: Update CDI imports; remove Logger @Produces; convert EJB @Singleton to CDI; update lifecycle annotations
8. **Phase 8 - Cleanup**: Delete WebLogic stub classes; delete web.xml

## Steps

### Step 1: Update pom.xml packaging type
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>`
- Why: Quarkus applications are packaged as JAR files, not WAR files
- Depends on: none
- Verify: grep '<packaging>jar</packaging>' pom.xml returns match

### Step 2: Replace Java EE BOM with Quarkus BOM
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: 
  - Add Quarkus version property: `<quarkus.platform.version>3.6.4</quarkus.platform.version>` in properties section
  - Add dependencyManagement section before dependencies:
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
- Why: Quarkus uses a BOM to manage all extension versions consistently
- Depends on: Step 1
- Verify: grep 'quarkus-bom' pom.xml returns match

### Step 3: Replace Java EE dependencies with Quarkus extensions
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Replace existing dependencies with Quarkus extensions:
  - Remove: javax javaee-web-api, javax javaee-api, jboss-jms-api_2.0_spec, jboss-rmi-api_1.0_spec
  - Add:
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
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-reactive-messaging-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-flyway</artifactId>
    </dependency>
    ```
  - Keep: flyway-core with updated version 9.22.3
  - Keep: audit-logging-library dependency as-is
- Why: Quarkus uses its own extensions that provide Jakarta EE APIs plus additional functionality
- Depends on: Step 2
- Verify: grep 'quarkus-hibernate-orm-panache' pom.xml && grep 'quarkus-resteasy-reactive-jackson' pom.xml

### Step 4: Update Maven compiler plugin configuration
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Update maven-compiler-plugin version to 3.11.0 and set source/target to 17
- Why: Quarkus 3 requires Java 17 as minimum version
- Depends on: Step 3
- Verify: grep '<version>3.11.0</version>' pom.xml | grep -A5 'maven-compiler-plugin'

### Step 5: Add Quarkus Maven plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add quarkus-maven-plugin after maven-compiler-plugin:
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
- Why: Required for Quarkus build and dev mode functionality
- Depends on: Step 4
- Verify: grep 'quarkus-maven-plugin' pom.xml

### Step 6: Remove maven-war-plugin
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Delete the maven-war-plugin section completely
- Why: Not needed for JAR packaging
- Depends on: Step 5
- Verify: ! grep 'maven-war-plugin' pom.xml

### Step 7: Add Maven Surefire plugin configuration
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add surefire plugin configuration:
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
- Why: Quarkus requires specific system properties for testing
- Depends on: Step 6
- Verify: grep 'maven-surefire-plugin' pom.xml

### Step 8: Add native build profile
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
- Why: Enables building native executables with GraalVM
- Depends on: Step 7
- Verify: grep 'quarkus.package.type' pom.xml

### Step 9: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with datasource and persistence configuration:
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
  quarkus.hibernate-orm.jdbc.statement-fetch-size=10
  quarkus.hibernate-orm.jdbc.statement-batch-size=10
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  quarkus.flyway.baseline-on-migrate=true
  quarkus.flyway.locations=classpath:db/migration
  
  # Kafka/Reactive Messaging configuration for order processing
  mp.messaging.outgoing.orders.connector=smallrye-kafka
  mp.messaging.outgoing.orders.topic=orders
  mp.messaging.outgoing.orders.value.serializer=org.apache.kafka.common.serialization.StringSerializer
  
  mp.messaging.incoming.orders-in.connector=smallrye-kafka
  mp.messaging.incoming.orders-in.topic=orders
  mp.messaging.incoming.orders-in.value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
  mp.messaging.incoming.orders-in.auto.offset.reset=earliest
  
  # Application configuration
  quarkus.http.port=8080
  quarkus.http.test-port=8081
  ```
- Why: Quarkus uses application.properties instead of persistence.xml for configuration
- Depends on: Step 8
- Verify: test -f src/main/resources/application.properties

### Step 10: Update beans.xml to Jakarta namespaces
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do: Replace namespace and schema:
  - BEFORE: `xmlns="http://xmlns.jcp.org/xml/ns/javaee"` and `http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd`
  - AFTER: `xmlns="https://jakarta.ee/xml/ns/jakartaee"` and `https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd`
  - Change version in beans tag: `version="3.0"`
- Why: Jakarta EE uses different XML namespaces than Java EE
- Depends on: Step 9
- Verify: grep 'jakarta.ee' src/main/webapp/WEB-INF/beans.xml

### Step 11: Update Order entity imports
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 10
- Verify: grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/Order.java && ! grep 'import javax.persistence' src/main/java/com/redhat/coolstore/model/Order.java

### Step 12: COMPLEX - Add explicit sequence generator to Order entity
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Id @GeneratedValue private long orderId;`
  - AFTER: 
    ```java
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", allocationSize = 1)
    private long orderId;
    ```
  - Add import: `import jakarta.persistence.GenerationType;` and `import jakarta.persistence.SequenceGenerator;`
- Why: Hibernate 6 changed implicit sequence name determination; explicit names avoid migration issues
- Depends on: Step 11
- Verify: grep '@SequenceGenerator' src/main/java/com/redhat/coolstore/model/Order.java

### Step 13: Update OrderItem entity imports
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 10
- Verify: grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/OrderItem.java && ! grep 'import javax.persistence' src/main/java/com/redhat/coolstore/model/OrderItem.java

### Step 14: COMPLEX - Add explicit sequence generator to OrderItem entity
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Id @GeneratedValue private long id;`
  - AFTER:
    ```java
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orderitem_seq")
    @SequenceGenerator(name = "orderitem_seq", sequenceName = "orderitem_sequence", allocationSize = 1)
    private long id;
    ```
  - Add import: `import jakarta.persistence.GenerationType;` and `import jakarta.persistence.SequenceGenerator;`
- Why: Hibernate 6 changed implicit sequence name determination; explicit names avoid migration issues
- Depends on: Step 13
- Verify: grep '@SequenceGenerator' src/main/java/com/redhat/coolstore/model/OrderItem.java

### Step 15: Update InventoryEntity imports
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 10
- Verify: grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/InventoryEntity.java

### Step 16: Update CatalogItemEntity imports
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 10
- Verify: grep 'import jakarta.persistence' src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java

### Step 17: Update ShoppingCart imports
- Phase: Model Layer
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all `javax.xml.bind.annotation` imports with `jakarta.xml.bind.annotation`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 10
- Verify: grep 'import jakarta.xml.bind.annotation' src/main/java/com/redhat/coolstore/model/ShoppingCart.java

### Step 18: COMPLEX - Replace @PersistenceContext with @Inject in CatalogService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - Remove: `@Stateless` annotation and `import javax.ejb.Stateless;`
  - Replace: `import javax.inject.Inject;` with `import jakarta.inject.Inject;`
  - Replace: `import javax.persistence.EntityManager;` with `import jakarta.persistence.EntityManager;`
  - Replace: `import javax.persistence.criteria.*` with `import jakarta.persistence.criteria.*;`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`
  - Add: `import jakarta.transaction.Transactional;`
  - Add: `@ApplicationScoped` annotation to class
  - Add: `@Transactional` annotation to `updateInventoryItems` method
- Why: Quarkus uses CDI instead of EJB; EntityManager operations need @Transactional; @Inject is standard for EntityManager in Quarkus
- Depends on: Step 11, Step 12, Step 13, Step 14, Step 15, Step 16
- Verify: grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/CatalogService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/CatalogService.java

### Step 19: COMPLEX - Replace @PersistenceContext with @Inject in OrderService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - Remove: `@Stateless` annotation and `import javax.ejb.Stateless;`
  - Replace: all `javax.` imports with `jakarta.` equivalents
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`
  - Add: `import jakarta.transaction.Transactional;`
  - Add: `@ApplicationScoped` annotation to class
  - Add: `@Transactional` annotation to `save` method
- Why: Quarkus uses CDI instead of EJB; persist operations require @Transactional
- Depends on: Step 18
- Verify: grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/OrderService.java && grep '@Transactional' src/main/java/com/redhat/coolstore/service/OrderService.java

### Step 20: Update ProductService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - Remove: `@Stateless` annotation
  - Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
  - Replace: `javax.persistence.*` with `jakarta.persistence.*`
  - Add: `import jakarta.enterprise.context.ApplicationScoped;`
  - Add: `@ApplicationScoped` annotation to class
- Why: Quarkus uses CDI instead of EJB
- Depends on: Step 19
- Verify: grep '@ApplicationScoped' src/main/java/com/redhat/coolstore/service/ProductService.java && ! grep 'javax.' src/main/java/com/redhat/coolstore/service/ProductService.java

### Step 21: Update PromoService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do: Replace `javax.enterprise.context.ApplicationScoped` with `jakarta.enterprise.context.ApplicationScoped`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 20
- Verify: grep 'import jakarta.enterprise.context.ApplicationScoped' src/main/java/com/redhat/coolstore/service/PromoService.java

### Step 22: COMPLEX - Convert OrderServiceMDB to Reactive Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: @MessageDriven with JMS MessageListener pattern
  - AFTER: CDI bean with @Incoming Reactive Messaging
  - Specific changes:
    1. Remove: `@MessageDriven` annotation and all `@ActivationConfigProperty`
    2. Remove: `implements MessageListener`
    3. Remove: all `javax.ejb.*` and `javax.jms.*` imports
    4. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    5. Add: `import jakarta.inject.Inject;`
    6. Add: `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    7. Add: `@ApplicationScoped` to class
    8. Replace: `public void onMessage(Message rcvMessage)` signature with:
       ```java
       @Incoming("orders-in")
       public void processOrder(String orderStr)
       ```
    9. Remove: TextMessage extraction logic - just use the String parameter directly
    10. Remove: try-catch for JMSException
  - Full method body becomes:
    ```java
    @Incoming("orders-in")
    public void processOrder(String orderStr) {
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
- Why: @MessageDriven EJBs are not supported in Quarkus; Smallrye Reactive Messaging provides the replacement pattern
- Depends on: Step 19
- Verify: grep '@Incoming' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java && ! grep '@MessageDriven' src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java

### Step 23: COMPLEX - Convert InventoryNotificationMDB to Reactive Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Manual JNDI lookup and JMS subscription
  - AFTER: CDI bean with @Incoming Reactive Messaging
  - Specific changes:
    1. Remove: all `javax.jms.*`, `javax.naming.*`, `javax.rmi.*` imports
    2. Remove: `implements MessageListener`
    3. Remove: all JNDI and JMS connection fields (JNDI_FACTORY, JMS_FACTORY, TOPIC, tcon, tsession, tsubscriber)
    4. Remove: `init()`, `close()`, and `getInitialContext()` methods
    5. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    6. Add: `import jakarta.inject.Inject;`
    7. Add: `import org.eclipse.microprofile.reactive.messaging.Incoming;`
    8. Add: `@ApplicationScoped` to class
    9. Replace: `public void onMessage(Message rcvMessage)` with:
       ```java
       @Incoming("orders-in")
       public void checkInventory(String orderStr)
       ```
    10. Simplify method body - remove TextMessage casting, just use String parameter
    11. Remove: try-catch for JMSException
- Why: JNDI lookups and manual JMS subscriptions are not supported in Quarkus; use Reactive Messaging
- Depends on: Step 22
- Verify: grep '@Incoming' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java && ! grep 'InitialContext' src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java

### Step 24: COMPLEX - Convert ShoppingCartOrderProcessor JMS to Reactive Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - BEFORE: @Stateless with @Resource JMS Topic and JMSContext
  - AFTER: @ApplicationScoped with @Channel Emitter
  - Specific changes:
    1. Remove: `@Stateless` annotation and `import javax.ejb.Stateless;`
    2. Remove: `@Resource` import and annotation
    3. Remove: `private transient JMSContext context;` and `private Topic ordersTopic;` fields
    4. Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
    5. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    6. Add: `import org.eclipse.microprofile.reactive.messaging.Channel;`
    7. Add: `import org.eclipse.microprofile.reactive.messaging.Emitter;`
    8. Add: `@ApplicationScoped` to class
    9. Add field: `@Inject @Channel("orders") Emitter<String> ordersEmitter;`
    10. Replace method body:
        - BEFORE: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));`
        - AFTER: `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: JMS Topics must be replaced with Reactive Messaging Emitters in Quarkus
- Depends on: Step 23
- Verify: grep '@Channel' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java && grep 'Emitter' src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java

### Step 25: COMPLEX - Update ShoppingCartService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - Remove: `@Stateful` annotation
  - Replace: all `javax.` imports with `jakarta.` equivalents
  - Add: `import jakarta.enterprise.context.SessionScoped;`
  - Add: `@SessionScoped` to class (replaces @Stateful for session state)
  - Add: `@Transactional` to `checkOutShoppingCart` method
- Why: @Stateful EJBs are replaced with @SessionScoped CDI beans in Quarkus; database operations need @Transactional
- Depends on: Step 24
- Verify: grep '@SessionScoped' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java && ! grep '@Stateful' src/main/java/com/redhat/coolstore/service/ShoppingCartService.java

### Step 26: Update ShippingService
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - Replace: all `javax.` imports with `jakarta.` equivalents
  - Change: `@Dependent` remains the same (already correct scope)
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 25
- Verify: grep 'import jakarta.' src/main/java/com/redhat/coolstore/service/ShippingService.java && ! grep 'import javax.' src/main/java/com/redhat/coolstore/service/ShippingService.java

### Step 27: Update ShippingServiceRemote
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do:
  - Remove: `@Remote` annotation and `import javax.ejb.Remote;`
  - Add: Comment noting this interface is for local use only in Quarkus (remote EJB not supported)
- Why: Remote EJBs are not supported in Quarkus
- Depends on: Step 26
- Verify: ! grep '@Remote' src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java

### Step 28: Update RestApplication
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
  - Remove: `extends Application` and the entire class can be deleted, but per one-file-per-step rule, just comment it out
  - Replace: `javax.ws.rs.*` with `jakarta.ws.rs.*`
  - Add comment: "JAX-RS activation is automatic in Quarkus, but keeping class for explicit path configuration"
- Why: JAX-RS activation is automatic in Quarkus but @ApplicationPath still works for setting the base path
- Depends on: Step 27
- Verify: grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/RestApplication.java

### Step 29: Update CartEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - Replace: all `javax.ws.rs.*` imports with `jakarta.ws.rs.*`
  - Replace: `javax.enterprise.context.SessionScoped` with `jakarta.enterprise.context.SessionScoped`
  - Replace: `javax.inject.Inject` with `jakarta.inject.Inject`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 28
- Verify: grep 'import jakarta.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java && ! grep 'import javax.ws.rs' src/main/java/com/redhat/coolstore/rest/CartEndpoint.java

### Step 30: Update OrderEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Replace: all `javax.` imports with `jakarta.` equivalents
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 29
- Verify: grep 'import jakarta.' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java && ! grep 'import javax.' src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java

### Step 31: Update ProductEndpoint
- Phase: REST Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do:
  - Replace: all `javax.` imports with `jakarta.` equivalents
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 30
- Verify: grep 'import jakarta.' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java && ! grep 'import javax.' src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java

### Step 32: COMPLEX - Convert DataBaseMigrationStartup from EJB to CDI
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - BEFORE: @Singleton @Startup EJB with @Resource datasource injection
  - AFTER: CDI bean with @Inject datasource and Quarkus startup event
  - Specific changes:
    1. Remove: `@Singleton`, `@Startup`, `@TransactionManagement` annotations
    2. Remove: `import javax.ejb.*;` and `import javax.annotation.Resource;`
    3. Remove: `@Resource(mappedName = "java:jboss/datasources/CoolstoreDS")` annotation
    4. Replace: `javax.` imports with `jakarta.` equivalents
    5. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    6. Add: `import jakarta.enterprise.event.Observes;`
    7. Add: `import io.quarkus.runtime.StartupEvent;`
    8. Add: `@ApplicationScoped` to class
    9. Change: `@Inject DataSource dataSource;` (remove mappedName)
    10. Replace: `@PostConstruct private void startup()` with `void onStart(@Observes StartupEvent ev)`
    11. Add comment noting Quarkus Flyway extension handles migration automatically via config
- Why: EJB @Singleton/@Startup not supported; Quarkus uses CDI with startup events; datasource injection is simplified
- Depends on: Step 31
- Verify: grep '@Observes StartupEvent' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java && ! grep '@Singleton' src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java

### Step 33: COMPLEX - Remove Logger producer from Producers class
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - BEFORE: @Produces Logger with InjectionPoint
  - AFTER: Delete the @Produces method and related imports
  - Specific changes:
    1. Replace: `javax.enterprise.inject.Produces` with `jakarta.enterprise.inject.Produces`
    2. Replace: `javax.enterprise.inject.spi.InjectionPoint` with `jakarta.enterprise.inject.spi.InjectionPoint`
    3. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    4. Add: `@ApplicationScoped` annotation to class
    5. Add comment: "Custom Logger production not needed in Quarkus - use io.quarkus.logging.Log or inject Logger directly"
    6. Keep class but mark @Produces method as deprecated or remove it entirely
  - Note: All Logger injections throughout the app will work without this producer in Quarkus
- Why: Quarkus provides built-in Logger injection; custom @Produces for Logger is not needed
- Depends on: Step 32
- Verify: grep 'import jakarta.enterprise' src/main/java/com/redhat/coolstore/utils/Producers.java

### Step 34: Update StartupListener
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
  - Replace: `javax.enterprise.context.ApplicationScoped` with `jakarta.enterprise.context.ApplicationScoped`
  - Replace: `javax.enterprise.event.Observes` with `jakarta.enterprise.event.Observes`
  - Note: Class implements weblogic.application.ApplicationLifecycleListener which will be deleted later
  - Add TODO comment: "Remove WebLogic lifecycle listener after testing"
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 33
- Verify: grep 'import jakarta.enterprise' src/main/java/com/redhat/coolstore/utils/StartupListener.java

### Step 35: Update Transformers
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do:
  - Replace: `javax.json.*` imports with `jakarta.json.*`
- Why: Jakarta EE renamed the javax package to jakarta
- Depends on: Step 34
- Verify: grep 'import jakarta.json' src/main/java/com/redhat/coolstore/utils/Transformers.java

### Step 36: Delete persistence.xml
- Phase: Cleanup
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file - configuration moved to application.properties
- Why: Quarkus uses application.properties for datasource and Hibernate configuration
- Depends on: Step 9, Step 35
- Verify: ! test -f src/main/resources/META-INF/persistence.xml

### Step 37: Delete Resources producer class
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete this file completely - @PersistenceContext replaced with @Inject throughout
- Why: EntityManager can be injected directly with @Inject in Quarkus; @Produces EntityManager pattern not needed
- Depends on: Step 18, Step 19, Step 20, Step 36
- Verify: ! test -f src/main/java/com/redhat/coolstore/persistence/Resources.java

### Step 38: Delete web.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file - not needed for Quarkus JAR packaging
- Why: Quarkus does not use web.xml deployment descriptors
- Depends on: Step 37
- Verify: ! test -f src/main/webapp/WEB-INF/web.xml

### Step 39: Delete WebLogic ApplicationLifecycleEvent
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this WebLogic stub class completely
- Why: WebLogic-specific APIs not needed in Quarkus
- Depends on: Step 38
- Verify: ! test -f src/main/java/weblogic/application/ApplicationLifecycleEvent.java

### Step 40: Delete WebLogic ApplicationLifecycleListener
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this WebLogic stub class completely
- Why: WebLogic-specific APIs not needed in Quarkus
- Depends on: Step 39
- Verify: ! test -f src/main/java/weblogic/application/ApplicationLifecycleListener.java

### Step 41: Delete WebLogic NonCatalogLogger
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this WebLogic stub class completely
- Why: WebLogic-specific APIs not needed in Quarkus
- Depends on: Step 40
- Verify: ! test -f src/main/java/weblogic/i18n/logging/NonCatalogLogger.java

## Verification

### Build
```bash
mvn clean compile
```
Expected: Successful compilation with no errors

### Test
```bash
mvn test
```
Note: Tests may need to be updated separately if they exist. The source indicates `<maven.test.skip>true</maven.test.skip>` so tests are currently skipped.

### Dev Mode
```bash
mvn quarkus:dev
```
Expected: Application starts successfully on port 8080

### Blackbox Testing

Per the README, the application requires external services to run fully:

1. **Start PostgreSQL database:**
   ```bash
   podman run --name myPostgresDb \
      -p 5432:5432 \
      -e POSTGRES_USER=postgresUser \
      -e POSTGRES_PASSWORD=postgresPW \
      -e POSTGRES_DB=postgresDB \
      -d postgres
   ```

2. **Start Kafka (replaces JMS):**
   ```bash
   podman run -d --name kafka \
      -p 9092:9092 \
      -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
      -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092 \
      -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
      quay.io/strimzi/kafka:latest-kafka-3.6.0
   ```
   
   Note: May need to start Zookeeper first or use a Kafka image with embedded Zookeeper

3. **Start the Quarkus application:**
   ```bash
   mvn quarkus:dev
   ```

4. **Access the application:**
   - Navigate to http://127.0.0.1:8080
   - The UI should load the coolstore interface

5. **Test key business flows:**
   - Browse products
   - Add items to cart
   - Complete checkout process
   - Verify order processing messages appear in logs
   - Verify inventory notification processing occurs

6. **Verify reactive messaging:**
   - Check application logs for "Message recd !" and order processing output
   - Confirm database has order records after checkout

Note: Keycloak integration mentioned in README may need separate configuration update from OIDC setup, but that's outside core migration scope.

## Notes

### Critical Considerations

1. **JMS to Kafka Migration**: The most significant architectural change is replacing JMS Topics with Kafka via Smallrye Reactive Messaging. The original app used:
   - `topic/orders` for order processing
   - Two message-driven beans subscribing to this topic
   - JMSContext for publishing
   
   The new implementation uses:
   - Kafka topic `orders` 
   - @Incoming("orders-in") for consuming
   - @Channel("orders") Emitter for publishing
   - Both MDBs become regular CDI beans with @Incoming methods

2. **Transactional Boundaries**: Quarkus requires explicit @Transactional annotations for:
   - `OrderService.save()` - persists orders
   - `CatalogService.updateInventoryItems()` - updates inventory
   - `ShoppingCartService.checkOutShoppingCart()` - checkout flow
   
   The original EJB container provided automatic transaction management.

3. **Session State**: ShoppingCartService was @Stateful EJB for per-user cart state. In Quarkus:
   - Changed to @SessionScoped CDI bean
   - HTTP session provides the state container
   - Requires servlet session support (provided by quarkus-undertow if needed)

4. **Audit Logging Library**: The system dependency on `audit-logging-library-1.0.0.jar` remains as-is. Verify this library is compatible with Jakarta EE (not javax). If not, may need to:
   - Upgrade to version 2.0.0 (also present in lib/)
   - Or update pom.xml to use version 2.0.0
   - Or refactor OrderService to remove this dependency

5. **Flyway Version**: Updated from 4.1.2 to 9.22.3 for Quarkus compatibility. Verify migration scripts in `src/main/resources/db/migration/` are compatible.

6. **Native Compilation**: The audit-logging-library system dependency may cause issues with native builds. Consider adding reflection configuration if pursuing native executables.

### Testing Strategy

After migration:
1. Test each REST endpoint individually (Product, Cart, Order)
2. Test message flow: checkout → orders channel → order processing
3. Test inventory notifications trigger correctly
4. Verify database persistence and Flyway migrations
5. Load test to ensure session scoped carts work under concurrent users

### Rollback Points

If issues occur during execution:
- After Step 8: Build config complete, can test pom.xml changes
- After Step 10: Config files updated, can verify property loading
- After Step 17: Models migrated, can test entity persistence
- After Step 27: Services migrated, can test business logic
- After Step 31: REST layer done, can test endpoints
- After Step 35: Utilities updated, ready for cleanup
- After Step 41: Migration complete

### Known Limitations

1. Remote EJB calls (ShippingServiceRemote) - interface kept but remote calling not supported
2. WebLogic-specific features completely removed
3. JMS replaced with Kafka - requires Kafka infrastructure
4. Session replication across cluster may work differently than JBoss EAP HA mode
