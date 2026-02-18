#!/bin/bash

# Define the base directory for plugins
BASE_DIR="plugins"
mkdir -p "$BASE_DIR"

# List of all plugins to create (including the new No-Op and Allow-All)
PLUGINS=(
    "operio-audit-slf4j"
    "operio-audit-noop"
    "operio-consent-embedded"
    "operio-consent-allow-all"
    "operio-identity-http"
    "operio-identity-spring-security"
    "operio-masking-jackson"
    "operio-resolver-jackson"
)

# Mapping of plugins to their respective SPI interfaces
declare -A SPI_MAP
SPI_MAP["operio-audit-slf4j"]="com.koostack.operio.core.service.AuditLogger"
SPI_MAP["operio-audit-noop"]="com.koostack.operio.core.service.AuditLogger"
SPI_MAP["operio-consent-embedded"]="com.koostack.operio.core.service.ConsentService"
SPI_MAP["operio-consent-allow-all"]="com.koostack.operio.core.service.ConsentService"
SPI_MAP["operio-identity-http"]="com.koostack.operio.core.service.IdentityResolver"
SPI_MAP["operio-identity-spring-security"]="com.koostack.operio.core.service.IdentityResolver"
SPI_MAP["operio-masking-jackson"]="com.koostack.operio.core.service.MaskingStrategy"
SPI_MAP["operio-resolver-jackson"]="com.koostack.operio.core.service.SensitiveFieldResolver"

for PLUGIN in "${PLUGINS[@]}"; do
    echo "Scaffolding $PLUGIN..."
    
    # Generate clean package names (e.g., com.koostack.operio.plugin.audit.slf4j)
    PKG_SUFFIX=$(echo "$PLUGIN" | sed 's/operio-//' | tr '-' '.')
    FULL_PKG="com.koostack.operio.plugin.$PKG_SUFFIX"
    DIR_PATH="$BASE_DIR/$PLUGIN/src/main/java/$(echo "$FULL_PKG" | tr '.' '/')"
    RESOURCES_PATH="$BASE_DIR/$PLUGIN/src/main/resources/META-INF/services"
    
    mkdir -p "$DIR_PATH"
    mkdir -p "$RESOURCES_PATH"

    # Create the Gradle build file using your established conventions
    cat <<EOF > "$BASE_DIR/$PLUGIN/build.gradle.kts"
plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.maven-publish")
}

dependencies {
    implementation(project(":operio-core"))
    // TODO: Add specific implementation dependencies (e.g., Jackson, Spring Security, SLF4J)
}
EOF

    # Generate a CamelCase class name (e.g., operio-consent-allow-all -> ConsentAllowAll)
    CLASS_NAME=$(echo "$PLUGIN" | sed 's/operio-//' | awk -F'-' '{for(i=1;i<=NF;i++){printf "%s%s", toupper(substr($i,1,1)), substr($i,2)}}')
    SPI_INTERFACE=$(echo "${SPI_MAP[$PLUGIN]}" | awk -F. '{print $NF}')

    # Create the dummy Implementation class
    cat <<EOF > "$DIR_PATH/${CLASS_NAME}.java"
package $FULL_PKG;

import ${SPI_MAP[$PLUGIN]};

public class ${CLASS_NAME} implements $SPI_INTERFACE {
    // TODO: Implement required SPI methods
}
EOF

    # Create the SPI service registration file in META-INF
    echo "$FULL_PKG.${CLASS_NAME}" > "$RESOURCES_PATH/${SPI_MAP[$PLUGIN]}"

done

echo "Scaffolding complete. 🚀"