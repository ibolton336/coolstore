# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (workflow guide)

## D1: Source and target alignment
- Evidence: The pom.xml shows Java EE 7 dependencies (javaee-web-api 7.0, javaee-api 7.0) and the README describes deployment on JBoss EAP 7.4. The prompt asks to migrate from Java EE to Quarkus 3. The code is Java EE, matching the source in the prompt.
- Decision: Proceed with migration — code is Java EE, target is Quarkus 3
- Source: code
- Consequence: Migration proceeds as planned

## D2: Java release
- Evidence: pom.xml configures `<source>1.8</source>` and `<target>1.8</target>`, which is below Java 17 (the minimum Quarkus 3 supports).
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release
- Recommendation: A — minimal change, standard choice for Quarkus 3
- Decision: Open
- Consequence: Determines compiler settings, language-level fixes needed, and base container image

## D3: Production database
- Evidence: README.md shows PostgreSQL is configured (port 5432, postgresDB). The persistence.xml references `java:jboss/datasources/CoolstoreDS` which README configures as PostgreSQL via JBoss CLI. Flyway dependency present in pom.xml (version 4.1.2).
- Decision: PostgreSQL — configured in deployment documentation
- Source: code
- Consequence: Use quarkus-jdbc-postgresql extension and PostgreSQL data source configuration

## D4: Schema management
- Evidence: Flyway is already in use (pom.xml dependency), with migration scripts at src/main/resources/db/migration/V1_1__CreateSchema.sql and V1_2__AddInitialData.sql. persistence.xml has `javax.persistence.schema-generation.database.action` set to `none`.
- Decision: Keep Flyway — already in use with existing migrations
- Source: code
- Consequence: Retain quarkus-flyway extension, keep existing migration scripts, maintain current schema management approach

## D5: Messaging broker
- Evidence: OrderServiceMDB.java is a @MessageDriven bean consuming from `topic/orders`. README shows ActiveMQ configuration via JBoss CLI (`jms-topic add --topic-address=topic.orders`). ShoppingCartOrderProcessor.java publishes to the same topic using javax.jms.Topic.
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka — delivery and ordering semantics change  C) In-process CDI events, no broker
- Recommendation: A — maintains topic semantics, matches current ActiveMQ usage
- Decision: Open
- Consequence: For A, use quarkus-messaging-amqp extension; for B, use Kafka connector with semantic changes; for C, replace JMS with CDI events

## D6: View layer
- Evidence: Application has JSP pages (src/main/webapp/index.jsp and health.jsp). index.jsp contains server-side session initialization (`request.getSession(true)`) and embeds an AngularJS single-page application. JSP has no Quarkus support.
- Options: A) Keep the pages server-rendered on Quarkus — not available for JSP  B) Rewrite the pages as Qute templates  C) Go API-only — REST endpoints exist, with UI as separate work  D) Leave the view layer out of this migration as follow-up
- Recommendation: C — REST endpoints already exist (CartEndpoint, ProductEndpoint, OrderEndpoint), the frontend is AngularJS (client-side), and the JSP mainly serves as a container for static resources. Move static content to src/main/resources/META-INF/resources and make this a pure REST API.
- Decision: Open
- Consequence: For C, removes JSP dependency, moves static resources, application becomes pure REST API; session management moves to client or token-based. For B, requires rewriting 2 JSP pages as Qute. For D, view layer remains non-functional until follow-up work.

## D7: Authentication and authorisation
- Evidence: keycloak.json present in src/main/webapp/ and README shows Keycloak 20.0.5 setup with realm configuration (realm-export.json). No security constraints in web.xml, no @RolesAllowed annotations in code. Keycloak integration exists but authentication is handled client-side via keycloak.js in the AngularJS app.
- Decision: Keep OpenID Connect with Keycloak — already integrated
- Source: code
- Consequence: Use quarkus-oidc extension, migrate keycloak.json configuration to Quarkus properties

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService.java exposes a @Remote interface (ShippingServiceRemote). The build produces a WAR with no separate client artifact. No evidence in the repository of external callers.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose as REST endpoints  C) Not known — remove them and list as risk
- Recommendation: A — no client artifact published, likely internal use only
- Decision: Open
- Consequence: For A, remove @Remote, convert to local CDI bean. For B, create REST resource wrapping operations. For C, remove but document in plan notes as risk to verify before release.

## D9: Application-server APIs and vendored libraries
- Evidence: Code has weblogic.* packages (weblogic.application.ApplicationLifecycleListener, weblogic.i18n.logging.NonCatalogLogger) at src/main/java/weblogic/. These are stub implementations, not actual WebLogic dependencies. InventoryNotificationMDB.java uses weblogic.jndi.WLInitialContextFactory and javax.rmi.PortableRemoteObject for JNDI lookups. The pom.xml has system-scoped dependency on com.enterprise:audit-logging-library:1.0.0 (lib/audit-logging-library-1.0.0.jar). Two versions exist in lib/ directory (1.0.0 and 2.0.0).
- Options (for audit-logging-library): A) Replace it with equivalent (need to inspect)  B) Keep it as ordinary dependency — must resolve from Maven repo  C) Remove the feature that depends on it  D) Leave it out of scope as blocker
- Options (for weblogic stubs): These are handled by modules/lifecycle.md and modules/cleanup.md per the catalog.
- Recommendation: For audit-logging-library: B if the JAR is self-contained, otherwise D — need to understand what it does and whether it's available in a Maven repository
- Decision: Open
- Consequence: For B, change to regular dependency with repository coordinates; for C, remove usage; for D, document as blocker. WebLogic stubs will be removed via standard cleanup module.

## D10: Modules and packaging
- Evidence: pom.xml shows a single module build producing a WAR artifact. No parent POM, no submodules, no EAR packaging.
- Decision: Single module — already one WAR module
- Source: code
- Consequence: Convert existing module to Quarkus application, no restructuring needed
