package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-based core command dispatcher.
 *
 * <p>Implements both the generic {@link CommandDispatcher} SPI (actuator-based routing) and the
 * legacy {@link CoreCommandService} interface (thin delegation layer for backward compatibility
 * with existing REST resources and holders).
 *
 * <p>All non-deprecated command methods delegate to the dispatcher, which routes to the appropriate
 * {@link Actuator} by command record type.
 */
public class JooqCoreDispatcher
        implements CommandDispatcher, CoreCommandService<JooqCoreCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqCoreDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private EventService<?> eventService;

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-core-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqCoreCommandConfig> getConfigurationType() {
        return JooqCoreCommandConfig.class;
    }

    @Override
    public void configure(JooqCoreCommandConfig config) {
        this.eventService = config.eventService();

        actuators.put(
                CreateTenantCommandActuator.CreateTenantCommand.class,
                new CreateTenantCommandActuator(eventService));
        actuators.put(
                UpdateTenantDisplayNameCommandActuator.UpdateTenantDisplayNameCommand.class,
                new UpdateTenantDisplayNameCommandActuator(eventService));
        actuators.put(
                ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand.class,
                new ChangeTenantPlanCommandActuator(eventService));
        actuators.put(
                SuspendTenantCommandActuator.SuspendTenantCommand.class,
                new SuspendTenantCommandActuator(eventService));
        actuators.put(
                ActivateTenantCommandActuator.ActivateTenantCommand.class,
                new ActivateTenantCommandActuator(eventService));
        actuators.put(
                DeleteTenantCommandActuator.DeleteTenantCommand.class,
                new DeleteTenantCommandActuator(eventService));

        LOG.info("JooqCoreDispatcher configured with {} actuators", actuators.size());
    }

    // -- CoreCommandService delegation (backward compatibility) ----------------

    @Override
    public void createTenant(String tenantId, String displayName, String plan) {
        dispatch(new CreateTenantCommandActuator.CreateTenantCommand(tenantId, displayName, plan));
    }

    @Override
    public void updateTenantDisplayName(String tenantId, String displayName) {
        dispatch(
                new UpdateTenantDisplayNameCommandActuator.UpdateTenantDisplayNameCommand(
                        tenantId, displayName));
    }

    @Override
    public void changeTenantPlan(String tenantId, String newPlan) {
        dispatch(new ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand(tenantId, newPlan));
    }

    @Override
    public void suspendTenant(String tenantId, String reason) {
        dispatch(new SuspendTenantCommandActuator.SuspendTenantCommand(tenantId, reason));
    }

    @Override
    public void activateTenant(String tenantId) {
        dispatch(new ActivateTenantCommandActuator.ActivateTenantCommand(tenantId));
    }

    @Override
    public void deleteTenant(String tenantId) {
        dispatch(new DeleteTenantCommandActuator.DeleteTenantCommand(tenantId));
    }
}
