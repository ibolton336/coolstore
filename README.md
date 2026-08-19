# CoolStore Monolith - Quarkus 3 Application

This application has been migrated from Java EE 7 (JBoss EAP 7.4) to Quarkus 3.8.1.

## Prerequisites

* Java 21 (OpenJDK)
* Maven 3.8.5 or higher
* PostgreSQL 13+
* Keycloak 20.0.5 or higher
* Apache Artemis (or use embedded option)
* podman or docker

## Start PostgreSQL Database

```bash
podman run --name myPostgresDb \
   -p 5432:5432 \
   -e POSTGRES_USER=postgresUser \
   -e POSTGRES_PASSWORD=postgresPW \
   -e POSTGRES_DB=postgresDB \
   -d postgres
```

## Start Keycloak

Extract keycloak-20.0.5.zip

```bash
cd keycloak-20.0.5
./bin/kc.sh start-dev --http-port=8081
```

Open http://127.0.0.1:8081 in your browser

1. Set an administrator username and password, then login to keycloak
2. Click on the "Master" dropdown, and select "Create Realm"
3. Click on "Browse" and locate the file `realm-export.json` in this repo
4. Click on "Create" to create the "eap" realm
5. Click on "Users" and "Create new user"
6. Enter a username, e.g. "user1" and click on "Create"
7. From the next form, click on the "Credentials" tab and "Set password"
8. Set a password and password confirmation, and unselect "Temporary"
9. Click on "Save" to store the password

Keycloak is now configured correctly.

## Configure JMS (Artemis)

You have two options:

### Option 1: External Artemis Server
Download and install Apache Artemis, then update `application.properties`:
```properties
quarkus.artemis.url=tcp://localhost:61616
quarkus.artemis.username=admin
quarkus.artemis.password=admin
```

### Option 2: Use in-VM Artemis (Development)
Quarkus can use an embedded Artemis server for development. Update `application.properties`:
```properties
quarkus.artemis.url=vm://0
```

## Build and Run the Application

### Development Mode (with live reload)
```bash
./mvnw quarkus:dev
```

Navigate to http://127.0.0.1:8080

### Package and Run
```bash
# Package the application
./mvnw package

# Run the packaged application
java -jar target/quarkus-app/quarkus-run.jar
```

### Native Build (Optional)
```bash
./mvnw package -Pnative
./target/coolstore-monolith-runner
```

## Application Endpoints

* **Main Application**: http://127.0.0.1:8080
* **REST API**: http://127.0.0.1:8080/services/
  * Products: http://127.0.0.1:8080/services/products/
  * Cart: http://127.0.0.1:8080/services/cart/{cartId}
  * Orders: http://127.0.0.1:8080/services/orders/
* **Health Check**: http://127.0.0.1:8080/q/health
* **Metrics**: http://127.0.0.1:8080/q/metrics

## Configuration

All configuration is in `src/main/resources/application.properties`. Key settings:

### Database
```properties
quarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/postgresDB
quarkus.datasource.username=postgresUser
quarkus.datasource.password=postgresPW
```

### Keycloak/OIDC
```properties
quarkus.oidc.auth-server-url=http://127.0.0.1:8081/realms/eap
quarkus.oidc.client-id=coolstore-app
```

### Messaging (JMS)
```properties
quarkus.artemis.url=tcp://localhost:61616
mp.messaging.outgoing.orders.destination=orders
mp.messaging.incoming.orders.destination=orders
```

## Testing the Application

1. Navigate to http://127.0.0.1:8080
2. Browse the product catalog
3. Click "Sign in" in the top right
4. Login with the user credentials created on Keycloak (e.g., user1)
5. Add items to cart
6. Complete the checkout process
7. Check application logs to see order processing messages

## Migration from Java EE 7

This application has been fully migrated from Java EE 7 to Quarkus 3. Major changes include:

* **Build**: WAR → Quarkus JAR packaging
* **Java**: Version 8 → 21
* **APIs**: javax.* → jakarta.*
* **Services**: EJB (@Stateless/@Stateful) → CDI (@ApplicationScoped/@SessionScoped)
* **Persistence**: persistence.xml → application.properties configuration
* **Messaging**: Message-Driven Beans → Quarkus Reactive Messaging
* **Audit Library**: Upgraded to version 2.0.0 with async TCP streaming

See `/tmp/MIGRATION_SUMMARY.md` for detailed migration documentation.

## Troubleshooting

### Database Connection Issues
Verify PostgreSQL is running:
```bash
podman ps | grep postgres
```

### Keycloak Connection Issues
Verify Keycloak is accessible:
```bash
curl http://127.0.0.1:8081/realms/eap
```

### Messaging Issues
Check Artemis configuration in `application.properties` and ensure the broker is running.

### View Logs
In dev mode, logs appear in the console. For packaged apps:
```bash
java -jar target/quarkus-app/quarkus-run.jar 2>&1 | tee app.log
```

## Development Tips

* **Live Reload**: In dev mode (`./mvnw quarkus:dev`), code changes are automatically reloaded
* **Dev Services**: Quarkus can automatically start PostgreSQL in a container if not available
* **Dev UI**: Access http://localhost:8080/q/dev/ in dev mode for additional tools

![coolstore](assets/coolstore.png "coolstore")
