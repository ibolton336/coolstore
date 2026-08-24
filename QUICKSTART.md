# Quick Start Guide - Quarkus Migrated Application

## Prerequisites

- Java 17 or later
- Maven 3.8.5+
- PostgreSQL (optional for full functionality)
- Apache Artemis (optional for external messaging)

## Quick Start

### 1. Build the Application

```bash
mvn clean package -DskipTests
```

Output: `target/monolith-1.0.0-SNAPSHOT-runner.jar` (57 MB)

### 2. Run in Development Mode (Recommended)

```bash
mvn quarkus:dev
```

Features:
- Hot reload on code changes
- Dev UI at http://localhost:8080/q/dev/
- Automatic restart on resource changes

### 3. Run in Production Mode

```bash
java -jar target/monolith-1.0.0-SNAPSHOT-runner.jar
```

## Application URLs

- **Application**: http://localhost:8080
- **Health Check**: http://localhost:8080/q/health
- **Metrics**: http://localhost:8080/q/metrics
- **OpenAPI/Swagger**: http://localhost:8080/q/swagger-ui
- **Dev UI** (dev mode only): http://localhost:8080/q/dev/

## REST API Endpoints

All endpoints are under `/services`:

- `GET /services/products` - List all products
- `GET /services/products/{itemId}` - Get product by ID
- `GET /services/cart/{cartId}` - Get shopping cart
- `POST /services/cart/{cartId}/{itemId}/{quantity}` - Add item to cart
- `DELETE /services/cart/{cartId}/{itemId}/{quantity}` - Remove item from cart
- `POST /services/cart/checkout/{cartId}` - Checkout cart
- `GET /services/orders` - List all orders
- `GET /services/orders/{orderId}` - Get order by ID

## Configuration

All configuration is in `src/main/resources/application.properties`.

### Database Configuration (PostgreSQL)

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=postgresUser
quarkus.datasource.password=postgresPW
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
```

### Start PostgreSQL with Podman/Docker

```bash
podman run --name myPostgresDb \
   -p 5432:5432 \
   -e POSTGRES_USER=postgresUser \
   -e POSTGRES_PASSWORD=postgresPW \
   -e POSTGRES_DB=postgresDB \
   -d postgres
```

### Messaging Configuration

Currently using in-memory connector for development. For production with Artemis:

1. Update `application.properties`:
```properties
mp.messaging.outgoing.orders.connector=smallrye-artemis
mp.messaging.outgoing.orders.address=orders
mp.messaging.incoming.orders.connector=smallrye-artemis
mp.messaging.incoming.orders.address=orders
```

2. Start Artemis broker on port 61616

### Authentication/OIDC (Currently Disabled)

To enable Keycloak authentication, update `application.properties`:
```properties
quarkus.oidc.enabled=true
quarkus.oidc.auth-server-url=http://127.0.0.1:8081/realms/eap
```

## Development Tips

### Live Reload
In dev mode (`mvn quarkus:dev`), changes to Java files, resources, and configurations are automatically detected and reloaded.

### Debugging
Run with remote debugging enabled:
```bash
mvn quarkus:dev -Ddebug=5005
```

### Continuous Testing
```bash
mvn quarkus:test
```

### Build Native Image (Optional)
```bash
mvn package -Pnative
./target/monolith-1.0.0-SNAPSHOT-runner
```

## Troubleshooting

### Build Fails
```bash
mvn clean install -U
```

### Port Already in Use
Change port in `application.properties`:
```properties
quarkus.http.port=8090
```

### Database Connection Issues
- Ensure PostgreSQL is running
- Check connection parameters in `application.properties`
- Verify Flyway migrations are enabled

### Messaging Issues
- Check that in-memory connector is properly configured
- For external broker, verify Artemis is running and accessible

## Logging

Configure logging levels in `application.properties`:
```properties
quarkus.log.level=INFO
quarkus.log.category."com.redhat.coolstore".level=DEBUG
```

## Health Checks

```bash
# Liveness
curl http://localhost:8080/q/health/live

# Readiness
curl http://localhost:8080/q/health/ready

# Full health
curl http://localhost:8080/q/health
```

## Metrics

Prometheus-compatible metrics:
```bash
curl http://localhost:8080/q/metrics
```

## Key Differences from Java EE Version

1. **No Application Server Required** - Runs as standalone JAR
2. **Faster Startup** - Seconds instead of minutes
3. **Dev Mode** - Hot reload without restarts
4. **Native Image Support** - Can compile to native executable
5. **Kubernetes Native** - Built-in support for cloud deployments
6. **Reactive Messaging** - Modern async patterns instead of MDBs
7. **Configuration** - All in `application.properties` instead of XML

## Support

- Quarkus Documentation: https://quarkus.io/guides/
- Quarkus Community: https://quarkus.io/community/
- Issue Tracker: (Internal project repository)
