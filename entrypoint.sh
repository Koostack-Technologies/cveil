#!/bin/sh
set -e

BUILD_TYPE=$(cat /app/.build-type 2>/dev/null || echo "unknown")

if [ "$BUILD_TYPE" = "quarkus" ] && [ -f /app/quarkus-app/quarkus-run.jar ]; then
    echo "Starting Cveil REST API (Quarkus fast-jar)..."
    exec java \
        ${JAVA_OPTS:--XX:+UseZGC -Xms128m -Xmx512m} \
        --enable-preview \
        -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
        -jar /app/quarkus-app/quarkus-run.jar \
        "$@"
elif [ "$BUILD_TYPE" = "dist" ] && [ -f /app/dist/bin/cveil-rest-api ]; then
    echo "Starting Cveil REST API (application distribution)..."
    export JAVA_OPTS="${JAVA_OPTS:--XX:+UseZGC -Xms128m -Xmx512m --enable-preview}"
    exec /app/dist/bin/cveil-rest-api "$@"
else
    echo "ERROR: No runnable application found. Check the Docker build output." >&2
    exit 1
fi
