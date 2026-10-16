# Migration Plan

## Goal
Migrate a Java EE 7 monolith application from JBoss EAP 7.4/WebLogic to Quarkus 3

## Source → Target
Java EE 7 (JBoss EAP 7.4/WebLogic) → Quarkus 3 with Jakarta EE

## Scope
- Files affected: 38
- Estimated complexity: High
- Hardest areas: 
  1. InventoryNotificationMDB with WebLogic-specific JNDI/JMS setup
  2. ShoppingCartService with remote EJB lookup pattern
  3. DataBaseMigrationStartup using EJB @Singleton/@Startup annotations
  4. WebLogic-specific lifecycle listeners and compatibility classes

## Key Decisions Applied
1. **EntityManager injection pattern**: Remove the `@Produces` EntityManager pattern in Resources.java (illegal in Quarkus) and use direct `@Inject` for EntityManager across all services
2. **Message-driven beans**: Convert JMS MDBs to Quarkus Reactive Messaging with SmallRye for consistent async messaging
3. **Remote EJB calls**: Replace remote EJB lookup in ShoppingCartService with local CDI injection (since ShippingService will be local in the monolith)
4. **WebLogic compatibility classes**: Remove WebLogic-specific classes (NonCatalogLogger, ApplicationLifecycleListener) and replace with standard Java logging and Quarkus lifecycle events
5. **System dependency**: Convert system-scoped audit-logging-library to a proper Maven dependency or local repository installation

## Approach
This migration follows a dependency-ordered, phased approach:

**Phase 1 - Build Configuration**: Migrate pom.xml from Java EE 7 war to Quarkus 3 with Quarkus Maven plugin, Jakarta EE dependencies, and Quarkus extensions (Hibernate ORM, REST, Messaging, Flyway)

**Phase 2 - Persistence Layer**: Convert persistence.xml to application.properties with Quarkus datasource configuration, remove the illegal `@Produces` EntityManager pattern, and update @PersistenceContext to @Inject

**Phase 3 - Data Models**: Update all JPA entity imports from javax.persistence to jakarta.persistence (8 entity classes)

**Phase 4 - Service Layer**: Convert EJB services to CDI beans by replacing @Stateless/@Stateful with @ApplicationScoped/@RequestScoped, migrate MDBs to Quarkus messaging, fix EntityManager injection, and replace remote EJB lookups with CDI

**Phase 5 - REST API**: Update JAX-RS imports to Jakarta EE, keep @ApplicationPath, update endpoint imports and session management

**Phase 6 - Utilities**: Migrate startup beans, update producer patterns, and handle Flyway initialization

**Phase 7 - WebLogic Cleanup**: Remove WebLogic-specific compatibility classes that are no longer needed

**Phase 8 - Configuration Files**: Remove web.xml and beans.xml (not needed in Quarkus), migrate JMS configuration to application.properties

## Steps

### Step 1: Migrate pom.xml to Quarkus 3
- Phase: Build Configuration
- File: pom.xml
- Action: MODIFY
- What to do: Replace Java EE 7 dependencies and WAR packaging with Quarkus 3 configuration
  - Change packaging from `war` to `jar`
  - Replace `javaee-web-api` and `javaee-api` with Quarkus BOM and extensions
  - Add Quarkus Maven plugin
  - Add extensions: quarkus-hibernate-orm-panache, quarkus-jdbc-postgresql, quarkus-resteasy-reactive-jackson, quarkus-messaging-kafka or quarkus-artemis, quarkus-flyway
  - Update compiler plugin to Java 17 target (or 11 minimum)
  - Replace system-scoped audit-logging-library dependency with proper Maven coordinates or install to local Maven repo
  - Remove maven-war-plugin
- Why: Quarkus uses a different build model than traditional Java EE WAR files
- Depends on: none
- Verify: `mvn clean compile` succeeds without Java EE dependency errors

### Step 2: Create Quarkus application.properties
- Phase: Persistence Layer
- File: src/main/resources/application.properties
- Action: CREATE
- What to do: Create Quarkus configuration file with datasource, Hibernate, and messaging properties
  ```properties
  # Datasource configuration (replacing persistence.xml JNDI datasource)
  quarkus.datasource.db-kind=postgresql
  quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
  quarkus.datasource.username=postgresUser
  quarkus.datasource.password=postgresPW
  
  # Hibernate ORM configuration
  quarkus.hibernate-orm.database.generation=none
  quarkus.hibernate-orm.log.sql=false
  quarkus.hibernate-orm.sql-load-script=no-file
  
  # Flyway configuration
  quarkus.flyway.migrate-at-start=true
  quarkus.flyway.baseline-on-migrate=true
  quarkus.flyway.locations=classpath:db/migration
  
  # Messaging configuration (for JMS replacement)
  mp.messaging.outgoing.orders.connector=smallrye-jms
  mp.messaging.incoming.orders-in.connector=smallrye-jms
  mp.messaging.incoming.orders-in.destination=topic/orders
  
  # HTTP port
  quarkus.http.port=8080
  ```
- Why: Quarkus uses application.properties instead of XML configuration files for datasource and persistence settings
- Depends on: Step 1
- Verify: File exists with required datasource properties

### Step 3: COMPLEX — Remove @Produces EntityManager from Resources.java
- Phase: Persistence Layer
- File: src/main/java/com/redhat/coolstore/persistence/Resources.java
- Action: DELETE
- What to do: Delete this entire file - Quarkus automatically provides EntityManager for injection
- Why: In Quarkus, `@Produces` on EntityManager is illegal. Quarkus creates the EntityManager bean automatically when datasource is configured. Services will inject EntityManager directly with `@Inject`
- Depends on: Step 2
- Verify: File no longer exists

### Step 4: Migrate CatalogItemEntity imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/CatalogItemEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
  - `import javax.persistence.*;` → `import jakarta.persistence.*;`
- Why: Quarkus 3 uses Jakarta EE namespace instead of javax
- Depends on: Step 1
- Verify: No javax.persistence imports remain, file compiles

### Step 5: Migrate InventoryEntity imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/InventoryEntity.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus 3 uses Jakarta EE namespace instead of javax
- Depends on: Step 1
- Verify: No javax.persistence imports remain, file compiles

### Step 6: Migrate Order imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Order.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus 3 uses Jakarta EE namespace instead of javax
- Depends on: Step 1
- Verify: No javax.persistence imports remain, file compiles

### Step 7: Migrate OrderItem imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/OrderItem.java
- Action: MODIFY
- What to do: Replace all `javax.persistence` imports with `jakarta.persistence`
- Why: Quarkus 3 uses Jakarta EE namespace instead of javax
- Depends on: Step 1
- Verify: No javax.persistence imports remain, file compiles

### Step 8: Migrate Product model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Product.java
- Action: MODIFY
- What to do: Replace all `javax` imports with `jakarta` (if any persistence or validation imports present)
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax imports remain (except java.* core packages)

### Step 9: Migrate Promotion model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/Promotion.java
- Action: MODIFY
- What to do: Replace all `javax` imports with `jakarta` (if any persistence or validation imports present)
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax imports remain (except java.* core packages)

### Step 10: Migrate ShoppingCart model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCart.java
- Action: MODIFY
- What to do: Replace all `javax` imports with `jakarta` (if any persistence or validation imports present)
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax imports remain (except java.* core packages)

### Step 11: Migrate ShoppingCartItem model imports
- Phase: Data Models
- File: src/main/java/com/redhat/coolstore/model/ShoppingCartItem.java
- Action: MODIFY
- What to do: Replace all `javax` imports with `jakarta` (if any persistence or validation imports present)
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax imports remain (except java.* core packages)

### Step 12: COMPLEX — Convert CatalogService from @Stateless EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/CatalogService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB with `@Inject EntityManager`
  - AFTER: CDI `@ApplicationScoped` bean with `@Inject EntityManager`
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@Stateless` → `@ApplicationScoped`
    4. Update: `import javax.persistence.*` → `import jakarta.persistence.*`
    5. Update: `import javax.inject.Inject` → `import jakarta.inject.Inject`
- Why: Quarkus doesn't use EJB; CDI beans with @ApplicationScoped provide the same stateless behavior
- Depends on: Step 3, Step 4, Step 5
- Verify: `grep -n "@Stateless" src/main/java/com/redhat/coolstore/service/CatalogService.java` returns nothing

### Step 13: COMPLEX — Convert ProductService from @Stateless EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ProductService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: CDI `@ApplicationScoped` bean
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@Stateless` → `@ApplicationScoped`
    4. Update: `import javax.inject.Inject` → `import jakarta.inject.Inject`
- Why: Quarkus doesn't use EJB; @ApplicationScoped provides stateless behavior
- Depends on: Step 12
- Verify: No @Stateless annotation remains

### Step 14: COMPLEX — Convert OrderService from @Stateless EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB with JPA operations
  - AFTER: CDI `@ApplicationScoped` bean
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;` and `import jakarta.transaction.Transactional;`
    3. Replace: `@Stateless` → `@ApplicationScoped`
    4. Update all imports: `javax.persistence.*` → `jakarta.persistence.*`
    5. Update: `javax.inject.Inject` → `jakarta.inject.Inject`
    6. Add `@Transactional` annotation to methods that persist/update entities
- Why: Quarkus uses CDI with @Transactional for transaction management instead of EJB container transactions
- Depends on: Step 3, Step 6
- Verify: No @Stateless annotation, @Transactional present on persistence methods

### Step 15: COMPLEX — Convert PromoService from @Stateless EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/PromoService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless` EJB
  - AFTER: CDI `@ApplicationScoped` bean
  - Specific changes:
    1. Remove: `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Replace: `@Stateless` → `@ApplicationScoped`
    4. Update: `javax.inject.Inject` → `jakarta.inject.Inject`
- Why: Quarkus doesn't use EJB
- Depends on: Step 1
- Verify: No @Stateless annotation remains

### Step 16: COMPLEX — Convert ShippingService from Remote @Stateless EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateless @Remote` EJB implementing ShippingServiceRemote
  - AFTER: CDI `@ApplicationScoped` bean implementing ShippingServiceRemote interface (keep interface for now)
  - Specific changes:
    1. Remove: `import javax.ejb.Remote;`, `import javax.ejb.Stateless;`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`
    3. Remove: `@Stateless` and `@Remote` annotations
    4. Add: `@ApplicationScoped` annotation
- Why: In Quarkus monolith, remote EJB is not needed - use local CDI injection
- Depends on: Step 1
- Verify: No EJB annotations remain, class is @ApplicationScoped

### Step 17: COMPLEX — Convert ShoppingCartService from @Stateful EJB to CDI
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartService.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Stateful` EJB with remote EJB lookup for ShippingService
  - AFTER: CDI `@SessionScoped` bean with direct CDI injection
  - Specific changes:
    1. Remove: `import javax.ejb.Stateful;`, `import javax.naming.*`, `import java.util.Hashtable;`
    2. Add: `import jakarta.enterprise.context.SessionScoped;`, `import jakarta.inject.Inject;`
    3. Replace: `@Stateful` → `@SessionScoped`
    4. Update: `javax.inject.Inject` → `jakarta.inject.Inject` for existing injections
    5. Remove: `lookupShippingServiceRemote()` method and all its calls
    6. Add: `@Inject ShippingServiceRemote shippingService;` field
    7. Replace: All `lookupShippingServiceRemote().calculateShipping()` calls → `shippingService.calculateShipping()`
    8. Replace: All `lookupShippingServiceRemote().calculateShippingInsurance()` calls → `shippingService.calculateShippingInsurance()`
- Why: Quarkus uses CDI @SessionScoped for stateful behavior; remote EJB lookup is replaced with local CDI injection
- Depends on: Step 16
- Verify: No @Stateful, no JNDI/InitialContext code, ShippingService injected with @Inject

### Step 18: COMPLEX — Convert OrderServiceMDB from @MessageDriven to Quarkus Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/OrderServiceMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: `@MessageDriven` JMS MDB with activationConfig
  - AFTER: CDI bean with `@Incoming` method from MicroProfile Reactive Messaging
  - Specific changes:
    1. Remove: `import javax.ejb.*`, `import javax.jms.*`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.inject.Inject;`
    3. Remove: `@MessageDriven` annotation and activationConfig
    4. Add: `@ApplicationScoped` to class
    5. Replace: `implements MessageListener` with no interface
    6. Replace: `onMessage(Message rcvMessage)` method signature with `@Incoming("orders-in") public void onMessage(String orderStr)`
    7. Remove: JMS message unwrapping code - `orderStr` is directly the String body
    8. Update: All `javax.inject.Inject` → `jakarta.inject.Inject`
- Why: Quarkus uses MicroProfile Reactive Messaging instead of JMS MessageDriven beans
- Depends on: Step 2, Step 12, Step 14
- Verify: No @MessageDriven, no JMS imports, @Incoming annotation present

### Step 19: COMPLEX — Convert InventoryNotificationMDB from WebLogic JMS to Quarkus Messaging
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java
- Action: MODIFY
- What to do:
  - BEFORE: Plain MessageListener with WebLogic-specific JNDI and manual JMS setup
  - AFTER: CDI bean with `@Incoming` method from MicroProfile Reactive Messaging
  - Specific changes:
    1. Remove: All `javax.jms.*`, `javax.naming.*`, `javax.rmi.*` imports
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import org.eclipse.microprofile.reactive.messaging.Incoming;`, `import jakarta.inject.Inject;`
    3. Remove: `implements MessageListener`, all JNDI constants, connection fields
    4. Add: `@ApplicationScoped` to class
    5. Replace: `onMessage(Message rcvMessage)` with `@Incoming("orders-in") public void onMessage(String orderStr)`
    6. Remove: TextMessage unwrapping - receive String directly
    7. Remove: `init()` and `close()` methods - not needed with Quarkus messaging
    8. Remove: `getInitialContext()` method
    9. Update: `import javax.inject.Inject` → `import jakarta.inject.Inject`
- Why: Quarkus Reactive Messaging replaces WebLogic-specific JNDI/JMS configuration with declarative annotations
- Depends on: Step 2, Step 12
- Verify: No WebLogic imports, no JNDI code, @Incoming annotation present

### Step 20: Convert ShoppingCartOrderProcessor service
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShoppingCartOrderProcessor.java
- Action: MODIFY
- What to do:
  - Replace `javax.inject.Inject` → `jakarta.inject.Inject`
  - Replace `javax.enterprise.context.*` → `jakarta.enterprise.context.*` (if present)
  - If it publishes to JMS, replace with `@Outgoing` MicroProfile Reactive Messaging
- Why: Update to Jakarta EE namespace and Quarkus messaging
- Depends on: Step 1
- Verify: No javax imports remain (except java.*)

### Step 21: Migrate RestApplication JAX-RS configuration
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/RestApplication.java
- Action: MODIFY
- What to do: Replace `import javax.ws.rs.*` with `import jakarta.ws.rs.*`
  - Keep `@ApplicationPath("/services")` annotation - it's supported in Quarkus
- Why: Quarkus 3 uses Jakarta EE namespace
- Depends on: Step 1
- Verify: jakarta.ws.rs imports, @ApplicationPath still present

### Step 22: COMPLEX — Migrate CartEndpoint REST endpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/CartEndpoint.java
- Action: MODIFY
- What to do:
  - BEFORE: `@SessionScoped` JAX-RS endpoint with javax imports
  - AFTER: `@SessionScoped` JAX-RS endpoint with jakarta imports
  - Specific changes:
    1. Replace: `import javax.enterprise.context.SessionScoped` → `import jakarta.enterprise.context.SessionScoped`
    2. Replace: `import javax.inject.Inject` → `import jakarta.inject.Inject`
    3. Replace: `import javax.ws.rs.*` → `import jakarta.ws.rs.*`
- Why: Update to Jakarta EE namespace; Quarkus supports JAX-RS endpoints with CDI scopes
- Depends on: Step 17, Step 21
- Verify: All javax imports replaced with jakarta, @SessionScoped present

### Step 23: Migrate ProductEndpoint REST endpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/ProductEndpoint.java
- Action: MODIFY
- What to do: 
  - Replace: `import javax.ws.rs.*` → `import jakarta.ws.rs.*`
  - Replace: `import javax.inject.Inject` → `import jakarta.inject.Inject` (if present)
  - Replace: `import javax.enterprise.context.*` → `import jakarta.enterprise.context.*` (if present)
- Why: Update to Jakarta EE namespace
- Depends on: Step 13, Step 21
- Verify: All javax imports replaced with jakarta

### Step 24: Migrate OrderEndpoint REST endpoint
- Phase: REST API
- File: src/main/java/com/redhat/coolstore/rest/OrderEndpoint.java
- Action: MODIFY
- What to do:
  - Replace: `import javax.ws.rs.*` → `import jakarta.ws.rs.*`
  - Replace: `import javax.inject.Inject` → `import jakarta.inject.Inject` (if present)
  - Replace: `import javax.enterprise.context.*` → `import jakarta.enterprise.context.*` (if present)
- Why: Update to Jakarta EE namespace
- Depends on: Step 14, Step 21
- Verify: All javax imports replaced with jakarta

### Step 25: COMPLEX — Migrate DataBaseMigrationStartup from EJB Singleton to Quarkus Startup
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/DataBaseMigrationStartup.java
- Action: MODIFY
- What to do:
  - BEFORE: `@Singleton @Startup` EJB with `@Resource` DataSource injection and manual Flyway
  - AFTER: CDI bean with `@Observes StartupEvent` or rely on Quarkus Flyway extension
  - Option 1 (Recommended): Delete manual Flyway code, let Quarkus Flyway extension handle migration automatically via application.properties
  - Option 2: Keep custom logic but modernize:
    1. Remove: `import javax.ejb.*`, `import javax.annotation.Resource`
    2. Add: `import jakarta.enterprise.context.ApplicationScoped;`, `import io.quarkus.runtime.StartupEvent;`, `import jakarta.enterprise.event.Observes;`
    3. Remove: `@Singleton @Startup` annotations
    4. Add: `@ApplicationScoped` annotation
    5. Replace: `@Resource(mappedName = "java:jboss/datasources/CoolstoreDS") DataSource dataSource;` with `@Inject AgroalDataSource dataSource;`
    6. Replace: `@PostConstruct private void startup()` with `void onStart(@Observes StartupEvent ev)`
    7. Update: `import javax.inject.Inject` → `import jakarta.inject.Inject`
  - Recommendation: Use Option 1 and let Quarkus Flyway extension handle migrations (already configured in Step 2)
- Why: Quarkus has built-in Flyway support; manual EJB-based initialization is redundant
- Depends on: Step 2
- Verify: Either file simplified to use Quarkus Flyway extension config, or modernized with @Observes StartupEvent

### Step 26: Migrate Producers utility class
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Producers.java
- Action: MODIFY
- What to do:
  - Replace: `import javax.enterprise.inject.Produces` → `import jakarta.enterprise.inject.Produces`
  - Replace: `import javax.enterprise.inject.spi.InjectionPoint` → `import jakarta.enterprise.inject.spi.InjectionPoint`
  - Logger producer is acceptable in Quarkus - keep as is after namespace update
- Why: Update to Jakarta EE namespace; Logger producers are allowed in Quarkus
- Depends on: Step 1
- Verify: All javax.enterprise imports replaced with jakarta.enterprise

### Step 27: Update Transformers utility class
- Phase: Utilities
- File: src/main/java/com/redhat/coolstore/utils/Transformers.java
- Action: MODIFY
- What to do: Update any javax imports to jakarta (if any persistence or enterprise imports present)
- Why: Update to Jakarta EE namespace
- Depends on: Step 1
- Verify: No javax imports remain except java.* core packages

### Step 28: Remove StartupListener WebLogic lifecycle class
- Phase: WebLogic Cleanup
- File: src/main/java/com/redhat/coolstore/utils/StartupListener.java
- Action: DELETE
- What to do: Delete this file - WebLogic ApplicationLifecycleListener is not needed in Quarkus
  - If startup/shutdown logic is needed, implement with Quarkus lifecycle events (@Observes StartupEvent / ShutdownEvent)
- Why: WebLogic-specific API not available in Quarkus; Quarkus has its own lifecycle event system
- Depends on: Step 25
- Verify: File no longer exists

### Step 29: Remove NonCatalogLogger WebLogic compatibility class
- Phase: WebLogic Cleanup
- File: src/main/java/weblogic/i18n/logging/NonCatalogLogger.java
- Action: DELETE
- What to do: Delete this stub class
  - If any code uses this logger, replace with standard java.util.logging.Logger or JBoss Logging
- Why: WebLogic-specific compatibility stub not needed; use standard logging
- Depends on: Step 1
- Verify: File no longer exists, no references remain

### Step 30: Remove ApplicationLifecycleEvent WebLogic class
- Phase: WebLogic Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleEvent.java
- Action: DELETE
- What to do: Delete this file - WebLogic-specific class not needed
- Why: WebLogic API not available in Quarkus
- Depends on: Step 28
- Verify: File no longer exists

### Step 31: Remove ApplicationLifecycleListener WebLogic class
- Phase: WebLogic Cleanup
- File: src/main/java/weblogic/application/ApplicationLifecycleListener.java
- Action: DELETE
- What to do: Delete this file - WebLogic-specific class not needed
- Why: WebLogic API not available in Quarkus
- Depends on: Step 28
- Verify: File no longer exists

### Step 32: Remove ShippingServiceRemote interface (optional cleanup)
- Phase: Service Layer
- File: src/main/java/com/redhat/coolstore/service/ShippingServiceRemote.java
- Action: MODIFY
- What to do: 
  - Option 1: Keep as local interface for service contract (rename to ShippingService interface, update ShippingService class name)
  - Option 2: Remove interface and have ShoppingCartService inject ShippingService class directly
  - Recommendation: Keep interface for better design but it's no longer "Remote"
- Why: Remote EJB interface no longer serves its original purpose in monolithic Quarkus app
- Depends on: Step 16, Step 17
- Verify: Interface exists without @Remote semantics, or removed if not needed

### Step 33: Remove web.xml deployment descriptor
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/web.xml
- Action: DELETE
- What to do: Delete this file - not needed in Quarkus (contains only `<distributable/>` tag)
- Why: Quarkus doesn't use web.xml; configuration is in application.properties
- Depends on: Step 1, Step 2
- Verify: File no longer exists

### Step 34: Remove beans.xml CDI descriptor
- Phase: Configuration Files
- File: src/main/webapp/WEB-INF/beans.xml
- Action: DELETE
- What to do: Delete this file - not needed in Quarkus
- Why: Quarkus has CDI enabled by default with no beans.xml required
- Depends on: Step 1
- Verify: File no longer exists

### Step 35: Remove persistence.xml
- Phase: Configuration Files
- File: src/main/resources/META-INF/persistence.xml
- Action: DELETE
- What to do: Delete this file - replaced by application.properties configuration in Step 2
- Why: Quarkus uses application.properties for datasource and Hibernate configuration
- Depends on: Step 2, all model and service migrations complete
- Verify: File no longer exists, datasource works via application.properties

### Step 36: Migrate database migration SQL scripts (verify paths)
- Phase: Configuration Files
- File: src/main/resources/db/migration/V1_1__CreateSchema.sql
- Action: MODIFY (if needed)
- What to do: Verify Flyway script naming and content is compatible with Quarkus Flyway extension
  - Scripts should remain in `src/main/resources/db/migration/` directory
  - Naming convention `V{version}__{description}.sql` should be maintained
  - Verify SQL syntax is PostgreSQL compatible
- Why: Quarkus Flyway requires proper naming and location
- Depends on: Step 2, Step 25
- Verify: Scripts in correct location, proper naming convention

### Step 37: Verify initial data SQL script
- Phase: Configuration Files
- File: src/main/resources/db/migration/V1_2__AddInitialData.sql
- Action: MODIFY (if needed)
- What to do: Verify script is compatible with Quarkus Flyway migration
  - Verify INSERT statements are PostgreSQL compatible
  - Check that data matches entity structure after Jakarta EE migration
- Why: Ensure data migration works with Quarkus
- Depends on: Step 36
- Verify: Script syntax valid, data compatible with migrated entities

### Step 38: Update keycloak.json or migrate to Quarkus OIDC
- Phase: Configuration Files
- File: src/main/webapp/keycloak.json
- Action: MODIFY
- What to do:
  - Option 1: Keep keycloak.json if using Keycloak adapter
  - Option 2 (Recommended): Migrate to Quarkus OIDC configuration in application.properties
    ```properties
    quarkus.oidc.auth-server-url=http://127.0.0.1:8081/realms/eap
    quarkus.oidc.client-id=<client-id-from-keycloak.json>
    quarkus.oidc.credentials.secret=<client-secret>
    ```
  - Add Quarkus OIDC extension to pom.xml if using Option 2: `quarkus-oidc`
- Why: Quarkus has native OIDC support; can centralize security configuration
- Depends on: Step 1, Step 2
- Verify: Authentication works with Keycloak

## Verification

- Build: `mvn clean package -DskipTests` (or `mvn clean install -DskipTests` if tests are enabled later)
- Test: Tests are currently disabled (`<maven.test.skip>true</maven.test.skip>` in pom.xml). After migration, enable tests and verify with `mvn test`
- Blackbox: 
  1. Start PostgreSQL database: `podman run --name myPostgresDb -p 5432:5432 -e POSTGRES_USER=postgresUser -e POSTGRES_PASSWORD=postgresPW -e POSTGRES_DB=postgresDB -d postgres`
  2. Start Keycloak on port 8081 and configure eap realm (see README.md)
  3. Start Quarkus app: `mvn quarkus:dev` or `java -jar target/quarkus-app/quarkus-run.jar`
  4. Open http://localhost:8080 in browser
  5. Verify catalog loads with products
  6. Sign in with Keycloak user (e.g., user1)
  7. Add items to cart
  8. Complete checkout process
  9. Verify order messages are processed (check application logs for "Message recd" and order processing)
  10. Verify inventory updates after order

## Notes

### Migration Complexity Areas

1. **Message-Driven Beans**: The two MDBs (OrderServiceMDB and InventoryNotificationMDB) require careful migration from JMS to MicroProfile Reactive Messaging. InventoryNotificationMDB is especially complex due to WebLogic-specific JNDI setup. Test messaging thoroughly.

2. **Remote EJB Lookup**: ShoppingCartService uses remote EJB lookup for ShippingService. This becomes local CDI injection in the monolith. If ShippingService were to be extracted as a separate microservice later, this would need REST or messaging communication instead.

3. **EntityManager Producer**: The `@Produces` EntityManager pattern in Resources.java is illegal in Quarkus. All services must be updated to inject EntityManager directly after this file is removed.

4. **WebLogic Dependencies**: The application has WebLogic compatibility classes that must be removed. Any functionality they provided should be replaced with Quarkus equivalents or standard Java.

5. **System Scope Dependency**: The audit-logging-library uses system scope pointing to lib/ directory. This should be installed to local Maven repository or configured as a proper dependency before migration.

6. **Flyway Integration**: Quarkus has built-in Flyway support. The custom DataBaseMigrationStartup class can be replaced with Quarkus configuration, simplifying the codebase.

### Post-Migration Tasks

1. **Enable and fix tests**: Currently tests are skipped. After migration, enable tests and update test dependencies (Arquillian → Quarkus test framework)

2. **Performance tuning**: Review Quarkus build optimization options (native compilation, index generation)

3. **Monitoring**: Add Quarkus health checks, metrics, and observability extensions

4. **Containerization**: Create Dockerfile using Quarkus Docker support for OpenShift deployment

5. **Security review**: Verify Keycloak OIDC integration works correctly after migration

### Gotchas

- **Transaction boundaries**: In EJBs, transactions are automatic. With CDI, add `@Transactional` explicitly where needed (already noted in OrderService step)
- **Session state**: `@SessionScoped` in Quarkus requires session affinity in clustered deployments
- **JMS topic subscription**: MicroProfile Reactive Messaging handles subscriptions differently than JMS MDBs - verify message delivery semantics
- **Flyway baseline**: The original code calls `flyway.baseline()` - ensure Quarkus config includes `quarkus.flyway.baseline-on-migrate=true`
- **JNDI**: Quarkus has limited JNDI support - all JNDI lookups must be replaced with CDI injection
- **JAX-RS context**: Ensure @SessionScoped works correctly with JAX-RS in Quarkus (session management may differ from Java EE containers)
