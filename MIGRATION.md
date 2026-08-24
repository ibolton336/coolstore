# CoolStore Monolith - Quarkus 3 Migration

This is the migrated version of the CoolStore Monolith application, now running on Quarkus 3.

## Migration Summary

The application has been migrated from Java EE 7 (JBoss EAP 7.4) to Quarkus 3 with the following key changes:

### Technology Stack Changes

- **Java EE 7 → Jakarta EE 10**: All `javax.*` imports migrated to `jakarta.*`
- **JBoss EAP 7.4 → Quarkus 3.8.4**: Application server replaced with Quarkus runtime
- **WAR packaging → JAR packaging**: Now builds as an executable uber-jar
- **Java 8 → Java 17**: Updated to modern Java version

### Key Migrations

1. **Dependency Injection**
   - EJB `@Stateless` → CDI `@ApplicationScoped`
   - EJB `@Stateful` → CDI `@SessionScoped`
   - EJB `@Remote` removed (local injection used instead)

2. **Persistence**
   - Removed `persistence.xml` (configured via application.properties)
   - EntityManager now injected directly
   - Added `@Transactional` annotations where needed
   - Flyway integration now managed by Quarkus

3. **Messaging**
   - JMS Message-Driven Beans → SmallRye Reactive Messaging with `@Incoming`
   - JMS Topic publishing → Artemis JMS with ConnectionFactory
   - Removed JNDI lookups

4. **REST Services**
   - JAX-RS annotations migrated to Jakarta namespace
   - Continued using RESTEasy Reactive

5. **Removed Components**
   - WebLogic-specific classes (ApplicationLifecycleListener)
   - Manual Flyway initialization (now automatic)
   - JNDI lookups for EJB and JMS resources

6. **Logger Migration**
   - `java.util.logging.Logger` → `org.jboss.logging.Logger`

## Prerequisites

* Java 17 or later
* Maven 3.8.5 or later
* PostgreSQL database
* Apache Artemis or embedded Artemis (configured in application.properties)
* Keycloak v20.0.5 or later (optional, for authentication)

## Running the Application

### Start PostgreSQL Database

```bash
podman run --name myPostgresDb \
   -p 5432:5432 \
   -e POSTGRES_USER=postgresUser \
   -e POSTGRES_PASSWORD=postgresPW \
   -e POSTGRES_DB=postgresDB \
   -d postgres
```

### Start Keycloak (Optional)

Follow the same Keycloak setup instructions as before, or disable authentication in `application.properties`.

### Build and Run

```bash
# Development mode with live reload
mvn quarkus:dev

# Production build
mvn clean package
java -jar target/monolith-1.0.0-SNAPSHOT-runner.jar
```

### Access the Application

- Application: http://localhost:8080
- Health Check: http://localhost:8080/q/health
- Metrics: http://localhost:8080/q/metrics

## Configuration

All configuration is now in `src/main/resources/application.properties`:

- Database connection settings
- Artemis JMS configuration
- OIDC/Keycloak settings
- Flyway migration settings
- Logging configuration

## Key Differences from Java EE Version

1. **No Application Server Required**: Runs as a standalone Java application
2. **Faster Startup**: Quarkus optimizes startup time significantly
3. **Native Image Ready**: Can be compiled to native executable (optional)
4. **Reactive Messaging**: Uses MicroProfile Reactive Messaging for async processing
5. **Dev Mode**: `mvn quarkus:dev` provides live reload during development

## Testing

```bash
# Run tests
mvn test

# Run in dev mode with continuous testing
mvn quarkus:dev
```

## Building Native Image

```bash
mvn package -Pnative
./target/monolith-1.0.0-SNAPSHOT-runner
```

## Notes

- The audit logging library is still loaded from `lib/audit-logging-library-1.0.0.jar`
- Artemis needs to be configured or you can use an embedded broker
- Session-scoped beans for shopping cart maintain state per HTTP session
- Flyway migrations run automatically on startup

## Architecture

The application maintains the same REST API endpoints:
- `/services/products` - Product catalog
- `/services/cart` - Shopping cart operations
- `/services/orders` - Order management

Messaging topics:
- `orders` - Order processing topic (consumed by OrderServiceMDB)
- `orders` - Also consumed by InventoryNotificationMDB for inventory alerts
