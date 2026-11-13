# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)

## D1: Confirm migration from Java EE to Quarkus 3
- Evidence: The code is a Java EE 7 application (pom.xml has javaee-web-api 7.0 and javaee-api 7.0 dependencies, README.md states "Java EE 7 application" for deployment on "JBoss 7.4"). The workflow guide targets Quarkus 3. The code is not already on Quarkus.
- Decision: Confirmed — migrate from Java EE 7 to Quarkus 3
- Source: code
- Consequence: Proceed with the full migration plan

## D2: Java release
- Evidence: pom.xml sets maven.compiler.source and target to 1.8 (Java 8), which is below Java 17.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release
- Recommendation: A — Java 17 minimizes the jump and is sufficient for Quarkus 3
- Decision: B) Java 21 — a later long-term-support release
- Source: human
- Consequence: Compiler settings will target Java 21, requiring language-level compatibility fixes for the larger version jump, and deployment will use Java 21 base images

## D3: Production database
- Evidence: persistence.xml references JNDI data source "java:jboss/datasources/CoolstoreDS" but does not specify a database type. README.md shows PostgreSQL is configured for deployment (connection URL jdbc:postgresql://127.0.0.1:5432/postgresDB). Flyway is already in use (flyway-core 4.1.2 dependency, migration files at src/main/resources/db/migration/V1_1__CreateSchema.sql and V1_2__AddInitialData.sql).
- Decision: PostgreSQL
- Source: code
- Consequence: Use quarkus-jdbc-postgresql extension, configure PostgreSQL data source properties, and keep H2 for dev/test profiles

## D4: Schema management
- Evidence: The application uses JPA entities (@Entity on CatalogItemEntity, Order, InventoryEntity, OrderItem). Flyway is already in use with migration files present. persistence.xml has schema-generation.database.action set to "none", confirming Flyway manages the schema.
- Decision: Keep Flyway
- Source: code
- Consequence: Add quarkus-flyway extension, migrate the existing Flyway migrations and configuration

## D5: Messaging broker
- Evidence: The application has @MessageDriven bean (OrderServiceMDB.java) that listens to "topic/orders" using javax.jms API. README.md shows ActiveMQ Artemis is configured on JBoss EAP (jms-topic add --topic-address=topic.orders).
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka  C) In-process CDI events, no broker
- Recommendation: A — ActiveMQ Artemis matches the current deployment's messaging infrastructure and keeps the same semantics
- Decision: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics
- Source: human
- Consequence: Add quarkus-artemis-jms extension and configure ActiveMQ Artemis broker connection, preserving the existing JMS topic semantics

## D6: View layer
- Evidence: The application has JSP files (src/main/webapp/index.jsp and health.jsp). index.jsp creates a session and loads an Angular.js single-page application; health.jsp content is minimal. The main UI is an Angular.js application with static resources.
- Options: A) Keep the pages server-rendered on Quarkus  B) Rewrite the pages as Qute templates  C) Go API-only — REST endpoints for what the pages do  D) Leave the view layer out of this migration
- Recommendation: A — index.jsp is primarily a loader for the Angular.js SPA, so keeping minimal JSP support via quarkus-undertow is the smallest change. The JSP files are simple and serve mainly as entry points to static content.
- Decision: C) Go API-only — REST endpoints for what the pages do, with the user interface as separate work
- Source: human
- Consequence: Remove JSP files, keep REST endpoints that already exist, and document that the user interface will need to be deployed separately (the Angular.js SPA can be served by a separate web server or migrated to a modern framework)

## D7: Authentication and authorisation
- Evidence: web.xml has no security constraints or login configuration. keycloak.json exists in src/main/webapp/ configuring OpenID Connect to Keycloak realm "eap" at http://localhost:8081/. README.md confirms Keycloak v20.0.5 is the authentication provider. The Angular.js app loads keycloak.min.js for client-side authentication.
- Decision: OpenID Connect with Keycloak
- Source: code
- Consequence: Add quarkus-oidc extension and configure the Keycloak provider settings from keycloak.json

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService.java exposes a @Remote interface (ShippingServiceRemote). The build produces a WAR file with no separate client artifact published (pom.xml shows only the monolith WAR packaging). No grounding data indicates external callers.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose as REST endpoints  C) Not known — remove and list in Notes as risk
- Recommendation: A — single WAR deployment with no client artifact suggests the remote interface is not called externally
- Decision: Open
- Consequence: A removes the @Remote interface; B adds REST endpoint for calculateShipping operations; C removes but documents as risk

## D9: Application-server APIs and vendored libraries
- Evidence: The code imports weblogic.* packages (weblogic.application.ApplicationLifecycleListener in StartupListener.java, weblogic.jndi.WLInitialContextFactory in InventoryNotificationMDB.java). The build depends on a vendored system-scoped JAR: audit-logging-library 1.0.0 at lib/audit-logging-library-1.0.0.jar, used by OrderService.java (imports com.enterprise.audit.logging.*). Additionally, InventoryNotificationMDB.java uses JNDI lookups and weblogic-specific messaging initialization that appear to be dead code (class has no @MessageDriven annotation).
- Options for each:
  - weblogic.application.ApplicationLifecycleListener: A) Replace with Quarkus startup/shutdown observers  B) Keep as dependency  C) Remove the feature  D) Leave out of scope
  - audit-logging-library: A) Replace with standard logging  B) Keep as ordinary dependency  C) Remove the feature  D) Leave out of scope
  - InventoryNotificationMDB weblogic code: A) Remove (appears to be unused dead code)  B) Keep  C) Leave out of scope
- Recommendation: 
  - weblogic.application.ApplicationLifecycleListener: A — replace with @Observes StartupEvent/ShutdownEvent in Quarkus
  - audit-logging-library: Need to know if this library can be used outside WebLogic and if it's required in production
  - InventoryNotificationMDB weblogic code: A — remove the entire InventoryNotificationMDB class as it has no @MessageDriven annotation and appears to be unused code
- Decision: Open (for audit-logging-library)
- Consequence: Lifecycle listener replacement is straightforward. Audit library decision affects whether to add the JAR as a dependency, replace with standard logging, or keep the audit functionality. InventoryNotificationMDB removal is code cleanup.

## D10: Modules and packaging
- Evidence: pom.xml shows a single module WAR project (packaging: war, single artifactId: monolith).
- Decision: Single Quarkus application module
- Source: code
- Consequence: The WAR becomes one Quarkus application with no build structure changes needed
