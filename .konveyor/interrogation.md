# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (workflow guide)

## D1: Source framework matches prompt
- Evidence: The code is a Java EE 7 application (javaee-web-api 7.0, javaee-api 7.0 in pom.xml) deployed on JBoss EAP 7.4, matching the prompt's expectation to migrate from Java EE to Quarkus.
- Decision: Confirmed — source is Java EE 7 as expected
- Source: code
- Consequence: Proceed with migration plan from Java EE to Quarkus 3.

## D2: Java release
- Evidence: pom.xml sets maven.compiler.source and target to 1.8, below the Java 17 minimum for Quarkus 3.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release
- Recommendation: A — smallest jump from Java 8, standard Quarkus 3 baseline
- Decision: Open
- Consequence: Determines compiler settings, language-level fixes needed, and container base images.

## D3: Production database
- Evidence: README.md and persistence.xml show PostgreSQL is the production database. The JDBC URL jdbc:postgresql://127.0.0.1:5432/postgresDB is configured in JBoss datasource, and Flyway migrations in src/main/resources/db/migration/ contain PostgreSQL-specific schema.
- Decision: PostgreSQL
- Source: code
- Consequence: Use quarkus-jdbc-postgresql extension and configure datasource properties; H2 for dev/test profiles as per standard pattern.

## D4: Schema management
- Evidence: Flyway is already in use (org.flywaydb:flyway-core:4.1.2 in pom.xml) with migrations V1_1__CreateSchema.sql and V1_2__AddInitialData.sql. persistence.xml has javax.persistence.schema-generation.database.action set to "none".
- Decision: Keep Flyway
- Source: code
- Consequence: Add quarkus-flyway extension, upgrade Flyway version to Quarkus-compatible version, keep existing migration scripts.

## D5: Messaging broker
- Evidence: Two @MessageDriven beans (OrderServiceMDB, InventoryNotificationMDB) consume from "topic/orders". README.md shows ActiveMQ Artemis is configured in JBoss (jms-topic add --topic-address=topic.orders).
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka  C) In-process CDI events, no broker
- Recommendation: A — ActiveMQ Artemis maintains current semantics and the README shows it's the organization's broker
- Decision: Open
- Consequence: A adds quarkus-artemis-jms extension and connection config; B changes to Kafka with different semantics; C removes broker dependency entirely.

## D6: View layer
- Evidence: Two JSP files exist: src/main/webapp/index.jsp (54 lines, full application UI) and src/main/webapp/health.jsp (1 line). index.jsp loads AngularJS and serves as the SPA entry point; the actual UI is client-side JavaScript in bower_components and app/ directories.
- Options: A) Keep the pages server-rendered on Quarkus — JSP through a JSF implementation's Quarkus extension, servlets through quarkus-undertow  B) Rewrite the pages as Qute templates  C) Go API-only — REST endpoints for what the pages do, with the user interface as separate work  D) Leave the view layer out of this migration and record it as follow-up
- Recommendation: D — Quarkus has no JSP support. The JSPs are minimal (index.jsp is just a static HTML shell for the AngularJS SPA, health.jsp returns "1"). Move static content to META-INF/resources, serve as static files with quarkus-undertow, minimal migration effort.
- Decision: Open
- Consequence: A is not available for JSP; D moves webapp content to META-INF/resources and serves statically with quarkus-undertow; B/C would be significant rewrites.

## D7: Authentication and authorization
- Evidence: src/main/webapp/keycloak.json configures OpenID Connect against a Keycloak server (realm "eap", resource "eap-app"). README.md shows Keycloak v20.0.5 is the identity provider. web.xml has no security-constraint elements, and no @RolesAllowed annotations found in code — authentication is present but authorization rules are client-side.
- Decision: OpenID Connect against Keycloak
- Source: code
- Consequence: Use quarkus-oidc extension with keycloak configuration; no security constraints to migrate since none exist.

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService exposes a @Remote interface (ShippingServiceRemote) with methods calculateShipping and calculateShippingInsurance. The build produces a single WAR with no client JAR artifact, and no other modules or repositories reference it.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose the same operations as REST endpoints; the callers must change  C) Not known — remove them, and list the operations in the plan's Notes as a risk to confirm before release
- Recommendation: A — no client artifact published, single-module WAR deployment, no evidence of remote callers
- Decision: Open
- Consequence: A removes @Remote and interface becomes local CDI; B creates REST endpoints requiring caller changes; C removes but documents risk.

## D9: WebLogic lifecycle listener
- Evidence: src/main/java/com/redhat/coolstore/utils/StartupListener.java extends weblogic.application.ApplicationLifecycleListener (stub classes in src/main/java/weblogic/). It logs on postStart and preStop.
- Options: A) Replace it with the Quarkus equivalent — @Observes StartupEvent and ShutdownEvent  B) Keep it as an ordinary dependency  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: A — standard Quarkus lifecycle events provide identical functionality
- Decision: A — Replace with Quarkus lifecycle events
- Source: code
- Consequence: Add step to replace ApplicationLifecycleListener with CDI @Observes StartupEvent/ShutdownEvent; remove weblogic stub classes.

## D10: WebLogic JNDI in InventoryNotificationMDB
- Evidence: src/main/java/com/redhat/coolstore/service/InventoryNotificationMDB.java has weblogic.jndi.WLInitialContextFactory and t3://localhost:7001 JNDI lookups. However, this class implements MessageListener but has no @MessageDriven annotation — it's not actually activated as an MDB. The init() and close() methods are never called in the codebase.
- Options: A) Replace it with the Quarkus equivalent — Reactive Messaging or Artemis JMS consumer  B) Keep it as an ordinary dependency  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: C — this is dead code (no @MessageDriven, init() never called), the same functionality is handled by OrderServiceMDB
- Decision: C — Remove dead code
- Source: code
- Consequence: Delete InventoryNotificationMDB.java as unused code; no migration work needed for this class.

## D11: Audit logging library
- Evidence: pom.xml includes com.enterprise:audit-logging-library:1.0.0 as a system-scoped dependency pointing to lib/audit-logging-library-1.0.0.jar. No imports of this library found in the source code with grep search.
- Options: A) Replace it with the Quarkus or standard equivalent  B) Keep it as an ordinary dependency — it must resolve from a repository the build can reach and must not need the application server at run time  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: C — no code references found; appears unused. If actually needed, organization should clarify usage.
- Decision: Open
- Consequence: C removes the dependency; B/D requires organization to publish to Maven repository or provide usage details; A needs to know what it does.

## D12: Modules and packaging
- Evidence: The build in pom.xml is a single WAR module (packaging=war), not a multi-module EAR.
- Decision: Single Quarkus application module
- Source: code
- Consequence: Convert the single WAR to a Quarkus application; no module restructuring needed.
