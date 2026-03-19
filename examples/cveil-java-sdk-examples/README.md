# cveil Java SDK Examples

Self-contained examples demonstrating every major feature of the [cveil Java SDK](../../sdks/cveil-sdk-java/).
Each example serves dual duty as **e2e validation** and **documentation snippets**.

## Prerequisites

- Java 21+
- A running cveil environment (local or remote)
- A valid tenant API token

## Configuration

All examples read their configuration from environment variables:

```bash
export CVEIL_API_URL=http://localhost:8080    # cveil REST API base URL
export CVEIL_API_TOKEN=cveil_...              # Tenant API token (required)
export CVEIL_JURISDICTION=EU                  # Default jurisdiction (optional, defaults to EU)
```

## Running Examples

### Run all examples (e2e test suite)

```bash
./gradlew :examples:cveil-java-sdk-examples:run
```

### Run individual examples

```bash
./gradlew :examples:cveil-java-sdk-examples:runCatalogue
./gradlew :examples:cveil-java-sdk-examples:runGovernance
./gradlew :examples:cveil-java-sdk-examples:runSubjects
./gradlew :examples:cveil-java-sdk-examples:runConsent
./gradlew :examples:cveil-java-sdk-examples:runMasking
./gradlew :examples:cveil-java-sdk-examples:runBatch
./gradlew :examples:cveil-java-sdk-examples:runDsar
```

## Examples

| # | Example | Description |
|---|---------|-------------|
| 01 | [CatalogueSetup](src/main/java/com/koostack/cveil/examples/CatalogueSetup.java) | Register entities, fields, categories, and link them |
| 02 | [GovernanceSetup](src/main/java/com/koostack/cveil/examples/GovernanceSetup.java) | Create purposes, regulations, jurisdictions, policies, and wire them together |
| 03 | [SubjectManagement](src/main/java/com/koostack/cveil/examples/SubjectManagement.java) | Full subject lifecycle — register, list, get, update, remove |
| 04 | [ConsentLifecycle](src/main/java/com/koostack/cveil/examples/ConsentLifecycle.java) | Grant, revoke, evaluate consent; view summary and audit history |
| 05 | [DataMasking](src/main/java/com/koostack/cveil/examples/DataMasking.java) | Real-time field masking with and without consent, jurisdiction overrides |
| 06 | [BatchOperations](src/main/java/com/koostack/cveil/examples/BatchOperations.java) | Batch grant/revoke (up to 1000 scopes) and batch masking |
| 07 | [DsarCompliance](src/main/java/com/koostack/cveil/examples/DsarCompliance.java) | GDPR Article 17 (erasure) and Article 20 (data portability) |

## Snippet Markers

Each example contains `// [START snippet_name]` and `// [END snippet_name]` markers
that can be extracted for embedding in documentation. For example:

```java
// [START grant_consent]
var response = client.grant(new GrantRequest(
        "consent-user",
        new ConsentScope("PII", "*", "*", "Marketing"),
        LocalDateTime.now().plusDays(30).toString()
));
// [END grant_consent]
```

## Self-Cleanup

Every example cleans up the resources it creates in a `finally` block, making them
safe to run repeatedly against the same environment. If cleanup fails partially,
a warning is printed but the example still reports success/failure based on the
main operations.
