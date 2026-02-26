# =============================================================================
# Operio REST API — Production Dockerfile
#
# Multi-stage build that produces a minimal container image for the Quarkus
# REST API.  Two build strategies are supported:
#
#   1. Quarkus fast-jar (preferred) — requires the Quarkus Gradle plugin.
#      Produces the optimal Quarkus packaging with build-time augmentation,
#      fast startup, and low memory footprint.
#
#   2. Gradle application distribution (fallback) — uses the standard
#      Gradle `application` plugin's `installDist` task.  Available as a
#      fallback if the Quarkus plugin is unavailable.
#
# Build strategy is selected via the OPERIO_BUILD_STRATEGY build arg:
#
#   docker build -t operio-rest-api .                              # auto-detect
#   docker build -t operio-rest-api --build-arg BUILD_STRATEGY=quarkus .
#   docker build -t operio-rest-api --build-arg BUILD_STRATEGY=dist .
#
# For the native image variant, see the companion Dockerfile.native at the
# bottom of this file (commented out) or build with:
#
#   docker build -t operio-rest-api-native -f Dockerfile.native .
#
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# -- Gradle wrapper & dependency resolution (cached layer) --------------------
# Copy only the files needed for dependency resolution first.  This lets
# Docker cache the expensive dependency-download layer separately from the
# source code layer, so rebuilds after code-only changes skip resolution.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/

# Copy all subproject build files (for dependency resolution).
# We use a find-and-copy pattern to avoid breaking the cache when source
# files change but build files don't.
COPY operio-core/build.gradle.kts operio-core/build.gradle.kts
COPY operio-rest-api/build.gradle.kts operio-rest-api/build.gradle.kts
COPY operio-spring-boot-starter/build.gradle.kts operio-spring-boot-starter/build.gradle.kts
COPY operio-bundle-core/build.gradle.kts operio-bundle-core/build.gradle.kts
COPY operio-bundle-embedded/build.gradle.kts operio-bundle-embedded/build.gradle.kts
COPY plugins/ plugins/

# Pre-download dependencies (cached unless build files change).
# The `dependencies` task resolves all configurations without compiling.
RUN chmod +x gradlew && \
    ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# -- Full source copy & build ------------------------------------------------
COPY . .

# Build argument to select the packaging strategy.
# - "quarkus"  — use `quarkusBuild` (requires Quarkus Gradle plugin enabled)
# - "dist"     — use `installDist` from the Gradle application plugin
# - "auto"     — try `quarkusBuild` first, fall back to `installDist`
ARG BUILD_STRATEGY=auto

RUN chmod +x gradlew && \
    if [ "$BUILD_STRATEGY" = "quarkus" ]; then \
        echo "==> Building with Quarkus fast-jar (quarkusBuild)..." && \
        ./gradlew :operio-rest-api:quarkusBuild --no-daemon; \
    elif [ "$BUILD_STRATEGY" = "dist" ]; then \
        echo "==> Building with Gradle application distribution (installDist)..." && \
        ./gradlew :operio-rest-api:installDist --no-daemon; \
    else \
        echo "==> Auto-detecting build strategy..." && \
        if ./gradlew :operio-rest-api:quarkusBuild --no-daemon 2>/dev/null; then \
            echo "==> Quarkus fast-jar build succeeded."; \
        else \
            echo "==> quarkusBuild not available — falling back to installDist..." && \
            ./gradlew :operio-rest-api:installDist --no-daemon; \
        fi; \
    fi

# Determine which build output to flag for the runtime stage.
# If quarkus-app/ exists, the Quarkus fast-jar was produced.
# Otherwise, the application distribution is in build/install/.
# Ensure both output directories exist so COPY --from=build never fails
# in the runtime stage (Docker COPY errors if the source path is missing).
# Only one directory will contain actual content; the other stays empty.
RUN mkdir -p operio-rest-api/build/quarkus-app \
             operio-rest-api/build/install/operio-rest-api && \
    if [ -f "operio-rest-api/build/quarkus-app/quarkus-run.jar" ]; then \
        echo "quarkus" > /app/.build-type; \
    else \
        echo "dist" > /app/.build-type; \
    fi

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# -- Security: run as non-root ------------------------------------------------
RUN groupadd --gid 1001 operio && \
    useradd --uid 1001 --gid operio --shell /bin/false --create-home operio

WORKDIR /app

# -- Copy build output --------------------------------------------------------
# Copy both possible layouts; only one will exist after the build stage.
# Quarkus fast-jar layout:
COPY --from=build --chown=operio:operio /app/operio-rest-api/build/quarkus-app/ /app/quarkus-app/
# Gradle installDist layout:
COPY --from=build --chown=operio:operio /app/operio-rest-api/build/install/operio-rest-api/ /app/dist/
# Build type marker:
COPY --from=build --chown=operio:operio /app/.build-type /app/.build-type

# -- Entrypoint script --------------------------------------------------------
# Selects the correct launch command based on which build output exists.
COPY --chown=operio:operio entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# -- Labels -------------------------------------------------------------------
LABEL org.opencontainers.image.title="Operio REST API" \
      org.opencontainers.image.description="Multi-tenant consent management platform — Quarkus REST API" \
      org.opencontainers.image.vendor="KooStack" \
      org.opencontainers.image.url="https://github.com/koostack/operio" \
      org.opencontainers.image.source="https://github.com/koostack/operio" \
      org.opencontainers.image.licenses="Apache-2.0"

# -- Runtime configuration ----------------------------------------------------
# Port exposed by Quarkus HTTP server (configurable via QUARKUS_HTTP_PORT)
EXPOSE 8080

# Health check: hit the application health endpoint.
# Quarkus SmallRye Health is at /q/health; the custom Operio endpoint is
# at /api/v1/health.  Use the SmallRye one for Kubernetes-style probes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8080/q/health/ready || exit 1

# -- Environment variable defaults --------------------------------------------
# These can be overridden at `docker run` time or via docker-compose/k8s.
# No sensitive values are baked into the image.
ENV QUARKUS_HTTP_PORT=8080 \
    QUARKUS_VIRTUAL_THREADS_ENABLED=true \
    OPERIO_MODE=production

# Switch to non-root user
USER operio

ENTRYPOINT ["/app/entrypoint.sh"]
CMD []

# =============================================================================
# Native Image Variant (reference — use Dockerfile.native for actual builds)
# =============================================================================
#
# This variant produces a statically-linked native binary via GraalVM.
# It requires the Quarkus Gradle plugin to be enabled for native packaging.
#
# --- Dockerfile.native ---
#
# FROM ghcr.io/graalvm/native-image:21 AS build
# WORKDIR /app
# COPY . .
# RUN chmod +x gradlew && \
#     ./gradlew :operio-rest-api:build \
#         -Dquarkus.native.enabled=true \
#         -Dquarkus.native.container-build=true \
#         --no-daemon
#
# FROM quay.io/quarkus/quarkus-micro-image:2.0
# WORKDIR /app
# RUN chown 1001 /app && chmod "g+rwX" /app
# COPY --from=build --chown=1001:root /app/operio-rest-api/build/*-runner /app/application
# EXPOSE 8080
# USER 1001
# HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
#     CMD curl -sf http://localhost:8080/q/health/ready || exit 1
# ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
