# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (workflow guide)
- Decisions: 9 — decided 1 (D1), known 4 (D2, D4, D5, D9), assumed 0, open 4 (D3, D6, D7, D8)

## D1: Source framework confirmation
- Evidence: pom.xml declares `javaee-web-api` and `javaee-api` version 7.0 (scope provided), confirming this is a Java EE 7 application. The workflow guide specifies migration from "Java EE" to "Quarkus 3", which matches. However, the code also contains WebLogic-specific packages (weblogic.application, weblogic.i18n.logging, weblogic.jndi) and uses WildFly JNDI context factory in ShoppingCartService.java, suggesting mixed application server history.
- Options: A) Confirm this is a Java EE application to migrate to Quarkus 3  B) This is not the right migration path for this application
- Recommendation: A — the dependencies and structure clearly indicate a Java EE 7 application, and the WebLogic packages appear to be stubs or legacy code that will be handled by the migration modules.
- Decision: A — Confirm this is a Java EE application to migrate to Quarkus 3
- Source: human
- Consequence: Migration proceeds as planned; WebLogic stubs and JNDI lookups will be addressed by migration modules.

## D2: Java release
- Evidence: pom.xml sets `<source>1.8</source>` and `<target>1.8</target>` in maven-compiler-plugin configuration, which is below Java 17 (the minimum for Quarkus 3).
- Decision: A — Java 17
- Source: code
- Consequence: Compiler settings will be updated to Java 17, language-level fixes included in scope, and base images updated for Java 17.

## D3: Production database
- Evidence: persistence.xml references `java:jboss/datasources/CoolstoreDS`. README.md shows PostgreSQL database configuration for JBoss EAP 7.4 with JDBC driver setup. The JDBC URL in README is `jdbc:postgresql://127.0.0.1:5432/postgresDB`.
- Options: A) PostgreSQL  B) Another database (say which)
- Recommendation: A — README.md explicitly configures PostgreSQL as the production database.
- Decision: Open
- Consequence: A uses PostgreSQL JDBC extension and configures PostgreSQL data source; B requires specifying the alternative database and its configuration.

## D4: Schema management
- Evidence: persistence.xml has `<property name="javax.persistence.schema-generation.database.action" value="none"/>`, indicating schema generation is off. The repository contains Flyway migration scripts in src/main/resources/db/migration/ (V1_1__CreateSchema.sql and V1_2__AddInitialData.sql), and pom.xml includes flyway-core version 4.1.2 dependency.
- Decision: Keep Flyway
- Source: code
- Consequence: Quarkus Flyway extension will be added, existing migration scripts will be kept, and Flyway configuration will be updated for Quarkus.

## D5: Messaging broker
- Evidence: The application has two @MessageDriven beans (OrderServiceMDB and InventoryNotificationMDB) consuming from `topic/orders`, and ShoppingCartOrderProcessor produces messages to the same topic using JMSContext and @Resource Topic. README.md shows ActiveMQ configuration in JBoss EAP standalone-full-ha.xml with topic/orders and cluster configuration.
- Decision: A — An AMQP 1.0 broker (ActiveMQ Artemis)
- Source: code
- Consequence: Migration will use quarkus-messaging-amqp extension, configure AMQP connector for ActiveMQ Artemis, and convert MDBs to reactive messaging consumers.

## D6: View layer
- Evidence: src/main/webapp contains JSP files (index.jsp with 54 lines, health.jsp with 1 line). JSP has no Quarkus support according to the decision catalog. The repository also contains static web content (bower_components/, partials/, app/ directories, and coolstore.json, keycloak.json).
- Options: A) Rewrite the pages as Qute templates  B) Go API-only with REST endpoints  C) Leave the view layer out of this migration as follow-up work
- Recommendation: B — health.jsp appears to be a simple health check (1 line) that can be replaced by Quarkus health endpoints. index.jsp is more substantial (54 lines) but appears to be a single-page application shell given the bower_components and app/ structure suggesting client-side rendering. An API-only approach would align with modern architecture.
- Decision: Open
- Consequence: A adds Qute extension and creates templates for each JSP; B removes JSPs and documents REST endpoints; C leaves JSPs untouched and notes the UI won't work until follow-up.

## D7: Callers of remote EJB interfaces
- Evidence: ShippingService.java is annotated with @Remote and implements ShippingServiceRemote interface with methods calculateShipping() and calculateShippingInsurance(). The interface is called locally within the same application by ShoppingCartService.java using JNDI lookup with WildFly naming context. The build produces a single WAR artifact (pom.xml has `<packaging>war</packaging>` and `<finalName>ROOT</finalName>`), not a client JAR, suggesting no external callers.
- Options: A) No other application calls them — remove the remote interfaces  B) Other applications call them — expose as REST endpoints  C) Not known — remove them and list as risk in Notes
- Recommendation: A — the remote interface is only called locally within the same application, and no client artifact is published.
- Decision: Open
- Consequence: A removes @Remote annotations and converts to local CDI beans; B creates REST resources for each remote operation and notes callers must change; C removes with risk noted.

## D8: Application-server APIs and vendored libraries
- Evidence: Multiple issues found:
1. WebLogic packages: weblogic.application.ApplicationLifecycleListener, weblogic.application.ApplicationLifecycleEvent, weblogic.i18n.logging.NonCatalogLogger (src/main/java/weblogic/)
2. WebLogic JNDI: InventoryNotificationMDB.java uses weblogic.jndi.WLInitialContextFactory
3. WildFly JNDI: ShoppingCartService.java uses org.wildfly.naming.client.WildFlyInitialContextFactory
4. Vendored library: audit-logging-library-1.0.0.jar with system scope dependency (com.enterprise.audit.logging.*) used in OrderService.java
5. javax.rmi.PortableRemoteObject used in InventoryNotificationMDB.java

The WebLogic packages appear to be stub implementations (ApplicationLifecycleListener has empty methods), and lifecycle/JNDI lookups are covered by modules mentioned in the decision catalog. The audit logging library is organization-specific.
- Options: A) Replace weblogic/wildfly JNDI with CDI injection (covered by modules), keep audit-logging-library as ordinary dependency if it works standalone  B) Replace audit-logging-library with standard logging  C) Remove audit logging feature  D) Leave audit-logging-library out of scope as blocker
- Recommendation: A — the WebLogic stubs and JNDI lookups are already handled by existing modules (lifecycle.md, ejb-to-cdi.md). The audit-logging-library appears to be a file-based logger that may work as a regular dependency, but its compatibility is unknown without documentation.
- Decision: Open
- Consequence: A uses module guidance for WebLogic/JNDI removal and attempts to use audit-logging-library as regular dependency; B replaces with java.util.logging or slf4j; C removes audit logging calls from OrderService; D blocks migration until library compatibility confirmed.

## D9: Modules and packaging
- Evidence: pom.xml shows a single module build with `<packaging>war</packaging>` and `<artifactId>monolith</artifactId>`. No parent pom, no modules section, and no other pom.xml files in the repository.
- Decision: Keep as single Quarkus application
- Source: code
- Consequence: The single WAR module becomes one Quarkus application module with the Quarkus Maven plugin.
