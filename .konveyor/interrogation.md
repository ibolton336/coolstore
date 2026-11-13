# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)
- Decisions: 9 — decided 0, known 4, assumed 0, open 5

## D1: Source/target alignment
- Evidence: pom.xml declares packaging=war, Java EE 7 dependencies (javaee-web-api 7.0, javaee-api 7.0), and targets Java 8. README confirms deployment to JBoss EAP 7.4. The source is Java EE 7, matching the prompt. Target Quarkus 3 is stated in the workflow guide.
- Decision: Proceed with Java EE 7 to Quarkus 3 migration
- Source: code
- Consequence: Migration proceeds as specified

## D2: Java release
- Evidence: pom.xml sets maven.compiler.source and target to 1.8. Quarkus 3 requires Java 17 minimum.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release
- Recommendation: A — minimal change, reduces risk
- Decision: Open
- Consequence: A sets compiler to Java 17 and impacts base container images; B sets compiler to Java 21, requires more language-level updates and newer base images

## D3: Production database
- Evidence: README shows PostgreSQL configured as production database (jdbc:postgresql://127.0.0.1:5432/postgresDB, JNDI name java:jboss/datasources/CoolstoreDS). persistence.xml references this JNDI datasource. Flyway migrations present in src/main/resources/db/migration/.
- Decision: PostgreSQL
- Source: code
- Consequence: quarkus-jdbc-postgresql extension added, datasource properties configured for PostgreSQL

## D4: Schema management
- Evidence: Flyway 4.1.2 already in pom.xml dependencies. persistence.xml sets schema-generation.database.action=none. Two Flyway migrations present: V1_1__CreateSchema.sql and V1_2__AddInitialData.sql.
- Decision: Keep Flyway
- Source: code
- Consequence: Add quarkus-flyway extension, update Flyway version to Quarkus-compatible version, migrate Flyway configuration to application.properties

## D5: Messaging broker
- Evidence: Application has two @MessageDriven beans (OrderServiceMDB, InventoryNotificationMDB) and JMS producer in ShoppingCartOrderProcessor using JMS Topic "topic/orders". README shows ActiveMQ Artemis configured in JBoss EAP (jms-topic add --topic-address=topic.orders). InventoryNotificationMDB has WebLogic-specific JNDI context but this is legacy stub code.
- Options: A) AMQP 1.0 broker (ActiveMQ Artemis) — keeps queue and topic semantics  B) Apache Kafka — delivery and ordering semantics change  C) In-process CDI events — messages don't survive restart
- Recommendation: A — maintains current topology and semantics, ActiveMQ Artemis is what JBoss EAP uses
- Decision: Open
- Consequence: A adds quarkus-smallrye-reactive-messaging-amqp and connector configuration; B adds quarkus-smallrye-reactive-messaging-kafka and changes semantics; C removes broker dependency and uses @Observes/@Event

## D6: View layer
- Evidence: Two JSP files found: src/main/webapp/index.jsp (54 lines, main AngularJS SPA bootstrap) and src/main/webapp/health.jsp (1 line). The application is primarily a REST API (CartEndpoint, OrderEndpoint, ProductEndpoint) with static web resources (bower_components, partials). The JSPs bootstrap the frontend SPA.
- Options: A) Keep server-rendered — not available for JSP  B) Rewrite as Qute templates  C) Go API-only — serve static resources, drop JSP  D) Leave out of scope
- Recommendation: C — the JSPs are minimal (one is 1 line, the other bootstraps the SPA), the real UI is AngularJS static files, and REST endpoints already exist
- Decision: Open
- Consequence: B rewrites 2 JSP files as Qute templates; C moves static resources to META-INF/resources, removes JSP files, application becomes REST API + static files; D leaves JSPs untouched and non-functional

## D7: Authentication and authorisation
- Evidence: src/main/webapp/keycloak.json exists with realm "eap", auth-server-url http://localhost:8081/, resource "eap-app". README describes Keycloak 20.0.5 setup with user creation. index.jsp includes keycloak.min.js. web.xml has no security-constraint or login-config (authentication is client-side via Keycloak JS adapter). No @RolesAllowed annotations found in code.
- Decision: OpenID Connect with Keycloak
- Source: code
- Consequence: Add quarkus-oidc extension, migrate keycloak.json configuration to application.properties quarkus.oidc.* properties, frontend continues using Keycloak JS adapter

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService has @Remote annotation and implements ShippingServiceRemote interface. Build produces single WAR artifact (no separate client JAR). No evidence in repository of other applications calling this remote interface. ShippingService is used internally by PromoService via injection.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose as REST endpoints; callers must change  C) Not known — remove them and list as risk
- Recommendation: A — no client artifact published, no cross-application dependency evidence
- Decision: Open
- Consequence: A removes @Remote and remote interface, making it a simple CDI bean; B creates REST endpoint wrapping the operations and notes caller changes; C removes but adds risk note to plan

## D9: Application-server APIs and vendored libraries
- Evidence: System-scoped dependency on audit-logging-library-1.0.0.jar in lib/ directory (com.enterprise:audit-logging-library:1.0.0). WebLogic packages present: weblogic.application.ApplicationLifecycleListener/Event (used by StartupListener), weblogic.i18n.logging.NonCatalogLogger, and weblogic.jndi references in InventoryNotificationMDB. The lifecycle listener use case is covered by modules/lifecycle.md. The weblogic stubs are covered by modules/cleanup.md.
- Options: A) Replace audit-logging-library with standard logging or Quarkus equivalent  B) Keep as ordinary dependency — must resolve from accessible repository  C) Remove the feature  D) Leave out of scope as blocker
- Recommendation: B — I do not know what this library does, but if it's a logging library (per the name) it likely doesn't require the application server at runtime
- Decision: Open
- Consequence: A requires finding replacement and updating call sites; B moves to normal Maven dependency if available in Maven Central or organization repository; C removes all uses of the library; D blocks migration until library is addressed
