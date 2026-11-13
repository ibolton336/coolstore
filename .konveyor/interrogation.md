# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)
- Decisions: 10 — decided 0, known 4 (D1, D2, D4, D10), assumed 0, open 6 (D3, D5, D6, D7, D8, D9)

## D1: Source and target alignment
- Evidence: pom.xml shows Java EE 7 application (`javaee-web-api` 7.0, `javaee-api` 7.0) packaged as WAR. The workflow guide specifies migration from Java EE to Quarkus 3. The code is Java EE, not already on Quarkus.
- Decision: Confirmed — migrate Java EE 7 to Quarkus 3
- Source: code
- Consequence: Proceed with full Java EE to Quarkus 3 migration plan.

## D2: Java release
- Evidence: pom.xml sets `<source>1.8</source>` and `<target>1.8</target>`, which is below Java 17.
- Options: A) Java 17 — the minimum Quarkus 3 supports  B) Java 21 — a later long-term-support release, and a larger jump for the code and for the images that run it
- Recommendation: A — minimal change, meets Quarkus 3 requirements
- Decision: Open
- Consequence: A sets compiler to Java 17; B sets compiler to Java 21. Both affect base image and any language-level fixes needed.

## D3: Production database
- Evidence: README.md shows PostgreSQL configuration for JBoss deployment. persistence.xml references JNDI datasource `java:jboss/datasources/CoolstoreDS`. README shows PostgreSQL JDBC driver setup and connection to PostgreSQL database.
- Decision: PostgreSQL
- Source: code
- Consequence: Use PostgreSQL JDBC extension, configure data source properties for PostgreSQL, H2 for dev/test profiles.

## D4: Schema management
- Evidence: pom.xml includes Flyway 4.1.2 dependency. src/main/resources/db/migration/ contains V1_1__CreateSchema.sql and V1_2__AddInitialData.sql. persistence.xml has `javax.persistence.schema-generation.database.action` set to `none`.
- Decision: Keep Flyway
- Source: code
- Consequence: Upgrade to Quarkus-compatible Flyway version, retain migration scripts, keep schema generation off.

## D5: Messaging broker
- Evidence: Two @MessageDriven beans (OrderServiceMDB, InventoryNotificationMDB) use JMS topics (`topic/orders`). README shows ActiveMQ configuration in JBoss. InventoryNotificationMDB uses WebLogic JNDI context factory but this is server-side JMS messaging with queue/topic semantics.
- Options: A) An AMQP 1.0 broker, for example ActiveMQ Artemis — keeps queue and topic semantics  B) Apache Kafka — only where the organisation is moving its messaging to Kafka; delivery and ordering semantics change  C) In-process CDI events, no broker — only when every producer and consumer of a destination is inside this application and messages need not survive a restart
- Recommendation: A — maintains JMS semantics, README shows ActiveMQ in use
- Decision: Open
- Consequence: A adds AMQP messaging extension and broker configuration; B adds Kafka extension with changed semantics; C removes broker dependency and converts to CDI events.

## D6: View layer
- Evidence: src/main/webapp/index.jsp and health.jsp are JSP files. index.jsp contains server-side directive `<% request.getSession(true); %>`. The application has AngularJS frontend but JSP provides the container page.
- Options: A) Keep the pages server-rendered on Quarkus — JSF through a JSF implementation's Quarkus extension, servlets through quarkus-undertow. JSP has no Quarkus support, so JSP pages cannot take this option  B) Rewrite the pages as Qute templates  C) Go API-only — REST endpoints for what the pages do, with the user interface as separate work  D) Leave the view layer out of this migration and record it as follow-up
- Recommendation: B — JSPs cannot run on Quarkus; rewriting to Qute keeps server-side rendering
- Decision: Open
- Consequence: B adds Qute and converts index.jsp and health.jsp to templates; C removes JSPs and serves static AngularJS; D leaves JSPs as-is and notes UI won't work until follow-up.

## D7: Authentication and authorisation
- Evidence: src/main/webapp/keycloak.json shows Keycloak integration (realm "eap", resource "eap-app"). README.md documents Keycloak v20.0.5 setup. web.xml has no security constraints, so authentication is handled client-side via Keycloak JavaScript adapter.
- Options: A) OpenID Connect against an identity provider, for example Keycloak — needs a provider the organisation runs  B) Keep the current user store (database, LDAP or a properties file) behind Quarkus Security, with the same form or basic login  C) Carry over the role checks and path rules only, and leave the authentication mechanism to be wired after the migration
- Recommendation: A — Keycloak already integrated, documented in README
- Decision: Open
- Consequence: A adds quarkus-oidc extension and configures Keycloak provider; B would change authentication mechanism; C defers authentication setup.

## D8: Callers of remote EJB interfaces
- Evidence: ShippingService has @Remote annotation and implements ShippingServiceRemote interface. No evidence in repository of external callers. Build produces single WAR, no client artifact published.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose the same operations as REST endpoints; the callers must change  C) Not known — remove them, and list the operations in the plan's Notes as a risk to confirm before release
- Recommendation: A — no client artifact published suggests no external callers
- Decision: Open
- Consequence: A removes @Remote and converts to @ApplicationScoped; B adds REST endpoints for calculateShipping and calculateShippingInsurance; C removes remote but documents risk.

## D9: Application-server APIs and vendored libraries
- Evidence: Multiple WebLogic-specific packages in use:
  1. weblogic.application.ApplicationLifecycleListener (StartupListener.java)
  2. weblogic.i18n.logging.NonCatalogLogger stub class
  3. weblogic.jndi.WLInitialContextFactory (InventoryNotificationMDB.java)
  4. System-scoped vendored JAR: audit-logging-library-1.0.0.jar (pom.xml line 47-52)
  
For WebLogic ApplicationLifecycleListener: modules/lifecycle.md covers this replacement.
For weblogic.jndi.WLInitialContextFactory: covered by modules/ejb-to-cdi.md JNDI removal.
For NonCatalogLogger: appears to be a stub class in repository.
For audit-logging-library-1.0.0.jar: system-scoped dependency needs resolution.
- Options: A) Replace it with the Quarkus or standard equivalent (name it)  B) Keep it as an ordinary dependency — it must resolve from a repository the build can reach and must not need the application server at run time  C) Remove the feature that depends on it  D) Leave it out of scope and record it as a blocker
- Recommendation: B — for audit-logging-library-1.0.0.jar, unknown functionality but likely a logging utility that can remain as regular dependency
- Decision: Open
- Consequence: B converts system-scoped to regular dependency and publishes JAR to accessible repository; A/C/D affect build dependencies and may require code changes.

## D10: Modules and packaging
- Evidence: pom.xml shows single module with `<packaging>war</packaging>`. Single deployable WAR artifact.
- Decision: Single Quarkus application module
- Source: code
- Consequence: Convert single WAR module to Quarkus application with appropriate packaging.
