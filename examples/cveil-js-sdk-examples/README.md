# cveil JS/TypeScript SDK Examples

Self-contained examples demonstrating every major feature of the [cveil JS SDK](../../sdks/cveil-sdk-js/).
Each example serves dual duty as **e2e validation** and **documentation snippets**.

## Prerequisites

- Node.js 20+
- A running cveil environment (local or remote)
- A valid tenant API token

## Setup

```bash
cd examples/cveil-js-sdk-examples
npm install
```

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
npm start
```

### Run individual examples

```bash
npm run example:catalogue
npm run example:governance
npm run example:subjects
npm run example:consent
npm run example:masking
npm run example:batch
npm run example:dsar
```

## Examples

| # | Example | Description |
|---|---------|-------------|
| 01 | [catalogue-setup](src/01-catalogue-setup.ts) | Register entities, fields, categories, and link them |
| 02 | [governance-setup](src/02-governance-setup.ts) | Create purposes, regulations, jurisdictions, policies, and wire them together |
| 03 | [subject-management](src/03-subject-management.ts) | Full subject lifecycle — register, list, get, update, remove |
| 04 | [consent-lifecycle](src/04-consent-lifecycle.ts) | Grant, revoke, evaluate consent; view summary and audit history |
| 05 | [data-masking](src/05-data-masking.ts) | Real-time field masking with and without consent, jurisdiction overrides |
| 06 | [batch-operations](src/06-batch-operations.ts) | Batch grant/revoke (up to 1000 scopes) and batch masking |
| 07 | [dsar-compliance](src/07-dsar-compliance.ts) | GDPR Article 17 (erasure) and Article 20 (data portability) |

## Snippet Markers

Each example contains `// [START snippet_name]` and `// [END snippet_name]` markers
that can be extracted for embedding in documentation. For example:

```typescript
// [START grant_consent]
const response = await client.grant({
  subject: "consent-user",
  scope: { category: "PII", fieldName: "*", entityName: "*", purposeName: "Marketing" },
  expiresAt,
});
// [END grant_consent]
```

## Self-Cleanup

Every example cleans up the resources it creates in a `finally` block, making them
safe to run repeatedly against the same environment. If cleanup fails partially,
a warning is printed but the example still reports success/failure based on the
main operations.
