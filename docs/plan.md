# Migration Plan

## Goal
Migrate a Java EE 7 monolith application from JBoss EAP 7.4 to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4 / WebLogic) → Quarkus 3.x with Jakarta EE 10

## Scope
- Files affected: 43
- Estimated complexity: High
- Hardest areas: 
  - Message-Driven Beans (MDB) to Reactive Messaging conversion
  - JNDI lookups replacement with CDI injection
  - Remote EJB elimination
  - WebLogic-specific classes removal

## Key Decisions Applied
1. **MDB to Reactive Messaging**: Replaced JMS MessageDriven beans with SmallRye Reactive Messaging using `@Incoming` annotation and Emitter pattern for producers. This is the standard Quarkus pattern for asynchronous messaging.

2. **Remote EJB Pattern**: The ShippingService uses Remote EJB interface pattern. Decision: Convert to local CDI bean with `@ApplicationScoped` since Quarkus doesn't support Remote EJBs and the application will be a single deployment unit.

3. **Stateful EJB Scope**: ShoppingCartService is `@Stateful` - converted to `@SessionScoped` CDI bean to maintain per-user session state as this aligns with the shopping cart use case.

4. **WebLogic Stub Classes**: The weblogic.application package contains stub classes for compatibility. Decision: Remove entirely and replace usage (StartupListener) with Quarkus lifecycle events using `@Observes StartupEvent`.

5. **Database Configuration**: Moved from JNDI datasource in persistence.xml to Quarkus application.properties using quarkus.datasource properties.

6. **Packaging**: Changed from WAR to JAR packaging as Quarkus applications are packaged as runnable JARs.

## Approach
**Phase 1: Build Configuration** - Update pom.xml with Quarkus BOM, Maven plugins, and replace Java EE dependencies with Quarkus extensions for JAX-RS, CDI, Hibernate ORM, JDBC, and reactive messaging.

**Phase 2: Configuration Files** - Create Quarkus application.properties with datasource, Hibernate, and messaging configuration. Update XML descriptors (persistence.xml, beans.xml) to Jakarta EE namespaces or remove where unnecessary.

**Phase 3: Persistence Layer** - Replace `@PersistenceContext` with `@Inject` for EntityManager, remove producer methods, update all javax.persistence imports to jakarta.persistence.

**Phase 4: Model/Entity Classes** - Update all model classes from javax.persistence to jakarta.persistence imports. No structural changes needed as JPA entity model is compatible.

**Phase 5: Service Layer - Simple EJBs** - Convert `@Stateless` EJBs to `@ApplicationScoped` CDI beans, add `@Transactional` annotations to methods requiring transactions, update all javax imports to jakarta.

**Phase 6: Service Layer - Complex (Stateful, MDB, Messaging)** - Convert Stateful EJB to SessionScoped, transform MessageDriven beans to Reactive Messaging consumers, replace JMS Topic producer with Emitter, eliminate JNDI lookups.

**Phase 7: REST/API Layer** - Remove JAX-RS Application subclass (optional in Quarkus), update all REST endpoint imports from javax.ws.rs to jakarta.ws.rs.

**Phase 8: Utility Classes** - Update Startup bean from WebLogic ApplicationLifecycleListener to Quarkus StartupEvent, update Producers and other utilities.

**Phase 9: Cleanup** - Delete WebLogic stub classes, beans.xml (not required in Quarkus), update persistence.xml or remove if fully migrated to properties, and delete web.xml if present.

## Steps

### Step 1: Update pom.xml packaging to jar
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Change `<packaging>war</packaging>` to `<packaging>jar</packaging>`
- Why: Quarkus applications are packaged as executable JARs, not WARs
- Depends on: none
- Verify: Packaging element shows jar

### Step 2: Add Quarkus BOM to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add to properties section: `<quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>`, `<quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>`, `<quarkus.platform.version>3.8.0</quarkus.platform.version>`, `<compiler-plugin.version>3.11.0</compiler-plugin.version>`, `<maven.compiler.release>17</maven.compiler.release>`, `<surefire-plugin.version>3.0.0</surefire-plugin.version>`. Add dependencyManagement section with Quarkus BOM import
- Why: Centralizes Quarkus dependency versions and enables Quarkus extensions
- Depends on: Step 1
- Verify: Properties and dependencyManagement sections present

### Step 3: Replace Java EE dependencies with Quarkus extensions in pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Remove dependencies: javax javaee-web-api, javax javaee-api, jboss-jms-api_2.0_spec, jboss-rmi-api_1.0_spec. Add Quarkus extensions (without version): quarkus-resteasy-reactive-jackson, quarkus-hibernate-orm, quarkus-jdbc-postgresql, quarkus-smallrye-reactive-messaging-amqp, quarkus-flyway, quarkus-arc, quarkus-oidc. Keep audit-logging-library dependency as-is.
- Why: Quarkus provides modern replacements for Java EE APIs
- Depends on: Step 2
- Verify: Only Quarkus dependencies present (except audit library)

### Step 4: Add Quarkus Maven plugin to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add to build/plugins: quarkus-maven-plugin with groupId `${quarkus.platform.group-id}`, artifactId `quarkus-maven-plugin`, version `${quarkus.platform.version}`, extensions true, executions with build, generate-code, and generate-code-tests goals
- Why: Required for Quarkus application builds and dev mode
- Depends on: Step 2
- Verify: Plugin section contains quarkus-maven-plugin

### Step 5: Update Maven Compiler plugin in pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Update maven-compiler-plugin to version `${compiler-plugin.version}`, add configuration with compilerArgs containing `-parameters` arg, update source/target to use `${maven.compiler.release}`
- Why: Quarkus requires parameter names for CDI injection
- Depends on: Step 2
- Verify: Compiler plugin has -parameters flag

### Step 6: Add Maven Surefire plugin configuration to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Update maven-surefire-plugin to version `${surefire-plugin.version}`, add systemPropertyVariables with `java.util.logging.manager=org.jboss.logmanager.LogManager` and `maven.home=${maven.home}`
- Why: Required for Quarkus testing framework
- Depends on: Step 2
- Verify: Surefire plugin has system properties

### Step 7: Add Maven Failsafe plugin to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add maven-failsafe-plugin with version `${surefire-plugin.version}`, executions for integration-test and verify goals, systemPropertyVariables with native.image.path, java.util.logging.manager, and maven.home
- Why: Supports Quarkus integration tests
- Depends on: Step 2
- Verify: Failsafe plugin present with correct goals

### Step 8: Add native build profile to pom.xml
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Add profile with id=native, activation on property name=native, properties skipITs=false and quarkus.package.type=native
- Why: Enables Quarkus native compilation with GraalVM
- Depends on: Step 2
- Verify: Native profile exists

### Step 9: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with datasource configuration (quarkus.datasource.db-kind=postgresql, jdbc.url, username, password using same values from README), hibernate-orm configuration (database.generation=none, log.sql=false), and AMQP connector configuration for reactive messaging (mp.messaging.incoming.orders.connector=smallrye-amqp, mp.messaging.outgoing.orders.connector=smallrye-amqp, amqp-host, amqp-port)
- Why: Quarkus uses application.properties for centralized configuration instead of XML descriptors
- Depends on: none
- Verify: File exists with datasource and messaging properties

### Step 10: Update persistence.xml namespace to Jakarta EE
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do: Replace `http://xmlns.jcp.org/xml/ns/persistence` with `https://jakarta.ee/xml/ns/persistence`, replace `persistence_2_1.xsd` with `persistence_3_0.xsd`, update version attribute to `3.0`, replace property names prefixed with `javax.persistence` to `jakarta.persistence`, remove jta-data-source element
- Why: Quarkus uses Jakarta EE 10 namespaces and datasource is configured in application.properties
- Depends on: Step 9
- Verify: No javax namespaces remain, version is 3.0

### Step 11: Update beans.xml namespace to Jakarta EE
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do: Replace `http://xmlns.jcp.org/xml/ns/javaee` with `https://jakarta.ee/xml/ns/jakartaee`, replace `beans_1_1.xsd` with `beans_3_0.xsd`, update version attribute to `3.0`
- Why: Quarkus uses Jakarta EE 10 namespaces (though beans.xml content is largely ignored in Quarkus)
- Depends on: none
- Verify: No javax namespaces remain, version is 3.0

### Step 12: Update EntityManager injection in Resources.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: MODIFY
- What to do: Replace `import javax.persistence` with `import jakarta.persistence`, replace `@PersistenceContext` with `@Inject` on EntityManager field, remove `@Produces` annotation and remove getEntityManager() producer method, change class annotation from `@Dependent` to remove it entirely
- Why: Quarkus injects EntityManager directly via CDI; producer methods are not needed
- Depends on: none
- Verify: Uses @Inject, no @PersistenceContext or @Produces

### Step 13: Update imports in CatalogItemEntity.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 14: Update imports in InventoryEntity.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 15: Update imports in Order.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 16: Update imports in OrderItem.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 17: Update imports in Product.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 18: Update imports in Promotion.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 19: Update imports in ShoppingCart.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 20: Update imports in ShoppingCartItem.java
- Phase: Model/Entity Classes
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace all `import javax.persistence.*` with `import jakarta.persistence.*`
- Why: Jakarta EE 10 moved persistence API from javax to jakarta namespace
- Depends on: none
- Verify: No javax.persistence imports remain

### Step 21: Migrate CatalogService to Quarkus CDI
- Phase: Service Layer - Simple EJBs
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do: Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`, replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace `import javax.persistence.*` with `import jakarta.persistence.*`, replace `@Stateless` annotation with `@ApplicationScoped`, add `import jakarta.transaction.Transactional` and add `@Transactional` annotation to the class
- Why: Quarkus uses CDI beans instead of EJBs; @ApplicationScoped provides singleton behavior; @Transactional enables JTA transactions
- Depends on: Step 12
- Verify: No @Stateless, has @ApplicationScoped and @Transactional

### Step 22: Migrate ProductService to Quarkus CDI
- Phase: Service Layer - Simple EJBs
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do: Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`, replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace `import javax.persistence.EntityManager` with `import jakarta.persistence.EntityManager`, replace `@Stateless` annotation with `@ApplicationScoped`, add `import jakarta.transaction.Transactional` and add `@Transactional` annotation to the class
- Why: Quarkus uses CDI beans instead of EJBs; @ApplicationScoped provides singleton behavior; @Transactional enables JTA transactions
- Depends on: Step 12
- Verify: No @Stateless, has @ApplicationScoped and @Transactional

### Step 23: Migrate OrderService to Quarkus CDI
- Phase: Service Layer - Simple EJBs
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do: Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`, replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace `import javax.persistence.EntityManager` with `import jakarta.persistence.EntityManager`, replace `@Stateless` annotation with `@ApplicationScoped`, add `import jakarta.transaction.Transactional` and add `@Transactional` annotation to the class
- Why: Quarkus uses CDI beans instead of EJBs; @ApplicationScoped provides singleton behavior; @Transactional enables JTA transactions
- Depends on: Step 12
- Verify: No @Stateless, has @ApplicationScoped and @Transactional

### Step 24: Migrate ShippingService to Quarkus CDI and remove Remote EJB
- Phase: Service Layer - Simple EJBs
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do: Replace `import javax.ejb.Stateless` with `import jakarta.enterprise.context.ApplicationScoped`, replace `import javax.ejb.Remote` with removing the import, replace `@Stateless` annotation with `@ApplicationScoped`, remove `@Remote(ShippingServiceRemote.class)` annotation, add `import jakarta.transaction.Transactional` and add `@Transactional` annotation to the class
- Why: Quarkus does not support Remote EJBs; convert to local CDI bean since application is single deployment
- Depends on: Step 12
- Verify: No @Stateless or @Remote, has @ApplicationScoped and @Transactional

### Step 25: Migrate PromoService to Quarkus CDI
- Phase: Service Layer - Simple EJBs
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do: Replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, add `import jakarta.enterprise.context.ApplicationScoped` and add `@ApplicationScoped` annotation to the class (if not already present)
- Why: Ensure CDI scope annotation for proper bean discovery in Quarkus
- Depends on: none
- Verify: Has @ApplicationScoped or another scope annotation

### Step 26: COMPLEX - Migrate ShoppingCartOrderProcessor from EJB to CDI with Reactive Messaging
- Phase: Service Layer - Complex (Stateful, MDB, Messaging)
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateless` EJB with JMS `@Resource Topic` and `JMSContext`
    - AFTER: `@ApplicationScoped` CDI bean with SmallRye Reactive Messaging `Emitter`
    - Specific changes:
        1. Remove: `import javax.ejb.Stateless`, `import javax.annotation.Resource`, `import javax.jms.JMSContext`, `import javax.jms.Topic`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import org.eclipse.microprofile.reactive.messaging.Channel`, `import org.eclipse.microprofile.reactive.messaging.Emitter`
        3. Replace: `import javax.inject.Inject` with `import jakarta.inject.Inject`
        4. Replace: `@Stateless` with `@ApplicationScoped`
        5. Remove: `@Resource(lookup = "java:/topic/orders") private Topic ordersTopic;` field and `private transient JMSContext context;` field
        6. Add: `@Inject @Channel("orders") Emitter<String> ordersEmitter;` field
        7. Replace: `context.createProducer().send(ordersTopic, Transformers.shoppingCartToJson(cart));` with `ordersEmitter.send(Transformers.shoppingCartToJson(cart));`
- Why: Quarkus uses SmallRye Reactive Messaging instead of JMS; Emitter replaces Topic producer
- Depends on: Step 9
- Verify: No JMS imports, uses Emitter with @Channel annotation

### Step 27: COMPLEX - Migrate ShoppingCartService from Stateful EJB to SessionScoped CDI
- Phase: Service Layer - Complex (Stateful, MDB, Messaging)
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE: `@Stateful` EJB with JNDI lookup for Remote EJB
    - AFTER: `@SessionScoped` CDI bean with direct injection
    - Specific changes:
        1. Remove: `import javax.ejb.Stateful`, `import javax.naming.Context`, `import javax.naming.InitialContext`, `import javax.naming.NamingException`, `import java.util.Hashtable`
        2. Add: `import jakarta.enterprise.context.SessionScoped`
        3. Replace: `import javax.inject.Inject` with `import jakarta.inject.Inject`
        4. Replace: `@Stateful` with `@SessionScoped`
        5. Remove: entire `lookupShippingServiceRemote()` static method
        6. Add: `@Inject ShippingService shippingService;` field
        7. Replace: all calls to `lookupShippingServiceRemote().calculateShipping(sc)` with `shippingService.calculateShipping(sc)` and `lookupShippingServiceRemote().calculateShippingInsurance(sc)` with `shippingService.calculateShippingInsurance(sc)`
- Why: Quarkus doesn't support Stateful EJBs - SessionScoped CDI bean maintains per-user state; JNDI lookups replaced with CDI injection
- Depends on: Step 24
- Verify: No @Stateful, uses @SessionScoped, no JNDI code, injects ShippingService

### Step 28: COMPLEX - Convert OrderServiceMDB from MessageDriven to Reactive Messaging Consumer
- Phase: Service Layer - Complex (Stateful, MDB, Messaging)
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: `@MessageDriven` EJB implementing `MessageListener` with JMS
    - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` method
    - Specific changes:
        1. Remove: `import javax.ejb.ActivationConfigProperty`, `import javax.ejb.MessageDriven`, `import javax.jms.*`, `implements MessageListener`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import org.eclipse.microprofile.reactive.messaging.Incoming`
        3. Replace: `import javax.inject.Inject` with `import jakarta.inject.Inject`
        4. Remove: `@MessageDriven` annotation with all activationConfig properties
        5. Add: `@ApplicationScoped` annotation to class
        6. Replace: `public void onMessage(Message rcvMessage)` signature with `@Incoming("orders") public void onMessage(String orderStr)`
        7. Simplify method body: remove TextMessage casting and JMSException handling, directly use orderStr parameter instead of `msg.getBody(String.class)`
- Why: Quarkus doesn't support MessageDriven beans; SmallRye Reactive Messaging @Incoming replaces MDB pattern
- Depends on: Step 9, Step 21, Step 23
- Verify: No @MessageDriven, has @ApplicationScoped and @Incoming("orders"), no JMS imports

### Step 29: COMPLEX - Convert InventoryNotificationMDB from JMS to Reactive Messaging
- Phase: Service Layer - Complex (Stateful, MDB, Messaging)
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: MessageListener with WebLogic JNDI and JMS setup
    - AFTER: `@ApplicationScoped` CDI bean with `@Incoming` method
    - Specific changes:
        1. Remove: `import javax.jms.*`, `import javax.naming.*`, `import javax.rmi.PortableRemoteObject`, `import java.util.Hashtable`, `implements MessageListener`
        2. Add: `import jakarta.enterprise.context.ApplicationScoped`, `import org.eclipse.microprofile.reactive.messaging.Incoming`
        3. Replace: `import javax.inject.Inject` with `import jakarta.inject.Inject`
        4. Add: `@ApplicationScoped` annotation to class
        5. Remove: all JNDI constant fields, all JMS connection fields
        6. Replace: `public void onMessage(Message rcvMessage)` signature with `@Incoming("orders") public void onMessage(String orderStr)`
        7. Simplify method body: remove TextMessage casting and JMSException handling, directly use orderStr parameter
        8. Remove: `init()`, `close()`, and `getInitialContext()` methods entirely
- Why: Quarkus doesn't support JMS or JNDI; SmallRye Reactive Messaging @Incoming replaces listener pattern
- Depends on: Step 9, Step 21
- Verify: No JMS or JNDI imports, has @ApplicationScoped and @Incoming("orders")

### Step 30: Update imports in CartEndpoint.java
- Phase: REST/API Layer
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do: Replace `import javax.enterprise.context.SessionScoped` with `import jakarta.enterprise.context.SessionScoped`, replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace all `import javax.ws.rs.*` with `import jakarta.ws.rs.*`
- Why: Jakarta EE 10 moved JAX-RS and CDI APIs from javax to jakarta namespace
- Depends on: none
- Verify: No javax.ws.rs or javax.enterprise imports remain

### Step 31: Update imports in OrderEndpoint.java
- Phase: REST/API Layer
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace all `import javax.ws.rs.*` with `import jakarta.ws.rs.*`
- Why: Jakarta EE 10 moved JAX-RS and CDI APIs from javax to jakarta namespace
- Depends on: none
- Verify: No javax.ws.rs imports remain

### Step 32: Update imports in ProductEndpoint.java
- Phase: REST/API Layer
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace all `import javax.ws.rs.*` with `import jakarta.ws.rs.*`
- Why: Jakarta EE 10 moved JAX-RS and CDI APIs from javax to jakarta namespace
- Depends on: none
- Verify: No javax.ws.rs imports remain

### Step 33: Update RestApplication.java
- Phase: REST/API Layer
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do: Replace `import javax.ws.rs.ApplicationPath` with `import jakarta.ws.rs.ApplicationPath`, replace `import javax.ws.rs.core.Application` with `import jakarta.ws.rs.core.Application`. Optionally add comment that this class is not required in Quarkus but kept for explicit path configuration
- Why: Jakarta EE 10 moved JAX-RS API from javax to jakarta namespace; JAX-RS Application subclass is optional in Quarkus
- Depends on: none
- Verify: No javax.ws.rs imports remain

### Step 34: COMPLEX - Migrate StartupListener from WebLogic to Quarkus lifecycle
- Phase: Utility Classes
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: MODIFY
- What to do:
    - BEFORE: Extends `weblogic.application.ApplicationLifecycleListener`
    - AFTER: Observes Quarkus `StartupEvent`
    - Specific changes:
        1. Remove: `import weblogic.application.*`, `extends ApplicationLifecycleListener`
        2. Add: `import io.quarkus.runtime.StartupEvent`, `import jakarta.enterprise.context.ApplicationScoped`, `import jakarta.enterprise.event.Observes`
        3. Add: `@ApplicationScoped` annotation to class
        4. Replace: `public void preStart(ApplicationLifecycleEvent evt)` with `public void onStart(@Observes StartupEvent event)`
        5. Remove: other lifecycle methods (postStart, postStop, preStop) if present
- Why: WebLogic-specific lifecycle API not available in Quarkus; use Quarkus StartupEvent pattern
- Depends on: none
- Verify: No weblogic imports, uses @Observes StartupEvent

### Step 35: Update Producers.java imports
- Phase: Utility Classes
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do: Replace `import javax.enterprise.inject.Produces` with `import jakarta.enterprise.inject.Produces`, replace `import javax.enterprise.context.ApplicationScoped` with `import jakarta.enterprise.context.ApplicationScoped`. Add note that `@Produces` can be removed from methods if they have scope annotations per Quarkus CDI optimization
- Why: Jakarta EE 10 moved CDI APIs from javax to jakarta namespace
- Depends on: none
- Verify: No javax.enterprise imports remain

### Step 36: Update DataBaseMigrationStartup.java
- Phase: Utility Classes
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do: Replace `import javax.annotation.PostConstruct` with `import jakarta.annotation.PostConstruct`, replace `import javax.ejb.Singleton` with `import jakarta.enterprise.context.ApplicationScoped`, replace `import javax.ejb.Startup` with `import io.quarkus.runtime.Startup`, replace `import javax.inject.Inject` with `import jakarta.inject.Inject`, replace `@Singleton` with `@ApplicationScoped`, ensure `@Startup` annotation remains (Quarkus version), add `import jakarta.transaction.Transactional` and add `@Transactional` to methods that perform database operations
- Why: Replace EJB Singleton with CDI ApplicationScoped; Quarkus @Startup triggers initialization; @Transactional needed for DB operations
- Depends on: none
- Verify: No @Singleton, has @ApplicationScoped, @Startup, and @Transactional

### Step 37: Update Transformers.java imports
- Phase: Utility Classes
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Replace any `import javax.json.*` with `import jakarta.json.*` (if JSON-P is used), or consider migrating to Jackson (com.fasterxml.jackson) for JSON processing which is more common in Quarkus
- Why: Jakarta EE 10 moved JSON-P from javax to jakarta namespace
- Depends on: none
- Verify: No javax.json imports remain

### Step 38: Delete ApplicationLifecycleEvent.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file - no longer needed as WebLogic stub
- Why: WebLogic-specific class not used in Quarkus
- Depends on: Step 34
- Verify: File no longer exists

### Step 39: Delete ApplicationLifecycleListener.java
- Phase: Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file - no longer needed as WebLogic stub
- Why: WebLogic-specific class not used in Quarkus
- Depends on: Step 34
- Verify: File no longer exists

### Step 40: Delete NonCatalogLogger.java
- Phase: Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this file - no longer needed as WebLogic stub
- Why: WebLogic-specific logging class not used in Quarkus
- Depends on: none
- Verify: File no longer exists

### Step 41: Delete ShippingServiceRemote.java interface
- Phase: Cleanup
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: DELETE
- What to do: Delete this file - Remote EJB interface no longer needed
- Why: Quarkus doesn't support Remote EJBs; all services are local CDI beans
- Depends on: Step 24, Step 27
- Verify: File no longer exists

### Step 42: Delete or update beans.xml
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file - beans.xml is optional in Quarkus and its content is largely ignored
- Why: Quarkus enables CDI by default with bean discovery mode; explicit beans.xml not required
- Depends on: Step 11
- Verify: File no longer exists

### Step 43: Remove or simplify persistence.xml
- Phase: Cleanup
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do: Since datasource and Hibernate properties are now in application.properties, you can either: (1) Delete this file entirely, OR (2) Keep minimal persistence.xml with just `<persistence-unit name="primary">` and no properties, as Quarkus can work without it when using default configuration
- Why: Quarkus prefers application.properties for configuration; persistence.xml is optional
- Depends on: Step 9, Step 10
- Verify: File either deleted or contains minimal content

## Verification

- Build: `mvn clean compile`
- Test: Tests are disabled in original pom.xml (`maven.test.skip=true`), but after migration run `mvn test` if tests are added
- Blackbox: 
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start AMQP broker (e.g., Apache Artemis or RabbitMQ) configured for queues/topics
  3. Start Keycloak on port 8081 and import realm-export.json, create user
  4. Start Quarkus app: `mvn quarkus:dev`
  5. Navigate to `http://localhost:8080`
  6. Sign in with Keycloak user credentials
  7. Add items to cart and complete checkout
  8. Verify in logs that reactive messaging processes order messages
  9. Verify inventory quantities are updated in database
  10. Confirm no exceptions in application logs

## Notes

1. **Reactive Messaging**: The migration moves from JMS Topics to AMQP-based reactive messaging. You'll need an AMQP broker (Apache Artemis, RabbitMQ, etc.) running. Configure broker details in application.properties.

2. **Session State**: ShoppingCartService maintains session state. Ensure `quarkus-undertow` extension is included if using @SessionScoped in a non-reactive REST application, or consider moving to stateless pattern with external session storage for cloud-native deployment.

3. **Database Schema**: The application uses Flyway for migrations. Ensure database scripts in `src/main/resources/db/migration/` are compatible with your target PostgreSQL version.

4. **Hibernate 6**: Quarkus 3 uses Hibernate 6, which changed sequence generation defaults. Review the `@GeneratedValue` strategy in entity classes (particularly OrderItem.java per analysis) and ensure sequences exist or adjust strategy.

5. **Audit Logging Library**: The system-scoped dependency on audit-logging-library-1.0.0.jar needs review. System-scoped dependencies don't work well in Quarkus. Consider: (1) install to local Maven repo, (2) deploy to Maven repository manager, or (3) include as project dependency with proper scope.

6. **Keycloak Integration**: The original app uses Keycloak. Ensure quarkus-oidc extension is configured correctly in application.properties with Keycloak server URL, realm, and client details from keycloak.json.

7. **Port Configuration**: Original runs on 8080. Quarkus defaults to 8080 as well, but ensure no conflicts with Keycloak on 8081.

8. **Development Mode**: Quarkus provides live reload with `mvn quarkus:dev` - use this during migration testing for rapid feedback.

9. **WebLogic Artifacts**: Three stub classes under weblogic package were created for compatibility with original WebLogic deployment. These are fully removed as Quarkus provides its own lifecycle and logging mechanisms.

10. **Message Processing**: The application uses topic-based messaging where multiple consumers (OrderServiceMDB and InventoryNotificationMDB) process the same order message. Ensure AMQP connector is configured for topic/fanout pattern, not point-to-point queue.
