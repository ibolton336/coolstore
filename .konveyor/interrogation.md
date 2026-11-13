# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)

## D1: Source and target match
- Evidence: The code is a Java EE 7 application (javaee-web-api 7.0 and javaee-api 7.0 in pom.xml, @Stateless/@MessageDriven EJBs in service layer, persistence.xml with JPA 2.1, JMS message-driven beans). The prompt specifies migrating from Java EE to Quarkus 3. The source matches.
- Decision: Proceed with migration from Java EE 7 to Quarkus 3
- Source: code
- Consequence: Migration proceeds as planned

## D2: Java release
- Evidence: The build currently targets Java 1.8 (pom.xml: `<source>1.8</source>` and `<target>1.8</target>`). Quarkus 3 requires minimum Java 17.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release
- Recommendation: A — minimal change, meets Quarkus 3 requirements
- Decision: Open
- Consequence: A changes compiler settings to Java 17, requires Java 17 base images; B changes to Java 21, requires Java 21 base images and potentially more language-level adjustments

## D3: Production database
- Evidence: The application has JPA entities (CatalogItemEntity, Order, InventoryEntity, OrderItem) and a persistence.xml with JNDI data source `java:jboss/datasources/CoolstoreDS`. The README.md shows the application is deployed with PostgreSQL in production (connection to `jdbc:postgresql://127.0.0.1:5432/postgresDB`). The Flyway migrations in src/main/resources/db/migration/ contain the schema DDL and data.
- Decision: PostgreSQL
- Source: code — README.md deployment instructions specify PostgreSQL database setup, JDBC connection URL in documentation
- Consequence: Use quarkus-jdbc-postgresql extension, configure PostgreSQL data source properties, keep H2 for dev/test profiles

## D4: Schema management
- Evidence: The application uses Flyway (dependency `org.flywaydb:flyway-core:4.1.2` in pom.xml). Flyway migration scripts exist at src/main/resources/db/migration/V1_1__CreateSchema.sql and V1_2__AddInitialData.sql. The persistence.xml has `javax.persistence.schema-generation.database.action` set to `none`.
- Decision: Keep Flyway
- Source: code — pom.xml line 33-37, migration files in src/main/resources/db/migration/
- Consequence: Migrate to quarkus-flyway extension, keep existing migration scripts

## D5: Messaging broker
- Evidence: The application has @MessageDriven beans (OrderServiceMDB, InventoryNotificationMDB) using `javax.jms` and listening to `topic/orders`. The README.md shows ActiveMQ Artemis is configured via JBoss EAP 7.4's messaging-activemq subsystem.
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka — only where the organisation is moving to Kafka; delivery and ordering semantics change  C) In-process CDI events, no broker — only when every producer and consumer is inside this application
- Recommendation: A — the README shows the application already uses ActiveMQ/Artemis, keeps existing semantics, both MDBs process messages from the same topic which suggests they're in this application
- Decision: Open
- Consequence: A adds quarkus-artemis-jms and configures AMQP connector; B requires quarkus-kafka and changes message semantics; C removes broker dependency and uses CDI events instead

## D6: View layer: JSP pages
- Evidence: The application has two JSP pages: src/main/webapp/index.jsp (54 lines) and src/main/webapp/health.jsp (1 line). The index.jsp loads an AngularJS client-side application with scripts for app.js, controllers, services, and directives. It renders no server-side content, only the HTML shell. The health.jsp simply returns "1" with no dynamic content.
- Decision: Turn pages into static HTML
- Source: code — index.jsp at src/main/webapp/index.jsp only loads client-side JavaScript libraries and Angular application, performs no server-side rendering beyond session creation; health.jsp outputs static content
- Consequence: Convert index.jsp to static index.html in src/main/resources/META-INF/resources, convert health.jsp to static file or implement as REST endpoint, move webapp assets to META-INF/resources

## D7: Authentication and authorization
- Evidence: The web.xml (src/main/webapp/WEB-INF/web.xml) has no security constraints or login configuration. The application has a keycloak.json file (src/main/webapp/keycloak.json) configuring the "eap" realm with auth-server at http://localhost:8081/. The README.md documents Keycloak v20.0.5 setup and user authentication. No @RolesAllowed or other Java EE security annotations found in the code. Authentication appears to be handled client-side via keycloak.min.js loaded in index.jsp.
- Decision: OpenID Connect against Keycloak
- Source: code — keycloak.json at src/main/webapp/keycloak.json, README.md documents Keycloak integration
- Consequence: Use quarkus-oidc extension, migrate keycloak.json configuration to quarkus.oidc.* properties, client-side authentication may need adjustment for Quarkus serving static content

## D8: Callers of remote EJB interfaces
- Evidence: The ShippingService bean has `@Remote` annotation and implements ShippingServiceRemote interface (src/main/java/com/redhat/coolstore/service/ShippingService.java line 11-12). The build produces a single WAR file with no client artifact (pom.xml shows packaging as war). No evidence of other applications calling this remote interface.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose the same operations as REST endpoints; the callers must change  C) Not known — remove them, and list the operations in the plan's Notes as a risk to confirm before release
- Recommendation: A — the build publishes no client artifact, and the application appears self-contained
- Decision: Open
- Consequence: A removes @Remote and interface; B adds REST resource for calculateShipping and calculateShippingInsurance; C removes but notes risk in plan

## D9: WebLogic application lifecycle listeners
- Evidence: The application has weblogic.* packages in src/main/java/weblogic/ (NonCatalogLogger, ApplicationLifecycleListener, ApplicationLifecycleEvent) and uses them in StartupListener.java (src/main/java/com/redhat/coolstore/utils/StartupListener.java imports weblogic.application.ApplicationLifecycleEvent and weblogic.application.ApplicationLifecycleListener). These are stubs (the weblogic classes in the repo are minimal implementations).
- Decision: Replace with Quarkus lifecycle events
- Source: code — modules/lifecycle.md in the javaee-to-quarkus skill handles server lifecycle listeners
- Consequence: Replace StartupListener with Quarkus @Observes StartupEvent and ShutdownEvent, remove weblogic.* stub packages

## D10: Audit logging library
- Evidence: The OrderService (src/main/java/com/redhat/coolstore/service/OrderService.java) imports and uses com.enterprise.audit.logging.* classes (AuditConfiguration, AuditLoggingException, FileSystemAuditLogger). This is a system-scoped dependency on audit-logging-library-1.0.0.jar in the lib/ directory (pom.xml lines 40-46). The library is used to initialize an audit logger in @PostConstruct.
- Options: A) Replace it with the Quarkus or standard equivalent  B) Keep it as an ordinary dependency — must resolve from a repository and not need application server at runtime  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: B — the library appears to be a simple file-based logger that doesn't require application server APIs. It should work as a regular dependency if published to a Maven repository
- Decision: Open
- Consequence: A would replace with standard logging framework; B changes pom.xml to normal dependency with repository coordinates; C removes audit logging feature from OrderService; D blocks migration until resolved

## D11: WebLogic JNDI initialization in InventoryNotificationMDB
- Evidence: The InventoryNotificationMDB class (src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java) has an init() method that uses weblogic.jndi.WLInitialContextFactory and creates JNDI lookups for JMS resources. However, this MDB is not configured as @MessageDriven (lacks the annotation), so it's not actually deployed as an MDB. It appears to be dead code.
- Options: A) Remove the class — it's not being used  B) Complete its implementation as a proper MDB  C) Leave it out of scope
- Recommendation: A — the class has no @MessageDriven annotation and won't be deployed; OrderServiceMDB already handles the topic/orders messages
- Decision: Open
- Consequence: A removes the file; B adds @MessageDriven and migrates to Quarkus messaging; C leaves the broken code in place

## D12: Modules and packaging
- Evidence: The build is a single WAR module (pom.xml: `<packaging>war</packaging>`). There are no other Maven modules in the repository.
- Decision: One Quarkus application module
- Source: code — pom.xml shows single WAR packaging
- Consequence: Convert the single WAR to a Quarkus application with quarkus-maven-plugin
