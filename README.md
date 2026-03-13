# Cveil

Multi-tenant consent management platform built on Quarkus.

## Local Development

### Prerequisites

- **Docker** and **Docker Compose** (v2+)
- **Java 21+** (only if running the API outside Docker)
- **Bash** (for the certificate generation script)

### 1. Generate TLS Certificates

Run this once to create self-signed certificates for local Postgres and Redis:

```bash
./certs/local/generate-certs.sh
```

This creates a local CA and server certificates in `certs/local/`. The private keys and certs are gitignored.

### 2. Start the Full Stack

```bash
docker compose up --build
```

This builds the Quarkus application from source and starts three services:

| Service       | Port | Description                              |
|---------------|------|------------------------------------------|
| `cveil-api`  | 8080 | Quarkus REST API                         |
| `postgres`    | 5432 | PostgreSQL 16 with TLS                   |
| `redis`       | 6379 | Redis 7 with TLS                         |

### 3. Infrastructure-Only Mode (IDE Development)

To run only Postgres and Redis (and launch the API from your IDE):

```bash
docker compose up postgres redis
```

Then run the Quarkus application with the same environment variables defined in `docker-compose.yml` (adjust hosts from `postgres`/`redis` to `localhost`).

### 4. Create Your First Tenant

A bootstrap key is pre-configured for local development. Use it to create a tenant:

```bash
curl -X POST http://localhost:8080/api/v1/platform/tenants \
  -H "Authorization: Bearer opr_bootstrap_localdev_do_not_use_in_prod" \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "dev_tenant", "displayName": "Dev Tenant", "plan": "free"}'
```

### 5. Health Check

```bash
curl http://localhost:8080/q/health/ready
```

### 6. Tear Down

```bash
# Stop services
docker compose down

# Stop services AND delete all data volumes
docker compose down -v
```

### API Documentation

Swagger UI is available at [http://localhost:8080/q/swagger-ui](http://localhost:8080/q/swagger-ui) when the API is running.

---

## Deploying to Railway

### Required Services

Create three services in your Railway project:

1. **PostgreSQL** — use Railway's managed Postgres plugin
2. **Redis** — use Railway's managed Redis plugin
3. **Cveil API** — deploy from this repository

### Environment Variables

Set the following environment variables on the Cveil API service. Railway's managed Postgres and Redis provide connection details via template variables.

| Variable | Value | Notes |
|---|---|---|
| `CVEIL_MODE` | `production` | Required for tenant management |
| `CVEIL_APP_NAME` | `cveil` | Application identifier |
| `CVEIL_DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` | Consent database |
| `CVEIL_DB_DIALECT` | `POSTGRES` | |
| `CVEIL_DB_USERNAME` | `${{Postgres.PGUSER}}` | |
| `CVEIL_DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` | |
| `CVEIL_DB_SSL_MODE` | `require` | Railway Postgres uses TLS |
| `CVEIL_PLATFORM_DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` | Platform database (same DB, different schemas) |
| `CVEIL_PLATFORM_DB_DIALECT` | `POSTGRES` | |
| `CVEIL_PLATFORM_DB_USERNAME` | `${{Postgres.PGUSER}}` | |
| `CVEIL_PLATFORM_DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` | |
| `CVEIL_PLATFORM_DB_SSL_MODE` | `require` | |
| `CVEIL_REDIS_HOST` | `${{Redis.REDISHOST}}` | |
| `CVEIL_REDIS_PORT` | `${{Redis.REDISPORT}}` | |
| `CVEIL_REDIS_PASSWORD` | `${{Redis.REDISPASSWORD}}` | |
| `CVEIL_REDIS_TLS_ENABLED` | `true` | Railway Redis uses TLS |
| `CVEIL_REDIS_TLS_VERIFY_PEER` | `false` | Railway uses internal certs |
| `CVEIL_BOOTSTRAP_KEY` | *(generate a strong random value)* | Delete after creating your first API key |
| `PORT` | `8080` | Railway injects this; Quarkus listens on it |
| `QUARKUS_HTTP_PORT` | `${{PORT}}` | Bind to Railway's assigned port |
| `QUARKUS_HTTP_HOST` | `0.0.0.0` | Accept connections from the Railway proxy |

### Build Configuration

Railway auto-detects the `Dockerfile` at the repository root. The build uses `BUILD_STRATEGY=quarkus` (default in the Dockerfile) which produces a Quarkus fast-jar deployment.

### Health Check

Configure Railway's health check to poll:

```
GET /q/health/ready
```

Set the health check path in Railway's service settings. The endpoint returns `200 OK` with `{"status":"UP"}` when the service is ready.

### Post-Deployment Setup

1. Deploy the service and wait for it to become healthy.
2. Create your first tenant using the bootstrap key (same curl as local dev, but using your Railway domain).
3. **Delete the `CVEIL_BOOTSTRAP_KEY` environment variable** after creating your first real API key — the bootstrap key is only needed for initial setup.

---

## License

See [LICENSE](LICENSE) for details.