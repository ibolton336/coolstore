# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)
- Decisions: 9 — decided 0, known 3, assumed 0, open 6

## D1: Source framework matches prompt
- Evidence: pom.xml shows Java EE 7 dependencies (javaee-web-api 7.0, javaee-api 7.0), packaging is WAR, EJB annotations (@Stateless, @Stateful, @MessageDriven) present in service layer, persistence.xml for JPA configuration. The code matches the prompt's description of a Java EE application.
- Decision: Confirmed — this is a Java EE 7 application to migrate to Quarkus 3
- Source: code
- Consequence: Proceed with Java EE to Quarkus 3 migration as specified.

## D2: Java release
- Evidence: pom.xml sets maven.compiler.source and target to 1.8. Quarkus 3 requires Java 17 minimum.
- Options: A) Java 17 — minimum for Quarkus 3  B) Java 21 — later LTS release
- Recommendation: A — smallest necessary change, well-supported by Quarkus 3
- Decision: Open
- Consequence: A sets compiler to 17, runtime images to Java 17; B requires Java 21 images and may need more language-level adjustments.

## D3: Production database
- Evidence: persistence.xml references JNDI datasource java:jboss/datasources/CoolstoreDS. README.md shows PostgreSQL setup instructions (podman run postgres container, connection URL jdbc:postgresql://127.0.0.1:5432/postgresDB). Flyway migration scripts in src/main/resources/db/migration (V1_1__CreateSchema.sql, V1_2__AddInitialData.sql).
- Decision: PostgreSQL — production database is already configured
- Source: code
- Consequence: Migration configures quarkus-jdbc-postgresql extension, converts datasource configuration to application.properties with PostgreSQL driver.

## D4: Schema management
- Evidence: Flyway 4.1.2 dependency in pom.xml. DataBaseMigrationStartup.java (@Singleton @Startup EJB) initializes Flyway programmatically at startup with baseline and migrate. Migration scripts exist in src/main/resources/db/migration/. persistence.xml sets javax.persistence.schema-generation.database.action to "none".
- Decision: Flyway — already managing schema migrations
- Source: code
- Consequence: Keep Flyway, add quarkus-flyway extension, convert DataBaseMigrationStartup initialization to Quarkus configuration (quarkus.flyway.*), update Flyway version to Quarkus-compatible.

## D5: Messaging broker
- Evidence: Two MDB patterns present: OrderServiceMDB.java uses @MessageDriven with destinationType=javax.jms.Topic, destinationLookup=topic/orders. InventoryNotificationMDB.java manually creates TopicConnection using WebLogic JNDI (weblogic.jndi.WLInitialContextFactory). README.md shows ActiveMQ Artemis configuration (JBoss messaging-activemq subsystem, jms-topic add topic/orders). Both consumers subscribe to "topic/orders".
- Options: A) AMQP 1.0 broker (ActiveMQ Artemis) — maintains topic semantics  B) Apache Kafka  C) In-process CDI events
- Recommendation: A — organization already runs ActiveMQ Artemis on JBoss, maintains existing message delivery semantics
- Decision: Open
- Consequence: A adds quarkus-smallrye-reactive-messaging-amqp and broker configuration; B changes to Kafka connector with different semantics; C removes broker dependency, converts to CDI events.

## D6: View layer
- Evidence: Two JSP files found: src/main/webapp/index.jsp (serves AngularJS single-page application, loads JavaScript/CSS, minimal server-side logic with request.getSession(true)), src/main/webapp/health.jsp (simple health check returning "1"). No JSF configuration. Static resources in src/main/webapp/app/ (AngularJS controllers, services, directives). REST API in com.redhat.coolstore.rest package.
- Options: A) Keep JSP on Quarkus — not supported, JSP has no Quarkus extension  B) Rewrite as Qute templates  C) Go API-only — move frontend to separate deployment  D) Leave out of scope
- Recommendation: C — index.jsp is a thin wrapper for AngularJS SPA, health.jsp is trivial. The application is already API-driven via REST endpoints. Serve static resources from META-INF/resources, add explicit /health endpoint.
- Decision: Open
- Consequence: A is impossible (JSP unsupported); B rewrites 2 pages as Qute; C moves webapp content to META-INF/resources, adds REST health endpoint, documents that UI is static; D leaves pages broken until follow-up.

## D7: Authentication and authorization
- Evidence: src/main/webapp/keycloak.json present with realm="eap", auth-server-url="http://localhost:8081/", resource="eap-app". README.md includes detailed Keycloak v20.0.5 setup (create realm from realm-export.json, create users). web.xml has no security-constraint or login-config elements. No @RolesAllowed annotations found in code. Frontend JavaScript handles authentication (keycloak.min.js loaded in index.jsp).
- Decision: OpenID Connect via Keycloak — already integrated
- Source: code
- Consequence: Add quarkus-oidc extension, convert keycloak.json to quarkus.oidc.* properties in application.properties, maintain Keycloak as identity provider.

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService.java has @Remote annotation and implements ShippingServiceRemote interface (calculateShipping, calculateShippingInsurance methods). ShoppingCartService.java has lookupShippingServiceRemote() performing local JNDI lookup ("ejb:/ROOT/ShippingService!..."). This is intra-application JNDI, not inter-application remote calls. No client artifact published in pom.xml.
- Options: A) No other application calls them — remove remote interfaces  B) Expose as REST endpoints  C) Not known — remove and document risk
- Recommendation: A — JNDI lookup is local (within same WAR), no separate client module, remote interface serves only to satisfy EJB local lookup pattern
- Decision: Open
- Consequence: A converts to direct CDI injection, removes @Remote and JNDI lookup; B adds REST resource, documents caller migration; C documents risk in plan notes.

## D9: Application-server APIs and vendored libraries
- Evidence: Three categories found:
1. WebLogic APIs: weblogic.application.ApplicationLifecycleListener in StartupListener.java, weblogic.jndi.WLInitialContextFactory in InventoryNotificationMDB.java, weblogic.i18n.logging.NonCatalogLogger stub (empty class)
2. JBoss APIs: org.wildfly.naming.client.WildFlyInitialContextFactory in ShoppingCartService.java  
3. Vendored library: audit-logging-library-1.0.0.jar (system-scoped in pom.xml), used by OrderService.java (com.enterprise.audit.logging classes in @PostConstruct/@PreDestroy)

For WebLogic lifecycle: Already covered by modules/lifecycle.md (converts to Quarkus @Observes StartupEvent/ShutdownEvent).
For JNDI lookups: Already covered by modules/ejb-to-cdi.md (converts to CDI @Inject).
For NonCatalogLogger: Empty stub, can be deleted by modules/cleanup.md.

Remaining decision: audit-logging-library-1.0.0.jar
- Options: A) Replace with standard logging  B) Keep as regular dependency  C) Remove audit feature  D) Leave out of scope
- Recommendation: B if library is available in Maven Central or organization repository, otherwise A — OrderService uses it for audit logging (FileSystemAuditLogger writing to ./device-inventory-audit-logs), replacing with SLF4J/structured logging maintains audit capability
- Decision: Open
- Consequence: A replaces FileSystemAuditLogger calls with SLF4J audit logger; B changes system scope to compile, requires library in accessible Maven repository; C removes @PostConstruct/@PreDestroy and audit calls; D blocks migration until resolved.
