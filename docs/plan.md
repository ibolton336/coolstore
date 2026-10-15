# Migration Plan

## Goal
Migrate coolstore-monolith from Java EE 7 on JBoss 7.4 to Quarkus 3 microservices framework.

## Source → Target
Java EE 7 (JBoss 7.4) → Quarkus 3

## Scope
- Files affected: 39
- Estimated complexity: High
- Hardest areas: 
  1. Remote EJB lookups and stateful/stateless session beans
  2. JMS Message-Driven Beans (MDB) migration
  3. WebLogic-specific lifecycle listeners and logging

## Key Decisions Applied
1. **System-scoped JAR dependency**: The audit-logging-library-1.0.0.jar will need to be replaced with a proper Maven dependency or converted to Quarkus extension. For this plan, we'll assume it can be replaced by adding the JAR to a proper local Maven repository with updated coordinates.
2. **Remote EJB calls**: Convert the remote EJB lookup pattern in ShippingService to a direct local CDI injection since the migration is to a monolith-first Quarkus app.
3. **WebLogic stubs**: The weblogic.application and weblogic.i18n.logging packages are custom stubs - they will be removed and replaced with Quarkus lifecycle and logging equivalents.
4. **JMS MDB activation**: Convert @MessageDriven annotations to Quarkus Reactive Messaging or SmallRye Reactive Messaging patterns.

## Approach

**Phase 1: Build Configuration** - Convert pom.xml to Quarkus structure, add Quarkus BOM, update plugins, and configure Quarkus extensions.

**Phase 2: Configuration Files** - Replace Java EE XML descriptors (persistence.xml, beans.xml, web.xml) with Quarkus application.properties and update datasource configuration.

**Phase 3: Models** - Update JPA entities to use Quarkus Panache patterns or keep standard JPA with minor adjustments.

**Phase 4: Persistence Layer** - Convert EntityManager producer pattern to Quarkus CDI injection.

**Phase 5: Services Layer** - Convert EJB annotations (@Stateless, @Stateful, @MessageDriven) to CDI scopes, update remote EJB lookups, and migrate MDB patterns to Quarkus messaging.

**Phase 6: REST API** - Update JAX-RS application configuration and REST endpoints to Quarkus patterns.

**Phase 7: Utilities** - Replace WebLogic lifecycle listeners with Quarkus lifecycle events and update logging patterns.

**Phase 8: Cleanup** - Remove legacy deployment descriptors and WebLogic stub classes.

## Steps

### Step 1: Update pom.xml - Add Quarkus BOM and properties
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do:
    - BEFORE: Java EE 7 dependencies with javaee-web-api and javaee-api
    - AFTER: Quarkus 3 BOM with Quarkus platform dependencies
    - Specific changes:
        1. Update properties: Add quarkus.platform.version (e.g., 3.2.0.Final), quarkus.platform.group-id, update Java version to 17
        2. Add dependencyManagement section with Quarkus BOM
        3. Remove: javaee-web-api, javaee-api dependencies
        4. Add Quarkus extensions: quarkus-hibernate-orm, quarkus-jdbc-postgresql, quarkus-resteasy-reactive-jackson, quarkus-arc (CDI), quarkus-smallrye-reactive-messaging-jms, quarkus-flyway, quarkus-oidc (for Keycloak)
        5. Update maven-compiler-plugin to Java 17
        6. Replace maven-war-plugin with quarkus-maven-plugin
        7. Update audit-logging-library from system scope to proper Maven coordinates or comment out temporarily
- Why: Quarkus requires its own BOM and extensions instead of Java EE APIs
- Depends on: none
- Verify: mvn clean compile succeeds

### Step 2: Create Quarkus application.properties
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create file with Quarkus datasource and JPA configuration:
    ```
    quarkus.datasource.db-kind=postgresql
    quarkus.datasource.username=postgresUser
    quarkus.datasource.password=postgresPW
    quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgresDB
    quarkus.hibernate-orm.database.generation=none
    quarkus.hibernate-orm.sql-load-script=no-file
    quarkus.hibernate-orm.log.sql=false
    quarkus.flyway.migrate-at-start=true
    quarkus.flyway.locations=classpath:db/migration
    quarkus.http.port=8080
    quarkus.oidc.auth-server-url=http://localhost:8081/realms/eap
    quarkus.oidc.client-id=eap-app
    quarkus.oidc.credentials.secret=secret
    ```
- Why: Quarkus uses application.properties for configuration instead of XML files
- Depends on: Step 1
- Verify: File exists with required datasource properties

### Step 3: Update persistence.xml for Quarkus
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: MODIFY
- What to do:
    - Replace jta-data-source with Quarkus default datasource
    - Remove JNDI datasource reference "java:jboss/datasources/CoolstoreDS"
    - Keep persistence-unit name as "primary" but simplify properties
    - Remove hibernate.jdbc.use_get_generated_keys property
- Why: Quarkus manages datasources differently, no JNDI lookups needed
- Depends on: Step 2
- Verify: No JNDI references remain in file

### Step 4: Update CatalogItemEntity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Keep as standard JPA entity, no changes required. Verify imports are javax.persistence.* (will work with Quarkus)
- Why: Standard JPA entities work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 5: Update InventoryEntity
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Keep as standard JPA entity, no changes required
- Why: Standard JPA entities work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 6: Update Order
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Keep as standard JPA entity, no changes required
- Why: Standard JPA entities work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 7: Update OrderItem
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Keep as standard JPA entity if it has JPA annotations, otherwise no changes needed
- Why: Standard JPA entities work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 8: Update Product
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Keep as-is, it's a POJO used for REST serialization
- Why: POJOs work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 9: Update Promotion
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Keep as-is, it's a POJO
- Why: POJOs work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 10: Update ShoppingCart
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Keep as-is, it's a POJO
- Why: POJOs work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 11: Update ShoppingCartItem
- Phase: Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Keep as-is, it's a POJO
- Why: POJOs work in Quarkus without modification
- Depends on: Step 1
- Verify: File compiles with Quarkus dependencies

### Step 12: COMPLEX - Convert Resources EntityManager producer
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete this file entirely
- Why: Quarkus provides EntityManager injection directly without needing a producer class with @PersistenceContext. Services can inject EntityManager using @Inject directly.
- Depends on: Step 1, Step 3
- Verify: File no longer exists

### Step 13: Convert CatalogService from @Stateless to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
    - Remove: import javax.ejb.Stateless and @Stateless annotation
    - Add: import javax.enterprise.context.ApplicationScoped and @ApplicationScoped annotation
    - Keep: @Inject EntityManager (now works directly without Resources producer)
- Why: Quarkus uses CDI scopes instead of EJB annotations
- Depends on: Step 12
- Verify: File compiles, grep -n "@Stateless" returns nothing

### Step 14: Convert OrderService from implicit scope to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
    - Add: import javax.enterprise.context.ApplicationScoped and @ApplicationScoped annotation
    - Ensure EntityManager is injected with @Inject
- Why: Quarkus requires explicit CDI scopes
- Depends on: Step 12
- Verify: File compiles with @ApplicationScoped annotation

### Step 15: Convert ProductService to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
    - Add: @ApplicationScoped annotation
    - Ensure all dependencies use @Inject
- Why: Quarkus requires explicit CDI scopes
- Depends on: Step 12
- Verify: File compiles with @ApplicationScoped annotation

### Step 16: Convert PromoService to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
    - Add: @ApplicationScoped annotation if not already present
- Why: Quarkus requires explicit CDI scopes
- Depends on: Step 1
- Verify: File compiles

### Step 17: COMPLEX - Convert ShippingService from @Stateless @Remote to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
    - BEFORE: @Stateless @Remote EJB with remote interface
    - AFTER: @ApplicationScoped CDI bean implementing same interface locally
    - Specific changes:
        1. Remove: import javax.ejb.Stateless, javax.ejb.Remote, @Stateless, @Remote annotations
        2. Add: import javax.enterprise.context.ApplicationScoped, @ApplicationScoped annotation
        3. Keep: implements ShippingServiceRemote (interface becomes local contract)
- Why: Quarkus doesn't support remote EJBs; convert to local CDI bean injection
- Depends on: Step 1
- Verify: File compiles, no EJB annotations remain

### Step 18: Update ShippingServiceRemote interface
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do: Keep as plain Java interface, no changes needed (remove Remote marker if present)
- Why: Interface becomes a local contract in Quarkus
- Depends on: Step 17
- Verify: Interface is plain Java with no EJB annotations

### Step 19: COMPLEX - Convert ShoppingCartService from @Stateful to @SessionScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
    - BEFORE: @Stateful EJB with remote EJB lookup for ShippingService
    - AFTER: @SessionScoped CDI bean with direct @Inject for ShippingService
    - Specific changes:
        1. Remove: import javax.ejb.Stateful, @Stateful annotation
        2. Add: import javax.enterprise.context.SessionScoped, @SessionScoped annotation
        3. Remove: lookupShippingServiceRemote() method and all its calls
        4. Add: @Inject ShippingServiceRemote shippingService field
        5. Replace: lookupShippingServiceRemote().calculateShipping(sc) with shippingService.calculateShipping(sc)
        6. Replace: lookupShippingServiceRemote().calculateShippingInsurance(sc) with shippingService.calculateShippingInsurance(sc)
        7. Remove: imports for Context, InitialContext, NamingException, Hashtable
- Why: Quarkus doesn't support stateful EJBs or remote EJB lookups; use SessionScoped CDI with direct injection
- Depends on: Step 17
- Verify: No JNDI lookup code remains, grep -n "InitialContext" returns nothing in this file

### Step 20: Convert ShoppingCartOrderProcessor to @ApplicationScoped
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
    - Add: @ApplicationScoped annotation
- Why: Quarkus requires explicit CDI scopes
- Depends on: Step 1
- Verify: File compiles

### Step 21: COMPLEX - Convert OrderServiceMDB from @MessageDriven to Quarkus Messaging
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: @MessageDriven with activationConfig for topic/orders
    - AFTER: @ApplicationScoped with @Incoming reactive messaging
    - Specific changes:
        1. Remove: import javax.ejb.*, @MessageDriven, @ActivationConfigProperty annotations
        2. Remove: implements MessageListener
        3. Add: import javax.enterprise.context.ApplicationScoped, @ApplicationScoped
        4. Add: import org.eclipse.microprofile.reactive.messaging.Incoming, @Incoming("orders")
        5. Change method: onMessage(Message rcvMessage) → process(String orderStr)
        6. Remove: JMS Message handling, directly receive String payload
        7. Update method body: remove msg.getBody(String.class), use orderStr parameter directly
        8. Add: @Transactional if needed for database operations
- Why: Quarkus uses SmallRye Reactive Messaging instead of JMS MDBs
- Depends on: Step 1, Step 14
- Verify: No @MessageDriven annotation, has @Incoming annotation

### Step 22: COMPLEX - Convert or remove InventoryNotificationMDB
- Phase: Services Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
    - BEFORE: Plain MessageListener with manual JNDI lookup for WebLogic JMS
    - AFTER: @ApplicationScoped with @Incoming reactive messaging
    - Specific changes:
        1. Remove: All WebLogic JNDI code (getInitialContext, init, close methods)
        2. Remove: implements MessageListener
        3. Add: @ApplicationScoped annotation
        4. Add: @Incoming("orders") annotation to onMessage method
        5. Change method signature: onMessage(Message rcvMessage) → processInventoryNotification(String orderStr)
        6. Remove: JMS Message unwrapping, directly use String parameter
        7. Remove: All WebLogic-specific constants (JNDI_FACTORY, JMS_FACTORY, TOPIC)
- Why: Quarkus doesn't support WebLogic JNDI or manual JMS setup
- Depends on: Step 1, Step 13
- Verify: No WebLogic imports remain, has @Incoming annotation

### Step 23: Update DataBaseMigrationStartup lifecycle
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
    - Review file content and ensure it works with Quarkus Flyway extension
    - If it uses @Singleton @Startup pattern, keep it
    - If it uses EJB lifecycle, convert to Quarkus @Observes StartupEvent
- Why: Quarkus has its own lifecycle management
- Depends on: Step 1
- Verify: File compiles with Quarkus

### Step 24: Update Producers Logger producer
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
    - Keep the Logger @Produces method as-is
    - Alternatively, can remove this and use @Inject Logger directly (Quarkus provides this)
    - Add @ApplicationScoped or @Dependent scope if not present
- Why: Quarkus provides Logger injection by default, but custom producer works too
- Depends on: Step 1
- Verify: File compiles

### Step 25: COMPLEX - Remove StartupListener and replace with Quarkus lifecycle
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: DELETE
- What to do: Delete this file - its functionality will be replaced by Quarkus lifecycle events if needed
- Why: WebLogic ApplicationLifecycleListener is not supported in Quarkus
- Depends on: none
- Verify: File no longer exists

### Step 26: Create Quarkus lifecycle handler (if needed)
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/StartupHandler.java
- Action: CREATE
- What to do: Create Quarkus lifecycle handler:
    ```java
    package com.redhat.coolstore.utils;
    
    import io.quarkus.runtime.StartupEvent;
    import io.quarkus.runtime.ShutdownEvent;
    import javax.enterprise.context.ApplicationScoped;
    import javax.enterprise.event.Observes;
    import javax.inject.Inject;
    import java.util.logging.Logger;
    
    @ApplicationScoped
    public class StartupHandler {
        
        @Inject
        Logger log;
        
        void onStart(@Observes StartupEvent ev) {
            log.info("Application started");
        }
        
        void onStop(@Observes ShutdownEvent ev) {
            log.info("Application stopped");
        }
    }
    ```
- Why: Replace WebLogic lifecycle with Quarkus lifecycle events
- Depends on: Step 25
- Verify: File exists and compiles

### Step 27: Update Transformers utility
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Review and ensure JSON parsing works with Quarkus. May need to update imports if using different JSON library
- Why: Ensure compatibility with Quarkus JSON handling
- Depends on: Step 1
- Verify: File compiles

### Step 28: Update RestApplication
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do:
    - Keep @ApplicationPath("/services") annotation
    - Can optionally remove "extends Application" as Quarkus auto-discovers JAX-RS resources
    - Or keep as-is, both work in Quarkus
- Why: Quarkus supports standard JAX-RS Application class
- Depends on: Step 1
- Verify: File compiles

### Step 29: Update CartEndpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
    - Keep @SessionScoped, @Path, and all JAX-RS annotations
    - Ensure Serializable is kept for SessionScoped
    - No changes needed unless using Quarkus Reactive
- Why: Standard JAX-RS works in Quarkus
- Depends on: Step 1, Step 19
- Verify: File compiles

### Step 30: Update OrderEndpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do: Keep as-is, standard JAX-RS endpoint works in Quarkus
- Why: Standard JAX-RS works in Quarkus
- Depends on: Step 1
- Verify: File compiles

### Step 31: Update ProductEndpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: Keep as-is, standard JAX-RS endpoint works in Quarkus
- Why: Standard JAX-RS works in Quarkus
- Depends on: Step 1
- Verify: File compiles

### Step 32: Update beans.xml for Quarkus
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: MODIFY
- What to do:
    - Simplify to minimal CDI configuration
    - Change bean-discovery-mode to "annotated" (Quarkus default)
    - Or move to src/main/resources/META-INF/beans.xml
- Why: Quarkus prefers beans.xml in META-INF, and uses different defaults
- Depends on: Step 1
- Verify: beans.xml is valid for Quarkus

### Step 33: Configure Quarkus messaging for JMS
- Phase: Configuration Files
- File: src/main/resources/application.properties
- Action: MODIFY
- What to do: Add SmallRye Reactive Messaging JMS connector configuration:
    ```
    mp.messaging.incoming.orders.connector=smallrye-jms
    mp.messaging.incoming.orders.destination=topic/orders
    mp.messaging.incoming.orders.destination-type=topic
    ```
- Why: Connect @Incoming("orders") methods to JMS topic
- Depends on: Step 2, Step 21, Step 22
- Verify: Configuration includes messaging properties

### Step 34: Remove WebLogic ApplicationLifecycleListener stub
- Phase: Cleanup
- File: weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this WebLogic stub class
- Why: Not needed in Quarkus, replaced by Quarkus lifecycle events
- Depends on: Step 25, Step 26
- Verify: File no longer exists

### Step 35: Remove WebLogic ApplicationLifecycleEvent stub
- Phase: Cleanup
- File: weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this WebLogic stub class
- Why: Not needed in Quarkus
- Depends on: Step 25, Step 26
- Verify: File no longer exists

### Step 36: Remove WebLogic NonCatalogLogger stub
- Phase: Cleanup
- File: weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this WebLogic stub class
- Why: Not needed in Quarkus, use standard Java logging
- Depends on: none
- Verify: File no longer exists

### Step 37: Remove web.xml deployment descriptor
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file - Quarkus doesn't use web.xml
- Why: Quarkus uses application.properties and annotations for configuration
- Depends on: Step 1, Step 2
- Verify: File no longer exists

### Step 38: Move beans.xml to META-INF
- Phase: Configuration Files
- File: src/main/resources/META-INF/beans.xml
- Action: CREATE
- What to do: Move/copy simplified beans.xml from webapp/WEB-INF to resources/META-INF:
    ```xml
    <?xml version="1.0" encoding="UTF-8"?>
    <beans xmlns="http://xmlns.jcp.org/xml/ns/javaee"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
           http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd"
           bean-discovery-mode="annotated">
    </beans>
    ```
- Why: Quarkus expects beans.xml in META-INF, not WEB-INF
- Depends on: Step 32
- Verify: File exists in src/main/resources/META-INF/

### Step 39: Remove old beans.xml from WEB-INF
- Phase: Cleanup
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete old beans.xml after creating new one in META-INF
- Why: Avoid duplicate CDI configuration
- Depends on: Step 38
- Verify: File no longer exists in WEB-INF

## Verification

- Build: `mvn clean compile`
- Test: Tests are currently skipped (maven.test.skip=true), so no automated test verification available. After migration, enable tests and run: `mvn test`
- Blackbox: 
  1. Start PostgreSQL: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start Keycloak on port 8081 with realm configured
  3. Start Quarkus app: `mvn quarkus:dev`
  4. Verify application starts on http://localhost:8080
  5. Access the UI at http://localhost:8080/index.jsp (or Quarkus may serve from different path)
  6. Test key flows:
     - Browse products
     - Add items to cart
     - Checkout order
     - Verify JMS messages are processed (check logs)
  7. Verify database has order data after checkout

## Notes

1. **JMS Broker**: The original app uses JBoss/WildFly embedded JMS. Quarkus requires external JMS broker (e.g., Apache Artemis, ActiveMQ). Add broker configuration to application.properties or use embedded Artemis for testing.

2. **Session State**: The @SessionScoped beans (ShoppingCartService, CartEndpoint) rely on HTTP session state. Ensure Quarkus session handling is configured, especially for distributed deployments.

3. **Flyway Migrations**: The existing Flyway migrations should work as-is with Quarkus Flyway extension. Verify db/migration/V1_1__CreateSchema.sql and V1_2__AddInitialData.sql execute correctly.

4. **Keycloak Integration**: Original app uses keycloak.json. Quarkus OIDC extension uses application.properties. Map the keycloak.json configuration to Quarkus OIDC properties.

5. **Static Content**: The webapp folder contains AngularJS frontend (index.jsp, app/, etc.). Quarkus serves static content from src/main/resources/META-INF/resources/. May need to restructure or configure custom static path.

6. **Audit Library**: The system-scoped audit-logging-library needs proper resolution. Options:
   - Install to local Maven repo: `mvn install:install-file -Dfile=lib/audit-logging-library-1.0.0.jar -DgroupId=com.enterprise -DartifactId=audit-logging-library -Dversion=1.0.0 -Dpackaging=jar`
   - Or exclude if not actually used in code

7. **Remote EJB Removal**: Verify that converting ShippingService from remote to local doesn't break any distributed architecture assumptions. The original lookup suggests possible multi-node deployment.

8. **MDB Topic Subscription**: Both OrderServiceMDB and InventoryNotificationMDB listen to "topic/orders". Ensure Quarkus messaging supports multiple subscribers to same topic (it does with proper configuration).

9. **Reactive vs Imperative**: This plan uses SmallRye Reactive Messaging for JMS. If preferring traditional blocking JMS, consider using Quarkus Qpid JMS extension instead.

10. **Transaction Management**: EJB methods had implicit transaction management. In Quarkus, add @Transactional annotation to service methods that need transactions (already standard in many Quarkus patterns).
