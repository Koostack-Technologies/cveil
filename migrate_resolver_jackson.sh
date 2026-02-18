#!/bin/bash

# Configuration
OLD_MODULE="operio-sqlite"
NEW_MODULE="operio-jooq"
GROUP_ID="io.operio"

echo "============================================="
echo "MIGRATION (GRADLE): $OLD_MODULE -> $NEW_MODULE"
echo "============================================="

# 1. Clean up Old Module
if [ -d "$OLD_MODULE" ]; then
    echo "Deleting $OLD_MODULE..."
    rm -rf "$OLD_MODULE"
    echo "✓ Deleted $OLD_MODULE"
else
    echo "Warning: '$OLD_MODULE' not found. Skipping deletion."
fi

# 2. Scaffold New Module Structure
if [ -d "$NEW_MODULE" ]; then
    echo "Warning: '$NEW_MODULE' already exists. Skipping creation."
else
    echo "Creating directory structure for $NEW_MODULE..."
    mkdir -p "$NEW_MODULE/src/main/java"
    mkdir -p "$NEW_MODULE/src/main/resources"
    mkdir -p "$NEW_MODULE/src/test/java"
    echo "✓ Directories created"
fi

# 3. Generate build.gradle
BUILD_FILE="$NEW_MODULE/build.gradle"
echo "Generating $BUILD_FILE..."

# Note: Using 'java-library' plugin which is best practice for modules
cat <<EOF > "$BUILD_FILE"
plugins {
    id 'java-library'
}

dependencies {
    // Internal Project Dependency
    api project(':operio-core')

    // jOOQ Open Source Edition
    // Note: Version is often managed in the root build.gradle or platform
    implementation 'org.jooq:jooq:3.19.5' 
    
    // Standard JDBC/Persistence API
    implementation 'jakarta.persistence:jakarta.persistence-api:3.1.0'
    
    // Test Dependencies
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
}

test {
    useJUnitPlatform()
}
EOF
echo "✓ build.gradle created"

# 4. Update settings.gradle (Auto-append if simple)
SETTINGS_FILE="settings.gradle"
if [ -f "$SETTINGS_FILE" ]; then
    # Check if already included
    if grep -q "$NEW_MODULE" "$SETTINGS_FILE"; then
        echo "✓ $NEW_MODULE is already in settings.gradle"
    else
        echo "Attempting to add $NEW_MODULE to settings.gradle..."
        # Appends standard include syntax. 
        # CAUTION: Review this change if your settings file is complex/Kotlin DSL.
        echo "" >> "$SETTINGS_FILE"
        echo "include '$NEW_MODULE'" >> "$SETTINGS_FILE"
        echo "✓ Added include to settings.gradle"
    fi
    
    # Optional: Remove old module from settings
    # This is a basic sed replacement; use with caution.
    if grep -q "$OLD_MODULE" "$SETTINGS_FILE"; then
        echo "Removing $OLD_MODULE from settings.gradle..."
        # Works on Linux (GNU sed) and macOS (BSD sed)
        sed -i.bak "/include '$OLD_MODULE'/d" "$SETTINGS_FILE" && rm "${SETTINGS_FILE}.bak"
        echo "✓ Removed old module ref from settings.gradle"
    fi
else
    echo "Warning: settings.gradle not found in root. You must manually register the module."
fi

echo "============================================="
echo "DONE. Run './gradlew clean build' to verify."
echo "============================================="