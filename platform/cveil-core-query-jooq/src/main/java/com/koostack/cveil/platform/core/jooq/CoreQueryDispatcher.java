package com.koostack.cveil.platform.core.jooq;

import com.koostack.cveil.core.cqrs.Actuator;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.platform.core.CoreQueryService;
import com.koostack.cveil.core.platform.core.models.TenantCredentials;
import com.koostack.cveil.core.platform.core.models.TenantInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link CoreQueryService} interface (thin delegation layer for backward compatibility).
 */
public class CoreQueryDispatcher implements QueryDispatcher, CoreQueryService<JooqCoreQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(CoreQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

    private DSLContext dsl;
    private RetryConfig retryConfig = RetryableQuery.DEFAULT;

    // -- QueryDispatcher SPI --------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for query type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "core-query-dispatcher";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqCoreQueryConfig> getConfigurationType() {
        return JooqCoreQueryConfig.class;
    }

    @Override
    public void configure(JooqCoreQueryConfig config) {
        this.dsl = config.dsl();
        CoreRecordMapper mapper = new CoreRecordMapper(config.decryptor());

        actuators.put(
                GetTenantQueryActuator.GetTenantQuery.class,
                new GetTenantQueryActuator(dsl, retryConfig, mapper));
        actuators.put(
                ListTenantsQueryActuator.ListTenantsQuery.class,
                new ListTenantsQueryActuator(dsl, retryConfig, mapper));
        actuators.put(
                ListTenantsByStatusQueryActuator.ListTenantsByStatusQuery.class,
                new ListTenantsByStatusQueryActuator(dsl, retryConfig, mapper));
        actuators.put(
                GetTenantCredentialsQueryActuator.GetTenantCredentialsQuery.class,
                new GetTenantCredentialsQueryActuator(dsl, mapper));

        LOG.info("CoreQueryDispatcher configured with {} actuators", actuators.size());
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    // -- CoreQueryService delegation (backward compatibility) ------------------

    @Override
    public TenantInfo getTenant(String tenantId) {
        return dispatch(new GetTenantQueryActuator.GetTenantQuery(tenantId));
    }

    @Override
    public List<TenantInfo> listTenants() {
        return dispatch(new ListTenantsQueryActuator.ListTenantsQuery());
    }

    @Override
    public List<TenantInfo> listTenantsByStatus(String status) {
        return dispatch(new ListTenantsByStatusQueryActuator.ListTenantsByStatusQuery(status));
    }

    @Override
    public TenantCredentials getTenantCredentials(String tenantId) {
        return dispatch(new GetTenantCredentialsQueryActuator.GetTenantCredentialsQuery(tenantId));
    }
}
