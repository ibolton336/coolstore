# Interrogation Record

- Application: coolstore-monolith
- Target: Quarkus 3 (from workflow guide)

## D1: Source and Target Match
- Evidence: The code is a Java EE 7 application (confirmed by pom.xml with javaee-web-api 7.0 and javaee-api 7.0 dependencies, packaged as WAR). The workflow guide specifies migration from Java EE to Quarkus 3. This is consistent.
- Decision: A — Proceed with Java EE 7 to Quarkus 3 migration
- Source: code
- Consequence: Migration proceeds as specified in the workflow guide

## D2: JMS/Messaging Strategy
- Evidence: The application uses JMS heavily with Message-Driven Beans (OrderServiceMDB.java, InventoryNotificationMDB.java) listening to JMS topics. InventoryNotificationMDB.java contains WebLogic-specific JNDI lookups (weblogic.jndi.WLInitialContextFactory) and manual topic subscription setup, while OrderServiceMDB.java uses standard Java EE @MessageDriven annotations. The application expects a JMS topic at "topic/orders".
- Options: 
  - A) Migrate to Quarkus Reactive Messaging with SmallRye
  - B) Use Quarkus with Apache Artemis JMS (closer to current implementation)
  - C) Keep WebLogic-specific code out of scope
- Recommendation: B — Apache Artemis JMS provides the closest migration path for the existing JMS code, minimizing changes while removing WebLogic dependencies
- Decision: Open
- Consequence: Choice A requires rewriting MDBs to use @Incoming annotations and reactive patterns. Choice B allows keeping MDB pattern with Quarkus extensions. Choice C leaves non-portable code that won't work in Quarkus.

## D3: EJB Session Beans Strategy
- Evidence: The application uses @Stateless EJBs (CatalogService.java, ShippingService.java with @Remote interface) and @SessionScoped REST endpoints (CartEndpoint.java). ShippingService.java implements a @Remote interface for potential distributed calls.
- Options:
  - A) Convert all EJBs to CDI beans, remove @Remote and treat ShippingService as local
  - B) Keep remote EJB interface and use Quarkus REST client for distributed calls
  - C) Use Quarkus with Hibernate ORM and CDI only (no EJB support)
- Recommendation: A — The @Remote interface on ShippingService appears unused (no RMI calls found), so treating all as local CDI beans is simpler
- Decision: Open
- Consequence: Choice A simplifies to CDI beans. Choice B maintains distribution option but requires REST client setup. Choice C is same as A for this application.

## D4: JPA and Persistence Configuration
- Evidence: The application uses JPA 2.1 with Hibernate (persistence.xml), EntityManager injection via @PersistenceContext in Resources.java, JTA datasource "java:jboss/datasources/CoolstoreDS", and Flyway 4.1.2 for database migrations. Uses PostgreSQL database.
- Options:
  - A) Migrate to Quarkus Hibernate ORM with Panache for simplified data access
  - B) Keep standard JPA with Quarkus Hibernate ORM extension (minimal changes)
  - C) Migrate Flyway to newer version compatible with Quarkus
- Recommendation: B — Standard JPA approach minimizes changes; Flyway will need version update regardless
- Decision: Open
- Consequence: Choice A requires rewriting entities to extend PanacheEntity and repositories. Choice B keeps existing JPA code mostly unchanged. Both choices need Flyway update to v9.x for Quarkus 3 compatibility.

## D5: WebLogic-Specific Code
- Evidence: The application contains WebLogic-specific code in src/main/java/weblogic/ directory (ApplicationLifecycleListener, ApplicationLifecycleEvent, NonCatalogLogger). StartupListener.java extends WebLogic's ApplicationLifecycleListener. InventoryNotificationMDB.java uses WebLogic JNDI factory.
- Options:
  - A) Replace with Quarkus equivalents (lifecycle events, CDI observers)
  - B) Remove WebLogic code entirely and simplify
  - C) Keep as proprietary dependency (not functional in Quarkus)
- Recommendation: A — Replace with Quarkus StartupEvent/ShutdownEvent CDI observers for lifecycle, and standard logging
- Decision: Open
- Consequence: Choice A requires rewriting lifecycle listeners and removing weblogic package. Choice B removes functionality if needed. Choice C leaves non-functional code.

## D6: Local/Vendored Dependencies
- Evidence: The application has a local JAR dependency: audit-logging-library-1.0.0.jar in lib/ directory (pom.xml declares it with scope=system). There's also audit-logging-library-2.0.0.jar present but not referenced.
- Options:
  - A) Keep as system-scoped dependency (not recommended for Quarkus native)
  - B) Install in local Maven repository or organization repository
  - C) Replace with alternative logging solution
  - D) Exclude from migration scope
- Recommendation: B — Install in proper Maven repository for better dependency management
- Decision: Open
- Consequence: Choice A may cause issues with Quarkus native builds. Choice B requires access to Maven repository. Choice C requires understanding library functionality. Choice D leaves functionality out.

## D7: Session Scope in REST
- Evidence: CartEndpoint.java uses @SessionScoped annotation for REST endpoint, relying on HTTP session state. This is used with JAX-RS in a clustered environment (web.xml has <distributable/> tag).
- Options:
  - A) Convert to stateless with client-side state management
  - B) Use Quarkus session management with distributed cache
  - C) Keep session scope with Quarkus Undertow extension
- Recommendation: A — Move to stateless REST following cloud-native patterns, using cart ID for state retrieval
- Decision: Open
- Consequence: Choice A requires architectural change to stateless pattern. Choice B maintains sessions but needs distributed cache setup. Choice C keeps current pattern with session replication.

## D8: Packaging and Deployment
- Evidence: The application is currently packaged as a WAR file (pom.xml packaging=war) for deployment to JBoss EAP 7.4. Uses JSP pages (index.jsp, health.jsp) and static web content.
- Options:
  - A) Convert to Quarkus JAR with embedded server
  - B) Keep WAR packaging for Quarkus (deprecated approach)
  - C) Containerize with Quarkus native image
- Recommendation: A — Standard Quarkus JAR packaging with embedded server
- Decision: Open
- Consequence: Choice A requires removing WAR structure and handling static content differently. Choice B is not recommended for Quarkus 3. Choice C provides best cloud-native deployment but requires native compatibility.

## D9: Java Version
- Evidence: The application currently uses Java 8 (pom.xml: source=1.8, target=1.8). Quarkus 3 requires Java 11 minimum, recommends Java 17+.
- Options:
  - A) Upgrade to Java 11 (minimum requirement)
  - B) Upgrade to Java 17 (recommended)
  - C) Upgrade to Java 21 (latest LTS)
- Recommendation: B — Java 17 is the recommended LTS version for Quarkus 3
- Decision: Open
- Consequence: All choices require code review for compatibility. Choice A provides minimum support. Choice B is recommended for Quarkus 3. Choice C provides longest support but may introduce more compatibility issues.

## D10: RESTEasy and JAX-RS Configuration
- Evidence: The application uses JAX-RS with @ApplicationPath("/services") in RestApplication.java and standard JAX-RS annotations. REST endpoints return JSON using MediaType.APPLICATION_JSON.
- Options:
  - A) Migrate to Quarkus RESTEasy Reactive (recommended for Quarkus 3)
  - B) Use Quarkus RESTEasy Classic (closer to current implementation)
- Recommendation: A — RESTEasy Reactive is the future direction for Quarkus 3 and provides better performance
- Decision: Open
- Consequence: Choice A may require minor annotation changes and reactive patterns. Choice B provides smoother migration with less changes.
