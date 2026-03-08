# CQRS Refactor Guide

## CommandService to Actuator Conversion

This document describes the pattern for extracting inline command logic from a `CommandService` implementation into a standalone `Actuator` class. The goal is to move each command method into its own single-responsibility class that implements `Actuator<I, O>` from `operio-core`.

### The Pattern

Every method on a `CommandService` (e.g. `IamCommandService`, `CoreCommandService`, `BillingCommandService`) follows the same shape:

1. Accept scalar inputs (strings, sets, booleans, etc.)
2. Validate those inputs
3. Construct a domain event payload
4. Publish the event via `EventService`
5. Return a result record

The refactor extracts each of these methods into its own class.

### Steps

#### 1. Create the Actuator class

Create a new class in the **same package** as the existing `CommandService` implementation. Name it `<Action>CommandActuator` (e.g. `RegisterUserCommandActuator`, `SuspendUserCommandActuator`).

The class implements `Actuator<Self.Command, Self.Result>`:

```java
public class RegisterUserCommandActuator
        implements Actuator<
                RegisterUserCommandActuator.RegisterUserCommand,
                RegisterUserCommandActuator.RegisterUserResult> {
    // ...
}
```

#### 2. Define the Command record inside the Actuator

Take every parameter from the original `CommandService` method signature and bundle them into a `record` declared as a public nested type of the actuator:

```java
// Before — method signature on IamCommandService:
RegisterUserResult registerUser(
        String realmId, String username, String email,
        String fullName, Set<String> roles, boolean temporaryPassword);

// After — record inside the actuator:
public record RegisterUserCommand(
        String realmId, String username, String email,
        String fullName, Set<String> roles, boolean temporaryPassword) {}
```

#### 3. Move the Result record into the Actuator

If the `CommandService` interface declares a result record for this method (e.g. `RegisterUserResult`), create an equivalent public nested record inside the actuator:

```java
public record RegisterUserResult(String username, String email, String realmId) {}
```

> **Note:** The original result record on the `CommandService` interface stays in place for now so existing callers are unaffected. The `CommandService` method maps from the actuator's result to the interface's result.

#### 4. Implement `execute(EventService<?>, Command)`

Move the **entire method body** — validation, event construction, publishing, logging, and result creation — from the `CommandService` into the actuator's `execute` method. The `EventService` is provided as the first argument by the `Actuator` contract:

```java
@Override
public RegisterUserResult execute(EventService<?> publisher, RegisterUserCommand command) {
    // validation
    // build domain event payload
    // publish via publisher.publishEvent(...)
    // return result
}
```

Any private helpers that are **only** used by this command (e.g. a specific `deriveAggregateId` variant) should move into the actuator as `private static` methods.

#### 5. Update the CommandService to delegate

In the `CommandService` implementation:

1. Add a field for the actuator instance:
   ```java
   private final RegisterUserCommandActuator registerUserActuator =
           new RegisterUserCommandActuator();
   ```

2. Replace the method body with delegation:
   ```java
   @Override
   public RegisterUserResult registerUser(
           String realmId, String username, String email,
           String fullName, Set<String> roles, boolean temporaryPassword) {
       var command = new RegisterUserCommandActuator.RegisterUserCommand(
               realmId, username, email, fullName, roles, temporaryPassword);
       var result = registerUserActuator.execute(eventService, command);
       return new RegisterUserResult(result.username(), result.email(), result.realmId());
   }
   ```

### Void-returning commands

When a command method returns `void`, the actuator uses `Void` as its output type parameter and returns `null`:

```java
public class SuspendUserCommandActuator
        implements Actuator<SuspendUserCommandActuator.SuspendUserCommand, Void> {

    @Override
    public Void execute(EventService<?> publisher, SuspendUserCommand command) {
        // validation, event construction, publishing ...
        return null;
    }
}
```

The `CommandService` method simply calls `execute` and discards the result:

```java
@Override
public void suspendUser(String realmId, String emailOrUsername) {
    String userId = requireUserId(realmId, emailOrUsername);
    var command = new SuspendUserCommandActuator.SuspendUserCommand(
            realmId, userId, emailOrUsername);
    suspendUserActuator.execute(eventService, command);
}
```

### Pre-resolved dependencies in the Command record

Some commands depend on infrastructure queries that the actuator should not own (e.g. resolving a username to a user ID via `IamQueryService`). In these cases, the `CommandService` performs the resolution **before** constructing the command record, and the command carries the already-resolved value:

```java
// CommandService resolves the userId, then passes it into the command
String userId = requireUserId(realmId, emailOrUsername);
var command = new AssignRoleCommand(realmId, userId, role, emailOrUsername);
```

The original identifier (`emailOrUsername`) is kept in the command for logging purposes only.

### What stays on the CommandService

- The `Plugin<T>` lifecycle (`getName`, `getPriority`, `configure`, `isReady`, etc.)
- The `EventService<?>` field and any shared infrastructure references (e.g. `DataSource`, `QueryService`)
- Shared private helpers still used by multiple methods (e.g. `requireUserId`) — these migrate out only when their last consumer is extracted
- Deprecated / legacy methods that don't follow the event-sourced pattern (e.g. direct JDBC API key methods)

### What moves to the Actuator

| Concern | Location |
|---|---|
| Command inputs | Nested `Command` record |
| Result type | Nested `Result` record (or `Void` for void methods) |
| Input validation | `execute()` body |
| Domain event construction | `execute()` body |
| Event publishing | `execute()` body via the `EventService` parameter |
| Command-specific helpers | `private static` methods on the actuator |

### Completed conversions

All non-deprecated methods in `JooqIamCommandService` (`platform/operio-iam-command`) have been extracted:

| Actuator | Command record | Result | Original method |
|---|---|---|---|
| `RegisterUserCommandActuator` | `RegisterUserCommand` | `RegisterUserResult` | `registerUser` |
| `SuspendUserCommandActuator` | `SuspendUserCommand` | `Void` | `suspendUser` |
| `ActivateUserCommandActuator` | `ActivateUserCommand` | `Void` | `activateUser` |
| `CreateServiceAccountCommandActuator` | `CreateServiceAccountCommand` | `CreateServiceAccountResult` | `createServiceAccount` |
| `RevokeServiceAccountCommandActuator` | `RevokeServiceAccountCommand` | `Void` | `revokeServiceAccount` |
| `AssignRoleCommandActuator` | `AssignRoleCommand` | `Void` | `assignRole` |
| `RemoveRoleCommandActuator` | `RemoveRoleCommand` | `Void` | `removeRole` |

---

## Phase 2: Absorbing Projection Side-Effects into Actuators

> **IAM-specific.** This phase applies exclusively to the IAM command/projection pair where the
> projection handler materializes domain events into Keycloak Admin API calls. Other projections
> (Core, Billing, Consent) write to read-model tables and remain asynchronous — they do **not**
> follow this pattern.

### Motivation

In Phase 1 the actuator publishes a domain event and the projection handler asynchronously
translates it into a Keycloak API call. This creates a window where the event store says "user
registered" but Keycloak has not yet created the user. For IAM operations where the caller expects
the side-effect to be complete when the HTTP response returns, this gap is unacceptable.

Phase 2 moves the Keycloak side-effect **into** the actuator so that it executes *before* the event
is published. If Keycloak fails, no event is recorded — the two systems stay consistent.

### The Pattern

```
Actuator.execute()

  1. Validate command inputs
  2. Perform external side-effect (e.g. KC call)    <-- NEW in Phase 2
  3. Build domain event payload
  4. Publish event to EventService
  5. Return result
```

If step 2 fails, steps 3-5 never execute. The event store only records operations that actually
succeeded in the external system.

### Steps

#### 1. Add the external client to the Actuator constructor

The actuator needs the infrastructure client (e.g. `Keycloak`) injected at construction time:

```java
public class RegisterUserCommandActuator
        implements Actuator<RegisterUserCommand, RegisterUserResult> {

    private final Keycloak adminClient;

    public RegisterUserCommandActuator(Keycloak adminClient) {
        this.adminClient = adminClient;
    }
}
```

#### 2. Add the external client to the CommandConfig record

The `CommandConfig` record (e.g. `JooqIamCommandConfig`) gains the new dependency so it can be
passed through during `configure()`:

```java
public record JooqIamCommandConfig(
        EventService<?> eventService,
        DataSource platformDataSource,
        Keycloak keycloakAdmin) { ... }
```

Add backward-compatible overloaded constructors (passing `null` for the new field) so existing
callers that don't need the client still compile.

#### 3. Wire the client in `configure()`

The `CommandService.configure()` method now instantiates the actuator with the client instead of
at field-declaration time:

```java
@Override
public void configure(JooqIamCommandConfig config) {
    this.eventService = config.eventService();
    this.registerUserActuator = new RegisterUserCommandActuator(config.keycloakAdmin());
}
```

#### 4. Move the projection logic into `execute()` before the event publish

Take the body of the projection handler method (e.g. `handleUserRegistered`) and place it in the
actuator's `execute()` method **before** the `publisher.publishEvent(...)` call:

```java
@Override
public RegisterUserResult execute(EventService<?> publisher, RegisterUserCommand command) {
    // validation ...

    // Phase 2: perform Keycloak side-effect BEFORE publishing event
    String keycloakUserId = createKeycloakUser(command, userId, roles);

    // Phase 1: publish domain event (only reached if KC succeeded)
    publisher.publishEvent(...);

    return new RegisterUserResult(...);
}
```

Key concerns when moving the logic:
- **Idempotency** — keep the "check if already exists" guard from the projection handler.
- **Error handling** — let exceptions propagate so the event is never written on failure.
- **Logging** — update log messages to reflect that the operation is synchronous, not projected.

#### 5. Convert the projection handler method to a no-op

The projection handler method becomes an acknowledgement stub so that replaying the event stream
does not trigger "unhandled event" warnings:

```java
private void handleUserRegistered(String payload) throws Exception {
    // No-op: handled synchronously by RegisterUserCommandActuator.
    var node = mapper.readTree(payload);
    LOG.debug("UserRegistered event acknowledged (actuator-handled): realmId={}, email={}",
            node.path("realmId").asText(), node.path("email").asText());
}
```

#### 6. Update the producer / wiring layer

Ensure the `Keycloak` admin client is constructed **before** `CommandService.configure()` is
called. In `OperioProducers`, this means moving the Keycloak builder block above the early-return
guard for Redis (which gates projection wiring but should not gate command-service wiring).

#### 7. Clean up dead code

After the move, remove from the projection handler:
- The old method body
- Any helper methods that were only used by that handler (e.g. `extractUserId`)
- Unused imports (e.g. `CredentialRepresentation`, `KeycloakBuilder`)

### What this does NOT apply to

| Domain | Projection target | Stays async? |
|---|---|---|
| **IAM** (user, service account, role) | Keycloak Admin API | **No** — moved into actuator |
| **Core** (tenant lifecycle) | Platform read-model tables (jOOQ) | Yes |
| **Billing** (plans, usage) | Platform read-model tables (jOOQ) | Yes |
| **Consent** (policy, entity, field, etc.) | Tenant read-model tables + Redis | Yes |

The IAM domain is unique because its projection target is an **external stateful system**
(Keycloak) where the caller needs synchronous confirmation. All other projections write to
databases or caches that the system controls and can tolerate eventual consistency.

### Reference implementation

All IAM actuators in `platform/operio-iam-command` now perform Keycloak side-effects before
publishing their domain event:

| Actuator | Keycloak operation |
|---|---|
| `RegisterUserCommandActuator` | Create user, assign roles, send password email |
| `SuspendUserCommandActuator` | Disable user (`setEnabled(false)`) |
| `ActivateUserCommandActuator` | Re-enable user (`setEnabled(true)`) |
| `AssignRoleCommandActuator` | Assign realm role (auto-creates if missing) |
| `RemoveRoleCommandActuator` | Remove realm role (tolerates not-found) |
| `CreateServiceAccountCommandActuator` | Create confidential client, retrieve secret, assign roles |
| `RevokeServiceAccountCommandActuator` | Disable client (`setEnabled(false)`) |

The `IamProjectionHandler` is now a pure acknowledgement handler — every `handle*` method is a
no-op that logs the event for observability. The retry loop and Keycloak client field have been
removed.

### Platform Domain: Absorbing `DefaultPlatformService` Orchestration

Phase 2 absorption applies to the platform domain too, but the logic being absorbed comes from
a different place and follows a different shape.

#### Where the logic lives today

For IAM, the absorbed logic lived in the **projection handler** — a single handler method per
event type that called Keycloak. For the platform domain, the absorbed logic lives in
**`DefaultPlatformService`** — an orchestration layer that fans a single user-facing operation
out to multiple command services with interleaved validation.

The canonical example is `createTenant()`:

```
DefaultPlatformService.createTenant():
  1. coreQueryService.getTenant()           ← uniqueness check
  2. planQueryService.getPlan()             ← plan validation + limit resolution
  3. coreCommandService.createTenant()      ← event
  4. billingCommandService.createBillingProfile()  ← event
  5. billingCommandService.createSubscription()    ← event (with resolved plan limits)
  6. coreCommandService.emitProvisioningStarted()  ← event
```

Steps 1–2 are **validation/enrichment** that today depend on query services (read model).
Steps 3–6 are **command invocations** that each publish a domain event. All six steps are
bundled into a single method on `DefaultPlatformService`.

#### What gets absorbed — and where

Each validation concern moves into the actuator that **owns** the aggregate being validated:

| Validation | Absorbed by | Mechanism |
|---|---|---|
| Tenant name uniqueness | `CreateTenantCommandActuator` | Hydrates `TenantAggregate` — if non-null, throws |
| Plan exists and is active | `CreateSubscriptionCommandActuator` | Hydrates `PlanAggregate` (or queries `PlanQueryService` until plan events exist) to resolve limits and validate active status |

This is the same principle as IAM Phase 2 (side-effect before publish), but the "side-effect" is
**aggregate hydration + validation** rather than a Keycloak API call.

#### Multi-dispatch: the REST resource becomes the orchestrator

Because `createTenant` touches **two bounded contexts** (Core and Billing), absorption doesn't
collapse into a single actuator — it decomposes into **multiple sequential dispatches**:

```java
@Inject @Named("coreDispatcher") CommandDispatcher coreDispatcher;
@Inject @Named("billingDispatcher") CommandDispatcher billingDispatcher;

public Response createTenant(@Context SecurityContext sc, @Valid CreateTenantRequest req) {
    Roles.requireRole(sc, Roles.PLATFORM_ADMIN);

    // 1. Create tenant (actuator validates uniqueness via hydration)
    coreDispatcher.dispatch(
            new CreateTenantCommandActuator.CreateTenantCommand(
                    req.tenantId(), req.displayName(), req.plan()));

    // 2. Create billing profile
    billingDispatcher.dispatch(
            new CreateBillingProfileCommandActuator.CreateBillingProfileCommand(
                    req.tenantId(), req.billingEmail()));

    // 3. Create subscription (actuator validates plan + resolves limits via hydration)
    billingDispatcher.dispatch(
            new CreateSubscriptionCommandActuator.CreateSubscriptionCommand(
                    req.tenantId(), req.plan()));

    // 4. Emit provisioning started
    coreDispatcher.dispatch(
            new EmitProvisioningStartedCommandActuator.EmitProvisioningStartedCommand(
                    req.tenantId(), req.displayName(), req.plan()));

    return Response.ok(MessageResponse.of("Tenant created")).build();
}
```

**Key properties:**

- **Sequential dispatch** — each dispatch must succeed before the next is called. If
  `CreateTenantCommand` throws (duplicate name), the billing commands are never reached.
- **Validation is distributed** — instead of a single `DefaultPlatformService` method doing all
  validation upfront, each actuator owns the validation for its own invariants.
- **`DefaultPlatformService.createTenant()` becomes dead code** — the orchestration is fully
  absorbed by the REST resource. The `PlatformService` interface method can be deprecated.
- **No single-method-to-single-actuator mapping** — unlike IAM (where `registerUser` → one
  actuator → one dispatch), a platform operation like `createTenant` maps to N dispatches.

#### Comparison: IAM vs Platform absorption

| Aspect | IAM | Platform |
|---|---|---|
| Logic source | Projection handler | `DefaultPlatformService` |
| What's absorbed | External side-effect (Keycloak call) | Validation + enrichment (query → hydration) |
| Dispatch per operation | 1 | N (multiple bounded contexts) |
| Orchestrator after absorption | Actuator (self-contained) | REST resource (sequences dispatches) |
| Projection after absorption | No-op stub | Unchanged (still async) |

---

## Phase 3: CommandDispatcher SPI and Dispatcher Rename

### Motivation

In Phases 1–2 each actuator was stored as a named field on the `CommandService` implementation,
and the delegation methods manually constructed command records and called `.execute()`. This
worked but coupled the dispatcher to every actuator type at compile time and required a separate
delegation method per command.

Phase 3 introduces a generic `CommandDispatcher` SPI in `operio-core` and replaces the
per-method-field pattern with a `Map<Class<?>, Actuator<?,?>>` registry. A single `dispatch(input)`
method routes any command record to its actuator by runtime class lookup.

### New SPI: `CommandDispatcher`

Location: `operio-core/src/main/java/com/koostack/operio/core/cqrs/CommandDispatcher.java`

```java
public interface CommandDispatcher {
    <I, O> O dispatch(I input);
}
```

- **Input**: any command record (e.g. `RegisterUserCommand`, `SuspendUserCommand`)
- **Output**: the actuator's typed result (or `Void`/`null` for void commands)
- **Error**: throws `IllegalArgumentException` if no actuator is registered for the command type

### Concrete implementation: `KeycloakIamDispatcher`

Location: `platform/operio-iam-command` (same package, replaces `JooqIamCommandService`)

The dispatcher:
1. Implements both `CommandDispatcher` (new SPI) and `IamCommandService` (backward compat)
2. Stores actuators in a `Map<Class<?>, Actuator<?, ?>>` populated during `configure()`
3. Each `IamCommandService` method constructs the command record and calls `dispatch(command)`

```java
public class KeycloakIamDispatcher
        implements CommandDispatcher, IamCommandService<JooqIamCommandConfig> {

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();
    private EventService<?> eventService;

    @Override
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(eventService, input);
    }

    @Override
    public void configure(JooqIamCommandConfig config) {
        this.eventService = config.eventService();
        actuators.put(RegisterUserCommand.class, new RegisterUserCommandActuator(config.keycloakAdmin()));
        actuators.put(SuspendUserCommand.class,  new SuspendUserCommandActuator());
        // ... remaining actuators
    }

    // IamCommandService methods delegate to dispatch()
    @Override
    public RegisterUserResult registerUser(...) {
        var result = dispatch(new RegisterUserCommand(...));
        return new RegisterUserResult(result.username(), result.email(), result.realmId());
    }
}
```

### Steps to apply this pattern to another domain

#### 1. Create the dispatcher class

Name it `<Infrastructure><Domain>Dispatcher` (e.g. `KeycloakIamDispatcher`,
`JooqCoreDispatcher`). It implements:
- `CommandDispatcher` — the generic routing SPI
- The existing `CommandService` interface — for backward compatibility with REST resources

#### 2. Add the actuator registry

```java
private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();
```

#### 3. Populate the registry in `configure()`

Register each actuator keyed by its command record class:

```java
actuators.put(MyCommand.class, new MyCommandActuator(dependencies...));
```

#### 4. Implement `dispatch()`

A single unchecked-cast lookup + execute:

```java
@Override
@SuppressWarnings("unchecked")
public <I, O> O dispatch(I input) {
    Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
    if (actuator == null) {
        throw new IllegalArgumentException(
                "No actuator registered for: " + input.getClass().getName());
    }
    return actuator.execute(eventService, input);
}
```

#### 5. Delegate legacy interface methods to `dispatch()`

Each method on the old `CommandService` interface constructs the command record, calls
`dispatch()`, and maps the actuator's result to the interface's result type.

#### 6. Update wiring

Replace the old `CommandService` instantiation with the new dispatcher in the producer/config
layer. The dispatcher still implements the old interface, so no downstream changes are needed.

### What changed

| Before | After |
|---|---|
| `JooqIamCommandService` | `KeycloakIamDispatcher` |
| Per-actuator fields | `Map<Class<?>, Actuator<?,?>>` registry |
| Manual `.execute()` calls | Single `dispatch(input)` routing |
| No generic SPI | `CommandDispatcher` in `operio-core/cqrs` |

### Backward compatibility

The dispatcher still implements `IamCommandService<JooqIamCommandConfig>`, so:
- `PlatformCqrsHolder` continues to expose `IamCommandService<?>`
- `PlatformIamResource` calls `cqrs.iamCommandService().registerUser(...)` unchanged
- `IamDtos.UserRegisteredResponse.from(IamCommandService.RegisterUserResult)` unchanged

The old `JooqIamCommandService.java` file is superseded but not deleted yet — it can be removed
once all references are confirmed migrated.

---

## Phase 4: REST Resource Migration to CommandDispatcher

### Motivation

In Phase 3 the `KeycloakIamDispatcher` implemented both `CommandDispatcher` and the legacy
`IamCommandService` interface. REST resources continued to call `cqrs.iamCommandService()` which
returned the old typed interface. This means the resource layer still depended on the legacy
interface's method signatures and result records.

Phase 4 migrates REST endpoint methods one-by-one to call `cqrs.iamDispatcher().dispatch(command)`
directly, bypassing the `IamCommandService` interface entirely. The endpoint constructs the
actuator's command record, dispatches it, and maps the actuator's result record to the response DTO.

### The Pattern

```
Before (via IamCommandService):
  REST method → cqrs.iamCommandService().registerUser(args...)
              → IamCommandService.RegisterUserResult
              → UserRegisteredResponse.from(IamCommandService.RegisterUserResult)

After (via CommandDispatcher):
  REST method → new RegisterUserCommand(args...)
              → cqrs.iamDispatcher().dispatch(command)
              → RegisterUserCommandActuator.RegisterUserResult
              → UserRegisteredResponse.from(RegisterUserCommandActuator.RegisterUserResult)
```

### Steps

#### 1. Expose the dispatcher from the holder

Add an `iamDispatcher()` method to `PlatformCqrsHolder` that returns a `CommandDispatcher`. Since
`KeycloakIamDispatcher` implements both interfaces, this is a pattern-match cast:

```java
public CommandDispatcher iamDispatcher() {
    if (iamCommandService instanceof CommandDispatcher cd) {
        return cd;
    }
    throw new IllegalStateException(
            "IAM command service does not implement CommandDispatcher");
}
```

#### 2. Add a response DTO factory for the actuator's result type

The response DTO (e.g. `UserRegisteredResponse`) needs a `from(ActuatorResult)` overload so it
can map the actuator's nested result record directly:

```java
public record UserRegisteredResponse(String username, String email, String realmId) {
    // existing — maps from the legacy interface result
    public static UserRegisteredResponse from(IamCommandService.RegisterUserResult r) { ... }

    // new — maps from the actuator's result record
    public static UserRegisteredResponse from(
            RegisterUserCommandActuator.RegisterUserResult r) {
        return new UserRegisteredResponse(r.username(), r.email(), r.realmId());
    }
}
```

#### 3. Update the REST method

Replace the `cqrs.iamCommandService().someMethod(args...)` call with:
1. Construct the actuator's command record from the request DTO
2. Call `cqrs.iamDispatcher().dispatch(command)`
3. Map the typed result to the response DTO

```java
public Response registerUser(...) {
    var command = new RegisterUserCommandActuator.RegisterUserCommand(
            PLATFORM_REALM,
            request.username(),
            request.email(),
            request.fullName(),
            request.roles(),
            request.temporaryPassword());
    RegisterUserCommandActuator.RegisterUserResult result =
            cqrs.iamDispatcher().dispatch(command);
    return Response.status(Response.Status.CREATED)
            .entity(UserRegisteredResponse.from(result))
            .build();
}
```

#### 4. Repeat for each endpoint method

Each command endpoint in the resource follows the same pattern. Query endpoints (list, get,
authenticate) still use `cqrs.iamQueryService()` — the dispatcher only replaces command calls.

### What changes per endpoint

| REST method | Before | After |
|---|---|---|
| `registerUser` | `iamCommandService().registerUser(...)` | `iamDispatcher().dispatch(RegisterUserCommand)` |
| `suspendUser` | `iamCommandService().suspendUser(...)` | `iamDispatcher().dispatch(SuspendUserCommand)` |
| `activateUser` | `iamCommandService().activateUser(...)` | `iamDispatcher().dispatch(ActivateUserCommand)` |
| `createServiceAccount` | `iamCommandService().createServiceAccount(...)` | `iamDispatcher().dispatch(CreateServiceAccountCommand)` |
| `revokeServiceAccount` | `iamCommandService().revokeServiceAccount(...)` | `iamDispatcher().dispatch(RevokeServiceAccountCommand)` |
| `assignRole` | `iamCommandService().assignRole(...)` | `iamDispatcher().dispatch(AssignRoleCommand)` |
| `removeRole` | `iamCommandService().removeRole(...)` | `iamDispatcher().dispatch(RemoveRoleCommand)` |

### What stays unchanged

- Query endpoints (`listUsers`, `getUser`, `findUserByEmail`, `getUserRoles`, `listServiceAccounts`,
  `getServiceAccount`, `login`, `refresh`, `logout`) continue to use `cqrs.iamQueryService()`
- `PlatformCqrsHolder` still exposes `iamCommandService()` for any remaining legacy callers
- `IamCommandService` interface unchanged — backward compat maintained

### Reference implementation

All command endpoints in `PlatformIamResource` have been migrated:

- **`registerUser`** — dispatches `RegisterUserCommandActuator.RegisterUserCommand`, maps result
- **`suspendUser`** — resolves userId via `iamQueryService().resolveUserId()`, dispatches `SuspendUserCommand`
- **`activateUser`** — resolves userId, dispatches `ActivateUserCommand`
- **`assignRole`** — resolves userId, dispatches `AssignRoleCommand`
- **`removeRole`** — resolves userId, dispatches `RemoveRoleCommand`
- **`createServiceAccount`** — dispatches `CreateServiceAccountCommand`, maps result
- **`revokeServiceAccount`** — dispatches `RevokeServiceAccountCommand`

Query endpoints (`listUsers`, `getUser`, `findUserByEmail`, `getUserRoles`, `listServiceAccounts`,
`getServiceAccount`, `login`, `refresh`, `logout`) continue to use `cqrs.iamQueryService()`.

The `IamCommandService` import has been removed from `PlatformIamResource`. The deprecated
API key endpoints in `PlatformResource` still use `cqrs.iamCommandService()` — those are not
part of the actuator refactor.

---

