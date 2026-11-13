# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)

## D1: Source framework matches prompt
- Evidence: The code is a Java EE 7 application (pom.xml declares javaee-web-api 7.0 and javaee-api 7.0), matching the task to migrate from Java EE to Quarkus 3. The application uses EJBs (@Stateless, @Stateful, @MessageDriven in multiple service files), JPA (persistence.xml), JAX-RS (@Path in CartEndpoint), and JMS (javax.jms imports), all Java EE technologies.
- Decision: Proceed with migration from Java EE to Quarkus 3
- Source: code
- Consequence: Migration proceeds as specified

## D2: Java release
- Evidence: pom.xml sets maven.compiler.source=1.8 and maven.compiler.target=1.8. Quarkus 3 requires Java 17 minimum.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release, and a larger jump for the code and for the images that run it
- Recommendation: A — smallest necessary change, meets Quarkus 3 requirements
- Decision: Open
- Consequence: A changes compiler settings to Java 17 and requires Java 17 base images; B changes to Java 21 and requires Java 21 base images with potential additional language-level changes

## D3: Production database
- Evidence: README.md deployment instructions configure PostgreSQL database with connection URL jdbc:postgresql://127.0.0.1:5432/postgresDB. The persistence.xml references JNDI data source java:jboss/datasources/CoolstoreDS which the README configures as PostgreSQL. DataBaseMigrationStartup.java uses this data source with Flyway migrations in src/main/resources/db/migration/.
- Decision: PostgreSQL
- Source: code
- Consequence: Plan targets PostgreSQL with quarkus-jdbc-postgresql extension and configures data source properties for PostgreSQL

## D4: Schema management
- Evidence: Flyway is already in use (pom.xml declares flyway-core 4.1.2, DataBaseMigrationStartup.java runs Flyway migrations at startup, migration scripts exist in src/main/resources/db/migration/). persistence.xml has schema-generation.database.action=none.
- Decision: Keep Flyway
- Source: code
- Consequence: Plan retains Flyway with quarkus-flyway extension, keeps existing migrations

## D5: Messaging broker
- Evidence: The application has @MessageDriven beans (OrderServiceMDB.java, InventoryNotificationMDB.java) and uses javax.jms. README shows configuration of JMS topic topic/orders on ActiveMQ Artemis (messaging-activemq subsystem in JBoss EAP).
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka — only where the organisation is moving its messaging to Kafka; delivery and ordering semantics change  C) In-process CDI events, no broker — only when every producer and consumer of a destination is inside this application and messages need not survive a restart
- Recommendation: A — README shows ActiveMQ Artemis is the current broker, both producer (ShoppingCartOrderProcessor) and consumers (OrderServiceMDB, InventoryNotificationMDB) are in this application, but messages should survive restarts for order processing
- Decision: Open
- Consequence: A configures quarkus-artemis-jms with AMQP 1.0 connector; B requires quarkus-smallrye-reactive-messaging-kafka with semantic changes; C removes broker dependency and uses CDI events

## D6: View layer - JSP pages
- Evidence: The application has two JSP pages: index.jsp (54 lines) loads an AngularJS client-side application with no server-side rendering—it only starts a session and includes JavaScript files; health.jsp (1 line) returns static content "1". Static resources are in src/main/webapp/app/, src/main/webapp/bower_components/, and src/main/webapp/partials/.
- Decision: A — Turn the pages into static HTML
- Source: code
- Consequence: index.jsp becomes index.html in src/main/resources/META-INF/resources/, health.jsp becomes health.html or a simple health endpoint, static resources move to src/main/resources/META-INF/resources/

## D7: Authentication and authorisation
- Evidence: src/main/webapp/keycloak.json exists, README.md includes Keycloak setup instructions (Keycloak v20.0.5, realm-export.json for "eap" realm), index.jsp loads keycloak.min.js. No security constraints in web.xml, but Keycloak client-side integration is present.
- Decision: A — OpenID Connect against Keycloak
- Source: code
- Consequence: Plan adds quarkus-oidc extension and configures Keycloak provider settings from keycloak.json

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService.java has @Remote annotation and implements ShippingServiceRemote interface. The build creates a WAR (not a client JAR), and there's no evidence in the repository of other applications calling this remote interface. All usage appears internal.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose the same operations as REST endpoints; the callers must change  C) Not known — remove them, and list the operations in the plan's Notes as a risk to confirm before release
- Recommendation: A — the build publishes no client artifact, all service calls are within this monolith
- Decision: Open
- Consequence: A removes @Remote annotations; B creates REST endpoints for calculateShipping and calculateShippingInsurance; C removes @Remote and notes the risk

## D9: WebLogic ApplicationLifecycleListener
- Evidence: StartupListener.java extends weblogic.application.ApplicationLifecycleListener, using weblogic.* packages. The weblogic.application package is defined in src/main/java/weblogic/application/ (stub classes in the repository). This is an application server lifecycle listener.
- Options: A) Replace it with Quarkus equivalent — @Observes StartupEvent and @Observes ShutdownEvent  B) Keep it as an ordinary dependency — it must resolve from a repository the build can reach and must not need the application server at run time  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: A — Quarkus has equivalent lifecycle events through CDI observers
- Decision: A — Replace with Quarkus lifecycle events
- Source: code
- Consequence: StartupListener will be rewritten to observe Quarkus StartupEvent and ShutdownEvent instead of extending WebLogic's ApplicationLifecycleListener

## D10: WebLogic JNDI classes in InventoryNotificationMDB
- Evidence: InventoryNotificationMDB.java uses weblogic.jndi.WLInitialContextFactory and references WebLogic-specific JNDI setup with t3://localhost:7001 provider URL. This class also implements MessageListener but is not annotated as @MessageDriven—it appears to be incomplete or unused code.
- Options: A) Replace it with Quarkus equivalent — proper JMS integration through Quarkus messaging  B) Keep it as an ordinary dependency — it must resolve from a repository the build can reach and must not need the application server at run time  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: C — This class has no @MessageDriven annotation, cannot work as-is, and duplicates functionality in OrderServiceMDB which is properly configured
- Decision: C — Remove InventoryNotificationMDB
- Source: code
- Consequence: InventoryNotificationMDB.java will be removed as it's non-functional code

## D11: WebLogic NonCatalogLogger stub
- Evidence: The repository contains stub class src/main/java/weblogic/i18n/logging/NonCatalogLogger.java (empty implementations). Grep search shows it's not imported or used anywhere in the application code—it's dead code.
- Decision: C — Remove the unused stub
- Source: code
- Consequence: Remove weblogic/i18n/logging/NonCatalogLogger.java as cleanup

## D12: System-scoped audit-logging-library dependency
- Evidence: pom.xml has system-scoped dependency on audit-logging-library-1.0.0.jar from lib/ directory (groupId com.enterprise). OrderService.java actively uses this library: imports com.enterprise.audit.logging.*, creates FileSystemAuditLogger in @PostConstruct, writes to ./device-inventory-audit-logs directory. Two JAR versions exist in lib/ (1.0.0 declared in pom.xml, 2.0.0 present but unused). This is an organisation-specific proprietary library for file-based audit logging.
- Options: A) Replace it with the Quarkus or standard equivalent (name it)  B) Keep it as an ordinary dependency — it must resolve from a repository the build can reach and must not need the application server at run time  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: B — The library appears to be pure Java file I/O (writes audit logs to filesystem), unlikely to depend on application server APIs. It should be published to an accessible Maven repository and changed to normal dependency scope.
- Decision: Open
- Consequence: A requires replacing with alternative audit framework; B changes scope to compile/runtime and requires Maven repository publication or keeping the JAR; C removes audit logging from OrderService; D blocks migration until library is analyzed

## D13: Modules and packaging
- Evidence: The build is a single WAR module (pom.xml packaging=war). There is only one pom.xml and one deployable artifact.
- Decision: Single Quarkus application module
- Source: code
- Consequence: The WAR becomes a Quarkus application with quarkus-maven-plugin
