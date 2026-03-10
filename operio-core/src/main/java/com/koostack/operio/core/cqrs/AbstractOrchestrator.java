package com.koostack.operio.core.cqrs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for cross-aggregate orchestrators (sagas).
 *
 * <p>An orchestrator coordinates commands across multiple {@link CommandDispatcher} instances
 * (bounded contexts) in a defined sequence. Each step may optionally define a compensating action
 * that is executed in reverse order if a later step fails.
 *
 * <p>Subclasses implement {@link #buildSteps} to declare the ordered list of {@link SagaStep}s and
 * {@link #toResult} to map the collected step outputs into the orchestrator's result type.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * public class CreateTenantOrchestrator
 *         extends AbstractOrchestrator<CreateTenantInput, CreateTenantResult> {
 *
 *     public CreateTenantOrchestrator(CommandDispatcher core, CommandDispatcher billing) {
 *         registerDispatcher("core", core);
 *         registerDispatcher("billing", billing);
 *     }
 *
 *     @Override
 *     protected List<SagaStep<CreateTenantInput>> buildSteps(CreateTenantInput input) {
 *         return List.of(
 *             SagaStep.of("create-tenant",
 *                 (in, ctx) -> dispatcher("core").dispatch(new CreateTenantCommand(...)),
 *                 (in, ctx) -> dispatcher("core").dispatch(new DeleteTenantCommand(...))),
 *             SagaStep.of("create-billing-profile",
 *                 (in, ctx) -> dispatcher("billing").dispatch(new CreateBillingProfileCommand(...)))
 *         );
 *     }
 *
 *     @Override
 *     protected CreateTenantResult toResult(CreateTenantInput input, SagaContext context) {
 *         return new CreateTenantResult(input.tenantId(), "created");
 *     }
 * }
 * }</pre>
 *
 * @param <I> the orchestrator input (request) type
 * @param <O> the orchestrator output (result) type
 */
public abstract class AbstractOrchestrator<I, O> {

    private static final Logger LOG = Logger.getLogger(AbstractOrchestrator.class.getName());

    private final Map<String, CommandDispatcher> dispatchers = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Dispatcher registry
    // -----------------------------------------------------------------------

    /**
     * Registers a named dispatcher that steps can reference.
     *
     * @param name logical name (e.g. "core", "billing", "iam")
     * @param dispatcher the dispatcher instance
     * @throws IllegalArgumentException if {@code dispatcher} is null
     */
    protected void registerDispatcher(String name, CommandDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new IllegalArgumentException("Dispatcher must not be null: " + name);
        }
        dispatchers.put(name, dispatcher);
    }

    /**
     * Retrieves a previously registered dispatcher by name.
     *
     * @param name the logical dispatcher name
     * @return the dispatcher
     * @throws IllegalStateException if no dispatcher is registered under that name
     */
    protected CommandDispatcher dispatcher(String name) {
        CommandDispatcher d = dispatchers.get(name);
        if (d == null) {
            throw new IllegalStateException(
                    "No dispatcher registered with name '"
                            + name
                            + "'. Available: "
                            + dispatchers.keySet());
        }
        return d;
    }

    /**
     * Returns an unmodifiable view of all registered dispatchers.
     */
    protected Map<String, CommandDispatcher> dispatchers() {
        return Collections.unmodifiableMap(dispatchers);
    }

    // -----------------------------------------------------------------------
    // Saga step definition
    // -----------------------------------------------------------------------

    /**
     * Action executed during the forward pass of a saga step.
     *
     * @param <I> the orchestrator input type (shared across all steps)
     */
    @FunctionalInterface
    public interface StepAction<I> {

        /**
         * Executes the step's forward logic.
         *
         * @param input the orchestrator input
         * @param context mutable context for passing data between steps
         */
        void execute(I input, SagaContext context);
    }

    /**
     * Optional compensating action invoked when a later step fails. Compensation is best-effort —
     * failures are logged but do not prevent other compensations from running.
     *
     * @param <I> the orchestrator input type
     */
    @FunctionalInterface
    public interface CompensationAction<I> {

        /**
         * Reverses the effect of the corresponding step.
         *
         * @param input the orchestrator input
         * @param context the saga context (may contain data set by prior steps)
         */
        void compensate(I input, SagaContext context);
    }

    /**
     * Immutable descriptor for one step in a saga.
     *
     * <p>Each step has a human-readable {@link #name()}, a mandatory {@link StepAction} that
     * performs the forward work, and an optional {@link CompensationAction} that undoes it.
     *
     * @param <I> the orchestrator input type
     */
    public static final class SagaStep<I> {
        private final String name;
        private final StepAction<I> action;
        private final CompensationAction<I> compensation;

        private SagaStep(String name, StepAction<I> action, CompensationAction<I> compensation) {
            this.name = name;
            this.action = action;
            this.compensation = compensation;
        }

        /**
         * Creates a step with no compensation action.
         *
         * @param name human-readable step name (used in logs and error messages)
         * @param action the forward action
         */
        public static <I> SagaStep<I> of(String name, StepAction<I> action) {
            return new SagaStep<>(name, action, null);
        }

        /**
         * Creates a step with a compensation action.
         *
         * @param name human-readable step name (used in logs and error messages)
         * @param action the forward action
         * @param compensation the compensating action invoked on rollback
         */
        public static <I> SagaStep<I> of(
                String name, StepAction<I> action, CompensationAction<I> compensation) {
            return new SagaStep<>(name, action, compensation);
        }

        /** Returns the human-readable name of this step. */
        public String name() {
            return name;
        }
    }

    /**
     * Mutable key-value bag carried through every step for inter-step data sharing.
     *
     * <p>Steps can store intermediate results (aggregate IDs, computed values, etc.) that
     * subsequent steps — or the final {@link #toResult} mapping — need to read.
     */
    public static final class SagaContext {
        private final Map<String, Object> data = new LinkedHashMap<>();

        /** Stores a value in the context. */
        public void put(String key, Object value) {
            data.put(key, value);
        }

        /** Retrieves a value by key, casting to the inferred type. */
        @SuppressWarnings("unchecked")
        public <T> T get(String key) {
            return (T) data.get(key);
        }

        /** Retrieves a value by key, casting to the specified type. */
        public <T> T get(String key, Class<T> type) {
            return type.cast(data.get(key));
        }

        /** Returns {@code true} if the context contains a value for the given key. */
        public boolean has(String key) {
            return data.containsKey(key);
        }

        /** Returns an unmodifiable snapshot of the context data. */
        public Map<String, Object> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    // -----------------------------------------------------------------------
    // Abstract contract — subclasses implement these
    // -----------------------------------------------------------------------

    /**
     * Build the ordered list of saga steps for this orchestrator.
     *
     * <p>Called once per {@link #execute} invocation. The returned list defines the forward
     * execution order; compensation runs in reverse.
     *
     * @param input the orchestrator input
     * @return an ordered, non-empty list of saga steps
     */
    protected abstract List<SagaStep<I>> buildSteps(I input);

    /**
     * Convert the completed saga context into the orchestrator's result type.
     *
     * <p>Only invoked when all steps complete successfully.
     *
     * @param input the original orchestrator input
     * @param context the saga context after all steps completed
     * @return the orchestration result
     */
    protected abstract O toResult(I input, SagaContext context);

    // -----------------------------------------------------------------------
    // Execution engine
    // -----------------------------------------------------------------------

    /**
     * Executes the orchestration saga.
     *
     * <p>Steps are executed in declaration order. If any step throws, all previously completed
     * steps that have a {@link CompensationAction} are compensated in reverse order (best-effort).
     * The original exception is then re-thrown wrapped in an {@link OrchestratorException}.
     *
     * @param input the orchestrator input
     * @return the result produced by {@link #toResult}
     * @throws OrchestratorException if any step fails (after compensation)
     */
    public O execute(I input) {
        List<SagaStep<I>> steps = buildSteps(input);
        SagaContext context = new SagaContext();
        List<SagaStep<I>> completed = new ArrayList<>();

        LOG.log(Level.INFO, "{0}: starting saga with {1} step(s)",
                new Object[] {orchestratorName(), steps.size()});

        for (SagaStep<I> step : steps) {
            try {
                LOG.log(Level.FINE, "{0}: executing step ''{1}''",
                        new Object[] {orchestratorName(), step.name()});
                step.action.execute(input, context);
                completed.add(step);
                LOG.log(Level.FINE, "{0}: step ''{1}'' completed",
                        new Object[] {orchestratorName(), step.name()});
            } catch (Exception ex) {
                LOG.log(Level.SEVERE,
                        String.format(
                                "%s: step '%s' failed — compensating %d prior step(s): %s",
                                orchestratorName(), step.name(), completed.size(), ex.getMessage()),
                        ex);

                compensate(input, context, completed);

                throw new OrchestratorException(
                        orchestratorName(),
                        step.name(),
                        completed.size(),
                        steps.size(),
                        ex);
            }
        }

        LOG.log(Level.INFO, "{0}: saga completed successfully ({1} step(s))",
                new Object[] {orchestratorName(), steps.size()});
        return toResult(input, context);
    }

    // -----------------------------------------------------------------------
    // Compensation (reverse-order, best-effort)
    // -----------------------------------------------------------------------

    private void compensate(I input, SagaContext context, List<SagaStep<I>> completed) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            SagaStep<I> step = completed.get(i);
            if (step.compensation != null) {
                try {
                    LOG.log(Level.INFO, "{0}: compensating step ''{1}''",
                            new Object[] {orchestratorName(), step.name()});
                    step.compensation.compensate(input, context);
                    LOG.log(Level.INFO, "{0}: compensation for step ''{1}'' succeeded",
                            new Object[] {orchestratorName(), step.name()});
                } catch (Exception compEx) {
                    // Compensation failure must not prevent other compensations from running.
                    // This is logged at SEVERE level for manual remediation.
                    LOG.log(Level.SEVERE,
                            String.format(
                                    "%s: compensation for step '%s' FAILED — manual remediation"
                                            + " required: %s",
                                    orchestratorName(), step.name(), compEx.getMessage()),
                            compEx);
                }
            } else {
                LOG.log(Level.WARNING, "{0}: step ''{1}'' has no compensation action — skipping",
                        new Object[] {orchestratorName(), step.name()});
            }
        }
    }

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    /**
     * Human-readable name for logging and error messages.
     *
     * <p>Defaults to the simple class name. Override to provide a custom name.
     */
    protected String orchestratorName() {
        return getClass().getSimpleName();
    }

    // -----------------------------------------------------------------------
    // Exception type
    // -----------------------------------------------------------------------

    /**
     * Thrown when a saga step fails after partial execution.
     *
     * <p>Carries metadata about which step failed and how many steps completed before the failure,
     * enabling callers to produce meaningful error responses.
     */
    public static class OrchestratorException extends RuntimeException {
        private final String orchestrator;
        private final String failedStep;
        private final int completedSteps;
        private final int totalSteps;

        public OrchestratorException(
                String orchestrator,
                String failedStep,
                int completedSteps,
                int totalSteps,
                Throwable cause) {
            super(
                    String.format(
                            "%s: step '%s' failed after %d/%d step(s) completed",
                            orchestrator, failedStep, completedSteps, totalSteps),
                    cause);
            this.orchestrator = orchestrator;
            this.failedStep = failedStep;
            this.completedSteps = completedSteps;
            this.totalSteps = totalSteps;
        }

        public String orchestrator() {
            return orchestrator;
        }

        public String failedStep() {
            return failedStep;
        }

        public int completedSteps() {
            return completedSteps;
        }

        public int totalSteps() {
            return totalSteps;
        }
    }
}
